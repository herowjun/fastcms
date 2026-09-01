/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * http://www.xjd2020.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.service.impl;

import com.fastcms.ai.audit.AiQuotaChecker;
import com.fastcms.ai.audit.AiQuotaExceededException;
import com.fastcms.ai.audit.AiUsageRecorder;
import com.fastcms.ai.service.IAiModelConfigService;
import com.fastcms.ai.service.IAiTemplateBackupService;
import com.fastcms.ai.service.IAiTemplateFileService;
import com.fastcms.ai.service.IAiTemplateMessageService;
import com.fastcms.ai.service.IAiTemplateSessionService;
import com.fastcms.ai.template.AiTemplateConstants;
import com.fastcms.ai.template.AiTemplateFileDto;
import com.fastcms.ai.template.AiTemplateResponseParser;
import com.fastcms.ai.template.AiTemplateSessionRequest;
import com.fastcms.ai.template.IAiTemplateGenService;
import com.fastcms.ai.template.TemplateGenPromptBuilder;
import com.fastcms.ai.support.ReplyStreamExtractor;
import com.fastcms.ai.tool.AiToolCallbackProvider;
import com.fastcms.common.utils.DirUtils;
import com.fastcms.core.template.Template;
import com.fastcms.core.template.TemplateService;
import com.fastcms.entity.AiModelConfig;
import com.fastcms.entity.AiTemplateFile;
import com.fastcms.entity.AiTemplateFileBackup;
import com.fastcms.entity.AiTemplateMessage;
import com.fastcms.entity.AiTemplateSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AI 模板生成服务实现
 *
 * <p>核心职责：
 * <ol>
 *     <li>会话生命周期管理（创建/查询/删除）</li>
 *     <li>调用 ChatClient 进行 SSE 流式对话</li>
 *     <li>解析 AI 响应为文件并写入预览工作目录</li>
 *     <li>持久化文件到 ai_template_file 表（跨重启恢复）</li>
 *     <li>应用模板：将工作目录文件复制到正式模板目录并刷新注册</li>
 * </ol>
 *
 * <p><b>模型选择策略</b>：每次对话前从 {@link IAiModelConfigService#getActiveConfig()} 读取激活配置，
 * 动态构造 {@link ChatModel}，这样后台切换模型后立即生效，无需重启。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiTemplateGenServiceImpl implements IAiTemplateGenService {

    private static final Logger log = LoggerFactory.getLogger(AiTemplateGenServiceImpl.class);

    /**
     * 预览目录根目录，可通过配置项 fastcms.ai.template.preview-root 覆盖。
     *
     * <p>默认 {@code ~/fastcms/ai-template-preview}（与 logback 日志目录 ~/fastcms/logs 同级）。
     * 历史教训：早期实现用相对路径 {@code ai-template-preview}，目录随 JVM 工作目录漂移——
     * IDE 启动落在工程根目录、mvn spring-boot:run 落在 web/ 下，切换启动方式后旧会话
     * 预览/断点续传全部失联。因此必须锚定绝对路径，与启动方式解耦。</p>
     *
     * <p>注意：会话 workDir 以绝对路径落库，旧会话仍指向旧位置，互不影响；
     * 迁移旧数据时把各旧目录下的会话文件夹直接拷入本目录即可。</p>
     */
    @Value("${fastcms.ai.template.preview-root:}")
    private String previewRootConfig;

    /**
     * SSE 流式调用的专用线程池（避免阻塞 Servlet 容器线程）
     */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-template-sse");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private IAiTemplateSessionService sessionService;

    @Autowired
    private IAiTemplateMessageService messageService;

    @Autowired
    private IAiTemplateFileService fileService;

    @Autowired
    private IAiTemplateBackupService backupService;

    @Autowired
    private IAiModelConfigService modelConfigService;

    @Autowired
    private TemplateGenPromptBuilder promptBuilder;

    @Autowired
    private AiTemplateResponseParser responseParser;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private AiQuotaChecker quotaChecker;

    @Autowired
    private AiUsageRecorder usageRecorder;

    @Autowired
    private AiToolCallbackProvider toolCallbackProvider;

    // ==================== 会话管理 ====================

    @Override
    public AiTemplateSession createSession(AiTemplateSessionRequest request, Long userId) {
        validateRequest(request);

        AiTemplateSession session = new AiTemplateSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setRequirement(request.getRequirement());
        session.setStatus(AiTemplateConstants.STATUS_ACTIVE);
        session.setUserId(userId);

        if (StringUtils.hasText(request.getTemplateId())) {
            // 调整型会话：绑定正式模板，AI 输出直写正式模板目录（写前自动备份）
            Template template = templateService.getTemplate(request.getTemplateId());
            if (template == null || template.getTemplatePath() == null) {
                throw new IllegalArgumentException("模板不存在: " + request.getTemplateId());
            }
            session.setTemplateId(template.getId());
            session.setTemplateName(template.getPathName());
            session.setTitle(StringUtils.hasText(request.getTitle())
                    ? request.getTitle()
                    : "调整 " + template.getPathName());
            session.setWorkDir(template.getTemplatePath().toString());
        } else {
            // 生成型会话：在预览工作目录中生成，应用后复制到正式模板目录
            session.setTemplateName(request.getTemplateName());
            session.setTitle(StringUtils.hasText(request.getTitle())
                    ? request.getTitle()
                    : request.getTemplateName());
            session.setWorkDir(getPreviewWorkDir(session.getSessionId(), request.getTemplateName()).toString());
        }

        sessionService.save(session);
        log.info("AI 模板生成会话创建: sessionId={}, templateName={}, templateId={}, userId={}",
                session.getSessionId(), session.getTemplateName(), session.getTemplateId(), userId);
        return session;
    }

    @Override
    public AiTemplateSession getSession(String sessionId) {
        return sessionService.getBySessionId(sessionId);
    }

    @Override
    public List<AiTemplateSession> listSessions(Long userId) {
        return sessionService.listByUserId(userId);
    }

    @Override
    public void deleteSession(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            return;
        }
        // 仅生成型会话删除预览工作目录；调整型会话的工作目录是正式模板目录，禁止删除
        if (session.getTemplateId() == null && StringUtils.hasText(session.getWorkDir())) {
            deleteDirectory(Paths.get(session.getWorkDir()));
        }
        // 删除数据库记录
        messageService.deleteBySessionId(sessionId);
        fileService.deleteBySessionId(sessionId);
        backupService.deleteBySessionId(sessionId);
        sessionService.removeById(session.getId());
        log.info("AI 模板生成会话删除: sessionId={}", sessionId);
    }

    @Override
    public List<AiTemplateMessage> listMessages(String sessionId) {
        return messageService.listBySessionId(sessionId);
    }

    @Override
    public List<AiTemplateFile> listFiles(String sessionId) {
        return fileService.listBySessionId(sessionId);
    }

    // ==================== SSE 流式对话 ====================

    @Override
    public void chatStream(String sessionId, String userInput, String currentFile, SseEmitter emitter) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            sendError(emitter, "会话不存在: " + sessionId);
            complete(emitter);
            return;
        }

        sseExecutor.execute(() -> {
            try {
                doChatStream(session, userInput, currentFile, emitter);
            } catch (Exception e) {
                log.error("AI 模板生成 SSE 对话异常: sessionId={}", sessionId, e);
                // 失败原因落库为带标记的 assistant 消息：会话刷新/重进后仍能看到失败原因
                // （不落库会产生"空壳会话"：只有用户需求，无回复无文件无 plan，且无从追溯）
                String errMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                try {
                    messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT,
                            AiTemplateConstants.MSG_FAIL_PREFIX + errMsg);
                } catch (Exception persistEx) {
                    log.warn("失败消息落库异常: sessionId={}", sessionId, persistEx);
                }
                sendError(emitter, errMsg);
            } finally {
                complete(emitter);
            }
        });
    }

    /**
     * 执行 SSE 流式对话的实际逻辑
     *
     * <p>流程：
     * <ol>
     *     <li>获取激活的 AI 模型配置，构造 ChatModel</li>
     *     <li>加载会话历史消息</li>
     *     <li>判断是首次对话（无历史）还是微调对话</li>
     *     <li>调用 ChatClient.stream() 获取流式响应，每个增量实时推送给前端（打字机效果）</li>
     *     <li>聚合完整响应后解析为文件</li>
     *     <li>持久化消息与文件</li>
     *     <li>通过 SSE 推送事件给前端</li>
     * </ol>
     */
    private void doChatStream(AiTemplateSession session, String userInput, String currentFile, SseEmitter emitter) throws Exception {
        // 0. 配额检查（fastcms.ai.daily-token-quota，超限直接拒绝，不产生模型调用）
        try {
            quotaChecker.check(session.getUserId());
        } catch (AiQuotaExceededException e) {
            sendError(emitter, e.getMessage());
            return;
        }

        long startTime = System.currentTimeMillis();
        boolean[] succeeded = {false};
        String[] errorMessage = {null};
        // token 用量：流式响应中仅最后一个 chunk 携带 usage（累积值），取最后非空值
        org.springframework.ai.chat.metadata.Usage[] lastUsage = {null};

        try {
            doChatStreamInternal(session, userInput, currentFile, emitter, lastUsage);
            succeeded[0] = true;
        } catch (Exception e) {
            errorMessage[0] = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            // 审计落库（audit-enabled=false 时静默跳过；失败不影响主流程）
            int promptTokens = lastUsage[0] == null || lastUsage[0].getPromptTokens() == null ? 0 : lastUsage[0].getPromptTokens();
            int completionTokens = lastUsage[0] == null || lastUsage[0].getCompletionTokens() == null ? 0 : lastUsage[0].getCompletionTokens();
            int totalTokens = lastUsage[0] == null || lastUsage[0].getTotalTokens() == null
                    ? promptTokens + completionTokens : lastUsage[0].getTotalTokens();
            if (succeeded[0]) {
                usageRecorder.record(session.getUserId(), sceneOf(session), session.getSessionId(),
                        modelConfigService.getActiveConfig() == null ? null : modelConfigService.getActiveConfig().getModel(),
                        promptTokens, completionTokens, totalTokens, System.currentTimeMillis() - startTime);
            } else {
                usageRecorder.recordError(session.getUserId(), sceneOf(session), session.getSessionId(),
                        modelConfigService.getActiveConfig() == null ? null : modelConfigService.getActiveConfig().getModel(),
                        System.currentTimeMillis() - startTime, errorMessage[0]);
            }
        }
    }

    /**
     * 会话场景：调整型 TEMPLATE_ADJUST / 生成型 TEMPLATE_GEN
     */
    private String sceneOf(AiTemplateSession session) {
        return StringUtils.hasText(session.getTemplateId())
                ? com.fastcms.service.IAiUsageLogService.Scene.TEMPLATE_ADJUST
                : com.fastcms.service.IAiUsageLogService.Scene.TEMPLATE_GEN;
    }

    private void doChatStreamInternal(AiTemplateSession session, String userInput, String currentFile,
                                      SseEmitter emitter, org.springframework.ai.chat.metadata.Usage[] lastUsage) throws Exception {
        // 1. 获取激活的模型配置
        AiModelConfig modelConfig = modelConfigService.getActiveConfig();
        if (modelConfig == null) {
            sendError(emitter, "未配置 AI 模型，请先在模型管理中添加并激活一个配置");
            return;
        }
        ChatModel chatModel = AiModelConfigServiceImpl.buildChatModel(modelConfig);
        ChatClient chatClient = ChatClient.builder(chatModel)
                // 挂载 @AiTool 注册的工具（当前无工具时为空数组，不影响调用）
                .defaultTools(toolCallbackProvider.getToolCallbacks())
                .build();

        // 2. 加载历史消息
        List<AiTemplateMessage> history = messageService.listBySessionId(session.getSessionId());
        boolean isFirstChat = history.isEmpty();

        // 3. 保存用户消息（分批/单轮两条路径都需要；历史加载在保存之前，不会重复注入）
        messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_USER, userInput);

        // 4. 生成型会话首次对话走分批流水线（规划轮 + 逐文件轮）。
        //    整套模板一次性输出极易超 max_tokens 上限被截断（JSON 不完整 → 无文件落盘 → 前端永久转圈）；
        //    分批后单轮输出量级天然小于上限，从结构上消除截断问题。微调/调整仍走单轮。
        //    断点续传：plan 已持久化且存在未生成文件（中途停止/单文件失败/服务重启），
        //    任何新一轮对话都继续流水线、只补齐缺失文件，而不是当作微调。
        if (!StringUtils.hasText(session.getTemplateId())
                && (isFirstChat || hasMissingPlanFiles(session))) {
            runBatchPipeline(session, modelConfig, chatClient, userInput, emitter, lastUsage);
            return;
        }

        // 5. 单轮路径（调整型会话 / 生成型微调）：构造消息列表。
        //    调整/微调对话需要模型推理（定位问题、多约束权衡），不注入 /no_think
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(session.getTemplateName())));

        // 加入历史消息（保持上下文）；失败标记消息（"生成失败："前缀）对模型是无意义
        // 上下文，跳过注入（仅用于前端展示与失败态判定）
        for (AiTemplateMessage msg : history) {
            if (AiTemplateConstants.ROLE_USER.equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (AiTemplateConstants.ROLE_ASSISTANT.equals(msg.getRole())
                    && !msg.getContent().startsWith(AiTemplateConstants.MSG_FAIL_PREFIX)) {
                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(msg.getContent()));
            }
        }

        // 构造本次用户输入
        String userPrompt;
        if (StringUtils.hasText(session.getTemplateId())) {
            // 调整型会话：每一轮都携带正式模板当前文件内容（用户可能在两轮之间手工修改过）
            String currentFilesWithContent = buildTemplateFileSection(Paths.get(session.getWorkDir()));
            // 归一化当前文件路径（去掉模板目录前缀，与文件清单中的相对路径一致），注入提示词让 AI 聚焦用户当前页面
            String normalizedCurrentFile = normalizeRelativePath(currentFile, session.getWorkDir());
            userPrompt = promptBuilder.buildAdjustPrompt(userInput, currentFilesWithContent, normalizedCurrentFile);
        } else {
            // 微调场景：附带当前已有文件清单
            String currentFiles = buildCurrentFileList(session.getSessionId());
            userPrompt = promptBuilder.buildRefinePrompt(userInput, currentFiles);
        }
        messages.add(new UserMessage(userPrompt));

        // 6. 调用 ChatClient 流式接口（Spring AI stream()）：
        //    AI 返回的是结构化 JSON（{reply, files}），通过 ReplyStreamExtractor 增量提取
        //    reply 字段的自然语言文本实时推送给前端（打字机效果），
        //    同时聚合完整响应到缓冲区——必须等完整响应才能解析文件。
        //    推理模型（Qwen3/DeepSeek-R1 等）会先输出 reasoning_content 思考过程，
        //    Spring AI 将其透传到 AssistantMessage.metadata["reasoningContent"]，
        //    这里同样增量推送给前端实时展示
        ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
        // Spring AI 透传的 reasoningContent 是"累积值"（每个 chunk 带到当前为止的完整思考文本），
        // 做差分后仅推送新增部分
        StringBuilder reasoningBuf = new StringBuilder();
        long[] roundUsage = new long[3];
        // 调整/微调轮同样走统一 options（显式 model + Qwen3 reasoning_effort=low）。
        // 实测（TEMPLATE_ADJUST 用量记录）：Qwen3.6 全力思考可把 completion 吃满 maxTokens
        // （8774 输入 / 16384 输出 / 耗时 6.4 分钟，正文一个 token 未出），最终只能报"AI 返回空响应"
        String fullResponse = callModelRound(chatClient, messages, emitter, replyExtractor, reasoningBuf, roundUsage,
                buildPipelineOptions(modelConfig, null));

        // 空响应兜底重试：思考吃满 maxTokens 导致正文为空时（roundUsage[1] 即 completion，
        // 顶到配置上限即为该情形），翻倍上限重试一次，给正文留出输出空间。
        // 使用全新的 extractor/reasoning 缓冲：上一轮的思考文本不拼进本轮前端流
        if (!StringUtils.hasText(fullResponse)
                && modelConfig.getMaxTokens() != null
                && roundUsage[1] >= modelConfig.getMaxTokens()) {
            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "\n（思考耗尽输出上限，正在提升上限重试…）");
            replyExtractor = new ReplyStreamExtractor();
            reasoningBuf = new StringBuilder();
            fullResponse = callModelRound(chatClient, messages, emitter, replyExtractor, reasoningBuf, roundUsage,
                    buildPipelineOptions(modelConfig, Math.max(modelConfig.getMaxTokens() * 2, 32768)));
        }
        lastUsage[0] = aggregateUsage(roundUsage);
        if (!StringUtils.hasText(fullResponse)) {
            // 与异常路径一致：失败原因落库（前端刷新后仍能显示失败态与重新生成入口）
            messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                    AiTemplateConstants.MSG_FAIL_PREFIX + "AI 返回空响应（思考过程可能耗尽了输出上限）");
            sendError(emitter, "AI 返回空响应");
            return;
        }

        // 完整思考过程（非推理模型为 null，不落库占位）
        String reasoningText = reasoningBuf.length() > 0 ? reasoningBuf.toString() : null;

        // 6. 解析响应（reply 自然语言 + files 文件列表，兼容旧版纯数组格式）
        AiTemplateResponseParser.ParseResult parsed = responseParser.parseResponse(fullResponse);
        String reply = parsed.getReply();
        List<AiTemplateFileDto> files = parsed.getFiles();
        boolean hasReply = StringUtils.hasText(reply);
        boolean hasFiles = !files.isEmpty();

        if (!hasFiles && !hasReply) {
            log.warn("AI 响应未解析出任何文件与回复: sessionId={}", session.getSessionId());
            // 仍保存原始响应作为 assistant 消息
            messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, fullResponse, reasoningText);
            sendDone(emitter, "AI 响应未包含可识别的文件内容，请尝试重新描述需求");
            return;
        }

        // 7. 保存 assistant 消息（先于文件写入：调整型会话的文件备份以消息ID为回滚粒度），
        //    优先保存 reply（自然语言摘要，避免巨型 JSON 挤占后续对话上下文），
        //    同时保存推理模型的完整思考过程（刷新页面后仍可回看，buildMessages 不会将其注入 prompt）
        String assistantMsg = hasReply ? reply : "已生成 " + files.size() + " 个文件";
        AiTemplateMessage assistantMessage = messageService.saveMessage(
                session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, assistantMsg, reasoningText);

        // 8. 持久化文件并写入工作目录（调整型会话写前自动备份到 ai_template_file_backup）
        int successCount = 0;
        if (hasFiles) {
            for (AiTemplateFileDto file : files) {
                try {
                    // 持久化到数据库
                    fileService.saveOrUpdateFile(
                            session.getSessionId(),
                            file.getPath(),
                            file.getContent() == null ? "" : file.getContent(),
                            file.getAction());

                    // 写入工作目录（生成型=预览目录；调整型=正式模板目录+备份）
                    writeToFile(session, file, assistantMessage.getId());

                    // 推送文件事件给前端
                    sendFileEvent(emitter, file);

                    successCount++;
                } catch (Exception e) {
                    log.warn("文件写入失败: sessionId={}, path={}", session.getSessionId(), file.getPath(), e);
                }
            }
        }

        // 兜底推送：流式期间未推送过 reply（如 AI 把 reply 放在 files 之后、或旧数组格式）时补推，
        // 保证前端聊天区始终有内容
        if (hasReply) {
            if (!replyExtractor.wasEmitted()) {
                sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
            }
        } else if (hasFiles) {
            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE, "已生成 " + successCount + " 个文件");
        }

        // 9. 推送完成事件
        String summary;
        if (hasFiles) {
            summary = hasReply ? reply : "生成完成，共 " + successCount + " 个文件";
        } else {
            // 纯对话场景（咨询/闲聊）：reply 即完整回复
            summary = reply;
        }
        sendDone(emitter, truncate(summary, 100));
        log.info("AI 模板生成对话完成: sessionId={}, files={}", session.getSessionId(), successCount);
    }

    /**
     * 截断文本（done 摘要等场景使用，避免前端 toast 过长）
     */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    // ==================== 分批流水线（生成型会话首次对话） ====================

    /**
     * 规划轮解析失败时的兜底文件清单（必备文件）
     */
    private static final List<String> DEFAULT_PLAN_FILES = List.of(
            AiTemplateConstants.FILE_TEMPLATE_PROPERTIES,
            AiTemplateConstants.FILE_LAYOUT,
            AiTemplateConstants.FILE_INDEX,
            AiTemplateConstants.FILE_ARTICLE,
            AiTemplateConstants.FILE_ARTICLE_LIST,
            AiTemplateConstants.FILE_PAGE,
            AiTemplateConstants.DIR_STATIC_CSS + "/base.css");

    /**
     * plan 文件清单的 JSON 序列化/反序列化（独立于响应解析器的 Mapper，仅处理简单字符串数组）
     */
    private static final tools.jackson.databind.ObjectMapper PLAN_MAPPER = new tools.jackson.databind.ObjectMapper();

    /**
     * 单轮流式调用的信号间隔超时：连续该时长无任何增量（含思考增量）判定为流死
     */
    private static final java.time.Duration ROUND_SIGNAL_TIMEOUT = java.time.Duration.ofMinutes(5);

    /**
     * 单轮流式调用的总时长上限（墙钟计时，任何信号无法重置）：
     * 兜住"流停滞但 keepalive 心跳不断重置信号间隔超时"的挂死场景；
     * 正常推理模型单文件 3-4 分钟，规划轮更短，15 分钟是充裕上限
     */
    private static final java.time.Duration ROUND_TOTAL_TIMEOUT = java.time.Duration.ofMinutes(15);

    /**
     * 分批流水线：规划轮（输出文件清单）+ 逐文件轮（一次只生成一个文件）
     *
     * <p>每轮模型调用的输出量级天然远低于 max_tokens 上限，从结构上消除
     * "整套模板一次性输出被截断"的故障；单个文件失败自动重试一次（附加压缩要求），
     * 重试仍失败则跳过并计入汇总，不阻塞其余文件。</p>
     *
     * <p>各轮的 reply/reasoning 通过既有 SSE 事件流式推送（前端打字机效果），
     * 文件状态通过 progress 事件全量快照推送（前端渲染进度卡）。</p>
     *
     * @param session   生成型会话（templateId 为空）
     * @param modelConfig 激活的模型配置（用于截断判断）
     * @param chatClient 已构建的 ChatClient
     * @param userInput 用户需求描述
     * @param emitter   SSE 推送器
     * @param usageOut  审计用量输出（多轮累计）
     */
    private void runBatchPipeline(AiTemplateSession session, AiModelConfig modelConfig, ChatClient chatClient,
                                  String userInput, SseEmitter emitter,
                                  org.springframework.ai.chat.metadata.Usage[] usageOut) {
        String systemPrompt = promptBuilder.buildSystemPrompt(session.getTemplateName());
        long[] usageAgg = new long[3];
        // 全流程思考过程（各轮拼接，落库后刷新页面仍可回看）
        StringBuilder allReasoning = new StringBuilder();

        // ===== 文件清单与已完成状态：首次走规划轮；断点续传直接复用持久化 plan =====
        List<String> plannedFiles;
        // 已生成文件（含内容，供单文件轮构建风格一致性上下文）
        List<AiTemplateFileDto> generatedFiles = new ArrayList<>();
        List<String> donePaths = new ArrayList<>();
        List<String> failedPaths = new ArrayList<>();
        String layoutContent = null;
        boolean resumed = false;

        List<String> persistedPlan = parsePlanFiles(session);
        if (persistedPlan.isEmpty()) {
            // ===== 规划轮：只输出文件清单 =====
            String planPrompt = promptBuilder.buildPlanPrompt(session.getTemplateName(), userInput);
            ReplyStreamExtractor planExtractor = new ReplyStreamExtractor();
            String planResponse = callModelRound(chatClient,
                    List.of(new SystemMessage(systemPrompt), new UserMessage(planPrompt)),
                    emitter, planExtractor, allReasoning, usageAgg,
                    buildPipelineOptions(modelConfig, null));

            AiTemplateResponseParser.ParseResult planParsed = responseParser.parseResponse(planResponse);
            plannedFiles = planParsed.getFiles().stream()
                    .map(AiTemplateFileDto::getPath)
                    .filter(p -> p != null && !p.isBlank())
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());
            // 规划解析兜底：解析失败时按必备文件清单生成，不让流程中断
            if (plannedFiles.isEmpty()) {
                plannedFiles = new ArrayList<>(DEFAULT_PLAN_FILES);
                sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（规划结果解析失败，按必备文件清单逐个生成）");
                log.warn("规划轮解析失败，使用默认清单: sessionId={}", session.getSessionId());
            }
            // 规划 reply 兜底（流式期间未推出时补推）
            if (StringUtils.hasText(planParsed.getReply()) && !planExtractor.wasEmitted()) {
                sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE, planParsed.getReply());
            }
            // 持久化 plan：刷新页面重算进度卡、中断后断点续传的依据
            persistPlan(session, plannedFiles);
        } else {
            // ===== 断点续传：从 DB 加载已生成文件，只补齐 plan 中缺失的部分 =====
            resumed = true;
            plannedFiles = persistedPlan;
            for (AiTemplateFile f : fileService.listBySessionId(session.getSessionId())) {
                AiTemplateFileDto dto = new AiTemplateFileDto();
                dto.setPath(f.getFilePath());
                dto.setContent(f.getContent());
                dto.setAction(f.getAction());
                generatedFiles.add(dto);
                donePaths.add(f.getFilePath());
                if (AiTemplateConstants.FILE_LAYOUT.equals(f.getFilePath())) {
                    layoutContent = f.getContent();
                }
            }
            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "（检测到未完成的生成任务，继续补齐缺失文件）");
            log.info("流水线断点续传: sessionId={}, planned={}, done={}",
                    session.getSessionId(), plannedFiles.size(), donePaths.size());
        }

        // ===== 逐文件轮：只遍历缺失文件（进度推送仍用全量 plan，保证前端视觉连续） =====
        List<String> pendingFiles = plannedFiles.stream()
                .filter(p -> !donePaths.contains(p))
                .collect(Collectors.toList());
        if (pendingFiles.isEmpty()) {
            String msg = "所有规划文件均已生成完毕。如需调整，请直接描述微调需求。";
            messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, msg, null);
            sendDone(emitter, msg);
            return;
        }
        // 单文件轮的需求描述：续传时用会话的原始需求（本轮输入可能只是"补齐"）；
        // 首轮时用户输入即原始需求，两者等价
        String genRequirement = resumed && StringUtils.hasText(session.getRequirement())
                ? session.getRequirement() : userInput;

        for (String path : pendingFiles) {
            sendProgress(emitter, plannedFiles, plannedFiles.indexOf(path), donePaths);
            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n\n📄 正在生成 " + path + " …");

            String existingContext = buildGenContext(generatedFiles, layoutContent);
            String filePrompt = promptBuilder.buildSingleFilePrompt(genRequirement, path, existingContext, null);
            AiTemplateFileDto fileDto = generateSingleFile(chatClient, systemPrompt, filePrompt,
                    path, emitter, allReasoning, usageAgg, modelConfig, null);

            // 直出失败（多为触达 max_tokens 截断）→ 分块生成路径：
            // 规划轮划分块（输出极小不会截断）+ 逐块生成（每块输出量级远低于上限），
            // 从结构上保证文件大小与 max_tokens 配置解耦——文件再大也只是块数变多
            if (fileDto == null) {
                fileDto = generateFileByChunks(chatClient, systemPrompt, genRequirement, path,
                        existingContext, emitter, allReasoning, usageAgg, modelConfig);
            }

            // 分块仍失败 → 压缩篇幅 + maxTokens 翻倍重试（兜底）。
            // 思考 tokens 计入 completion：推理模型单轮思考过长也可能吃满 max_tokens 配置导致 JSON 截断，
            // 重试轮在上限不为空时翻倍（至少 32768），给长思考留出完整输出空间
            if (fileDto == null) {
                sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（" + path + " 输出异常，正在重试…）");
                String retryPrompt = promptBuilder.buildSingleFilePrompt(genRequirement, path, existingContext,
                        "上一次输出被截断或格式非法。请务必压缩篇幅：删除全部注释、精简样式与结构，确保 JSON 完整且 content 为完整文件内容。");
                Integer retryMaxTokens = modelConfig.getMaxTokens() != null
                        ? Math.max(modelConfig.getMaxTokens() * 2, 32768) : null;
                fileDto = generateSingleFile(chatClient, systemPrompt, retryPrompt,
                        path, emitter, allReasoning, usageAgg, modelConfig, retryMaxTokens);
            }

            if (fileDto != null) {
                fileDto.setPath(path);
                fileDto.setAction(AiTemplateConstants.ACTION_CREATE);
                generatedFiles.add(fileDto);
                donePaths.add(path);
                if (AiTemplateConstants.FILE_LAYOUT.equals(path)) {
                    layoutContent = fileDto.getContent();
                }
                // 持久化 + 落盘 + 推送 file 事件（生成型会话无备份，messageId 传 null）
                try {
                    fileService.saveOrUpdateFile(session.getSessionId(), path,
                            fileDto.getContent() == null ? "" : fileDto.getContent(),
                            AiTemplateConstants.ACTION_CREATE);
                    writeToFile(session, fileDto, null);
                    sendFileEvent(emitter, fileDto);
                } catch (Exception e) {
                    log.warn("流水线文件写入失败: sessionId={}, path={}", session.getSessionId(), path, e);
                }
            } else {
                failedPaths.add(path);
                log.warn("流水线单文件生成失败（重试后仍失败）: sessionId={}, path={}", session.getSessionId(), path);
            }
            sendProgress(emitter, plannedFiles, -1, donePaths);
        }

        // ===== 汇总收尾 =====
        usageOut[0] = aggregateUsage(usageAgg);
        // donePaths 含断点续传时加载的历史文件，summary 统计全量完成度
        String summary = "已生成 " + donePaths.size() + "/" + plannedFiles.size() + " 个文件";
        if (!failedPaths.isEmpty()) {
            summary += "，失败：" + String.join("、", failedPaths) + "（可在输入框发送\"补齐\"或点击进度卡的\"补齐缺失文件\"按钮重试）";
        }
        String reasoningText = allReasoning.length() > 0 ? allReasoning.toString() : null;
        messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, summary, reasoningText);
        if (donePaths.isEmpty()) {
            sendError(emitter, "所有文件生成失败，请重试或调整需求描述");
            return;
        }
        sendDone(emitter, truncate(summary, 100));
        log.info("AI 模板分批生成完成: sessionId={}, resumed={}, planned={}, done={}, failed={}",
                session.getSessionId(), resumed, plannedFiles.size(), donePaths.size(), failedPaths.size());
    }

    /**
     * 解析会话持久化的规划文件清单（JSON 数组字符串）
     *
     * @return 合法清单；未持久化或解析失败时返回空列表（调用方按首次生成处理）
     */
    private List<String> parsePlanFiles(AiTemplateSession session) {
        String plan = session.getPlanFiles();
        if (!StringUtils.hasText(plan)) {
            return List.of();
        }
        try {
            List<String> files = PLAN_MAPPER.readValue(plan,
                    new tools.jackson.core.type.TypeReference<List<String>>() {});
            if (files == null) {
                return List.of();
            }
            return files.stream()
                    .filter(p -> p != null && !p.isBlank())
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析持久化 plan 失败（按首次生成处理）: sessionId={}", session.getSessionId(), e);
            return List.of();
        }
    }

    /**
     * 持久化规划文件清单到会话表（刷新页面重算进度卡、中断后断点续传的依据）；
     * 失败仅记录日志，不影响主流程（后续对话会按"已有文件对比"兜底）
     */
    private void persistPlan(AiTemplateSession session, List<String> plannedFiles) {
        try {
            session.setPlanFiles(PLAN_MAPPER.writeValueAsString(plannedFiles));
            sessionService.updateById(session);
        } catch (Exception e) {
            log.warn("持久化 plan 失败: sessionId={}", session.getSessionId(), e);
        }
    }

    /**
     * 断点续传判定：plan 已持久化且存在尚未生成的文件（对比 ai_template_file 已有记录）
     */
    private boolean hasMissingPlanFiles(AiTemplateSession session) {
        List<String> plan = parsePlanFiles(session);
        if (plan.isEmpty()) {
            // plan 为空且无任何已生成文件 = 首次生成彻底失败（如模型 404/异常），
            // 会话里只有用户需求。此时重发需求应重走流水线完整生成，
            // 而不是被当作"微调"落入单轮路径（微调拿不到文件清单上下文，输出必然无效）
            return fileService.listBySessionId(session.getSessionId()).isEmpty();
        }
        Set<String> done = fileService.listBySessionId(session.getSessionId()).stream()
                .map(AiTemplateFile::getFilePath)
                .collect(Collectors.toSet());
        return plan.stream().anyMatch(p -> !done.contains(p));
    }

    /**
     * 单文件生成轮：调用模型生成指定文件并解析校验
     *
     * @param maxTokensOverride 本轮覆盖的 max_tokens 上限（null 表示沿用模型配置默认值）。
     *                          推理模型的思考 tokens 计入 completion，思考过长 + 文件内容可能
     *                          触达配置上限导致 JSON 截断（解析失败），重试轮通过提高上限兜底
     * @return 校验通过的文件 DTO；调用异常/输出截断/解析失败/内容为空时返回 null（触发调用方重试）
     */
    private AiTemplateFileDto generateSingleFile(ChatClient chatClient, String systemPrompt, String filePrompt,
                                                 String targetPath, SseEmitter emitter, StringBuilder reasoningSink,
                                                 long[] usageAgg, AiModelConfig modelConfig, Integer maxTokensOverride) {
        ReplyStreamExtractor extractor = new ReplyStreamExtractor();
        long completionBefore = usageAgg[1];
        String response;
        try {
            response = callModelRound(chatClient,
                    List.of(new SystemMessage(systemPrompt), new UserMessage(filePrompt)),
                    emitter, extractor, reasoningSink, usageAgg,
                    buildPipelineOptions(modelConfig, maxTokensOverride));
        } catch (Exception e) {
            // 单轮调用异常（含两种超时）不冒泡：返回 null 走单文件重试，避免炸掉整条流水线
            log.warn("单文件生成调用异常（按失败处理，走重试）: path={}, err={}", targetPath, e.getMessage());
            return null;
        }
        long completionThisRound = usageAgg[1] - completionBefore;

        AiTemplateResponseParser.ParseResult parsed = responseParser.parseResponse(response);
        // 优先精确匹配 path；模型偶尔改写路径时容错取唯一文件
        AiTemplateFileDto target = null;
        for (AiTemplateFileDto f : parsed.getFiles()) {
            if (targetPath.equals(f.getPath())) {
                target = f;
                break;
            }
        }
        if (target == null && parsed.getFiles().size() == 1) {
            target = parsed.getFiles().get(0);
        }

        // 截断判定：以 JSON 实际解析结果为准（target 存在且 content 非空即成功）。
        // 推理模型的 reasoning tokens 也计入 completion，token 接近 max_tokens 不代表内容被截断，
        // 若作为失败依据会造成"内容完整却被误判丢弃"的假阳性（base.css 即此案例）；
        // 真截断时 JSON 必然不完整、解析拿不到 target，自然走重试路径。
        boolean valid = target != null && StringUtils.hasText(target.getContent());
        if (modelConfig.getMaxTokens() != null && completionThisRound >= modelConfig.getMaxTokens() * 0.9) {
            log.info("单文件输出接近 max_tokens 上限（仅记录，不判失败）: path={}, completion={}, maxTokens={}, parsed={}",
                    targetPath, completionThisRound, modelConfig.getMaxTokens(), valid);
        }
        if (!valid) {
            log.warn("单文件生成异常（未解析出有效内容）: path={}, hasContent={}, completion={}",
                    targetPath, target != null && StringUtils.hasText(target.getContent()), completionThisRound);
            return null;
        }
        // reply 兜底（流式期间未推出时补推）
        if (StringUtils.hasText(parsed.getReply()) && !extractor.wasEmitted()) {
            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n" + parsed.getReply());
        }
        return target;
    }

    /** 分块生成路径的块数上限：与规划 prompt 的"不超过 6 块"指引配套（留余量，超出走兜底重试） */
    private static final int MAX_CHUNK_PARTS = 8;

    /**
     * 分块生成路径（单文件直出失败后的降级方案）
     *
     * <p>针对超出 max_tokens 上限的大文件：规划轮让模型按功能划分块（输出极小不会截断），
     * 逐块轮每块输出量级远低于上限，从结构上保证「文件大小与 max_tokens 配置解耦」——
     * 文件再大也只是块数变多，单块仍能完整输出与校验，失败只重试该块，错误不传播。</p>
     *
     * <p>逐块轮复用 {@link #generateSingleFile}（reply+files 格式解析/校验/流式推送），
     * 每块的 content 即该块内容，全部成功后按序拼接为完整文件。</p>
     *
     * @return 拼接完成的文件 DTO；规划失败或任一块重试后仍失败时返回 null，由调用方走兜底重试
     */
    private AiTemplateFileDto generateFileByChunks(ChatClient chatClient, String systemPrompt,
                                                  String requirement, String targetPath, String existingContext,
                                                  SseEmitter emitter, StringBuilder reasoningSink,
                                                  long[] usageAgg, AiModelConfig modelConfig) {
        sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                "\n（" + targetPath + " 单轮输出失败（超限或格式非法），转分块生成模式）");
        try {
            // ===== 分块规划轮：只输出块数与每块摘要 =====
            String planPrompt = promptBuilder.buildChunkPlanPrompt(requirement, targetPath, existingContext);
            String planResponse = callModelRound(chatClient,
                    List.of(new SystemMessage(systemPrompt), new UserMessage(planPrompt)),
                    emitter, new ReplyStreamExtractor(), reasoningSink, usageAgg,
                    buildPipelineOptions(modelConfig, null));
            List<String> outline = parseChunkPlan(planResponse);
            if (outline == null || outline.size() < 2 || outline.size() > MAX_CHUNK_PARTS) {
                log.warn("分块规划解析失败或块数非法: path={}, response 前 200 字符={}", targetPath,
                        planResponse == null ? "null" : planResponse.substring(0, Math.min(200, planResponse.length())));
                return null;
            }
            int totalParts = outline.size();

            // ===== 逐块生成（块失败只重试该块，错误不传播）=====
            int maxLines = computeChunkPartLines(modelConfig);
            StringBuilder content = new StringBuilder();
            for (int i = 1; i <= totalParts; i++) {
                sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（分块 " + i + "/" + totalParts + "：" + outline.get(i - 1) + " …）");
                String partPrompt = promptBuilder.buildChunkPartPrompt(requirement, targetPath, i,
                        totalParts, outline, existingContext, maxLines, null);
                AiTemplateFileDto part = generateSingleFile(chatClient, systemPrompt, partPrompt,
                        targetPath, emitter, reasoningSink, usageAgg, modelConfig, null);
                if (part == null) {
                    sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "\n（第 " + i + " 块输出异常，压缩篇幅重试…）");
                    String retryPrompt = promptBuilder.buildChunkPartPrompt(requirement, targetPath, i,
                            totalParts, outline, existingContext, maxLines,
                            "上一次输出被截断或格式非法。请压缩篇幅至一半以内，确保 JSON 完整且 content 为本块完整内容。");
                    part = generateSingleFile(chatClient, systemPrompt, retryPrompt,
                            targetPath, emitter, reasoningSink, usageAgg, modelConfig, null);
                }
                if (part == null || !StringUtils.hasText(part.getContent())) {
                    log.warn("分块生成失败（块级重试后仍失败）: path={}, part={}/{}", targetPath, i, totalParts);
                    return null;
                }
                content.append(part.getContent()).append('\n');
            }

            AiTemplateFileDto fileDto = new AiTemplateFileDto();
            fileDto.setPath(targetPath);
            fileDto.setContent(content.toString());
            fileDto.setAction(AiTemplateConstants.ACTION_CREATE);
            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "\n（" + targetPath + " 分块生成完成，共 " + totalParts + " 块）");
            return fileDto;
        } catch (Exception e) {
            log.warn("分块生成异常: path={}", targetPath, e);
            return null;
        }
    }

    /**
     * 构建流水线轮次的 runtime options 覆盖
     *
     * <p>分批流水线各轮（规划/单文件/分块/重试）统一走本方法：</p>
     * <ul>
     *     <li>model、temperature、maxTokens 从模型配置显式复制（maxTokensOverride 非空时覆盖）。
     *         关键教训：本版本 spring-ai-openai 底层为官方 OpenAI SDK，runtime options 传入后
     *         <b>不会</b>从 ChatModel 默认 options 回填 model 等请求级字段——漏设 model 时 SDK
     *         会用自己的默认值（gpt-5-mini）发请求，兼容端点直接报 404 model not exist</li>
     *     <li>Qwen3 系列追加 reasoning_effort=low：API 级思考预算控制。
     *         实测 /no_think 软开关对新版 Qwen3.x 已失效（单轮思考仍可吃满 max_tokens，
     *         base.css 案例单文件耗时 50 分钟，其中 16 块每块长思考约 2 分钟），
     *         reasoning_effort 是标准请求体参数，端点不识别时会被忽略，无害</li>
     * </ul>
     *
     * @return 覆盖 options；modelConfig 为空时返回 null（沿用模型配置默认值）
     */
    private OpenAiChatOptions buildPipelineOptions(AiModelConfig modelConfig, Integer maxTokensOverride) {
        if (modelConfig == null) {
            return null;
        }
        // 必须从 baseOptionsBuilder 基底出发：runtime options 不会从默认 options 继承
        // 请求级字段（apiKey/baseUrl/timeout/customHeaders），漏设 timeout 会回退 SDK
        // 默认 60s callTimeout → 长推理流被掐断报 "Stream failed"（实测踩坑）
        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.baseOptionsBuilder(modelConfig);
        if (maxTokensOverride != null) {
            builder.maxTokens(maxTokensOverride);
        }
        if (modelConfig.getModel() != null && modelConfig.getModel().toLowerCase().contains("qwen3")) {
            builder.reasoningEffort("low");
        }
        return builder.build();
    }

    /**
     * 解析分块规划轮响应：{"total": N, "outline": ["摘要", ...]}
     *
     * <p>块数以 outline 数组实际长度为准（模型偶尔漏写/多写 total 字段，不依赖它）。</p>
     *
     * @return 块摘要列表；解析失败或为空时返回 null
     */
    private List<String> parseChunkPlan(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String text = raw.trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            tools.jackson.databind.JsonNode root = PLAN_MAPPER.readTree(text.substring(start, end + 1));
            tools.jackson.databind.JsonNode outline = root.get("outline");
            if (outline == null || !outline.isArray() || outline.isEmpty()) {
                return null;
            }
            List<String> result = new ArrayList<>(outline.size());
            for (tools.jackson.databind.JsonNode item : outline) {
                String s = item.isTextual() ? item.asString() : item.toString();
                if (StringUtils.hasText(s)) {
                    result.add(s.trim());
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("分块规划 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按模型 max_tokens 配置动态计算单块行数上限
     *
     * <p>块预算取 max_tokens 的一半（另一半留给 JSON 转义膨胀、reply 与安全余量），
     * 紧凑代码一行约 12 token；下限 20 行保证进度、上限 100 行控制单块风险。</p>
     */
    private int computeChunkPartLines(AiModelConfig modelConfig) {
        long budget = modelConfig.getMaxTokens() != null ? modelConfig.getMaxTokens() : 8192;
        return (int) Math.max(20, Math.min(budget / 2 / 12, 100));
    }

    /**
     * 通用单轮流式调用：reply 增量 + reasoning 增量实时推送，聚合完整响应返回
     *
     * <p>分批流水线各轮与单轮对话路径共用；usage 累计到 usageAgg[0..2]。</p>
     */
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseEmitter emitter,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg) {
        return callModelRound(chatClient, messages, emitter, replyExtractor, reasoningBuf, usageAgg, null);
    }

    /**
     * 带 options 覆盖的单轮流式调用
     *
     * <p>覆盖项通过 {@link #buildPipelineOptions} 构造（maxTokens 重试翻倍、思考预算控制），
     * 其余字段（model、apiKey、temperature 等）仍沿用构建 ChatModel 时的默认 options。</p>
     */
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseEmitter emitter,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg,
                                  OpenAiChatOptions optionsOverride) {
        StringBuilder responseBuffer = new StringBuilder();
        try {
            Prompt roundPrompt = optionsOverride != null
                    ? new Prompt(messages, optionsOverride)
                    : new Prompt(messages);
            chatClient.prompt(roundPrompt)
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        // 捕获 token 用量（OpenAI 兼容流式仅在最后一个 chunk 携带 usage，累加聚合）
                        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                            org.springframework.ai.chat.metadata.Usage u = resp.getMetadata().getUsage();
                            if (u.getTotalTokens() != null || u.getPromptTokens() != null || u.getCompletionTokens() != null) {
                                int prompt = u.getPromptTokens() != null ? u.getPromptTokens() : 0;
                                int completion = u.getCompletionTokens() != null ? u.getCompletionTokens() : 0;
                                int total = u.getTotalTokens() != null ? u.getTotalTokens() : prompt + completion;
                                usageAgg[0] += prompt;
                                usageAgg[1] += completion;
                                usageAgg[2] += total;
                            }
                        }
                        if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                            return;
                        }
                        org.springframework.ai.chat.messages.AssistantMessage output = resp.getResult().getOutput();
                        // 推理模型的思考过程（非推理模型无此字段，跳过）
                        Object reasoning = output.getMetadata() == null
                                ? null : output.getMetadata().get("reasoningContent");
                        if (reasoning != null && StringUtils.hasText(String.valueOf(reasoning))) {
                            String rc = String.valueOf(reasoning);
                            String prev = reasoningBuf.toString();
                            if (rc.length() > prev.length() && rc.startsWith(prev)) {
                                // 累积模式：推送差分增量
                                String delta = rc.substring(prev.length());
                                if (StringUtils.hasText(delta)) {
                                    sendEvent(emitter, AiTemplateConstants.SSE_EVENT_REASONING, delta);
                                }
                            } else if (!rc.equals(prev)) {
                                // 兜底：非累积模式（纯增量），直接推送
                                sendEvent(emitter, AiTemplateConstants.SSE_EVENT_REASONING, rc);
                            }
                            reasoningBuf.setLength(0);
                            reasoningBuf.append(rc);
                        }
                        // 正文增量
                        String chunk = output.getText();
                        if (!StringUtils.hasText(chunk)) {
                            return;
                        }
                        responseBuffer.append(chunk);
                        String replyDelta = replyExtractor.feed(chunk);
                        if (StringUtils.hasText(replyDelta)) {
                            sendEvent(emitter, AiTemplateConstants.SSE_EVENT_MESSAGE, replyDelta);
                        }
                    })
                    // 双超时兜底（模型流可能无限挂起）：
                    // 1. 信号间隔超时：连续 ROUND_SIGNAL_TIMEOUT 无任何增量（含思考增量）判定为流死；
                    //    但 keepalive/心跳类信号会不断重置该计时，因此单靠它不够
                    // 2. 总时长上限（blockLast 按墙钟计时，任何信号无法重置）：无论信号是否活跃，
                    //    单轮整体超过 ROUND_TOTAL_TIMEOUT 强制中断——实测出现过"思考完成后流停滞
                    //    但心跳不断"的挂死，正是靠这一层兜住
                    // 两种超时均抛异常，单轮对话路径转为 error 事件，流水线路径由
                    // generateSingleFile 捕获后走单文件重试
                    .timeout(ROUND_SIGNAL_TIMEOUT)
                    .blockLast(ROUND_TOTAL_TIMEOUT);
        } catch (Exception e) {
            log.error("ChatClient 流式调用失败", e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
        return responseBuffer.toString();
    }

    /**
     * 推送进度快照（全量文件清单及状态），前端渲染进度卡
     *
     * @param plannedFiles 规划的文件清单
     * @param currentIndex 当前正在生成的文件下标（-1 表示无生成中文件）
     * @param donePaths    已完成的文件路径
     */
    private void sendProgress(SseEmitter emitter, List<String> plannedFiles, int currentIndex, List<String> donePaths) {
        StringBuilder sb = new StringBuilder("{\"files\":[");
        for (int i = 0; i < plannedFiles.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String p = plannedFiles.get(i);
            String status = donePaths.contains(p) ? "done" : (i == currentIndex ? "current" : "pending");
            sb.append("{\"path\":\"").append(escapeJson(p))
                    .append("\",\"status\":\"").append(status).append("\"}");
        }
        sb.append("]}");
        sendEvent(emitter, AiTemplateConstants.SSE_EVENT_PROGRESS, sb.toString());
    }

    /**
     * 构建逐文件轮的上下文：已生成文件清单 + _layout.html 完整内容（截断）
     *
     * <p>页面文件依赖 _layout.html 的宏结构，注入全文保证各页面正确复用宏；
     * 其余文件仅注入路径清单，控制上下文规模。</p>
     */
    private String buildGenContext(List<AiTemplateFileDto> generatedFiles, String layoutContent) {
        if (generatedFiles.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AiTemplateFileDto f : generatedFiles) {
            sb.append("- ").append(f.getPath()).append("\n");
        }
        if (StringUtils.hasText(layoutContent)) {
            String clamped = layoutContent.length() > 6000
                    ? layoutContent.substring(0, 6000) + "\n...（过长已截断）" : layoutContent;
            sb.append("\n_layout.html 完整内容（页面必须复用其中的宏结构）：\n```\n")
                    .append(clamped).append("\n```\n");
        }
        return sb.toString();
    }

    /**
     * 聚合用量（分批流水线多轮 / 单轮 chunk 累加）转为 Usage
     */
    private org.springframework.ai.chat.metadata.Usage aggregateUsage(long[] usageAgg) {
        if (usageAgg[0] == 0 && usageAgg[1] == 0 && usageAgg[2] == 0) {
            return null;
        }
        return new org.springframework.ai.chat.metadata.Usage() {
            @Override
            public Integer getPromptTokens() {
                return (int) usageAgg[0];
            }

            @Override
            public Integer getCompletionTokens() {
                return (int) usageAgg[1];
            }

            @Override
            public Integer getTotalTokens() {
                return (int) (usageAgg[2] > 0 ? usageAgg[2] : usageAgg[0] + usageAgg[1]);
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }

    // ==================== 应用模板 ====================

    @Override
    public String applyTemplate(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        // 调整型会话的 AI 输出已直接写入正式模板目录，不存在"应用"动作
        if (StringUtils.hasText(session.getTemplateId())) {
            throw new IllegalStateException("调整型会话的修改已直接生效，无需应用");
        }

        Path workDir = Paths.get(session.getWorkDir());
        if (!Files.exists(workDir) || !Files.isDirectory(workDir)) {
            throw new IllegalStateException("会话工作目录不存在: " + workDir);
        }

        // 校验必备文件
        Path propertiesPath = workDir.resolve(AiTemplateConstants.FILE_TEMPLATE_PROPERTIES);
        if (!Files.exists(propertiesPath)) {
            throw new IllegalStateException("缺少必备文件: " + AiTemplateConstants.FILE_TEMPLATE_PROPERTIES);
        }

        // 目标模板目录
        String templateDir = DirUtils.getTemplateDir();
        if (!StringUtils.hasText(templateDir)) {
            throw new IllegalStateException("模板根目录未配置");
        }
        Path targetPath = Paths.get(templateDir, session.getTemplateName());

        // 若目标已存在，先删除（覆盖更新）
        if (Files.exists(targetPath)) {
            deleteDirectory(targetPath);
        }

        // 复制工作目录到目标目录
        try {
            copyDirectory(workDir, targetPath);
        } catch (IOException e) {
            throw new RuntimeException("复制模板文件失败: " + e.getMessage(), e);
        }

        // 刷新模板注册
        try {
            templateService.initialize();
        } catch (Exception e) {
            throw new RuntimeException("刷新模板注册失败: " + e.getMessage(), e);
        }

        // 更新会话状态
        sessionService.updateStatus(sessionId, AiTemplateConstants.STATUS_APPLIED);

        String result = "模板已应用到 " + targetPath + "，可在模板列表中查看并切换使用";
        log.info("AI 模板应用成功: sessionId={}, templateName={}, target={}",
                sessionId, session.getTemplateName(), targetPath);
        return result;
    }

    // ==================== 回滚 ====================

    @Override
    public String rollbackLast(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (!StringUtils.hasText(session.getTemplateId())) {
            throw new IllegalArgumentException("仅调整型会话支持回滚");
        }

        List<AiTemplateFileBackup> backups = backupService.listLatestRoundBackups(sessionId);
        if (backups == null || backups.isEmpty()) {
            throw new IllegalStateException("当前会话没有可回滚的修改");
        }

        Path workDir = Paths.get(session.getWorkDir());
        List<String> restored = new ArrayList<>();
        for (AiTemplateFileBackup backup : backups) {
            Path target = workDir.resolve(backup.getFilePath()).normalize();
            // 防路径穿越（备份记录理应合法，防御性检查）
            if (!target.startsWith(workDir)) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(backup.getExisted())) {
                    if (backup.getContent() == null) {
                        log.warn("备份缺少旧内容，跳过恢复: sessionId={}, path={}", sessionId, backup.getFilePath());
                        continue;
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(target, backup.getContent().getBytes(StandardCharsets.UTF_8));
                    restored.add(backup.getFilePath());
                } else {
                    // AI 新建的文件：回滚即删除
                    Files.deleteIfExists(target);
                    restored.add(backup.getFilePath());
                }
            } catch (IOException e) {
                log.warn("回滚文件失败: sessionId={}, path={}", sessionId, backup.getFilePath(), e);
            }
        }

        // 清理该轮备份（下一次回滚作用于更早一轮）及对应的文件记录
        Long messageId = backups.get(0).getMessageId();
        backupService.deleteByMessageId(messageId);
        for (AiTemplateFileBackup backup : backups) {
            fileService.removeFile(sessionId, backup.getFilePath());
        }

        String result = "已回滚最近一轮修改：" + String.join("、", restored);
        log.info("AI 模板回滚完成: sessionId={}, messageId={}, files={}", sessionId, messageId, restored.size());
        return result;
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造会话预览工作目录路径
     *
     * <p>工作目录格式：{@code <previewRoot>/<sessionId>/<templateName>/}
     * 加 sessionId 前缀避免不同会话同名模板冲突。</p>
     */
    private Path getPreviewWorkDir(String sessionId, String templateName) {
        Path previewRoot = resolvePreviewRoot();
        Path workDirPath = previewRoot.resolve(sessionId).resolve(templateName);
        try {
            Files.createDirectories(workDirPath);
        } catch (IOException e) {
            throw new RuntimeException("创建预览工作目录失败: " + workDirPath, e);
        }
        return workDirPath;
    }

    /**
     * 解析预览根目录：优先取配置项 fastcms.ai.template.preview-root，
     * 未配置时默认 {@code ~/fastcms/ai-template-preview}。
     */
    private Path resolvePreviewRoot() {
        if (StringUtils.hasText(previewRootConfig)) {
            return Paths.get(previewRootConfig);
        }
        return Paths.get(System.getProperty("user.home"), "fastcms", "ai-template-preview");
    }

    /**
     * 将文件内容写入会话工作目录
     *
     * <p>生成型会话写入预览工作目录；调整型会话写入正式模板目录，
     * 且写入/删除前先备份旧内容（以 messageId 为回滚粒度）。</p>
     */
    private void writeToFile(AiTemplateSession session, AiTemplateFileDto file, Long messageId) throws IOException {
        Path workDir = Paths.get(session.getWorkDir());
        Path filePath = workDir.resolve(file.getPath()).normalize();

        // 安全检查：防止路径穿越
        if (!filePath.startsWith(workDir)) {
            throw new SecurityException("非法文件路径: " + file.getPath());
        }

        // 调整型会话：写盘前备份（含 delete 动作——被删的文件也需要可恢复）
        if (StringUtils.hasText(session.getTemplateId()) && messageId != null) {
            backupService.backupBeforeWrite(session.getSessionId(), messageId, file.getPath(), filePath);
        }

        if (AiTemplateConstants.ACTION_DELETE.equals(file.getAction())) {
            Files.deleteIfExists(filePath);
            return;
        }

        // 创建父目录
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 写入文件
        Files.write(filePath, file.getContent().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造当前会话已有文件清单（供微调提示词使用）
     */
    private String buildCurrentFileList(String sessionId) {
        List<AiTemplateFile> files = fileService.listBySessionId(sessionId);
        if (files.isEmpty()) {
            return "（暂无文件）";
        }
        return files.stream()
                .map(f -> "- " + f.getFilePath() + " (" + f.getAction() + ")")
                .collect(Collectors.joining("\n"));
    }

    /**
     * 调整型会话注入提示词的模板文件内容上限（字符数）
     */
    private static final long ADJUST_MAX_TOTAL_CHARS = 400_000L;

    /**
     * 单个文件注入提示词的内容上限（字符数）
     */
    private static final long ADJUST_MAX_FILE_CHARS = 100_000L;

    /**
     * 纳入提示词的文本文件后缀（其余视为二进制资源，跳过）
     */
    private static final Set<String> ADJUST_TEXT_EXTENSIONS = Set.of(
            "html", "css", "js", "properties", "txt", "json", "xml", "md", "ftl", "svg", "scss", "less");

    /**
     * 归一化前端传入的当前文件路径：去掉模板目录前缀并统一分隔符，
     * 使其与提示词文件清单中的相对路径（如 index.html、static/css/base.css）一致。
     */
    private String normalizeRelativePath(String filePath, String workDir) {
        if (!StringUtils.hasText(filePath)) {
            return null;
        }
        String normalized = filePath.replace("\\", "/");
        String dirName = Paths.get(workDir).getFileName().toString();
        if (normalized.startsWith(dirName + "/")) {
            normalized = normalized.substring(dirName.length() + 1);
        }
        return normalized;
    }

    /**
     * 构造正式模板当前文件内容（供调整型会话提示词使用）
     *
     * <p>每轮对话都从磁盘实时读取，保证用户在两轮之间通过编辑器手工修改的内容
     * 也能被 AI 感知。二进制资源（图片/字体）跳过；总量超限时截断并提示。</p>
     */
    private String buildTemplateFileSection(Path templateDir) {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        int included = 0;
        try (Stream<Path> stream = Files.walk(templateDir)) {
            List<Path> textFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        int dot = name.lastIndexOf('.');
                        String ext = dot < 0 ? "" : name.substring(dot + 1);
                        return ADJUST_TEXT_EXTENSIONS.contains(ext);
                    })
                    .sorted()
                    .collect(Collectors.toList());

            for (Path p : textFiles) {
                String rel = templateDir.relativize(p).toString().replaceAll("\\\\", "/");
                String content;
                try {
                    content = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // 非 UTF-8 或读取失败的内容跳过，不影响其余文件
                    continue;
                }
                if (content.length() > ADJUST_MAX_FILE_CHARS) {
                    content = content.substring(0, (int) ADJUST_MAX_FILE_CHARS) + "\n...（内容过长已截断）";
                }
                if (total + content.length() > ADJUST_MAX_TOTAL_CHARS) {
                    sb.append("（其余文件因内容过多未列出，可分多轮调整）\n");
                    break;
                }
                total += content.length();
                sb.append("### ").append(rel).append("\n```\n").append(content).append("\n```\n\n");
                included++;
            }
        } catch (IOException e) {
            log.warn("扫描模板目录失败: {}", templateDir, e);
        }
        if (included == 0 && sb.length() == 0) {
            sb.append("（模板目录没有可注入的文本文件）");
        }
        return sb.toString();
    }

    private void sendFileEvent(SseEmitter emitter, AiTemplateFileDto file) {
        String data = "{\"path\":\"" + escapeJson(file.getPath())
                + "\",\"action\":\"" + escapeJson(file.getAction()) + "\"}";
        sendEvent(emitter, AiTemplateConstants.SSE_EVENT_FILE, data);
    }

    private void sendDone(SseEmitter emitter, String summary) {
        String data = "{\"summary\":\"" + escapeJson(summary) + "\"}";
        sendEvent(emitter, AiTemplateConstants.SSE_EVENT_DONE, data);
    }

    private void sendError(SseEmitter emitter, String message) {
        String data = "{\"message\":\"" + escapeJson(message) + "\"}";
        sendEvent(emitter, AiTemplateConstants.SSE_EVENT_ERROR, data);
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("SSE 推送失败: event={}, {}", eventName, e.getMessage());
        } catch (IllegalStateException e) {
            // 客户端断开后 emitter 被容器标记为已完成，后续 send 抛 IllegalStateException：
            // 只跳过推送，不中断生成流程（文件仍会完整落盘，前端刷新后可见）
            log.warn("SSE 推送失败（连接已关闭）: event={}, {}", eventName, e.getMessage());
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 简易 JSON 字符串转义（避免引入额外依赖）
     */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void validateRequest(AiTemplateSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        // 调整型会话：模板名/需求描述均从正式模板与首轮对话中产生，跳过生成型校验
        if (StringUtils.hasText(request.getTemplateId())) {
            return;
        }
        if (!StringUtils.hasText(request.getTemplateName())) {
            throw new IllegalArgumentException("模板目录名不能为空");
        }
        // 模板目录名必须为英文、数字、下划线、横线
        if (!request.getTemplateName().matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
            throw new IllegalArgumentException("模板目录名必须以英文字母开头，只能包含字母、数字、下划线、横线");
        }
        if (!StringUtils.hasText(request.getRequirement())) {
            throw new IllegalArgumentException("需求描述不能为空");
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("删除目录失败: {}", path, e);
        }
    }

    /**
     * 递归复制目录
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ==================== 模板源码目录镜像（已移除） ====================
    // templates 模块已从 Maven 编译链移除，dev/prod 模板目录均直接指向持久化文件目录
    // （dev=templates/src/main/resources，prod=部署目录/htmls），AI 写入天然持久化，无需镜像。

}
