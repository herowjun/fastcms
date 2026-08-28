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
import org.springframework.beans.factory.annotation.Autowired;
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
     * 预览目录根目录：相对于工作目录的 ai-template-preview/
     * <p>所有会话的工作目录都在此目录下，避免与正式模板目录冲突。</p>
     */
    private static final String PREVIEW_ROOT = "ai-template-preview";

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
                sendError(emitter, e.getMessage() == null ? e.toString() : e.getMessage());
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

        // 2. 加载历史消息
        List<AiTemplateMessage> history = messageService.listBySessionId(session.getSessionId());
        boolean isFirstChat = history.isEmpty();

        // 3. 构造消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(session.getTemplateName())));

        // 加入历史消息（保持上下文）
        for (AiTemplateMessage msg : history) {
            if (AiTemplateConstants.ROLE_USER.equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (AiTemplateConstants.ROLE_ASSISTANT.equals(msg.getRole())) {
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
        } else if (isFirstChat) {
            userPrompt = promptBuilder.buildUserPrompt(session.getTemplateName(), userInput);
        } else {
            // 微调场景：附带当前已有文件清单
            String currentFiles = buildCurrentFileList(session.getSessionId());
            userPrompt = promptBuilder.buildRefinePrompt(userInput, currentFiles);
        }
        messages.add(new UserMessage(userPrompt));

        // 4. 先保存用户消息
        messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_USER, userInput);

        // 5. 调用 ChatClient 流式接口（Spring AI stream()）：
        //    AI 返回的是结构化 JSON（{reply, files}），通过 ReplyStreamExtractor 增量提取
        //    reply 字段的自然语言文本实时推送给前端（打字机效果），
        //    同时聚合完整响应到缓冲区——必须等完整响应才能解析文件。
        //    推理模型（Qwen3/DeepSeek-R1 等）会先输出 reasoning_content 思考过程，
        //    Spring AI 将其透传到 AssistantMessage.metadata["reasoningContent"]，
        //    这里同样增量推送给前端实时展示
        ChatClient chatClient = ChatClient.builder(chatModel)
                // 挂载 @AiTool 注册的工具（当前无工具时为空数组，不影响调用）
                .defaultTools(toolCallbackProvider.getToolCallbacks())
                .build();
        StringBuilder responseBuffer = new StringBuilder();
        ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
        // Spring AI 透传的 reasoningContent 是"累积值"（每个 chunk 带到当前为止的完整思考文本），
        // 做差分后仅推送新增部分
        StringBuilder reasoningBuf = new StringBuilder();
        try {
            chatClient.prompt(new Prompt(messages))
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        // 捕获 token 用量（OpenAI 兼容流式仅在最后一个 chunk 携带 usage，持续覆盖取最后值）
                        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null
                                && resp.getMetadata().getUsage().getTotalTokens() != null) {
                            lastUsage[0] = resp.getMetadata().getUsage();
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
                    .blockLast();
        } catch (Exception e) {
            log.error("ChatClient 流式调用失败: sessionId={}", session.getSessionId(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }

        String fullResponse = responseBuffer.toString();
        if (!StringUtils.hasText(fullResponse)) {
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
     * <p>工作目录格式：{@code <工作目录>/ai-template-preview/<sessionId>/<templateName>/}
     * 加 sessionId 前缀避免不同会话同名模板冲突。</p>
     */
    private Path getPreviewWorkDir(String sessionId, String templateName) {
        String workDir = System.getProperty("user.dir");
        Path previewRoot = Paths.get(workDir, PREVIEW_ROOT, sessionId, templateName);
        try {
            Files.createDirectories(previewRoot);
        } catch (IOException e) {
            throw new RuntimeException("创建预览工作目录失败: " + previewRoot, e);
        }
        return previewRoot;
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

}
