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
import com.fastcms.ai.template.ComponentGenPromptBuilder;
import com.fastcms.ai.template.IAiTemplateGenService;
import com.fastcms.ai.template.TemplateGenPromptBuilder;
import com.fastcms.ai.component.PageSpecParser;
import com.fastcms.ai.component.PageSpecRenderer;
import com.fastcms.ai.support.FileProgressScanner;
import com.fastcms.ai.support.ReplyStreamExtractor;
import com.fastcms.ai.tool.AiToolCallbackProvider;
import com.fastcms.cms.entity.ArticleCategory;
import com.fastcms.cms.entity.Menu;
import com.fastcms.cms.entity.SinglePage;
import com.fastcms.cms.service.IArticleCategoryService;
import com.fastcms.cms.service.IMenuService;
import com.fastcms.cms.service.ISinglePageService;
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
import org.springframework.transaction.annotation.Transactional;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * 调整型会话渲染校验失败后的自动修复轮数上限（每轮都会把渲染错误反馈给模型）
     */
    private static final int MAX_RENDER_FIX_ATTEMPTS = 2;

    /**
     * 从（可能被截断的）响应原文中救出 reply 字段值
     *
     * <p>reply 字段位于响应 JSON 开头、通常在截断点之前就已输出完整。
     * 用正则提取字符串值（处理常见转义），救不出返回 null。</p>
     */
    private String extractReplySalvage(String fullResponse) {
        if (fullResponse == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"reply\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(fullResponse);
        if (m.find()) {
            return m.group(1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return null;
    }

    /**
     * 调整型会话单轮输出上限收紧值：调整是"改文件"任务，收紧上限让思考吃满时
     * 尽早暴露（触发翻倍重试），避免长时间无产出等待
     */
    private static final int ADJUST_MAX_TOKENS_CAP = 8192;

    /**
     * 预览渲染引擎（渲染校验与预览页面共用同一管线）
     */
    @Autowired
    private com.fastcms.ai.template.AiTemplatePreviewRenderer previewRenderer;

    /**
     * 旧模板「样式组件化」升级器（确定性前置 + 锚点扫描/校验，AI 改造轮由本服务驱动）
     */
    @Autowired
    private com.fastcms.ai.component.LegacyStyleUpgrader styleUpgrader;

    /**
     * SSE 流式调用的专用线程池（避免阻塞 Servlet 容器线程）。
     *
     * <p>模板生成是长任务（单会话最长 60 分钟），旧实现用 newCachedThreadPool 无上限创建线程，
     * 并发滥用会耗尽线程资源；这里改为有界池（SynchronousQueue，超过 max 直接拒绝），
     * 拒绝时向调用方返回"并发已达上限"提示。</p>
     */
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "ai-template-sse");
                t.setDaemon(true);
                return t;
            });

    /**
     * 客户端取消/断开时中断生成的信号（非失败，单独类型避免被当作生成错误处理）
     */
    private static final class ChatCancelledException extends RuntimeException {
        ChatCancelledException() {
            super("AI 生成已取消（客户端断开）");
        }
    }

    /**
     * SSE 通道封装：客户端断开检测与事件推送
     *
     * <p>断开感知的两条路径：①send 失败抛 IOException/IllegalStateException（容器在写出时发现连接已断）；
     * ②emitter.onError/onTimeout 回调。任一路径触发即标记取消，
     * 各生成轮次在 doOnNext/循环顶部检测取消标记，抛出 {@link ChatCancelledException} 中断生成，
     * 不再白烧上游 token（已生成的文件已落盘，断点续传语义保留）。</p>
     */
    private static final class SseChannel {
        private final SseEmitter emitter;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        SseChannel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void markCancelled() {
            cancelled.set(true);
        }

        void send(String eventName, String data) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                // 客户端已断开：标记取消（后续轮次中断），推送失败静默（无接收方）
                cancelled.set(true);
                log.debug("SSE 推送失败（客户端断开）: event={}", eventName);
            }
        }

        void complete() {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * applyTemplate 按模板名互斥锁（不同会话可应用同名模板，并发 apply 会互相踩踏）
     */
    private static final Map<String, Object> APPLY_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * 附件服务（图片槽位点选换图：附件 ID → URL 解析）
     */
    @Autowired
    private com.fastcms.service.IAttachmentService attachmentService;

    /**
     * 组件化生成管线（AI 输出 PageSpec → PageSpecRenderer 渲染）：
     * component（默认）/ html（直写 HTML 的旧分批流水线），可配置回退
     */
    @Value("${fastcms.ai.template.gen-mode:component}")
    private String genMode;

    @Autowired
    private com.fastcms.ai.template.ComponentGenPromptBuilder componentGenPromptBuilder;

    @Autowired
    private com.fastcms.ai.component.PageSpecParser pageSpecParser;

    @Autowired
    private com.fastcms.ai.component.PageSpecValidator pageSpecValidator;

    @Autowired
    private com.fastcms.ai.component.PageSpecRenderer pageSpecRenderer;

    @Autowired
    private com.fastcms.ai.component.AttachmentImageSearcher attachmentImageSearcher;

    /**
     * 站点数据初始化（应用模板时按 _pagespec.json 的信息架构补建菜单/分类/单页）
     */
    @Autowired
    private IMenuService menuService;

    @Autowired
    private IArticleCategoryService articleCategoryService;

    @Autowired
    private ISinglePageService singlePageService;

    // ==================== 会话管理 ====================

    @Override
    public AiTemplateSession createSession(AiTemplateSessionRequest request, Long userId) {
        validateRequest(request);

        AiTemplateSession session = new AiTemplateSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setRequirement(request.getRequirement());
        // 移动端适配选项：null 视为 true（兼容旧客户端与调整型会话）
        session.setMobileAdaptive(request.getMobileAdaptive() == null || request.getMobileAdaptive());
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

    /**
     * 会话的移动端适配选项：null 视为 true（旧会话/调整型会话未设置时保持响应式默认）
     */
    private boolean isMobileAdaptive(AiTemplateSession session) {
        return session.getMobileAdaptive() == null || session.getMobileAdaptive();
    }

    @Override
    public Path resolveEffectiveWorkDir(AiTemplateSession session) {
        if (!StringUtils.hasText(session.getWorkDir())) {
            throw new IllegalArgumentException("会话未记录工作目录: " + session.getSessionId());
        }
        // 调整型会话：会话存储的绝对路径可能因模板目录迁移而失效（如 templates 模块移除后
        // target/classes 不复存在），按模板 ID 实时解析当前正式模板目录
        if (StringUtils.hasText(session.getTemplateId())) {
            Template template = templateService.getTemplate(session.getTemplateId());
            if (template != null && template.getTemplatePath() != null) {
                Path current = template.getTemplatePath();
                if (!Paths.get(session.getWorkDir()).equals(current)) {
                    log.info("调整型会话工作目录已迁移，自动修正: sessionId={}, {} -> {}",
                            session.getSessionId(), session.getWorkDir(), current);
                    session.setWorkDir(current.toString());
                    sessionService.updateById(session);
                }
                return current;
            }
        }
        return Paths.get(session.getWorkDir());
    }

    @Override
    public List<AiTemplateSession> listSessions(Long userId) {
        return sessionService.listByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            return;
        }
        // 先删数据库记录（事务内保证多表一致；旧实现先删磁盘目录再删库，
        // DB 删除失败会留下"目录已删但会话仍出现在列表里"的孤儿数据）
        messageService.deleteBySessionId(sessionId);
        fileService.deleteBySessionId(sessionId);
        backupService.deleteBySessionId(sessionId);
        sessionService.removeById(session.getId());
        // 再删预览工作目录（仅生成型会话；调整型会话的工作目录是正式模板目录，禁止删除）。
        // 目录删除失败只留下无 DB 记录的孤儿目录（不出现在会话列表，可手工清理），可接受
        if (session.getTemplateId() == null && StringUtils.hasText(session.getWorkDir())) {
            deleteDirectory(Paths.get(session.getWorkDir()));
        }
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

    @Override
    public com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeStatusInfo getLegacyUpgradeStatus(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            return new com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeStatusInfo(false, 0, 0, 0, false);
        }
        try {
            return styleUpgrader.getStatus(resolveEffectiveWorkDir(session));
        } catch (Exception e) {
            return new com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeStatusInfo(false, 0, 0, 0, false);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public java.util.List<String> updateImageSlot(String sessionId, String sectionId, String slot, Long attachmentId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!StringUtils.hasText(sectionId) || !StringUtils.hasText(slot)) {
            throw new IllegalArgumentException("缺少图片槽位标识（sectionId/slot）");
        }
        if (attachmentId == null) {
            throw new IllegalArgumentException("缺少附件ID");
        }
        Path workDir = resolveEffectiveWorkDir(session);
        if (!Files.isRegularFile(workDir.resolve(COMPONENT_SPEC_FILE))) {
            throw new IllegalArgumentException("当前模板未组件化（缺少 " + COMPONENT_SPEC_FILE + "），不支持点选换图");
        }

        // 附件 → URL（服务端解析，前端只传附件 ID）
        com.fastcms.entity.Attachment attachment = attachmentService.getById(attachmentId);
        if (attachment == null) {
            throw new IllegalArgumentException("附件不存在: " + attachmentId);
        }
        String imageUrl;
        try {
            imageUrl = attachment.getPath();
        } catch (Exception e) {
            // 无应用上下文（理论上不可达，防御式兜底）：站内绝对路径
            imageUrl = "/" + attachment.getFilePath();
        }

        // 读 spec → 定位 section 槽位 → 替换值 + imageAssets 解析记录
        com.fastcms.ai.component.PageSpec spec;
        try {
            String raw = Files.readString(workDir.resolve(COMPONENT_SPEC_FILE), StandardCharsets.UTF_8);
            spec = pageSpecParser.parseResponse(raw).pagespec();
        } catch (IOException e) {
            throw new IllegalStateException("读取 PageSpec 失败: " + e.getMessage(), e);
        }
        if (spec == null || spec.pages() == null) {
            throw new IllegalStateException("PageSpec 解析失败，请回到 AI 对话中修复后重试");
        }

        java.util.Map<String, com.fastcms.ai.component.PageSpecPage> newPages = new java.util.LinkedHashMap<>();
        boolean updated = false;
        for (Map.Entry<String, com.fastcms.ai.component.PageSpecPage> entry : spec.pages().entrySet()) {
            List<com.fastcms.ai.component.SectionSpec> newSections = new ArrayList<>();
            boolean pageChanged = false;
            for (com.fastcms.ai.component.SectionSpec section : entry.getValue().safeSections()) {
                if (sectionId.equals(section.id())) {
                    Map<String, Object> data = new java.util.LinkedHashMap<>(section.safeData());
                    data.put(slot, imageUrl);
                    newSections.add(new com.fastcms.ai.component.SectionSpec(
                            section.id(), section.component(), section.variant(), data));
                    pageChanged = true;
                } else {
                    newSections.add(section);
                }
            }
            newPages.put(entry.getKey(), pageChanged
                    ? new com.fastcms.ai.component.PageSpecPage(newSections, entry.getValue().standalone())
                    : entry.getValue());
            updated |= pageChanged;
        }
        if (!updated) {
            throw new IllegalArgumentException(
                    "图片槽位定位失败: " + sectionId + "." + slot + "（模板可能已被 AI 修改，请刷新预览后重试）");
        }

        // imageAssets 解析记录：旧 URL 记录自然淘汰（不再被引用），新记录供后续微调轮沿用
        List<com.fastcms.ai.component.ImageAssetSpec> assets = new ArrayList<>(spec.safeImageAssets());
        final String resolvedUrl = imageUrl;
        assets.removeIf(a -> resolvedUrl.equals(a.resolved()));
        assets.add(new com.fastcms.ai.component.ImageAssetSpec(
                null, imageUrl, com.fastcms.ai.component.ImageAssetSpec.SOURCE_ATTACHMENT, attachmentId));
        spec = new com.fastcms.ai.component.PageSpec(spec.specVersion(), spec.foundation(),
                spec.templateName(), spec.siteName(), spec.siteType(), spec.stylePreset(),
                spec.primaryColor(), spec.safeSite(), newPages, assets);

        // 校验（media 槽位 URL 合法性）→ 重渲染 → 持久化
        List<String> errors = pageSpecValidator.validate(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("图片槽位更新未通过校验: " + String.join("; ", errors));
        }
        com.fastcms.ai.component.PageSpecRenderer.RenderResult renderResult;
        try {
            renderResult = pageSpecRenderer.render(spec, workDir, isMobileAdaptive(session));
        } catch (IOException e) {
            throw new IllegalStateException("模板重渲染失败: " + e.getMessage(), e);
        }
        List<String> writtenFiles = new ArrayList<>(renderResult.writtenFiles());
        for (String relPath : writtenFiles) {
            try {
                String content = Files.readString(workDir.resolve(relPath), StandardCharsets.UTF_8);
                fileService.saveOrUpdateFile(sessionId, relPath, content, AiTemplateConstants.ACTION_MODIFY);
            } catch (Exception e) {
                log.warn("图片槽位更新产物持久化失败: sessionId={}, path={}", sessionId, relPath, e);
            }
        }

        // 消息流留痕（衔接后续 AI 微调与回看）
        messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT,
                "🖼️ 已更换图片槽位 " + sectionId + "." + slot + "（附件: " + attachment.getFileName() + "）");
        log.info("图片槽位更新完成: sessionId={}, {}.{} -> attachmentId={}, written={}",
                sessionId, sectionId, slot, attachmentId, writtenFiles.size());
        return writtenFiles;
    }

    @Override
    public void updatePreviewImage(String sessionId, String imageUrl, Long attachmentId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!StringUtils.hasText(imageUrl)) {
            throw new IllegalArgumentException("缺少原图片地址");
        }
        if (attachmentId == null) {
            throw new IllegalArgumentException("缺少附件ID");
        }
        // 附件 → URL（服务端解析，前端只传附件 ID）
        com.fastcms.entity.Attachment attachment = attachmentService.getById(attachmentId);
        if (attachment == null) {
            throw new IllegalArgumentException("附件不存在: " + attachmentId);
        }
        String newUrl;
        try {
            newUrl = attachment.getPath();
        } catch (Exception e) {
            // 无应用上下文（理论上不可达，防御式兜底）：站内绝对路径
            newUrl = "/" + attachment.getFilePath();
        }

        // 读写 workDir 的 _preview_data.json：保留既有演示数据字段，仅更新 imageOverrides 映射
        Path workDir = resolveEffectiveWorkDir(session);
        Path file = workDir.resolve(AiTemplateConstants.FILE_PREVIEW_DATA);
        tools.jackson.databind.node.ObjectNode root;
        if (Files.isRegularFile(file)) {
            try {
                tools.jackson.databind.JsonNode existing =
                        JSON_MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
                root = existing != null && existing.isObject()
                        ? (tools.jackson.databind.node.ObjectNode) existing
                        : JSON_MAPPER.createObjectNode();
            } catch (Exception e) {
                // 不覆盖用户手写的演示数据：解析失败直接报错，提示人工修复
                throw new IllegalStateException("预览数据文件解析失败: " + file + "，请检查 JSON 格式后重试");
            }
        } else {
            root = JSON_MAPPER.createObjectNode();
        }
        // key 为模板渲染输出的原样 URL（含内联 SVG data URI），与 mock 数据构造时的查询键一致
        tools.jackson.databind.node.ObjectNode overrides =
                root.has("imageOverrides") && root.get("imageOverrides").isObject()
                        ? (tools.jackson.databind.node.ObjectNode) root.get("imageOverrides")
                        : root.putObject("imageOverrides");
        overrides.put(imageUrl, newUrl);
        try {
            Files.writeString(file, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入预览数据文件失败: " + e.getMessage(), e);
        }

        // 消息流留痕（区别于槽位换图：明确告知仅预览生效）
        messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT,
                "🖼️ 已更换预览演示图片（仅预览生效，正式环境的图片由文章数据决定）");
        log.info("预览演示图片更新完成: sessionId={}, attachmentId={}, keyLength={}",
                sessionId, attachmentId, imageUrl.length());
    }

    // ==================== SSE 流式对话 ====================

    @Override
    public void chatStream(String sessionId, String userInput, String currentFile, String focusSectionId,
                           String focusElementHint, boolean styleUpgrade, boolean deepRefresh,
                           boolean fullRefresh, SseEmitter emitter) {
        // SSE 通道封装：send 失败/断开回调即标记取消，各生成轮次检测后中断（见 SseChannel）
        SseChannel channel = new SseChannel(emitter);
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            sendError(channel, "会话不存在: " + sessionId);
            channel.complete();
            return;
        }
        // 客户端断开/超时的容器回调：send 失败之外的第二条断开感知路径
        emitter.onError(t -> channel.markCancelled());
        emitter.onTimeout(channel::markCancelled);

        try {
            sseExecutor.execute(() -> {
                try {
                    doChatStream(session, userInput, currentFile, focusSectionId, focusElementHint, styleUpgrade, deepRefresh, fullRefresh, channel);
                } catch (ChatCancelledException ce) {
                    // 客户端断开/用户停止：已生成的文件已落盘（断点续传语义保留），
                    // 落一条带标记的 assistant 消息，避免刷新后无法追溯这轮为何没有结果
                    log.info("AI 模板生成已取消（客户端断开）: sessionId={}", sessionId);
                    try {
                        messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT,
                                AiTemplateConstants.MSG_FAIL_PREFIX + "生成已中断（连接断开或已停止），已生成的文件已保存，重新发送消息可继续。");
                    } catch (Exception persistEx) {
                        log.warn("取消消息落库异常: sessionId={}", sessionId, persistEx);
                    }
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
                    sendError(channel, errMsg);
                } finally {
                    channel.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            // 有界线程池已满（并发长任务过多）：明确提示而不是无响应
            log.warn("AI 模板生成任务被拒绝（线程池已满）: sessionId={}", sessionId);
            sendError(channel, "当前 AI 任务并发已达上限，请稍后再试");
            channel.complete();
        }
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
    private void doChatStream(AiTemplateSession session, String userInput, String currentFile,
                              String focusSectionId, String focusElementHint, boolean styleUpgrade,
                              boolean deepRefresh, boolean fullRefresh, SseChannel channel) throws Exception {
        // 0. 配额检查（fastcms.ai.daily-token-quota，超限直接拒绝，不产生模型调用）
        try {
            quotaChecker.check(session.getUserId());
        } catch (AiQuotaExceededException e) {
            sendError(channel, e.getMessage());
            return;
        }

        long startTime = System.currentTimeMillis();
        boolean[] succeeded = {false};
        String[] errorMessage = {null};
        // token 用量：流式响应中仅最后一个 chunk 携带 usage（累积值），取最后非空值
        org.springframework.ai.chat.metadata.Usage[] lastUsage = {null};

        try {
            doChatStreamInternal(session, userInput, currentFile, focusSectionId, focusElementHint, styleUpgrade, deepRefresh, fullRefresh, channel, lastUsage);
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
            // 模型名只查一次（此前每个分支各调两次 getActiveConfig，各触发一次 DB 查询）
            AiModelConfig auditConfig = modelConfigService.getActiveConfig();
            String auditModel = auditConfig == null ? null : auditConfig.getModel();
            if (succeeded[0]) {
                usageRecorder.record(session.getUserId(), sceneOf(session), session.getSessionId(),
                        auditModel, promptTokens, completionTokens, totalTokens, System.currentTimeMillis() - startTime);
            } else {
                usageRecorder.recordError(session.getUserId(), sceneOf(session), session.getSessionId(),
                        auditModel, System.currentTimeMillis() - startTime, errorMessage[0]);
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
                                      String focusSectionId, String focusElementHint, boolean styleUpgrade,
                                      boolean deepRefresh, boolean fullRefresh,
                                      SseChannel channel, org.springframework.ai.chat.metadata.Usage[] lastUsage) throws Exception {
        // 1. 获取激活的模型配置
        AiModelConfig modelConfig = modelConfigService.getActiveConfig();
        if (modelConfig == null) {
            sendError(channel, "未配置 AI 模型，请先在模型管理中添加并激活一个配置");
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

        // 3. 保存用户消息（分批/单轮两条路径都需要；历史加载在保存之前，不会重复注入）。
        //    带选中区块时加前缀：消息流回看时可辨识本轮针对的区块，后续轮次历史注入也自然携带上下文
        boolean hasFocus = StringUtils.hasText(focusSectionId);
        String savedInput = hasFocus
                ? "（选中区块：" + focusSectionId + "）" + userInput
                : userInput;
        messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_USER, savedInput);

        // 3b. 样式组件化升级（旧模板专用）：确定性前置 + AI 分批改造循环。
        //     与普通调整不同：升级是系统驱动的多轮任务（锚点校验 + 断点续传），
        //     且只在旧模板（有 html 无 _pagespec.json、升级未完成）上生效；
        //     deepRefresh（深度焕新）在升级已完成后重置计划再改造一轮（需已有升级计划）
        if (styleUpgrade) {
            Path upgradeDir = resolveEffectiveWorkDir(session);
            boolean legacy = styleUpgrader.isLegacy(upgradeDir);
            // 深度焕新只对"升级过"的模板有意义：无计划且非旧模板时降级为普通对话（防误伤组件化模板）
            boolean deepRefreshAllowed = deepRefresh && styleUpgrader.hasUpgradePlan(upgradeDir);
            if (legacy || deepRefreshAllowed) {
                runStyleUpgradePipeline(session, modelConfig, chatClient, channel, lastUsage,
                        deepRefreshAllowed, fullRefresh);
                return;
            }
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "当前模板无需样式升级（可能已组件化或升级已完成），按普通对话处理。\n");
            // 降级为普通调整继续（走到下方单轮路径）
        }

        // 4. 生成型会话：默认走组件化流水线（AI 输出 PageSpec → 渲染引擎生成模板）。
        //    首次对话生成 PageSpec；已有 _pagespec.json 的会话（组件化微调）同样走该管线，
        //    AI 基于当前 spec 输出调整后的完整 spec，系统重渲染生效。
        //    配置 fastcms.ai.template.gen-mode=html 可回退直写 HTML 的旧分批流水线
        //    （旧会话已持久化 plan 文件时也自动沿用旧管线，避免中途换管线踩坏目录）。
        if (!StringUtils.hasText(session.getTemplateId())
                && "component".equalsIgnoreCase(genMode)
                && (isFirstChat || hasComponentSpec(session))) {
            runComponentPipeline(session, modelConfig, chatClient, userInput, focusSectionId, focusElementHint,
                    history, channel, lastUsage);
            return;
        }

        // 4b. 生成型会话首次对话走分批流水线（规划轮 + 逐文件轮）。
        //     整套模板一次性输出极易超 max_tokens 上限被截断（JSON 不完整 → 无文件落盘 → 前端永久转圈）；
        //     分批后单轮输出量级天然小于上限，从结构上消除截断问题。微调/调整仍走单轮。
        //     断点续传：plan 已持久化且存在未生成文件（中途停止/单文件失败/服务重启），
        //     任何新一轮对话都继续流水线、只补齐缺失文件，而不是当作微调。
        if (!StringUtils.hasText(session.getTemplateId())
                && (isFirstChat || hasMissingPlanFiles(session))) {
            runBatchPipeline(session, modelConfig, chatClient, userInput, channel, lastUsage);
            return;
        }

        // 5. 单轮路径（调整型会话 / 生成型微调）：构造消息列表。
        //    调整/微调对话需要模型推理（定位问题、多约束权衡），不注入 /no_think
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(session.getTemplateName(), isMobileAdaptive(session))));

        // 加入历史消息（保持上下文）；失败标记消息（"生成失败："前缀）对模型是无意义
        // 上下文，跳过注入（仅用于前端展示与失败态判定）。
        // 历史中的旧用户提示词内嵌全量模板文件、旧 AI 回复内嵌全量改写文件，单条可达数十万字符，
        // 必须截断+预算重放（从最早开始丢弃），否则多轮对话上下文必然溢出
        List<Message> replayed = new ArrayList<>();
        List<Long> replayCosts = new ArrayList<>();
        for (AiTemplateMessage msg : history) {
            String content = msg.getContent();
            if (!AiTemplateConstants.ROLE_USER.equals(msg.getRole())
                    && !AiTemplateConstants.ROLE_ASSISTANT.equals(msg.getRole())) {
                continue;
            }
            if (AiTemplateConstants.ROLE_ASSISTANT.equals(msg.getRole())
                    && content.startsWith(AiTemplateConstants.MSG_FAIL_PREFIX)) {
                continue;
            }
            if (content.length() > HISTORY_MSG_TRUNCATE_CHARS) {
                content = content.substring(0, HISTORY_MSG_TRUNCATE_CHARS) + "\n…（历史消息过长，已截断）";
            }
            Message m = AiTemplateConstants.ROLE_USER.equals(msg.getRole())
                    ? new UserMessage(content)
                    : new org.springframework.ai.chat.messages.AssistantMessage(content);
            replayed.add(m);
            replayCosts.add(estimateTokens(content));
        }
        long historyBudget = HISTORY_REPLAY_TOKEN_BUDGET;
        for (int i = 0; i < replayed.size(); i++) {
            long cost = replayCosts.get(i);
            if (historyBudget - cost < 0) {
                continue;
            }
            historyBudget -= cost;
            messages.add(replayed.get(i));
        }

        // 构造本次用户输入
        String userPrompt;
        if (StringUtils.hasText(session.getTemplateId())) {
            // 调整型会话：每一轮都携带正式模板当前文件内容（用户可能在两轮之间手工修改过）
            Path adjustWorkDir = resolveEffectiveWorkDir(session);
            // 归一化当前文件路径（去掉模板目录前缀，与文件清单中的相对路径一致）：
            // 既用于注入优先级（当前页面全文保真），也用于提示词聚焦
            String normalizedCurrentFile = normalizeRelativePath(currentFile, session.getWorkDir());
            String currentFilesWithContent = buildTemplateFileSection(adjustWorkDir, normalizedCurrentFile);
            // 选区上下文（路线 B）：预览页点选区块 → 反查组件源码文件 → 提示词约束 AI 只改该区块；
            // 定位不到（未组件化/引用被手改坏）时退回普通调整并 SSE 提示
            String focusSectionIdForPrompt = null;
            String focusComponentFile = null;
            if (StringUtils.hasText(focusSectionId)) {
                focusComponentFile = locateSectionComponentFile(adjustWorkDir, focusSectionId);
                if (focusComponentFile != null) {
                    focusSectionIdForPrompt = focusSectionId;
                } else {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "（选中区块 " + focusSectionId + " 未在模板文件中定位到，本轮按普通调整处理）\n");
                }
            }
            userPrompt = promptBuilder.buildAdjustPrompt(userInput, currentFilesWithContent, normalizedCurrentFile,
                    focusSectionIdForPrompt, focusElementHint, focusComponentFile);
        } else {
            // 微调场景：附带当前已有文件清单
            String currentFiles = buildCurrentFileList(session.getSessionId());
            userPrompt = promptBuilder.buildRefinePrompt(userInput, currentFiles);
        }
        messages.add(new UserMessage(userPrompt));

        // 6~9. 模型调用 → 解析 → 写盘 → 渲染校验。
        //      调整型会话在写盘后立即用预览渲染引擎校验本轮改动的 html 文件，
        //      渲染失败（FreeMarker 报错）时把错误反馈给模型自动修复（最多 MAX_RENDER_FIX_ATTEMPTS 轮），
        //      避免"AI 自称已修复但页面实际渲染失败"的盲改循环
        boolean isAdjust = StringUtils.hasText(session.getTemplateId());
        long[] usageTotal = new long[3];
        int totalFiles = 0;
        // 调整型会话收紧输出上限：调整是"改文件"任务，不需要深度推理。
        // 全量思考可把 completion 吃满 maxTokens（实测 16384 输出 / 耗时 6.4 分钟，正文一个字未出），
        // 压到 ADJUST_MAX_TOKENS_CAP 让"思考吃满"尽早暴露并触发翻倍重试，不再白等十几分钟
        Integer roundMaxTokens = isAdjust && modelConfig.getMaxTokens() != null
                ? Math.min(modelConfig.getMaxTokens(), ADJUST_MAX_TOKENS_CAP)
                : null;
        int effectiveMaxTokens = roundMaxTokens != null ? roundMaxTokens
                : (modelConfig.getMaxTokens() != null ? modelConfig.getMaxTokens() : 0);
        // 文件级进度状态：files 流式传输期间每识别出一个文件路径即推送一次状态
        // （已存在 → "正在修改 xxx…"，不存在 → "正在生成 xxx…"，与写盘动作的真实语义一致）
        java.util.function.Function<String, String> fileStatusResolver = path -> {
            Path dir = resolveEffectiveWorkDir(session);
            boolean exists = StringUtils.hasText(path) && Files.exists(dir.resolve(path).normalize());
            return (exists ? "正在修改 " : "正在生成 ") + path + "…";
        };
        for (int round = 0; ; round++) {
            // 客户端已断开：不再发起下一轮模型调用（白烧上游 token），中断并保留已落盘文件
            if (channel.isCancelled()) {
                throw new ChatCancelledException();
            }
            ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
            // Spring AI 透传的 reasoningContent 是"累积值"（每个 chunk 带到当前为止的完整思考文本），
            // 做差分后仅推送新增部分
            StringBuilder reasoningBuf = new StringBuilder();
            long[] roundUsage = new long[3];
            // 调整/微调轮同样走统一 options（显式 model + Qwen3 reasoning_effort=low）。
            // 实测（TEMPLATE_ADJUST 用量记录）：Qwen3.6 全力思考可把 completion 吃满 maxTokens
            // （8774 输入 / 16384 输出 / 耗时 6.4 分钟，正文一个 token 未出），最终只能报"AI 返回空响应"
            String fullResponse = callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf, roundUsage,
                    buildPipelineOptions(modelConfig, roundMaxTokens), fileStatusResolver);

            // 先解析（空响应时为 null）：截断重试的判定需要解析结果参与
            AiTemplateResponseParser.ParseResult parsed = StringUtils.hasText(fullResponse)
                    ? responseParser.parseResponse(fullResponse) : null;

            // 截断兜底重试：输出顶满生效上限（两种情形：思考吃满导致正文为空；
            // 或一次性输出大量文件内容导致 JSON 中途被截断、reply/files 均解析失败——
            // 实测 7 个文件完整 JSON 超 8k 上限即触发），翻倍上限重试一次，给正文留出输出空间。
            // 使用全新的 extractor/reasoning 缓冲：上一轮的思考文本不拼进本轮前端流
            boolean parseFailed = parsed == null
                    || (!StringUtils.hasText(parsed.getReply()) && parsed.getFiles().isEmpty());
            if (parseFailed && effectiveMaxTokens > 0 && roundUsage[1] >= effectiveMaxTokens) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（输出达到上限（思考耗尽或文件内容被截断），正在提升上限重试…）");
                replyExtractor = new ReplyStreamExtractor();
                reasoningBuf = new StringBuilder();
                fullResponse = callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf, roundUsage,
                        buildPipelineOptions(modelConfig, Math.max(effectiveMaxTokens * 2, 32768)), fileStatusResolver);
                if (StringUtils.hasText(fullResponse)) {
                    parsed = responseParser.parseResponse(fullResponse);
                }
            }
            usageTotal[0] += roundUsage[0];
            usageTotal[1] += roundUsage[1];
            usageTotal[2] += roundUsage[2];
            lastUsage[0] = aggregateUsage(usageTotal);
            if (!StringUtils.hasText(fullResponse)) {
                // 与异常路径一致：失败原因落库（前端刷新后仍能显示失败态与重新生成入口）
                messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                        AiTemplateConstants.MSG_FAIL_PREFIX + "AI 返回空响应（思考过程可能耗尽了输出上限）");
                sendError(channel, "AI 返回空响应");
                return;
            }

            // 完整思考过程（非推理模型为 null，不落库占位）
            String reasoningText = reasoningBuf.length() > 0 ? reasoningBuf.toString() : null;

            String reply = parsed == null ? null : parsed.getReply();
            List<AiTemplateFileDto> files = parsed == null ? List.of() : parsed.getFiles();
            boolean hasReply = StringUtils.hasText(reply);
            boolean hasFiles = !files.isEmpty();

            if (!hasFiles && !hasReply) {
                log.warn("AI 响应未解析出任何文件与回复: sessionId={}", session.getSessionId());
                // 尝试从（可能被截断的）原始 JSON 中救出 reply 字段：reply 位于响应开头、通常完整，
                // 救出后用户至少能看到 AI 说了什么；不再把原始全文存库（巨型 JSON 刷新后会
                // 整坨显示在聊天区、还会污染后续对话注入的 prompt）
                String salvagedReply = extractReplySalvage(fullResponse);
                String failMsg = AiTemplateConstants.MSG_FAIL_PREFIX
                        + "AI 响应解析失败（输出可能被截断，本轮文件未写盘）"
                        + (salvagedReply == null ? "" : "。AI 回复：" + salvagedReply);
                messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                        failMsg, reasoningText);
                sendError(channel, "AI 响应解析失败（输出被截断），已提示重试机制，请重试或简化本次改动范围");
                return;
            }

            // 保存 assistant 消息（先于文件写入：调整型会话的文件备份以消息ID为回滚粒度），
            // 优先保存 reply（自然语言摘要，避免巨型 JSON 挤占后续对话上下文），
            // 同时保存推理模型的完整思考过程（刷新页面后仍可回看，buildMessages 不会将其注入 prompt）
            String assistantMsg = hasReply ? reply : "已生成 " + files.size() + " 个文件";
            AiTemplateMessage assistantMessage = messageService.saveMessage(
                    session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, assistantMsg, reasoningText);

            // 持久化文件并写入工作目录（调整型会话写前自动备份到 ai_template_file_backup）
            int successCount = 0;
            List<String> writtenHtmlPaths = new ArrayList<>();
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
                        sendFileEvent(channel, file);

                        // 记录本轮写入的 html（渲染校验对象；删除动作无文件可校验）
                        if (file.getPath() != null
                                && !"delete".equalsIgnoreCase(file.getAction())
                                && file.getPath().toLowerCase().endsWith(".html")) {
                            writtenHtmlPaths.add(file.getPath());
                        }
                        successCount++;
                    } catch (Exception e) {
                        log.warn("文件写入失败: sessionId={}, path={}", session.getSessionId(), file.getPath(), e);
                    }
                }
            }
            totalFiles += successCount;

            // 渲染校验（仅调整型会话）：与预览共用同一渲染管线，预览能过校验必过
            List<String> renderErrors = isAdjust && !writtenHtmlPaths.isEmpty()
                    ? previewRenderer.checkRenderedFiles(resolveEffectiveWorkDir(session), writtenHtmlPaths)
                    : List.of();

            if (!renderErrors.isEmpty() && round < MAX_RENDER_FIX_ATTEMPTS) {
                // 自动修复轮：把渲染错误（含文件与行号）反馈给模型，附带写盘后的最新文件内容
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n\n（检测到 " + renderErrors.size() + " 个文件渲染失败，正在自动修复…）\n");
                if (hasReply && !replyExtractor.wasEmitted()) {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
                }
                Path workDir = resolveEffectiveWorkDir(session);
                String normalizedCur = normalizeRelativePath(currentFile, workDir.toString());
                // 渲染修复轮重建全新上下文（仅系统提示 + 修复提示词，修复提示词自包含）：
                // 若在原消息列表上追加，原提示词中的全量模板文件段会与修复段内容重复注入，上下文翻倍溢出；
                // 注入范围收窄为出错文件 + _layout.html + 当前聚焦页（渲染失败常源于布局宏不匹配）
                List<String> renderFixFiles = extractRenderErrorFiles(renderErrors);
                if (Files.isRegularFile(workDir.resolve("_layout.html").normalize())
                        && !renderFixFiles.contains("_layout.html")) {
                    renderFixFiles.add("_layout.html");
                }
                if (StringUtils.hasText(normalizedCur) && !renderFixFiles.contains(normalizedCur)
                        && Files.isRegularFile(workDir.resolve(normalizedCur).normalize())) {
                    renderFixFiles.add(normalizedCur);
                }
                messages = new ArrayList<>();
                messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(
                        session.getTemplateName(), isMobileAdaptive(session))));
                messages.add(new UserMessage(promptBuilder.buildRenderFixPrompt(
                        renderErrors, buildBatchFileSection(workDir, renderFixFiles), normalizedCur)));
                continue;
            }

            // 兜底推送：流式期间未推送过 reply（如 AI 把 reply 放在 files 之后、或旧数组格式）时补推，
            // 保证前端聊天区始终有内容
            if (hasReply) {
                if (!replyExtractor.wasEmitted()) {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
                }
            } else if (hasFiles) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, "已生成 " + successCount + " 个文件");
            }

            // 推送完成事件；自动修复后仍存在渲染错误时如实告知（不再谎报"已完成"）
            String summary;
            if (!renderErrors.isEmpty()) {
                StringBuilder errSb = new StringBuilder();
                for (int i = 0; i < renderErrors.size(); i++) {
                    errSb.append(i + 1).append(". ").append(renderErrors.get(i)).append('\n');
                }
                String failNote = "已自动修复多轮，仍有 " + renderErrors.size()
                        + " 个文件渲染失败：\n" + errSb
                        + "可把上述错误信息发给 AI 继续修复，或手工修改对应文件。";
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n\n" + failNote);
                messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, failNote);
                summary = "完成，但仍有 " + renderErrors.size() + " 个文件渲染失败（详见消息）";
            } else if (hasFiles) {
                summary = hasReply ? reply : "生成完成，共 " + successCount + " 个文件";
            } else {
                // 纯对话场景（咨询/闲聊）：reply 即完整回复
                summary = reply;
            }
            sendDone(channel, truncate(summary, 100));
            log.info("AI 模板生成对话完成: sessionId={}, files={}, renderErrors={}, fixRounds={}",
                    session.getSessionId(), totalFiles, renderErrors.size(), round);
            return;
        }
    }

    // ==================== 样式组件化升级管线（旧模板：保留功能、焕新视觉） ====================

    /**
     * 样式升级每批处理的文件数（2 个页面/批：输出量级安全，避免截断）
     */
    private static final int STYLE_UPGRADE_BATCH_SIZE = 2;

    /**
     * 样式升级单批内的自动修复轮上限（锚点丢失找回 / 渲染失败修复共用）
     */
    private static final int STYLE_UPGRADE_MAX_FIX_ROUNDS = 2;

    /**
     * 样式升级收尾视觉审计的自动修复轮上限（审计 → AI 修复 → 复审，闭环上限）
     */
    private static final int STYLE_UPGRADE_MAX_AUDIT_ROUNDS = 2;

    /**
     * 样式组件化升级管线：确定性前置（备份/锚点扫描/组件 CSS 引入）+ AI 分批改造循环
     * + 收尾视觉审计修复闭环
     *
     * <p>流程：每批 2 个文件（_layout.html 单独成批）→ 模型按硬性契约改造
     * （保留 id/锚点 class/脚本/FreeMarker 指令，追加 utility class）→ 写盘前锚点存活校验
     * （丢失自动找回一轮）→ 写盘 → 渲染校验（失败自动修复一轮）→ 更新升级计划进度。
     * 全部完成后视觉审计（失效 class / 未定义变量 / 旧 CSS 残留 / 漏改造页），
     * 有问题自动喂给 AI 修复并复审。中断可续：再次触发升级从 pending 继续。</p>
     *
     * @param deepRefresh 深度焕新：重置计划把文件（含已完成）再改造一轮；默认智能焕新
     *                    （先 AI 规划调用判定最小重做范围，只重做方向耦合的页面）
     * @param fullRefresh 全量焕新：跳过范围评估，全部计划文件恢复备份底稿重做（换设计方向兜底）
     */
    private void runStyleUpgradePipeline(AiTemplateSession session, AiModelConfig modelConfig,
                                         ChatClient chatClient, SseChannel channel,
                                         org.springframework.ai.chat.metadata.Usage[] lastUsage,
                                         boolean deepRefresh, boolean fullRefresh) throws Exception {
        Path workDir = resolveEffectiveWorkDir(session);
        String sessionId = session.getSessionId();

        // ===== 阶段零：智能焕新范围评估（AI 规划调用；仅智能焕新，评估失败回退全量） =====
        List<String> smartScope = null;
        if (deepRefresh && !fullRefresh) {
            smartScope = evaluateRefreshScope(session, chatClient, channel, workDir, lastUsage);
        }

        // ===== 阶段一：确定性前置（幂等，断点续传时读计划不重复执行；深度焕新走 restartPlan） =====
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, deepRefresh
                ? (smartScope != null
                        ? "正在智能焕新：从原始备份恢复 " + smartScope.size() + " 个页面的旧版底稿 → "
                                + "重置升级计划 → 刷新组件库 CSS → 以新的设计方向重新改造"
                                + "（其余页面保留当前版本，自动继承 tokens.css 换肤）…\n"
                        : "正在全量焕新：从原始备份恢复旧版页面 → 重置升级计划 → 刷新组件库 CSS → "
                                + "全部页面（含公共布局）以新的设计方向重新改造…\n")
                : "正在准备样式组件化升级：备份原文件 → 扫描 JS 依赖锚点 → 引入组件库 CSS…\n");
        com.fastcms.ai.component.LegacyStyleUpgrader.StyleUpgradePlan plan = deepRefresh
                ? styleUpgrader.restartPlan(workDir, session.getTemplateName(), smartScope)
                : styleUpgrader.prepare(workDir, session.getTemplateName());

        List<String> pending = new ArrayList<>(plan.pageFiles());
        if (pending.isEmpty()) {
            String summary = "样式组件化升级完成：组件库 CSS 已引入公共布局，无待改造页面文件。";
            messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT, summary);
            sendDone(channel, truncate(summary, 100));
            return;
        }

        int totalFiles = pending.size();
        int doneCount = plan.doneFiles() == null ? 0 : plan.doneFiles().size();
        // 智能焕新：done 初始值即"保留不重做"的页面数（收尾摘要区分"重做/保留"）
        int keptCount = doneCount;
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                "\n前置完成：扫描到 " + plan.anchors().size() + " 个 JS 依赖锚点，组件库 CSS 已就位，"
                        + (doneCount > 0 ? "继续上次升级：已完成 " + doneCount + "/" + (doneCount + totalFiles)
                        + " 个页面，剩余 " + totalFiles + " 个待改造。\n\n"
                        : "共 " + totalFiles + " 个文件待改造（公共布局 _layout.html 优先）。\n\n"));

        // 升级是"改文件"任务，输出上限与调整轮一致（防思考吃满）；解析失败时自动提升（见下方重试）
        Integer roundMaxTokens = modelConfig.getMaxTokens() != null
                ? Math.min(modelConfig.getMaxTokens(), ADJUST_MAX_TOKENS_CAP) : null;
        long[] usageTotal = {0, 0, 0};

        // ===== 阶段二：分批 AI 改造 =====
        // 队列由计划文件驱动：每批完成后重读 pending，只移出 AI 真正返回并写盘的文件
        //（防止"批次推进但 AI 漏掉某个文件"导致 pending 永不清空、升级横幅不消失）
        // 韧性策略：单批解析失败 → 提升输出上限重试一次 → 仍失败且批内多文件 → 拆为单文件逐个改造
        int curBatchSize = STYLE_UPGRADE_BATCH_SIZE;
        int idlePasses = 0;
        List<String> remaining = pending;
        while (!remaining.isEmpty()) {
            if (channel.isCancelled()) {
                throw new ChatCancelledException();
            }
            List<String> batch = new ArrayList<>(
                    remaining.subList(0, Math.min(curBatchSize, remaining.size())));
            // _layout.html 单独成批（文件最大最关键，避免与页面拼批撑爆输出上限）
            int layoutIdx = batch.indexOf("_layout.html");
            if (layoutIdx >= 0 && batch.size() > layoutIdx + 1) {
                batch = new ArrayList<>(batch.subList(0, layoutIdx + 1));
            }
            // token 预算：批次文件总量超预算时从队尾裁剪（被裁文件留在 pending，
            // 下一批继续），防止大文件拼批撑爆模型上下文（400 溢出）
            String batchSection = buildBatchFileSection(workDir, batch);
            int preTrimSize = batch.size();
            while (batch.size() > 1 && estimateTokens(batchSection) > UPGRADE_BATCH_TOKEN_BUDGET) {
                batch.remove(batch.size() - 1);
                batchSection = buildBatchFileSection(workDir, batch);
            }
            if (batch.size() < preTrimSize) {
                log.info("升级批次超出 token 预算({}), 已从 {} 个文件裁剪至 {}: sessionId={}",
                        UPGRADE_BATCH_TOKEN_BUDGET, preTrimSize, batch.size(), sessionId);
            }

            // 每批独立上下文：升级契约自包含，避免跨批记忆污染与上下文膨胀
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(
                    session.getTemplateName(), isMobileAdaptive(session))));
            messages.add(new UserMessage(promptBuilder.buildStyleUpgradePrompt(
                    batchSection, plan.anchors(),
                    doneCount, doneCount + remaining.size(),
                    batch.contains("_layout.html"), plan.refreshCount())));

            java.util.function.Function<String, String> fileStatusResolver = path -> "正在改造 " + path + "…";

            Integer roundTokens = roundMaxTokens;
            boolean elevatedRetryUsed = false;
            boolean splitBatch = false;
            int anchorFixRounds = 0;
            int renderFixRounds = 0;
            int remainingBefore = remaining.size();
            // 批级累积（跨修复轮不清零）：修复轮 AI 可能只输出非批内文件（如修坏的 _layout.html），
            // 若每轮重置，批内已写盘文件的进度会丢失 → 不进 done → pending 卡死 → 空转中断
            List<String> writtenHtmlPaths = new ArrayList<>();
            List<String> donePaths = new ArrayList<>();

            for (int round = 0; ; round++) {
                if (channel.isCancelled()) {
                    throw new ChatCancelledException();
                }
                ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
                StringBuilder reasoningBuf = new StringBuilder();
                long[] roundUsage = new long[3];
                String fullResponse = callModelRound(chatClient, messages, channel, replyExtractor,
                        reasoningBuf, roundUsage, buildPipelineOptions(modelConfig, roundTokens),
                        fileStatusResolver);
                usageTotal[0] += roundUsage[0];
                usageTotal[1] += roundUsage[1];
                usageTotal[2] += roundUsage[2];
                lastUsage[0] = aggregateUsage(usageTotal);

                AiTemplateResponseParser.ParseResult parsed = StringUtils.hasText(fullResponse)
                        ? responseParser.parseResponse(fullResponse) : null;
                List<AiTemplateFileDto> files = parsed == null ? List.of() : parsed.getFiles();
                boolean parseFailed = parsed == null
                        || (!StringUtils.hasText(parsed.getReply()) && files.isEmpty());

                // 解析失败韧性：先提升输出上限整批重试（不依赖用量上报——部分模型流式不上报用量，
                // 用量判断会漏掉真实截断），再拆批单文件，都失败才中断（已完成的进度保留，可续传）
                if (parseFailed) {
                    if (!elevatedRetryUsed) {
                        elevatedRetryUsed = true;
                        int elevated = Math.max((roundTokens != null ? roundTokens : 0) * 2, 32768);
                        roundTokens = elevated;
                        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                                "\n（本批输出解析失败，正在提升输出上限重试…）\n");
                        continue;
                    }
                    if (batch.size() > 1) {
                        splitBatch = true;
                        break;
                    }
                    throw new IllegalStateException("改造 " + batch.get(0)
                            + " 时 AI 输出解析失败，已完成的文件已保存，可重新发起升级继续（自动从剩余文件续传）");
                }

                // 锚点存活校验：写盘前用旧内容对比新内容（写盘后旧内容被覆盖，无从校验）
                List<String> missingAnchors = collectMissingAnchors(workDir, files);
                if (!missingAnchors.isEmpty() && anchorFixRounds < STYLE_UPGRADE_MAX_FIX_ROUNDS) {
                    anchorFixRounds++;
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "\n（检测到改造丢失 " + missingAnchors.size() + " 个 JS 锚点，正在自动找回…）\n");
                    String replySoFar = parsed.getReply();
                    if (StringUtils.hasText(replySoFar) && !replyExtractor.wasEmitted()) {
                        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, replySoFar);
                    }
                    // 锚点修复轮重建全新上下文（修复提示词自包含），注入 AI 刚输出的新版文件内容：
                    // 1) 此时尚未写盘，磁盘仍是旧内容，重读磁盘会把"待修正的新版"换成"改造前的旧版"（语义错误）；
                    // 2) 若在原消息列表上追加，批次文件段会重复注入，上下文翻倍溢出
                    messages = new ArrayList<>();
                    messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(
                            session.getTemplateName(), isMobileAdaptive(session))));
                    messages.add(new UserMessage(promptBuilder.buildAnchorFixPrompt(
                            missingAnchors, buildFileSectionFromDtos(files))));
                    continue;
                }

                // 保存消息 + 写盘（调整型会话写前自动备份到 ai_template_file_backup）
                String reply = parsed.getReply();
                String reasoningText = reasoningBuf.length() > 0 ? reasoningBuf.toString() : null;
                String assistantMsg = StringUtils.hasText(reply)
                        ? reply : "已改造 " + files.size() + " 个文件";
                AiTemplateMessage assistantMessage = messageService.saveMessage(
                        sessionId, AiTemplateConstants.ROLE_ASSISTANT, assistantMsg, reasoningText);

                for (AiTemplateFileDto file : files) {
                    try {
                        fileService.saveOrUpdateFile(sessionId, file.getPath(),
                                file.getContent() == null ? "" : file.getContent(), file.getAction());
                        writeToFile(session, file, assistantMessage.getId());
                        sendFileEvent(channel, file);
                        // 归一化后存入（与计划文件的 pending 条目精确匹配，容忍 "./xxx" 或反斜杠写法）；
                        // 修复轮可能重复输出同文件，去重防止渲染校验重复跑
                        String normalized = file.getPath() == null ? "" : file.getPath().trim()
                                .replace('\\', '/').replaceFirst("^\\./+", "");
                        if (!donePaths.contains(normalized)) {
                            donePaths.add(normalized);
                        }
                        if (file.getPath() != null
                                && !"delete".equalsIgnoreCase(file.getAction())
                                && file.getPath().toLowerCase().endsWith(".html")
                                && !writtenHtmlPaths.contains(file.getPath())) {
                            writtenHtmlPaths.add(file.getPath());
                        }
                    } catch (Exception e) {
                        log.warn("升级文件写入失败: sessionId={}, path={}", sessionId, file.getPath(), e);
                    }
                }

                // 渲染校验（与预览同一渲染管线）：失败反馈修复一轮
                List<String> renderErrors = !writtenHtmlPaths.isEmpty()
                        ? previewRenderer.checkRenderedFiles(workDir, writtenHtmlPaths) : List.of();
                if (!renderErrors.isEmpty() && renderFixRounds < STYLE_UPGRADE_MAX_FIX_ROUNDS) {
                    renderFixRounds++;
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "\n（检测到 " + renderErrors.size() + " 个文件渲染失败，正在自动修复…）\n");
                    // 渲染修复轮重建全新上下文，注入范围收窄为出错文件 + _layout.html
                    //（此时已写盘，磁盘即新版内容；避免与原批次提示词的文件段重复注入导致上下文翻倍）
                    List<String> renderFixFiles = extractRenderErrorFiles(renderErrors);
                    if (Files.isRegularFile(workDir.resolve("_layout.html").normalize())
                            && !renderFixFiles.contains("_layout.html")) {
                        renderFixFiles.add("_layout.html");
                    }
                    messages = new ArrayList<>();
                    messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(
                            session.getTemplateName(), isMobileAdaptive(session))));
                    messages.add(new UserMessage(promptBuilder.buildRenderFixPrompt(
                            renderErrors, buildBatchFileSection(workDir, renderFixFiles), null)));
                    continue;
                }

                // 批次完成：只把"本批目标文件中真正写盘的"计入进度，防止 AI 漏文件导致 pending 卡死
                List<String> batchDone = new ArrayList<>();
                for (String target : batch) {
                    if (donePaths.contains(target)) {
                        batchDone.add(target);
                    }
                }
                if (!batchDone.isEmpty()) {
                    styleUpgrader.markDone(workDir, batchDone);
                    doneCount += batchDone.size();
                }
                String failNote = renderErrors.isEmpty() && missingAnchors.isEmpty() ? ""
                        : "\n⚠ 本批仍有 " + renderErrors.size() + " 个渲染错误 / "
                        + missingAnchors.size() + " 个锚点未找回，可升级后对话修复。";
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n✅ 已完成 " + doneCount + "/" + (doneCount + remaining.size() - batchDone.size())
                                + " 个页面" + failNote + "\n");
                break;
            }

            if (splitBatch) {
                curBatchSize = 1;
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（本批多文件改造仍失败，改为逐个文件单独改造…）\n");
            }

            // 重读计划驱动队列；若一轮下来进度没有推进（AI 未漏解析但也没产出目标文件），拆批或中断防死循环
            remaining = styleUpgrader.readPending(workDir);
            if (remaining.size() >= remainingBefore) {
                idlePasses++;
                if (curBatchSize > 1) {
                    curBatchSize = 1;
                } else if (idlePasses > 1) {
                    throw new IllegalStateException("升级改造连续未产出目标文件（剩余 "
                            + remaining.size() + " 个），已完成的进度已保存，可重新发起升级继续");
                }
            } else {
                idlePasses = 0;
            }
        }

        // ===== 阶段三：视觉审计 + 自动修复闭环 =====
        // 审计器把"页面丑/乱"翻译成结构化问题（失效 class/未定义变量/旧 CSS 残留/漏改造页），
        // 喂给 AI 修复后复审，直到 clean 或轮次上限——这是升级质量的关键收尾
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                "\n所有文件改造完成，正在进行视觉审计（检查失效样式与旧 CSS 残留）…\n");
        com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeAuditReport audit =
                styleUpgrader.auditUpgrade(workDir);
        int auditRound = 0;
        while (!audit.clean() && auditRound < STYLE_UPGRADE_MAX_AUDIT_ROUNDS) {
            auditRound++;
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "\n🔍 视觉审计发现 " + audit.issues().size() + " 个样式问题（失效 class / 未定义变量 / "
                            + "旧 CSS 残留等，这是页面显乱的原因），自动修复第 " + auditRound + " 轮…\n");
            runAuditFixRound(session, modelConfig, chatClient, channel, lastUsage, usageTotal,
                    roundMaxTokens, audit);
            audit = styleUpgrader.auditUpgrade(workDir);
        }

        // ===== 阶段四：收尾留痕 =====
        String auditNote = audit.clean()
                ? "视觉审计通过（无失效样式）"
                : "仍有 " + audit.issues().size() + " 个样式问题未自动修复（可继续对话指定修复，或换设计方向再次深度焕新）";
        String scopeNote = deepRefresh && keptCount > 0
                ? "重做 " + (doneCount - keptCount) + " 个文件（含公共布局），保留 "
                        + keptCount + " 个页面（经 tokens.css 变量自动换肤）"
                : deepRefresh ? "全部 " + doneCount + " 个文件（含公共布局）"
                : "改造 " + doneCount + " 个文件（含公共布局）";
        String summary = (deepRefresh
                ? "深度焕新（第 " + plan.refreshCount() + " 次，本轮设计方向："
                        + designDirectionName(plan.refreshCount()) + "）完成："
                : "样式组件化升级完成：")
                + scopeNote + "，视觉切换为组件库风格，"
                + "JS 功能与元素锚点已按契约保留，" + auditNote
                + (plan.backupDir() == null ? "" : "，原文件备份于 " + plan.backupDir())
                + "。可继续对话微调视觉细节（换主色/调布局/改文案）。";
        messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT, summary);
        sendDone(channel, truncate(summary, 100));
        log.info("样式组件化升级完成: sessionId={}, files={}, anchors={}, auditRound={}, auditClean={}",
                sessionId, doneCount, plan.anchors().size(), auditRound, audit.clean());
    }

    /**
     * 深度焕新轮次对应的 设计方向名（与 TemplateGenPromptBuilder.buildStyleUpgradePrompt 的轮换表一致）
     */
    private static String designDirectionName(int refreshRound) {
        if (refreshRound <= 0) {
            return "默认现代风";
        }
        String[] names = {"现代商务风", "轻盈优雅风", "杂志编辑风"};
        return names[(refreshRound - 1) % names.length];
    }

    /**
     * 智能焕新范围评估（焕新前的 AI 规划调用）
     *
     * <p>给 AI 一份各页面"方向耦合度指纹"（深色区/渐变/主色/hero 计数 + utility 密度），
     * 让它判定落实新设计方向必须重做的最小文件集合；未选中的页面保留当前版本，
     * 通过 tokens.css 变量自动换肤。规划失败/解析失败回退 null（调用方走全量焕新）。</p>
     *
     * @return 重做文件清单（null = 回退全量）
     */
    private List<String> evaluateRefreshScope(AiTemplateSession session, ChatClient chatClient,
                                              SseChannel channel, Path workDir,
                                              org.springframework.ai.chat.metadata.Usage[] lastUsage) throws Exception {
        List<String> plannedFiles = styleUpgrader.listPlannedFiles(workDir);
        if (plannedFiles.isEmpty()) {
            return null;
        }
        int nextRound = styleUpgrader.getStatus(workDir).refreshCount() + 1;

        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                "\n智能焕新范围评估：AI 正在分析 " + plannedFiles.size()
                        + " 个页面的方向耦合度，判定最小重做集合…\n");

        // 指纹：路径 + 体量 + 方向耦合特征统计（深色区/渐变/主色/hero/utility 密度）
        StringBuilder fingerprints = new StringBuilder();
        for (String rel : plannedFiles) {
            String line = "- " + rel + ": 文件不存在";
            try {
                String content = java.nio.file.Files.readString(workDir.resolve(rel));
                int dark = countMatches(content, "bg-slate-800", "bg-slate-900", "bg-gray-800",
                        "bg-gray-900", "bg-primary-700", "bg-primary-800", "bg-primary-900", "text-white");
                int gradient = countMatches(content, "gradient");
                int primary = countMatches(content, "primary-");
                int hero = countMatches(content, "hero", "banner");
                int utility = countMatches(content, "max-w-", "rounded-", "bg-", "text-", "px-", "py-");
                line = "- " + rel + ": " + (content.length() / 1024) + "K字符, utility≈" + utility
                        + ", 深色区=" + dark + ", 渐变=" + gradient
                        + ", 主色=" + primary + ", hero=" + hero;
            } catch (java.io.IOException ignored) {
                // 保留"文件不存在"默认行
            }
            fingerprints.append(line).append('\n');
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(
                session.getTemplateName(), isMobileAdaptive(session))));
        messages.add(new UserMessage(promptBuilder.buildRefreshScopePrompt(
                fingerprints.toString(), nextRound)));

        long[] usageAgg = {0, 0, 0};
        String response = callModelRound(chatClient, messages, channel,
                new ReplyStreamExtractor(), new StringBuilder(), usageAgg);
        lastUsage[0] = aggregateUsage(usageAgg);

        // 解析 redesign 数组（容错：code fence / 前后说明文本都不影响）
        List<String> scope = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"redesign\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(response == null ? "" : response);
        if (m.find()) {
            java.util.regex.Matcher entry = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"").matcher(m.group(1));
            Set<String> planned = new java.util.HashSet<>(plannedFiles);
            while (entry.find()) {
                String p = entry.group(1).trim().replace('\\', '/');
                while (p.startsWith("./")) {
                    p = p.substring(2);
                }
                if (planned.contains(p) && !scope.contains(p)) {
                    scope.add(p);
                }
            }
        }
        if (scope.isEmpty()) {
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "\n（范围评估未得出有效清单，回退全量焕新）\n");
            return null;
        }
        // _layout.html 强制纳入（站点门面）
        if (plannedFiles.contains("_layout.html") && !scope.contains("_layout.html")) {
            scope.add("_layout.html");
        }
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                "\n评估结论：重做 " + scope.size() + " 个文件（" + String.join("、", scope)
                        + "），保留 " + (plannedFiles.size() - scope.size()) + " 个页面。\n\n");
        return scope;
    }

    /**
     * 简易多模式计数（指纹统计用）
     */
    private static int countMatches(String content, String... patterns) {
        int total = 0;
        for (String p : patterns) {
            int idx = 0;
            while ((idx = content.indexOf(p, idx)) >= 0) {
                total++;
                idx += p.length();
            }
        }
        return total;
    }

    /**
     * 视觉审计修复轮：把审计问题清单 + 涉事文件交给 AI 修复，写盘前锚点校验（丢失找回一轮）、
     * 写盘后渲染校验（失败修复一轮）。与主改造轮共用用量统计与输出上限。
     */
    private void runAuditFixRound(AiTemplateSession session, AiModelConfig modelConfig,
                                  ChatClient chatClient, SseChannel channel,
                                  org.springframework.ai.chat.metadata.Usage[] lastUsage,
                                  long[] usageTotal, Integer roundMaxTokens,
                                  com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeAuditReport audit) throws Exception {
        Path workDir = resolveEffectiveWorkDir(session);
        List<String> affectedFiles = new ArrayList<>(audit.issues().stream()
                .map(com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue::file)
                .filter(f -> !"*".equals(f))
                .distinct()
                .toList());
        // token 预算：问题文件过多时从队尾裁剪（被裁文件的问题下轮审计继续跟进）
        while (affectedFiles.size() > 1
                && estimateTokens(buildBatchFileSection(workDir, affectedFiles)) > UPGRADE_BATCH_TOKEN_BUDGET) {
            affectedFiles.remove(affectedFiles.size() - 1);
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptBuilder.buildSystemPrompt(
                session.getTemplateName(), isMobileAdaptive(session))));
        messages.add(new UserMessage(promptBuilder.buildAuditFixPrompt(
                audit.issues(), buildBatchFileSection(workDir, affectedFiles))));

        int fixRetries = 0;
        for (int round = 0; ; round++) {
            if (channel.isCancelled()) {
                throw new ChatCancelledException();
            }
            ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
            StringBuilder reasoningBuf = new StringBuilder();
            long[] roundUsage = new long[3];
            String fullResponse = callModelRound(chatClient, messages, channel, replyExtractor,
                    reasoningBuf, roundUsage, buildPipelineOptions(modelConfig, roundMaxTokens),
                    path -> "正在修复视觉问题…");
            usageTotal[0] += roundUsage[0];
            usageTotal[1] += roundUsage[1];
            usageTotal[2] += roundUsage[2];
            lastUsage[0] = aggregateUsage(usageTotal);

            AiTemplateResponseParser.ParseResult parsed = StringUtils.hasText(fullResponse)
                    ? responseParser.parseResponse(fullResponse) : null;
            List<AiTemplateFileDto> files = parsed == null ? List.of() : parsed.getFiles();
            if (parsed == null || (!StringUtils.hasText(parsed.getReply()) && files.isEmpty())) {
                throw new IllegalStateException("审计修复轮 AI 输出解析失败，本轮审计修复已跳过（已改造文件不受影响）");
            }

            // 锚点校验（修复轮同样不可破坏 JS 功能）：修复提示用 AI 刚输出的内容而非磁盘旧内容
            List<String> missingAnchors = collectMissingAnchors(workDir, files);
            if (!missingAnchors.isEmpty() && fixRetries < STYLE_UPGRADE_MAX_FIX_ROUNDS) {
                fixRetries++;
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（修复中丢失 " + missingAnchors.size() + " 个 JS 锚点，正在自动找回…）\n");
                String replySoFar = parsed.getReply();
                if (StringUtils.hasText(replySoFar) && !replyExtractor.wasEmitted()) {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, replySoFar);
                }
                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(
                        StringUtils.hasText(replySoFar) ? replySoFar : "已输出修复文件"));
                messages.add(new UserMessage(promptBuilder.buildAnchorFixPrompt(
                        missingAnchors, buildFileSectionFromDtos(files))));
                continue;
            }

            // 保存消息 + 写盘
            String reply = parsed.getReply();
            String reasoningText = reasoningBuf.length() > 0 ? reasoningBuf.toString() : null;
            String assistantMsg = StringUtils.hasText(reply)
                    ? reply : "已修复 " + files.size() + " 个文件的视觉问题";
            AiTemplateMessage assistantMessage = messageService.saveMessage(
                    session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT, assistantMsg, reasoningText);

            List<String> writtenHtmlPaths = new ArrayList<>();
            for (AiTemplateFileDto file : files) {
                try {
                    fileService.saveOrUpdateFile(session.getSessionId(), file.getPath(),
                            file.getContent() == null ? "" : file.getContent(), file.getAction());
                    writeToFile(session, file, assistantMessage.getId());
                    sendFileEvent(channel, file);
                    if (file.getPath() != null
                            && !"delete".equalsIgnoreCase(file.getAction())
                            && file.getPath().toLowerCase().endsWith(".html")) {
                        writtenHtmlPaths.add(file.getPath());
                    }
                } catch (Exception e) {
                    log.warn("审计修复文件写入失败: sessionId={}, path={}",
                            session.getSessionId(), file.getPath(), e);
                }
            }

            // 渲染校验：失败反馈修复一轮
            List<String> renderErrors = !writtenHtmlPaths.isEmpty()
                    ? previewRenderer.checkRenderedFiles(workDir, writtenHtmlPaths) : List.of();
            if (!renderErrors.isEmpty() && fixRetries < STYLE_UPGRADE_MAX_FIX_ROUNDS) {
                fixRetries++;
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（检测到 " + renderErrors.size() + " 个文件渲染失败，正在自动修复…）\n");
                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(assistantMsg));
                messages.add(new UserMessage(promptBuilder.buildRenderFixPrompt(
                        renderErrors, buildBatchFileSection(workDir, affectedFiles), null)));
                continue;
            }

            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    renderErrors.isEmpty() && missingAnchors.isEmpty()
                            ? "\n✅ 本轮视觉问题修复完成\n"
                            : "\n⚠ 本轮修复仍有遗留（" + renderErrors.size() + " 个渲染错误 / "
                                    + missingAnchors.size() + " 个锚点未找回）\n");
            return;
        }
    }

    /**
     * 从 AI 输出的文件 DTO 构建文件清单段（写盘前内容，用于锚点找回等修复提示）
     */
    private String buildFileSectionFromDtos(List<AiTemplateFileDto> files) {
        StringBuilder sb = new StringBuilder();
        for (AiTemplateFileDto file : files) {
            if (file.getPath() == null || file.getContent() == null
                    || "delete".equalsIgnoreCase(file.getAction())) {
                continue;
            }
            sb.append("### ").append(file.getPath()).append("\n```\n")
                    .append(file.getContent()).append("\n```\n\n");
        }
        return sb.length() == 0 ? "（无文件）" : sb.toString();
    }

    /**
     * 收集本批改造输出中丢失的 JS 依赖锚点（写盘前校验，旧内容取自磁盘）
     */
    private List<String> collectMissingAnchors(Path workDir, List<AiTemplateFileDto> files) {
        List<String> missing = new ArrayList<>();
        for (AiTemplateFileDto file : files) {
            if (file.getPath() == null || file.getContent() == null
                    || "delete".equalsIgnoreCase(file.getAction())
                    || !file.getPath().toLowerCase().endsWith(".html")) {
                continue;
            }
            Path old = workDir.resolve(file.getPath()).normalize();
            if (!Files.isRegularFile(old)) {
                continue;
            }
            try {
                String oldContent = Files.readString(old, StandardCharsets.UTF_8);
                missing.addAll(styleUpgrader.verifyAnchors(oldContent, file.getContent()));
            } catch (IOException e) {
                log.warn("锚点校验读取旧文件失败: {}", old, e);
            }
        }
        return missing;
    }

    /**
     * 升级批次文件清单（相对路径 + 完整内容，与 buildTemplateFileSection 同构但只含本批文件）
     */
    private String buildBatchFileSection(Path workDir, List<String> relPaths) {
        StringBuilder sb = new StringBuilder();
        for (String rel : relPaths) {
            Path file = workDir.resolve(rel).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                sb.append("### ").append(rel).append("\n```\n")
                        .append(Files.readString(file, StandardCharsets.UTF_8))
                        .append("\n```\n\n");
            } catch (IOException e) {
                log.warn("升级批次文件读取失败: {}", file, e);
            }
        }
        return sb.length() == 0 ? "（文件读取失败）" : sb.toString();
    }

    /**
     * 从渲染校验错误串（格式 "相对路径: 错误摘要"）提取去重后的文件路径列表
     */
    private static List<String> extractRenderErrorFiles(List<String> renderErrors) {
        List<String> paths = new ArrayList<>();
        for (String err : renderErrors) {
            if (err == null) {
                continue;
            }
            int sep = err.indexOf(": ");
            String path = sep > 0 ? err.substring(0, sep) : err;
            if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        return paths;
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

    // ==================== 组件化流水线（AI 输出 PageSpec → 渲染引擎生成模板） ====================

    /**
     * PageSpec 落盘文件名（会话工作目录中的事实源，存在即视为组件化会话）
     */
    private static final String COMPONENT_SPEC_FILE = "_pagespec.json";

    /**
     * 组件化管线流尾部阶段提示：reply 流完后仍在传输的是 PageSpec 页面规划（非文件内容），
     * 用语义匹配的文案避免用户误解为"文件已开始生成"
     */
    private static final String COMPONENT_POST_REPLY_STATUS = "正在接收页面规划，内容较大时可能需要几分钟…";

    /**
     * 组件化管线流尾部阶段提示（微调轮）：剩余传输的是调整后的页面数据而非初次规划，
     * 文案与生成轮区分，避免"规划"字样让用户误解为要重新生成
     */
    private static final String COMPONENT_REFINE_POST_REPLY_STATUS = "正在接收页面数据（全量），内容较大时可能需要几分钟…";

    /**
     * PageSpec 校验失败后的自动修正轮数上限（每轮把校验错误回喂给模型）
     */
    private static final int MAX_SPEC_FIX_ATTEMPTS = 2;

    /**
     * 会话工作目录是否已存在 PageSpec（组件化会话判定）
     */
    private boolean hasComponentSpec(AiTemplateSession session) {
        try {
            return Files.isRegularFile(resolveEffectiveWorkDir(session).resolve(COMPONENT_SPEC_FILE));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 纯问答轮兜底判定：回传的 PageSpec 与磁盘上生效版本（_pagespec.json）语义相同
     *
     * <p>微调契约允许纯咨询省略 pagespec（省略时由上方短路直接收尾），但模型仍可能
     * 习惯性回传完整 spec——此时用本方法判定语义未变即跳过渲染/全量写盘。
     * 比较用 Jackson 树等值（对象字段序不敏感、数组序敏感），比较前先做与渲染一致的
     * templateName 归一化，避免仅目录名差异被误判为变更；任何异常按「有变更」处理，
     * 走渲染兜底。</p>
     */
    private boolean isSpecUnchanged(Path workDir, AiTemplateSession session,
                                    com.fastcms.ai.component.PageSpec spec) {
        try {
            Path specPath = workDir.resolve(COMPONENT_SPEC_FILE);
            if (!Files.isRegularFile(specPath) || spec == null) {
                return false;
            }
            if (StringUtils.hasText(session.getTemplateName())
                    && !session.getTemplateName().equals(spec.safeTemplateName())) {
                spec = new com.fastcms.ai.component.PageSpec(spec.specVersion(), spec.foundation(),
                        session.getTemplateName(), spec.siteName(), spec.siteType(), spec.stylePreset(),
                        spec.primaryColor(), spec.safeSite(), spec.pages(), spec.imageAssets());
            }
            tools.jackson.databind.JsonNode disk =
                    JSON_MAPPER.readTree(Files.readString(specPath, StandardCharsets.UTF_8));
            tools.jackson.databind.JsonNode incoming =
                    JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(spec));
            return disk.equals(incoming);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 微调轮逐页对比：回传 spec 与磁盘生效版本的页面级差异（summary 如实报告修改范围用）
     *
     * <p>组件化契约要求全量回传 spec，传输量 ≠ 修改量——收尾若只报"已重新渲染 N 个文件"，
     * 用户会把"3 个页面、3KB"误读为修改范围。本方法逐页 Jackson 树比较（对象字段序
     * 不敏感）得出真正变更的页面清单。必须在渲染前调用（render 会写 _pagespec.json，
     * 之后磁盘已是新版本，diff 恒为空）。templateName 先做与 {@link #isSpecUnchanged}
     * 一致的归一化。</p>
     *
     * @return 有差异的页面 key 列表（含新增/删除/内容变更，spec 顺序优先）；磁盘无旧版本
     *         或比较异常 → null（调用方退化为不报告，行为同旧版）
     */
    private List<String> computeChangedPages(Path workDir, AiTemplateSession session,
                                             com.fastcms.ai.component.PageSpec spec) {
        try {
            Path specPath = workDir.resolve(COMPONENT_SPEC_FILE);
            if (!Files.isRegularFile(specPath) || spec == null || spec.pages() == null) {
                return null;
            }
            if (StringUtils.hasText(session.getTemplateName())
                    && !session.getTemplateName().equals(spec.safeTemplateName())) {
                spec = new com.fastcms.ai.component.PageSpec(spec.specVersion(), spec.foundation(),
                        session.getTemplateName(), spec.siteName(), spec.siteType(), spec.stylePreset(),
                        spec.primaryColor(), spec.safeSite(), spec.pages(), spec.imageAssets());
            }
            com.fastcms.ai.component.PageSpec disk = JSON_MAPPER.readValue(
                    Files.readString(specPath, StandardCharsets.UTF_8), com.fastcms.ai.component.PageSpec.class);
            tools.jackson.databind.JsonNode incomingPages =
                    JSON_MAPPER.valueToTree(spec).get("pages");
            tools.jackson.databind.JsonNode diskPages = JSON_MAPPER.valueToTree(disk).get("pages");
            List<String> changed = new ArrayList<>();
            // 内容变更/新增：以回传 spec 的页面顺序为准
            for (String pageKey : spec.pages().keySet()) {
                tools.jackson.databind.JsonNode p = incomingPages.get(pageKey);
                tools.jackson.databind.JsonNode d = diskPages == null ? null : diskPages.get(pageKey);
                if (d == null || !d.equals(p)) {
                    changed.add(pageKey);
                }
            }
            // 删除的页面：磁盘有、回传无（追加在后）
            if (disk.pages() != null) {
                for (String pageKey : disk.pages().keySet()) {
                    if (!spec.pages().containsKey(pageKey)) {
                        changed.add(pageKey);
                    }
                }
            }
            return changed;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 页面 key 的友好名称（summary 展示用）：index → 首页；article_list_products → 文章列表页(products)
     */
    private static String pageDisplayName(String pageKey) {
        String base = com.fastcms.ai.component.PageSpec.basePageKeyOf(pageKey);
        if (base == null) {
            return pageKey;
        }
        String name = switch (base) {
            case com.fastcms.ai.component.PageSpec.PAGE_INDEX -> "首页";
            case com.fastcms.ai.component.PageSpec.PAGE_ARTICLE_LIST -> "文章列表页";
            case com.fastcms.ai.component.PageSpec.PAGE_ARTICLE -> "文章详情页";
            case com.fastcms.ai.component.PageSpec.PAGE_PAGE -> "单页";
            default -> pageKey;
        };
        return base.equals(pageKey) ? name
                : name + "(" + pageKey.substring(base.length() + 1) + ")";
    }

    /**
     * 组件化流水线：AI 输出 PageSpec（结构规划）→ 校验 → 渲染引擎生成模板
     *
     * <p>与直写 HTML 流水线的本质区别：AI 不再逐文件产出代码，而是输出一份
     * 几 KB 的结构描述（用哪些组件 + 槽位文案），模板由 {@link com.fastcms.ai.component.PageSpecRenderer}
     * 确定性渲染——视觉质量由预制组件保证，从结构上消除截断与粗糙问题。</p>
     *
     * <p>闭环设计：</p>
     * <ol>
     *     <li>解析失败/校验失败 → 错误回喂模型自修正（最多 {@link #MAX_SPEC_FIX_ATTEMPTS} 轮，
     *         校验错误信息含位置与候选，可行动）</li>
     *     <li>渲染 → 写盘 → 持久化 ai_template_file（预览/应用走既有链路，零改造）</li>
     *     <li>渲染校验（与预览同管线）兜底组件包自身的回归问题</li>
     * </ol>
     *
     * <p>微调同样是 spec 往返：AI 基于当前 _pagespec.json 输出调整后的完整 spec，
     * 系统重渲染生效——换主色/换组件/改文案统一走这一条路。</p>
     *
     * @param history 本轮用户消息保存之前加载的历史（不含当前输入，与单轮路径一致）
     */
    private void runComponentPipeline(AiTemplateSession session, AiModelConfig modelConfig, ChatClient chatClient,
                                      String userInput, String focusSectionId, String focusElementHint,
                                      List<AiTemplateMessage> history, SseChannel channel,
                                      org.springframework.ai.chat.metadata.Usage[] usageOut) {
        String systemPrompt = componentGenPromptBuilder.buildSystemPrompt();
        long[] usageAgg = new long[3];
        StringBuilder allReasoning = new StringBuilder();

        Path workDir = resolveEffectiveWorkDir(session);
        boolean refine = Files.isRegularFile(workDir.resolve(COMPONENT_SPEC_FILE));

        // 消息列表：system + 历史（跳过失败标记消息）+ 本轮富化 prompt
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (AiTemplateMessage msg : history) {
            if (AiTemplateConstants.ROLE_USER.equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (AiTemplateConstants.ROLE_ASSISTANT.equals(msg.getRole())
                    && !msg.getContent().startsWith(AiTemplateConstants.MSG_FAIL_PREFIX)) {
                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(msg.getContent()));
            }
        }
        String userPrompt;
        if (refine) {
            try {
                String currentSpec = Files.readString(workDir.resolve(COMPONENT_SPEC_FILE), StandardCharsets.UTF_8);
                // 预览页点选了区块：只注入目标 section 的 spec 片段 + 只改该区块的约束
                if (StringUtils.hasText(focusSectionId)) {
                    String focusSection = extractFocusSection(currentSpec, focusSectionId);
                    if (focusSection != null) {
                        // 组件源码注入：焦点模式只注入选中区块对应的组件（需求只针对该区块）
                        List<ComponentGenPromptBuilder.ComponentSource> focusSources = collectComponentSources(
                                workDir, resolveFocusComponentFile(focusSection));
                        userPrompt = componentGenPromptBuilder.buildFocusRefinePrompt(
                                userInput, currentSpec, focusSectionId, focusSection, focusElementHint,
                                componentGenPromptBuilder.buildComponentSourcesBlock(focusSources));
                    } else {
                        // spec 中找不到该区块（AI 上一轮改掉了 id）：退回普通微调并提示
                        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                                "（选中区块 " + focusSectionId + " 已不存在，本轮按整页微调处理）\n");
                        userPrompt = componentGenPromptBuilder.buildRefinePrompt(userInput, currentSpec,
                                componentGenPromptBuilder.buildComponentSourcesBlock(
                                        collectComponentSources(workDir, null)));
                    }
                } else {
                    userPrompt = componentGenPromptBuilder.buildRefinePrompt(userInput, currentSpec,
                            componentGenPromptBuilder.buildComponentSourcesBlock(
                                    collectComponentSources(workDir, null)));
                }
            } catch (IOException e) {
                throw new RuntimeException("读取当前 PageSpec 失败: " + COMPONENT_SPEC_FILE, e);
            }
        } else {
            userPrompt = componentGenPromptBuilder.buildFirstGenPrompt(session.getTemplateName(), userInput);
        }
        messages.add(new UserMessage(userPrompt));

        // PageSpec 输出量小（几 KB）：收紧上限复用调整型会话的经验值，
        // 让"思考吃满 completion"尽早暴露并触发翻倍重试，不白等十几分钟
        Integer roundMaxTokens = modelConfig.getMaxTokens() != null
                ? Math.min(modelConfig.getMaxTokens(), ADJUST_MAX_TOKENS_CAP) : null;
        int effectiveMaxTokens = roundMaxTokens != null ? roundMaxTokens
                : (modelConfig.getMaxTokens() != null ? modelConfig.getMaxTokens() : 0);

        // ===== 规划轮循环：解析 + 校验，失败回喂自修正 =====
        com.fastcms.ai.component.PageSpec spec = null;
        String reply = null;
        ReplyStreamExtractor finalExtractor = null;
        // 循环外记录每轮最终响应全文（渲染修复轮回喂 AssistantMessage 用）
        String lastFullResponse = null;
        // 组件样式补丁应用结果（成功/失败明细，随 assistant 消息落库，下轮对话可见）
        String patchResultNote = null;
        // PageSpec 页面进度心跳：reply 流完后按"新页面识别 / 8KB 接收量"阈值推送状态，
        // 让数分钟的规划阶段持续有动态反馈（截断重试/修复轮从零重计）
        com.fastcms.ai.support.PageSpecProgressScanner specScanner = new com.fastcms.ai.support.PageSpecProgressScanner();
        java.util.function.Consumer<String> specHeartbeat = chunk -> {
            if (specScanner.feed(chunk)) {
                // 微调轮契约是全量回传 spec（改一个字也传整份），文案标明"全量/模板共"，
                // 避免被误读为"修改了 N 个页面"；首轮生成保留"已识别"（确为规划全部页面）
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS,
                        (refine
                                ? "正在接收页面数据（全量回传）…模板共 "
                                : "正在规划页面结构…已识别 ")
                        + specScanner.totalPages() + " 个页面，已接收 "
                        + Math.max(1, specScanner.receivedChars() / 1024) + " KB");
            }
        };
        for (int round = 0; ; round++) {
            if (channel.isCancelled()) {
                throw new ChatCancelledException();
            }
            specScanner.reset();
            ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
            StringBuilder reasoningBuf = new StringBuilder();
            long[] roundUsage = new long[3];
            String fullResponse = callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf,
                    roundUsage, buildPipelineOptions(modelConfig, roundMaxTokens), null,
                    refine ? COMPONENT_REFINE_POST_REPLY_STATUS : COMPONENT_POST_REPLY_STATUS, specHeartbeat);
            usageAgg[0] += roundUsage[0];
            usageAgg[1] += roundUsage[1];
            usageAgg[2] += roundUsage[2];
            usageOut[0] = aggregateUsage(usageAgg);
            allReasoning.append(reasoningBuf);

            if (!StringUtils.hasText(fullResponse)) {
                messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                        AiTemplateConstants.MSG_FAIL_PREFIX + "AI 返回空响应（思考过程可能耗尽了输出上限）");
                sendError(channel, "AI 返回空响应");
                return;
            }

            com.fastcms.ai.component.PageSpecParser.ParseResult parsed = pageSpecParser.parseResponse(fullResponse);
            reply = parsed.reply();
            spec = parsed.pagespec();

            // 截断兜底重试：输出顶满上限（思考吃满/JSON 截断），翻倍上限重试一次
            if (spec == null && effectiveMaxTokens > 0 && roundUsage[1] >= effectiveMaxTokens) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（输出达到上限，正在提升上限重试…）");
                specScanner.reset();
                replyExtractor = new ReplyStreamExtractor();
                reasoningBuf = new StringBuilder();
                fullResponse = callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf,
                        roundUsage, buildPipelineOptions(modelConfig,
                                Math.max(effectiveMaxTokens * 2, 32768)), null,
                        refine ? COMPONENT_REFINE_POST_REPLY_STATUS : COMPONENT_POST_REPLY_STATUS, specHeartbeat);
                usageAgg[0] += roundUsage[0];
                usageAgg[1] += roundUsage[1];
                usageAgg[2] += roundUsage[2];
                usageOut[0] = aggregateUsage(usageAgg);
                allReasoning.append(reasoningBuf);
                if (StringUtils.hasText(fullResponse)) {
                    parsed = pageSpecParser.parseResponse(fullResponse);
                    reply = parsed.reply();
                    spec = parsed.pagespec();
                }
            }
            finalExtractor = replyExtractor;
            lastFullResponse = fullResponse;

            // 纯问答轮（省略 pagespec 契约）：refine 轮模型只输出了 reply（无 pagespec、
            // 无补丁、未触发截断重试）→ 纯咨询，直接落库收尾，不进入校验/渲染；
            // 下方 isSpecUnchanged 短路保留，兜底模型仍习惯性回传完整 spec 的情况
            if (refine && spec == null && parsed.filePatches().isEmpty() && StringUtils.hasText(reply)
                    && !(effectiveMaxTokens > 0 && roundUsage[1] >= effectiveMaxTokens)) {
                String qaReasoning = allReasoning.length() > 0 ? allReasoning.toString() : null;
                messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                        reply, qaReasoning);
                if (!replyExtractor.wasEmitted()) {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
                }
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS, "");
                sendDone(channel, "本轮无文件修改");
                log.info("AI 组件化微调完成（纯问答，模型省略了 pagespec）: sessionId={}", session.getSessionId());
                return;
            }

            // spec 解析失败时优先回喂解析器给出的具体原因（JSON 语法错误原文，如
            // 数组未闭合 expected ']'），让修正轮精确定位而非误判为截断反复重试
            List<String> errors = spec == null
                    ? List.of(parsed.parseError() != null ? parsed.parseError()
                            : "未解析出 pagespec 字段（JSON 可能被截断或格式非法）")
                    : pageSpecValidator.validate(spec);
            if (errors.isEmpty()) {
                // 组件源码补丁：spec 校验通过才应用（失败轮次的补丁丢弃，fix 轮会重出）
                if (!parsed.filePatches().isEmpty()) {
                    patchResultNote = applyComponentPatches(workDir, parsed.filePatches(), channel);
                }
                // 纯问答轮兜底（spec 回传未省略）：spec 与磁盘版本语义相同且无组件补丁 →
                // 模型习惯性原样回传了完整 spec（契约允许纯咨询省略，见提示词），
                // 跳过图片装配/渲染/全量写盘，summary 如实提示，不再出现"已重新渲染"的误导
                if (refine && parsed.filePatches().isEmpty() && isSpecUnchanged(workDir, session, spec)) {
                    String reasoningText = allReasoning.length() > 0 ? allReasoning.toString() : null;
                    String assistantMsg = StringUtils.hasText(reply) ? reply : "本轮无文件修改";
                    messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                            assistantMsg, reasoningText);
                    if (StringUtils.hasText(reply) && finalExtractor != null && !finalExtractor.wasEmitted()) {
                        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
                    }
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS, "");
                    sendDone(channel, "本轮无文件修改");
                    log.info("AI 组件化微调完成（spec 未变化，跳过渲染）: sessionId={}", session.getSessionId());
                    return;
                }
                break;
            }

            // 校验失败：错误回喂自修正（错误信息含位置与候选，可行动）
            if (round < MAX_SPEC_FIX_ATTEMPTS) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（PageSpec 校验未通过，正在自动修正…）");
                if (StringUtils.hasText(reply) && !replyExtractor.wasEmitted()) {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
                }
                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(fullResponse));
                messages.add(new UserMessage(componentGenPromptBuilder.buildFixPrompt(errors)));
                continue;
            }

            // 修正轮耗尽仍失败：如实落库失败态
            String failMsg = AiTemplateConstants.MSG_FAIL_PREFIX + "PageSpec 校验失败（已自动修正多轮）: "
                    + String.join("；", errors);
            messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                    failMsg, allReasoning.length() > 0 ? allReasoning.toString() : null);
            sendError(channel, "PageSpec 校验失败: " + errors.get(0));
            return;
        }

        // ===== 渲染 + 渲染校验修复循环：spec → 模板目录 → 校验，失败回喂模型自动修复 =====
        // （对齐调整型会话的 MAX_RENDER_FIX_ATTEMPTS 机制；渲染错误含文件与行号，
        //   组件源码缺陷类错误引导模型换组件/调槽位数据规避）
        // 渲染前逐页 diff（render 会写 _pagespec.json，之后磁盘已是新版，diff 恒空）：
        // 收尾 summary 用真实修改范围替代"已重新渲染 N 个文件"，消除"改一个字也报
        // N 个页面/几 KB"的全量传输误导；diff 不可得时为 null，收尾退化为旧文案
        List<String> changedPages = refine ? computeChangedPages(workDir, session, spec) : null;
        int fileCount = 0;
        List<String> renderErrors = List.of();
        int renderRound;
        for (renderRound = 0; ; renderRound++) {
            // SSE 状态：AI 规划已出、进入本地装配阶段（渲染引擎从组件库拼装文件，
            // 持续数秒；期间用户在左预览看到的是"正在装配"而非长时间无反馈）
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS,
                    renderRound == 0 ? "正在装配组件、生成模板文件…"
                            : "正在重新装配模板文件（第 " + (renderRound + 1) + " 轮修复）…");
            // 渲染前强制 templateName 与会话一致（保证目录与注册信息对齐；
            // 修复轮模型新输出的 spec 同样要对齐，故放在循环内）
            if (StringUtils.hasText(session.getTemplateName())
                    && !session.getTemplateName().equals(spec.safeTemplateName())) {
                spec = new com.fastcms.ai.component.PageSpec(spec.specVersion(), spec.foundation(),
                        session.getTemplateName(), spec.siteName(), spec.siteType(),
                        spec.stylePreset(), spec.primaryColor(), spec.safeSite(), spec.pages(),
                        spec.imageAssets());
            }
            // 图片槽位解析：media 槽位 search: 引用 → 附件库搜图 / 演示图兜底（渲染前确定性预处理；
            // 解析结果随 _pagespec.json 落盘，演示图复制进模板 static/images/ 自包含。
            // 修复轮重跑幂等：新 spec 的 search: 引用需重新解析）
            List<String> imageWrittenFiles = List.of();
            try {
                // 附件库搜图可能持续数秒，先推送状态再进入装配
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS, "正在装配图片素材…");
                com.fastcms.ai.component.AttachmentImageSearcher.Result imageResult =
                        attachmentImageSearcher.resolve(spec, workDir);
                spec = imageResult.spec();
                imageWrittenFiles = imageResult.writtenFiles();
                // SSE 进度：图片装配摘要（无 media 槽位时不打扰）
                if (imageResult.attachmentHits() > 0 || imageResult.demoFallbacks() > 0) {
                    String imageNote = "\n\n🖼️ 图片装配：附件库命中 " + imageResult.attachmentHits() + " 张"
                            + (imageResult.demoFallbacks() > 0
                                    ? "，演示图兜底 " + imageResult.demoFallbacks() + " 张" : "");
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, imageNote);
                }
            } catch (Exception e) {
                log.warn("图片槽位解析失败（不影响主流程，未解析引用走组件占位兜底）: sessionId={}",
                        session.getSessionId(), e);
            }

            com.fastcms.ai.component.PageSpecRenderer.RenderResult renderResult;
            try {
                renderResult = pageSpecRenderer.render(spec, workDir, isMobileAdaptive(session));
            } catch (Exception e) {
                log.warn("PageSpec 渲染失败: sessionId={}", session.getSessionId(), e);
                messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                        AiTemplateConstants.MSG_FAIL_PREFIX + "模板渲染失败: " + e.getMessage());
                sendError(channel, "模板渲染失败: " + e.getMessage());
                return;
            }

            // ===== 持久化 + 文件事件（预览/应用走既有链路，零改造） =====
            // 渲染本身是本地秒级操作、产物瞬间全部就绪——逐文件按小间隔推送，
            // 前端状态条轮播"正在生成 xxx"、文件列表逐个出现，形成可感知的生成节奏
            List<String> allWrittenFiles = new java.util.ArrayList<>(imageWrittenFiles);
            allWrittenFiles.addAll(renderResult.writtenFiles());
            fileCount = 0;
            for (String relPath : allWrittenFiles) {
                try {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS, "正在生成 " + relPath + "…");
                    String content = Files.readString(workDir.resolve(relPath), StandardCharsets.UTF_8);
                    fileService.saveOrUpdateFile(session.getSessionId(), relPath, content,
                            AiTemplateConstants.ACTION_CREATE);
                    AiTemplateFileDto dto = new AiTemplateFileDto();
                    dto.setPath(relPath);
                    dto.setContent(content);
                    dto.setAction(AiTemplateConstants.ACTION_CREATE);
                    sendFileEvent(channel, dto);
                    fileCount++;
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("组件化渲染产物持久化失败: sessionId={}, path={}", session.getSessionId(), relPath, e);
                }
            }
            // 文件全部产出：清掉"正在生成 xxx"状态条（空 status，前端隐藏）
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS, "");

            // ===== 渲染校验（与预览同管线）：组件已预校验，此处兜底组件包自身的回归问题 =====
            // 渲染校验覆盖全部页面 html（基础页 + site 信息架构的 suffix 专属页；
            // _layout.html 布局宏由页面 import 间接校验，不单独渲染）
            List<String> pageFiles = renderResult.writtenFiles().stream()
                    .filter(f -> f.endsWith(".html") && !f.startsWith("_")).toList();
            renderErrors = previewRenderer.checkRenderedFiles(workDir, pageFiles);
            if (renderErrors.isEmpty() || renderRound >= MAX_RENDER_FIX_ATTEMPTS) {
                break;
            }

            // ===== 自动修复轮：渲染错误（含文件与行号）+ 落盘 spec 回喂模型 =====
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "\n\n（渲染校验发现 " + renderErrors.size() + " 个页面异常，正在自动修复…）\n");
            if (StringUtils.hasText(reply) && finalExtractor != null && !finalExtractor.wasEmitted()) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
            }
            messages.add(new org.springframework.ai.chat.messages.AssistantMessage(lastFullResponse));
            String specJsonForFix;
            try {
                specJsonForFix = Files.readString(workDir.resolve(COMPONENT_SPEC_FILE), StandardCharsets.UTF_8);
            } catch (IOException e) {
                specJsonForFix = "";
            }
            messages.add(new UserMessage(componentGenPromptBuilder.buildRenderFixPrompt(renderErrors, specJsonForFix)));

            // 修复轮模型调用（输出完整修复 spec；心跳从零重计）
            specScanner.reset();
            ReplyStreamExtractor fixExtractor = new ReplyStreamExtractor();
            StringBuilder fixReasoning = new StringBuilder();
            long[] fixUsage = new long[3];
            String fixResponse = callModelRound(chatClient, messages, channel, fixExtractor, fixReasoning,
                    fixUsage, buildPipelineOptions(modelConfig, roundMaxTokens), null,
                    refine ? COMPONENT_REFINE_POST_REPLY_STATUS : COMPONENT_POST_REPLY_STATUS, specHeartbeat);
            usageAgg[0] += fixUsage[0];
            usageAgg[1] += fixUsage[1];
            usageAgg[2] += fixUsage[2];
            usageOut[0] = aggregateUsage(usageAgg);
            allReasoning.append(fixReasoning);
            if (!StringUtils.hasText(fixResponse)) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（修复响应为空，停止自动修复）\n");
                break;
            }
            com.fastcms.ai.component.PageSpecParser.ParseResult fixParsed = pageSpecParser.parseResponse(fixResponse);
            com.fastcms.ai.component.PageSpec fixSpec = fixParsed.pagespec();
            if (fixSpec == null) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（修复响应未解析出 PageSpec，停止自动修复）\n");
                break;
            }
            List<String> fixErrors = pageSpecValidator.validate(fixSpec);
            if (!fixErrors.isEmpty()) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（修复后的 PageSpec 校验未通过，停止自动修复: " + fixErrors.get(0) + "）\n");
                break;
            }
            // 修复 spec 生效，进入下一轮渲染；修复轮输出的组件补丁同样应用
            // （渲染错误源于组件源码时，模型会借 filePatches 规避/修正）
            if (!fixParsed.filePatches().isEmpty()) {
                String fixPatchNote = applyComponentPatches(workDir, fixParsed.filePatches(), channel);
                if (fixPatchNote != null) {
                    patchResultNote = fixPatchNote;
                }
            }
            spec = fixSpec;
            reply = fixParsed.reply();
            lastFullResponse = fixResponse;
            finalExtractor = fixExtractor;
        }

        // ===== 收尾：落库 + 推送（渲染错误与补丁结果一并落库，保证下轮对话 AI 上下文可见，
        //      避免"错误只展示给用户、AI 看不见"导致的盲改循环） =====
        String reasoningText = allReasoning.length() > 0 ? allReasoning.toString() : null;
        String assistantMsg = StringUtils.hasText(reply) ? reply
                : (refine ? "微调完成，已重新渲染" : "已生成组件化模板（" + fileCount + " 个文件）");
        if (patchResultNote != null) {
            assistantMsg = assistantMsg + "\n\n【组件补丁】" + patchResultNote;
        }
        if (!renderErrors.isEmpty()) {
            StringBuilder errSb = new StringBuilder();
            for (int i = 0; i < renderErrors.size(); i++) {
                errSb.append(i + 1).append(". ").append(renderErrors.get(i)).append('\n');
            }
            assistantMsg = assistantMsg + "\n\n【渲染校验异常】已自动修复 " + renderRound
                    + " 轮，仍有 " + renderErrors.size() + " 个页面渲染失败：\n" + errSb
                    + "（可让 AI 继续修复：换组件规避或调整槽位数据；或手工修改对应文件）";
        }
        messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_ASSISTANT,
                assistantMsg, reasoningText);

        if (StringUtils.hasText(reply) && finalExtractor != null && !finalExtractor.wasEmitted()) {
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, reply);
        }

        String summary;
        if (!renderErrors.isEmpty()) {
            int markerIdx = assistantMsg.indexOf("【渲染校验异常】");
            String errNote = markerIdx >= 0 ? assistantMsg.substring(markerIdx)
                    : "仍有 " + renderErrors.size() + " 个页面渲染失败（详见消息）";
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n\n" + errNote);
            summary = "完成，但有 " + renderErrors.size() + " 个页面渲染异常（详见消息）";
        } else if (refine && changedPages != null) {
            // 真实修改范围（渲染前逐页 diff 所得）：pages 逐页相同时变更在页面之外
            // （filePatches 组件源码 / 主色等全局字段）；页面多时只列前 3 个防溢出
            String scope = changedPages.isEmpty() ? "组件源码/全局设置"
                    : changedPages.size() + " 个页面（"
                            + changedPages.stream().limit(3)
                                    .map(AiTemplateGenServiceImpl::pageDisplayName)
                                    .collect(Collectors.joining("、"))
                            + (changedPages.size() > 3 ? " 等" : "") + "）";
            summary = "微调完成：修改 " + scope + "，已重新渲染 " + fileCount + " 个文件";
        } else {
            summary = (refine ? "微调完成，已重新渲染 " : "生成完成，共 ") + fileCount + " 个文件";
        }
        sendDone(channel, truncate(summary, 100));
        log.info("AI 组件化模板生成完成: sessionId={}, refine={}, files={}, changedPages={}, renderErrors={}, fixRounds={}",
                session.getSessionId(), refine, fileCount, changedPages, renderErrors.size(), renderRound);
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
     * JSON 序列化/反序列化 Mapper（plan 清单持久化 + SSE 事件数据；替代手写 JSON 拼接，
     * Jackson 3 线程安全，异常为 unchecked）
     */
    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER = new tools.jackson.databind.ObjectMapper();

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
     * @param channel   SSE 通道（推送事件 + 客户端断开检测）
     * @param usageOut  审计用量输出（多轮累计）
     */
    private void runBatchPipeline(AiTemplateSession session, AiModelConfig modelConfig, ChatClient chatClient,
                                  String userInput, SseChannel channel,
                                  org.springframework.ai.chat.metadata.Usage[] usageOut) {
        String systemPrompt = promptBuilder.buildSystemPrompt(session.getTemplateName(), isMobileAdaptive(session));
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
                    channel, planExtractor, allReasoning, usageAgg,
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
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（规划结果解析失败，按必备文件清单逐个生成）");
                log.warn("规划轮解析失败，使用默认清单: sessionId={}", session.getSessionId());
            }
            // 规划 reply 兜底（流式期间未推出时补推）
            if (StringUtils.hasText(planParsed.getReply()) && !planExtractor.wasEmitted()) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, planParsed.getReply());
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
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
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
            sendDone(channel, msg);
            return;
        }
        // 单文件轮的需求描述：续传时用会话的原始需求（本轮输入可能只是"补齐"）；
        // 首轮时用户输入即原始需求，两者等价
        String genRequirement = resumed && StringUtils.hasText(session.getRequirement())
                ? session.getRequirement() : userInput;

        for (String path : pendingFiles) {
            // 客户端已断开：停止发起后续文件轮（白烧上游 token），已生成的文件保留（断点续传）
            if (channel.isCancelled()) {
                throw new ChatCancelledException();
            }
            sendProgress(channel, plannedFiles, plannedFiles.indexOf(path), donePaths);
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n\n📄 正在生成 " + path + " …");

            String existingContext = buildGenContext(generatedFiles, layoutContent);
            String filePrompt = promptBuilder.buildSingleFilePrompt(genRequirement, path, existingContext, null,
                    isMobileAdaptive(session));
            AiTemplateFileDto fileDto = generateSingleFile(chatClient, systemPrompt, filePrompt,
                    path, channel, allReasoning, usageAgg, modelConfig, null);

            // 直出失败（多为触达 max_tokens 截断）→ 分块生成路径：
            // 规划轮划分块（输出极小不会截断）+ 逐块生成（每块输出量级远低于上限），
            // 从结构上保证文件大小与 max_tokens 配置解耦——文件再大也只是块数变多
            if (fileDto == null) {
                fileDto = generateFileByChunks(chatClient, systemPrompt, genRequirement, path,
                        existingContext, channel, allReasoning, usageAgg, modelConfig);
            }

            // 分块仍失败 → 压缩篇幅 + maxTokens 翻倍重试（兜底）。
            // 思考 tokens 计入 completion：推理模型单轮思考过长也可能吃满 max_tokens 配置导致 JSON 截断，
            // 重试轮在上限不为空时翻倍（至少 32768），给长思考留出完整输出空间
            if (fileDto == null) {
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（" + path + " 输出异常，正在重试…）");
                String retryPrompt = promptBuilder.buildSingleFilePrompt(genRequirement, path, existingContext,
                        "上一次输出被截断或格式非法。请务必压缩篇幅：删除全部注释、精简样式与结构，确保 JSON 完整且 content 为完整文件内容。",
                        isMobileAdaptive(session));
                Integer retryMaxTokens = modelConfig.getMaxTokens() != null
                        ? Math.max(modelConfig.getMaxTokens() * 2, 32768) : null;
                fileDto = generateSingleFile(chatClient, systemPrompt, retryPrompt,
                        path, channel, allReasoning, usageAgg, modelConfig, retryMaxTokens);
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
                    sendFileEvent(channel, fileDto);
                } catch (Exception e) {
                    log.warn("流水线文件写入失败: sessionId={}, path={}", session.getSessionId(), path, e);
                }
            } else {
                failedPaths.add(path);
                log.warn("流水线单文件生成失败（重试后仍失败）: sessionId={}, path={}", session.getSessionId(), path);
            }
            sendProgress(channel, plannedFiles, -1, donePaths);
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
            sendError(channel, "所有文件生成失败，请重试或调整需求描述");
            return;
        }
        sendDone(channel, truncate(summary, 100));
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
            List<String> files = JSON_MAPPER.readValue(plan,
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
            session.setPlanFiles(JSON_MAPPER.writeValueAsString(plannedFiles));
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
                                                 String targetPath, SseChannel channel, StringBuilder reasoningSink,
                                                 long[] usageAgg, AiModelConfig modelConfig, Integer maxTokensOverride) {
        ReplyStreamExtractor extractor = new ReplyStreamExtractor();
        long completionBefore = usageAgg[1];
        String response;
        try {
            response = callModelRound(chatClient,
                    List.of(new SystemMessage(systemPrompt), new UserMessage(filePrompt)),
                    channel, extractor, reasoningSink, usageAgg,
                    buildPipelineOptions(modelConfig, maxTokensOverride));
        } catch (ChatCancelledException e) {
            // 客户端断开不是"单文件失败"：向上传播中断整条流水线，避免误入重试继续白烧 token
            throw e;
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
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n" + parsed.getReply());
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
                                                  SseChannel channel, StringBuilder reasoningSink,
                                                  long[] usageAgg, AiModelConfig modelConfig) {
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                "\n（" + targetPath + " 单轮输出失败（超限或格式非法），转分块生成模式）");
        try {
            // ===== 分块规划轮：只输出块数与每块摘要 =====
            String planPrompt = promptBuilder.buildChunkPlanPrompt(requirement, targetPath, existingContext);
            String planResponse = callModelRound(chatClient,
                    List.of(new SystemMessage(systemPrompt), new UserMessage(planPrompt)),
                    channel, new ReplyStreamExtractor(), reasoningSink, usageAgg,
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
                sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "\n（分块 " + i + "/" + totalParts + "：" + outline.get(i - 1) + " …）");
                String partPrompt = promptBuilder.buildChunkPartPrompt(requirement, targetPath, i,
                        totalParts, outline, existingContext, maxLines, null);
                AiTemplateFileDto part = generateSingleFile(chatClient, systemPrompt, partPrompt,
                        targetPath, channel, reasoningSink, usageAgg, modelConfig, null);
                if (part == null) {
                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "\n（第 " + i + " 块输出异常，压缩篇幅重试…）");
                    String retryPrompt = promptBuilder.buildChunkPartPrompt(requirement, targetPath, i,
                            totalParts, outline, existingContext, maxLines,
                            "上一次输出被截断或格式非法。请压缩篇幅至一半以内，确保 JSON 完整且 content 为本块完整内容。");
                    part = generateSingleFile(chatClient, systemPrompt, retryPrompt,
                            targetPath, channel, reasoningSink, usageAgg, modelConfig, null);
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
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "\n（" + targetPath + " 分块生成完成，共 " + totalParts + " 块）");
            return fileDto;
        } catch (ChatCancelledException e) {
            // 客户端断开不按"分块失败"处理：向上传播中断流水线，避免继续白烧 token
            throw e;
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
            tools.jackson.databind.JsonNode root = JSON_MAPPER.readTree(text.substring(start, end + 1));
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
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseChannel channel,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg) {
        return callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf, usageAgg, null);
    }

    /**
     * 带 options 覆盖的单轮流式调用
     *
     * <p>覆盖项通过 {@link #buildPipelineOptions} 构造（maxTokens 重试翻倍、思考预算控制），
     * 其余字段（model、apiKey、temperature 等）仍沿用构建 ChatModel 时的默认 options。</p>
     */
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseChannel channel,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg,
                                  OpenAiChatOptions optionsOverride) {
        return callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf, usageAgg, optionsOverride, null);
    }

    /**
     * 带 options 覆盖与文件进度状态的单轮流式调用
     *
     * @param fileStatusResolver 文件路径 → 状态文本（如"正在生成 index.html…"），
     *                           传 null 表示不推送文件级进度（分批流水线轮次自带 progress 快照）
     */
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseChannel channel,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg,
                                  OpenAiChatOptions optionsOverride,
                                  java.util.function.Function<String, String> fileStatusResolver) {
        return callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf, usageAgg,
                optionsOverride, fileStatusResolver, null);
    }

    /**
     * 带 options 覆盖、文件进度状态与流尾部阶段文案的单轮流式调用
     *
     * @param postReplyStatusLabel reply 流完、剩余字段仍在传输时的 status 提示文案；
     *                              传 null 用默认文案（"正在接收文件内容…"，适用 files 直出管线）。
     *                              组件化管线剩余传输的是 PageSpec 页面规划，需传入语义匹配的文案
     */
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseChannel channel,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg,
                                  OpenAiChatOptions optionsOverride,
                                  java.util.function.Function<String, String> fileStatusResolver,
                                  String postReplyStatusLabel) {
        return callModelRound(chatClient, messages, channel, replyExtractor, reasoningBuf, usageAgg,
                optionsOverride, fileStatusResolver, postReplyStatusLabel, null);
    }

    /**
     * 带流尾部阶段文案与 PageSpec 心跳消费的单轮流式调用（组件化管线专用）
     *
     * @param postReplyChunkConsumer reply 流完后每个剩余 chunk 的消费者（PageSpec 页面进度心跳：
     *                               页面识别/字节阈值触发时推送 status，见 {@link com.fastcms.ai.support.PageSpecProgressScanner}）；
     *                               传 null 不消费。仅消费 reply 之后的 chunk（reasoning/reply 阶段不喂入）
     */
    private String callModelRound(ChatClient chatClient, List<Message> messages, SseChannel channel,
                                  ReplyStreamExtractor replyExtractor, StringBuilder reasoningBuf, long[] usageAgg,
                                  OpenAiChatOptions optionsOverride,
                                  java.util.function.Function<String, String> fileStatusResolver,
                                  String postReplyStatusLabel,
                                  java.util.function.Consumer<String> postReplyChunkConsumer) {
        StringBuilder responseBuffer = new StringBuilder();
        // 文件传输阶段状态是否已推送（每次调用独立）
        boolean[] filesStatusSent = {false};
        FileProgressScanner fileScanner = new FileProgressScanner();
        try {
            Prompt roundPrompt = optionsOverride != null
                    ? new Prompt(messages, optionsOverride)
                    : new Prompt(messages);
            chatClient.prompt(roundPrompt)
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        // 客户端已断开：抛出取消信号中断本轮流式调用（Reactor 会取消上游订阅）
                        if (channel.isCancelled()) {
                            throw new ChatCancelledException();
                        }
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
                                // 累积模式（Spring AI 透传的是累积值）：推送差分增量，缓冲整体替换
                                String delta = rc.substring(prev.length());
                                if (StringUtils.hasText(delta)) {
                                    sendEvent(channel, AiTemplateConstants.SSE_EVENT_REASONING, delta);
                                }
                                reasoningBuf.setLength(0);
                                reasoningBuf.append(rc);
                            } else if (!rc.equals(prev)) {
                                // 纯增量模式（部分端点透传增量而非累积值）：直接作为增量推送并追加，
                                // 覆盖缓冲会导致前端重复推送同一内容
                                sendEvent(channel, AiTemplateConstants.SSE_EVENT_REASONING, rc);
                                reasoningBuf.append(rc);
                            }
                        }
                        // 正文增量
                        String chunk = output.getText();
                        if (!StringUtils.hasText(chunk)) {
                            return;
                        }
                        // feed 前记录 reply 是否已流完：本 chunk 在闭引号之后到达，说明
                        // 确有剩余字段（files/pagespec）在传；纯问答（reply 即全部内容，
                        // 闭引号后至多一个收尾符号）则一次状态都不推，避免误导性提示闪现
                        boolean replyAlreadyFinished = replyExtractor.isFinished();
                        responseBuffer.append(chunk);
                        String replyDelta = replyExtractor.feed(chunk);
                        if (StringUtils.hasText(replyDelta)) {
                            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, replyDelta);
                        }
                        // reply 已流完（闭引号已过）、本 chunk 属于 files/pagespec 等其余字段：
                        // 推送一次状态事件，让前端知道"回复已生成，剩余内容仍在传输中"，
                        // 消除"回复结束了却长时间转圈"的假死观感（大模板剩余字段可持续数分钟）
                        if (replyAlreadyFinished && !filesStatusSent[0]) {
                            filesStatusSent[0] = true;
                            sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS,
                                    postReplyStatusLabel != null ? postReplyStatusLabel
                                            : "正在接收文件内容，大模板可能需要几分钟…");
                        }
                        // 文件级进度：每当 files 流中完整出现一个 "path":"xxx"，
                        // 推送"正在生成/修改 xxx"状态（按文件是否已存在决定动词），
                        // 让用户明确知道 AI 正在产出哪个文件，而不是笼统地"接收文件内容"
                        if (fileStatusResolver != null) {
                            for (String path : fileScanner.feed(chunk)) {
                                sendEvent(channel, AiTemplateConstants.SSE_EVENT_STATUS,
                                        fileStatusResolver.apply(path));
                            }
                        }
                        // PageSpec 心跳：组件化管线剩余传输的是页面规划，页面识别/字节阈值
                        // 跨过时由消费者推送"已识别 N 个页面 / 已接收 X KB"（与文件级进度互斥使用）
                        if (postReplyChunkConsumer != null && replyExtractor.isFinished()) {
                            postReplyChunkConsumer.accept(chunk);
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
        } catch (ChatCancelledException e) {
            // 取消信号不包装为"AI 调用失败"：向上传播由 chatStream 统一落"已中断"消息
            throw e;
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
    private void sendProgress(SseChannel channel, List<String> plannedFiles, int currentIndex, List<String> donePaths) {
        // JSON_MAPPER 序列化替代手写拼接：转义完整覆盖（含控制字符），异常文件名不会再产生非法 JSON
        List<Map<String, String>> files = new ArrayList<>(plannedFiles.size());
        for (int i = 0; i < plannedFiles.size(); i++) {
            String p = plannedFiles.get(i);
            Map<String, String> item = new LinkedHashMap<>();
            item.put("path", p);
            item.put("status", donePaths.contains(p) ? "done" : (i == currentIndex ? "current" : "pending"));
            files.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("files", files);
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_PROGRESS, toJson(data));
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
    public ApplyResult applyTemplate(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        // 调整型会话的 AI 输出已直接写入正式模板目录，不存在"应用"动作
        if (StringUtils.hasText(session.getTemplateId())) {
            throw new IllegalStateException("调整型会话的修改已直接生效，无需应用");
        }

        Path workDir = resolveEffectiveWorkDir(session);
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

        // 同名模板并发 apply 互斥（不同会话可应用同名模板，并发替换会互相踩踏）
        Object lock = APPLY_LOCKS.computeIfAbsent(session.getTemplateName(), k -> new Object());
        synchronized (lock) {
            try {
                applyWorkDirToTarget(workDir, targetPath);
            } catch (IOException e) {
                throw new RuntimeException("模板应用失败: " + e.getMessage(), e);
            }
        }

        // 刷新模板注册 + 静态资源映射：
        // 只 initialize() 不刷新映射，新模板的 /<模板名>/static/** 会 404 直到重启
        // （与 createTemplate 的 initialize + refreshStaticMapping 模式保持一致）
        try {
            templateService.initialize();
            templateService.refreshStaticMapping();
        } catch (Exception e) {
            throw new RuntimeException("刷新模板注册失败: " + e.getMessage(), e);
        }

        // 更新会话状态
        sessionService.updateStatus(sessionId, AiTemplateConstants.STATUS_APPLIED);

        // 按模板目录名匹配新注册的正式模板 ID（前端据此无缝切换到正式模板编辑）
        String templateId = null;
        try {
            for (Template registered : templateService.getTemplateList()) {
                if (session.getTemplateName().equals(registered.getPathName())) {
                    templateId = registered.getId();
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("应用后按目录名匹配模板 ID 失败（不影响应用结果）: templateName={}", session.getTemplateName(), e);
        }

        // 站点数据初始化：按 _pagespec.json 信息架构补建分类/单页/菜单（只补缺不覆盖，失败不影响模板应用）
        String seedMsg = seedSiteData(workDir, templateId);

        String result = "模板已应用到 " + targetPath + "，可在模板列表中查看并切换使用" + seedMsg;
        log.info("AI 模板应用成功: sessionId={}, templateName={}, target={}, templateId={}",
                sessionId, session.getTemplateName(), targetPath, templateId);
        return new ApplyResult(result, templateId);
    }

    // ==================== 站点数据初始化（seed） ====================

    /**
     * 应用模板后按 _pagespec.json 的 site 信息架构初始化站点数据：
     * 分类、单页为全站共享（按 suffix/path 幂等补缺）；菜单为模板专属
     * （带 template_id，与预览导航一致，不污染其他模板的菜单）。
     *
     * <p>文章不初始化（内容归用户发布）；演示图 imageOverrides 不落库
     * （仅预览语义，正式环境使用真实文章封面）。任何失败只记日志并附加提示，
     * 不回滚已应用的模板文件。</p>
     */
    String seedSiteData(Path workDir, String templateId) {
        try {
            Path specPath = workDir.resolve(COMPONENT_SPEC_FILE);
            if (!Files.isRegularFile(specPath)) {
                return "";
            }
            com.fastcms.ai.component.PageSpec spec = pageSpecParser
                    .parseResponse(Files.readString(specPath, StandardCharsets.UTF_8)).pagespec();
            if (spec == null || spec.safeSite() == null) {
                return "";
            }
            com.fastcms.ai.component.SiteContentSpec site = spec.safeSite();
            int categories = seedCategories(site.safeCategories());
            int pages = seedSinglePages(site.safeSinglePages());
            // 菜单必须挂模板作用域；模板 ID 匹配失败（极端情况）时跳过菜单，避免污染全局菜单
            int menus = templateId == null ? 0 : seedMenus(site.safeMenus(), templateId);
            if (categories + pages + menus == 0) {
                return "";
            }
            return String.format("；已按模板信息架构初始化站点数据：菜单 %d、分类 %d、单页 %d（只补缺，不覆盖已有数据）",
                    menus, categories, pages);
        } catch (Exception e) {
            log.warn("AI 模板应用后站点数据初始化失败（不影响模板应用结果）", e);
            return "；站点数据初始化失败: " + e.getMessage() + "（模板文件已应用成功）";
        }
    }

    /**
     * 分类：suffix + path 设为信息架构标识，前台 /article/category/{path} 按路径解析
     */
    private int seedCategories(List<com.fastcms.ai.component.SiteContentSpec.CatalogItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        Set<String> existing = articleCategoryService.list().stream()
                .map(ArticleCategory::getSuffix).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        int created = 0;
        for (com.fastcms.ai.component.SiteContentSpec.CatalogItem item : items) {
            if (!StringUtils.hasText(item.suffix()) || existing.contains(item.suffix())) {
                continue;
            }
            ArticleCategory category = new ArticleCategory();
            category.setParentId(0L);
            category.setTitle(item.title());
            category.setSuffix(item.suffix());
            category.setPath(item.suffix());
            category.setType(ArticleCategory.CATEGORY_TYPE);
            category.setSortNum(existing.size() + created);
            articleCategoryService.save(category);
            existing.add(item.suffix());
            created++;
        }
        return created;
    }

    /**
     * 单页：path 设为信息架构标识（/page/{path} 按路径解析），正文先占位
     */
    private int seedSinglePages(List<com.fastcms.ai.component.SiteContentSpec.CatalogItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        Set<String> existing = singlePageService.list().stream()
                .map(SinglePage::getPath).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        int created = 0;
        for (com.fastcms.ai.component.SiteContentSpec.CatalogItem item : items) {
            if (!StringUtils.hasText(item.suffix()) || existing.contains(item.suffix())) {
                continue;
            }
            SinglePage page = new SinglePage();
            page.setTitle(item.title());
            page.setPath(item.suffix());
            page.setContentHtml("<p>页面内容编辑中，请到后台「单页管理」补充正文。</p>");
            page.setSeoKeywords(item.title());
            page.setSeoDescription(item.title() + " - 页面内容编辑中");
            page.setStatus(SinglePage.STATUS_PUBLISH);
            singlePageService.save(page);
            existing.add(item.suffix());
            created++;
        }
        return created;
    }

    /**
     * 菜单：模板专属（template_id 作用域），urlType 按信息架构类型映射，
     * menuUrl 存 suffix（Menu.getUrl() 按类型拼接 /page/、/article/category/ 等前缀）。
     * type=index 跳过（导航组件硬编码首页链接，与预览行为一致）。
     */
    private int seedMenus(List<com.fastcms.ai.component.SiteContentSpec.NavItem> items, String templateId) {
        if (items.isEmpty()) {
            return 0;
        }
        Set<String> existing = menuService.list().stream()
                .filter(menu -> templateId.equals(menu.getTemplateId()))
                .map(Menu::getMenuName).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return seedMenuLevel(items, 0L, templateId, existing, new int[]{existing.size()});
    }

    private int seedMenuLevel(List<com.fastcms.ai.component.SiteContentSpec.NavItem> items, Long parentId,
                              String templateId, Set<String> existing, int[] sort) {
        int created = 0;
        for (com.fastcms.ai.component.SiteContentSpec.NavItem item : items) {
            if (com.fastcms.ai.component.SiteContentSpec.NavItem.TYPE_INDEX.equals(item.safeType())
                    || !StringUtils.hasText(item.suffix()) || existing.contains(item.name())) {
                continue;
            }
            Menu menu = new Menu();
            menu.setParentId(parentId);
            menu.setMenuName(item.name());
            menu.setMenuUrl(item.suffix());
            menu.setUrlType(menuUrlType(item.safeType()));
            menu.setSortNum(sort[0]++);
            menu.setTarget("_self");
            menu.setStatus(Menu.STATUS_SHOW);
            menu.setTemplateId(templateId);
            menuService.save(menu);
            existing.add(item.name());
            created++;
            created += seedMenuLevel(item.safeChildren(), menu.getId(), templateId, existing, sort);
        }
        return created;
    }

    /**
     * 信息架构菜单类型 → 菜单 urlType（决定 Menu.getUrl() 的路径前缀）
     */
    private Integer menuUrlType(String type) {
        return switch (type == null ? "" : type) {
            case com.fastcms.ai.component.SiteContentSpec.NavItem.TYPE_PAGE -> Menu.PAGE_URL_TYPE;
            case com.fastcms.ai.component.SiteContentSpec.NavItem.TYPE_ARTICLE -> Menu.ARTICLE_URL_TYPE;
            // article_list 及未知类型按分类列表处理
            default -> Menu.CATEGORY_URL_TYPE;
        };
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

        Path workDir = resolveEffectiveWorkDir(session);
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

    // ==================== 会话工作目录文件编辑（生成型会话，应用前的手工打磨） ====================

    /**
     * 会话工作目录可编辑的文本文件后缀白名单（与正式模板编辑保持一致的口径；
     * 图片等二进制资源走上传接口 + 预览 URL 静态分支，不走文本读写）
     */
    private static final Set<String> SESSION_EDITABLE_SUFFIX = Set.of(
            ".html", ".js", ".css", ".txt", ".json", ".properties", ".md", ".svg", ".xml", ".ftl");

    /**
     * 校验并返回生成型会话（未绑定正式模板的会话才有独立的可编辑工作目录）
     */
    private AiTemplateSession requireGenerativeSession(String sessionId) {
        AiTemplateSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (StringUtils.hasText(session.getTemplateId())) {
            throw new IllegalArgumentException("调整型会话直接修改正式模板，请使用模板编辑功能");
        }
        return session;
    }

    /**
     * 写操作会话校验：已应用（applied）的会话目录只读，改动应走正式模板编辑
     */
    private AiTemplateSession requireWritableGenerativeSession(String sessionId) {
        AiTemplateSession session = requireGenerativeSession(sessionId);
        if (AiTemplateConstants.STATUS_APPLIED.equals(session.getStatus())) {
            throw new IllegalStateException("会话已应用，工作目录只读；请通过正式模板编辑修改");
        }
        return session;
    }

    /**
     * 文件路径（以模板目录名开头，与文件树约定一致）映射为会话工作目录内的文件：
     * 前缀截取 + normalize + 防路径穿越；非法路径返回 null
     */
    private Path resolveSessionFilePath(AiTemplateSession session, String filePath) {
        if (!StringUtils.hasText(filePath) || filePath.contains("..")) {
            return null;
        }
        String templateName = session.getTemplateName();
        if (!StringUtils.hasText(templateName) || !filePath.startsWith(templateName)) {
            return null;
        }
        Path workDir = resolveEffectiveWorkDir(session);
        Path resolved = workDir.resolve(filePath.substring(templateName.length())).normalize();
        return resolved.startsWith(workDir) ? resolved : null;
    }

    /**
     * 校验文件后缀在会话可编辑白名单内（无点号路径直接拒绝）
     */
    private void assertSessionEditableSuffix(String filePath) {
        int suffixIdx = filePath.lastIndexOf(".");
        if (suffixIdx < 0) {
            throw new IllegalArgumentException("文件路径缺少后缀: " + filePath);
        }
        if (!SESSION_EDITABLE_SUFFIX.contains(filePath.substring(suffixIdx).toLowerCase())) {
            throw new IllegalArgumentException("不支持的文件类型: " + filePath.substring(suffixIdx));
        }
    }

    @Override
    public List<TemplateService.FileTreeNode> getSessionFileTree(String sessionId) {
        AiTemplateSession session = requireGenerativeSession(sessionId);
        Path workDir = resolveEffectiveWorkDir(session);
        if (!Files.isDirectory(workDir)) {
            throw new IllegalStateException("会话工作目录不存在: " + workDir);
        }
        try {
            // 构造轻量 Template 指向会话工作目录，复用正式模板的树构建规则：
            // filePath 前缀 = 根目录最后一段（即会话的 templateName），与正式模板路径约定一致
            Template template = new Template();
            template.setTemplatePath(workDir);
            return templateService.getTemplateTreeFiles(template);
        } catch (IOException e) {
            throw new RuntimeException("加载会话文件树失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getSessionFile(String sessionId, String filePath) {
        AiTemplateSession session = requireGenerativeSession(sessionId);
        Path file = resolveSessionFilePath(session, filePath);
        if (file == null || Files.isDirectory(file)) {
            throw new IllegalArgumentException("文件不存在或不可读取: " + filePath);
        }
        assertSessionEditableSuffix(filePath);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void saveSessionFile(String sessionId, String filePath, String fileContent) {
        AiTemplateSession session = requireWritableGenerativeSession(sessionId);
        if (!StringUtils.hasText(fileContent)) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        assertSessionEditableSuffix(filePath);
        Path file = resolveSessionFilePath(session, filePath);
        if (file == null) {
            throw new IllegalArgumentException("非法文件路径: " + filePath);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, fileContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSessionFile(String sessionId, String filePath) {
        AiTemplateSession session = requireWritableGenerativeSession(sessionId);
        Path file = resolveSessionFilePath(session, filePath);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("删除文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> uploadSessionFiles(String sessionId, String dirName, org.springframework.web.multipart.MultipartFile[] files) {
        AiTemplateSession session = requireWritableGenerativeSession(sessionId);
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("缺少上传文件");
        }
        Path workDir = resolveEffectiveWorkDir(session);
        // 目标子目录：空值/仅模板目录名 → 工作目录根；其余须以模板目录名开头（与文件树路径约定一致）
        Path targetDir = workDir;
        if (StringUtils.hasText(dirName)) {
            if (dirName.contains("..")) {
                throw new IllegalArgumentException("非法目录路径: " + dirName);
            }
            String rel = dirName;
            String templateName = session.getTemplateName();
            if (StringUtils.hasText(templateName) && rel.startsWith(templateName)) {
                rel = rel.substring(templateName.length());
            }
            targetDir = workDir.resolve(rel).normalize();
            if (!targetDir.startsWith(workDir)) {
                throw new IllegalArgumentException("非法目录路径: " + dirName);
            }
        }
        List<String> written = new ArrayList<>();
        for (org.springframework.web.multipart.MultipartFile file : files) {
            // 只取文件名（剥掉客户端可能携带的路径），拒绝异常文件名
            String fileName = Paths.get(file.getOriginalFilename()).getFileName().toString();
            if (fileName.isBlank() || fileName.contains("..")) {
                continue;
            }
            try {
                Path target = targetDir.resolve(fileName).normalize();
                if (!target.startsWith(workDir)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                file.transferTo(target);
                written.add(workDir.relativize(target).toString().replaceAll("\\\\", "/"));
            } catch (IOException e) {
                throw new RuntimeException("上传文件失败: " + fileName + ", " + e.getMessage(), e);
            }
        }
        return written;
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
        Path workDir = resolveEffectiveWorkDir(session);
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
     * 调整轮文件注入的 token 预算。
     *
     * <p>预算推导（128K 上下文模型）：估算器与真实 tokenizer 存在 1.3 倍以内偏差
     * （HTML/类名密集内容 tokenize 更贵），且截断兜底重试会把输出上限提到 32768——
     * 必须按"文件段 + 历史重放"合计估算 × 1.3 + 脚手架 4K + 输出 32768 ≤ 131072 反推，
     * 即合计估算 ≤ 72K。文件段 60K + 历史 8K = 68K，留 5K 以上安全余量。</p>
     */
    private static final long ADJUST_FILE_SECTION_TOKEN_BUDGET = 60_000L;

    /**
     * 升级/审计修复轮批次文件注入的 token 预算。
     *
     * <p>修复轮（锚点/渲染）采用全新上下文并再次注入文件内容，连续两轮修复时上下文
     * 约为批次内容的 2 倍；按 2 × 预算 × 1.3 + 脚手架 6K + 输出 32768 ≤ 131072 反推，
     * 预算需 ≤ 35K。正常批次（2 个页面）远低于此值，预算仅拦截异常巨型文件。</p>
     */
    private static final long UPGRADE_BATCH_TOKEN_BUDGET = 34_000L;

    /**
     * 历史消息重放的 token 总预算：超出时丢弃预算外消息（当前轮提示词优先保真）
     */
    private static final long HISTORY_REPLAY_TOKEN_BUDGET = 8_000L;

    /**
     * 单条历史消息的字符截断上限（含全量文件内容的旧提示词/AI 回复会逐轮膨胀上下文）
     */
    private static final int HISTORY_MSG_TRUNCATE_CHARS = 8_000;

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
     * 从 PageSpec JSON 中提取指定 id 的 section 片段
     *
     * <p>遍历 pages.{pageKey}.sections[]，返回首个 id 匹配的 section 节点（紧凑 JSON）。
     * 用于预览页点选区块后把目标片段注入微调提示词，让 AI 聚焦该区块修改。</p>
     *
     * @return 命中返回该 section 的 JSON 文本；未命中返回 null（AI 上一轮可能改掉了 id）
     */
    private String extractFocusSection(String specJson, String sectionId) {
        try {
            tools.jackson.databind.JsonNode root = JSON_MAPPER.readTree(specJson);
            tools.jackson.databind.JsonNode pages = root == null ? null : root.get("pages");
            if (pages == null || !pages.isObject()) {
                return null;
            }
            for (var pageEntry : pages.properties()) {
                tools.jackson.databind.JsonNode page = pageEntry.getValue();
                tools.jackson.databind.JsonNode sections = page == null ? null : page.get("sections");
                if (sections == null || !sections.isArray()) {
                    continue;
                }
                for (tools.jackson.databind.JsonNode section : sections) {
                    tools.jackson.databind.JsonNode id = section == null ? null : section.get("id");
                    if (id != null && sectionId.equals(id.asString())) {
                        return section.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 PageSpec 提取选中区块失败: sectionId={}", sectionId, e);
        }
        return null;
    }

    // ==================== 组件源码补丁（filePatches） ====================

    /**
     * 组件源码补丁路径合法格式：_components/ 下的 .ftl 文件（文件名为包前缀__组件__变体）
     */
    private static final java.util.regex.Pattern COMPONENT_PATCH_PATH_PATTERN =
            java.util.regex.Pattern.compile("^_components/[A-Za-z0-9_\\-]+\\.ftl$");

    /**
     * 收集工作目录下的组件源码（渲染产物落盘版，含点选标记），供 refine 提示词注入
     *
     * <p>焦点模式传入目标组件文件名时只收集该组件（需求只针对选中区块）；
     * 否则收集全部组件源码（需求可能指向任意区块）。</p>
     */
    private List<ComponentGenPromptBuilder.ComponentSource> collectComponentSources(Path workDir, String focusComponentFile) {
        Path componentsDir = workDir.resolve("_components");
        if (!Files.isDirectory(componentsDir)) {
            return List.of();
        }
        List<ComponentGenPromptBuilder.ComponentSource> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.list(componentsDir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ftl"))
                    .sorted().toList()) {
                String name = file.getFileName().toString();
                if (focusComponentFile != null && !focusComponentFile.equals(name)) {
                    continue;
                }
                try {
                    sources.add(new ComponentGenPromptBuilder.ComponentSource(
                            "_components/" + name, Files.readString(file, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    log.warn("读取组件源码失败，跳过: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描组件目录失败: {}", componentsDir, e);
        }
        return sources;
    }

    /**
     * 从选中区块的 spec 片段解析对应组件文件名（焦点模式精准注入组件源码用）
     *
     * <p>文件名规则与 PageSpecRenderer 一致：component 的 ':' 换 '__' + variant + .ftl。
     * spec 片段无 component 字段时返回 null（调用方回退全量注入）。</p>
     */
    private String resolveFocusComponentFile(String focusSectionJson) {
        try {
            tools.jackson.databind.JsonNode section = JSON_MAPPER.readTree(focusSectionJson);
            String component = section == null ? null : section.path("component").asString(null);
            if (!StringUtils.hasText(component)) {
                return null;
            }
            String variant = section.path("variant").asString(null);
            return variant == null || variant.isBlank()
                    ? null
                    : component.replace(":", "__") + "__" + variant.trim() + ".ftl";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 选区定位（调整型会话，路线 B）：在模板目录的 HTML 文件中按 _aiSection assign 反查
     * 区块对应的组件源码文件名
     *
     * <p>渲染产物中每个区块的引用模式固定：
     * {@code <#assign comp = {...}> / <#assign _aiSection = "id"> / <#include "_components/xxx.ftl">}，
     * 预览页点选标记（data-ai-section-root / data-ai-slot）由同一处 assign 驱动，因此按此反查
     * 即用户所见即所得（不依赖 _pagespec.json，手工改过文件同样有效）。同一区块可能被多个页面
     * 引用（如布局中的导航/页脚），返回首个命中的组件文件名；找不到返回 null（调用方退回普通调整）。</p>
     */
    private String locateSectionComponentFile(Path workDir, String sectionId) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<#assign\\s+_aiSection\\s*=\\s*\"" + java.util.regex.Pattern.quote(sectionId) + "\">"
                        + "\\s*[\\r\\n]+\\s*<#include\\s+\"_components/([^\"]+\\.ftl)\">");
        try (Stream<Path> stream = Files.walk(workDir)) {
            for (Path file : stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .sorted().toList()) {
                String content;
                try {
                    content = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (IOException e) {
            log.warn("选区定位扫描模板目录失败: {}", workDir, e);
        }
        return null;
    }

    /**
     * 应用组件源码补丁：校验（路径合法/文件已存在/search 唯一匹配/标记保留）通过后
     * 写入 _component_overrides/，渲染时优先于组件包原版生效
     *
     * <p>基于 _components/ 当前落盘版（含系统注入标记）做替换；同一文件多个补丁串行应用。
     * 单个补丁失败不影响其余补丁，失败原因记入返回清单（SSE 提示 + 落库供下轮修复）。</p>
     *
     * @return 应用结果消息（成功数 + 失败明细，全部成功且无补丁时返回 null）
     */
    private String applyComponentPatches(Path workDir, List<PageSpecParser.FilePatch> patches, SseChannel channel) {
        if (patches == null || patches.isEmpty()) {
            return null;
        }
        Path overridesDir = workDir.resolve(PageSpecRenderer.COMPONENT_OVERRIDES_DIR);
        int ok = 0;
        List<String> failures = new ArrayList<>();
        // 文件级缓存：同一文件多补丁串行应用（前一个补丁的结果是后一个的输入）
        Map<String, String> fileContents = new java.util.HashMap<>();
        for (PageSpecParser.FilePatch patch : patches) {
            try {
                if (!COMPONENT_PATCH_PATH_PATTERN.matcher(patch.path()).matches()) {
                    failures.add(patch.path() + ": 路径非法（只允许 _components/ 下的组件 .ftl）");
                    continue;
                }
                Path target = workDir.resolve(patch.path()).normalize();
                if (!target.startsWith(workDir)) {
                    failures.add(patch.path() + ": 路径越界");
                    continue;
                }
                if (!Files.isRegularFile(target)) {
                    failures.add(patch.path() + ": 组件文件不存在");
                    continue;
                }
                String content = fileContents.containsKey(patch.path())
                        ? fileContents.get(patch.path())
                        : Files.readString(target, StandardCharsets.UTF_8);
                int first = content.indexOf(patch.search());
                if (patch.search().isEmpty() || first < 0) {
                    failures.add(patch.path() + ": search 片段在源码中未找到");
                    continue;
                }
                if (content.indexOf(patch.search(), first + 1) >= 0) {
                    failures.add(patch.path() + ": search 片段匹配多处（须唯一，请扩大片段范围）");
                    continue;
                }
                String patched = content.substring(0, first) + patch.replace()
                        + content.substring(first + patch.search().length());
                // 标记保留校验：点选标记丢了会破坏换图/选区功能
                if (content.contains("data-ai-section-root") && !patched.contains("data-ai-section-root")) {
                    failures.add(patch.path() + ": 替换后丢失 data-ai-section-root 标记（预览点选依赖）");
                    continue;
                }
                Files.createDirectories(overridesDir);
                Files.writeString(overridesDir.resolve(target.getFileName()), patched, StandardCharsets.UTF_8);
                fileContents.put(patch.path(), patched);
                ok++;
            } catch (Exception e) {
                log.warn("组件补丁应用失败: {}", patch.path(), e);
                failures.add(patch.path() + ": " + e.getMessage());
            }
        }
        StringBuilder msg = new StringBuilder();
        if (ok > 0) {
            msg.append("已应用 ").append(ok).append(" 个组件样式补丁");
        }
        if (!failures.isEmpty()) {
            if (msg.length() > 0) {
                msg.append("，");
            }
            msg.append("失败 ").append(failures.size()).append(" 个：");
            for (int i = 0; i < failures.size(); i++) {
                msg.append("\n").append(i + 1).append(". ").append(failures.get(i));
            }
        }
        String result = msg.length() > 0 ? msg.toString() : null;
        if (result != null) {
            sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, "\n\n🎨 " + result + "\n");
        }
        return result;
    }

    /**
     * 构造正式模板当前文件内容（供调整型会话提示词使用）
     *
     * <p>每轮对话都从磁盘实时读取，保证用户在两轮之间通过编辑器手工修改的内容
     * 也能被 AI 感知。二进制资源（图片/字体）跳过；总量超限时截断并提示。</p>
     */
    private String buildTemplateFileSection(Path templateDir, String currentFile) {
        StringBuilder sb = new StringBuilder();
        long budget = ADJUST_FILE_SECTION_TOKEN_BUDGET;
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
                    .collect(Collectors.toList());

            // 注入优先级：用户当前编辑的页面（全文保真）> 公共布局 _layout.html > 其余文件（路径序）
            // —— 模板目录总量可达数十万 token，远超模型上下文，必须预算化注入防 400 溢出
            Map<String, Path> byRel = new LinkedHashMap<>();
            for (Path p : textFiles) {
                byRel.put(templateDir.relativize(p).toString().replaceAll("\\\\", "/"), p);
            }
            List<String> ordered = new ArrayList<>(byRel.keySet());
            ordered.sort((a, b) -> {
                int pa = fileSectionPriority(a, currentFile);
                int pb = fileSectionPriority(b, currentFile);
                return pa != pb ? Integer.compare(pa, pb) : a.compareTo(b);
            });

            for (String rel : ordered) {
                String content;
                try {
                    content = Files.readString(byRel.get(rel), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // 非 UTF-8 或读取失败的内容跳过，不影响其余文件
                    continue;
                }
                long cost = estimateTokens(content);
                if (cost > budget) {
                    if (budget >= 2_000) {
                        // 剩余预算还能装下头部内容：按比例截断注入（结构可见，超长部分省略）
                        int keepChars = (int) Math.min(content.length(),
                                Math.max(2_000L, content.length() * budget / Math.max(cost, 1)));
                        content = content.substring(0, keepChars) + "\n...（内容过长已截断，完整内容请参考磁盘文件）";
                        budget -= estimateTokens(content);
                        sb.append("### ").append(rel).append("\n```\n").append(content).append("\n```\n\n");
                        included++;
                        log.info("调整轮注入预算截断: {} (估算 {} tokens, 剩余预算 {})", rel, cost, budget);
                    } else {
                        sb.append("### ").append(rel)
                                .append("\n（上下文预算已满未注入，如需调整此文件请单独指定）\n\n");
                        log.info("调整轮注入预算已满, 跳过: {}", rel);
                    }
                    continue;
                }
                budget -= cost;
                sb.append("### ").append(rel).append("\n```\n").append(content).append("\n```\n\n");
                included++;
            }
        } catch (IOException e) {
            log.warn("扫描模板目录失败: {}", templateDir, e);
        }
        if (included == 0 && sb.length() == 0) {
            sb.append("（模板目录没有可注入的文本文件）");
        }
        log.info("调整轮模板注入: {} 个文件, 估算 {} tokens (预算 {}), 聚焦: {}",
                included, ADJUST_FILE_SECTION_TOKEN_BUDGET - budget,
                ADJUST_FILE_SECTION_TOKEN_BUDGET,
                StringUtils.hasText(currentFile) ? currentFile : "无");
        return sb.toString();
    }

    /**
     * 文件注入优先级：当前编辑页面 0（最优先）> 公共布局 1 > 其余 2
     */
    private static int fileSectionPriority(String rel, String currentFile) {
        if (StringUtils.hasText(currentFile) && rel.equals(currentFile)) {
            return 0;
        }
        if ("_layout.html".equals(rel)) {
            return 1;
        }
        return 2;
    }

    /**
     * 粗估文本 token 数：CJK 字符≈1 token/字，其余≈3字符/token。
     * 仅供注入预算控制（宁可高估防止上下文 400 溢出），非精确计量。
     */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)) {
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 3;
    }

    private void sendFileEvent(SseChannel channel, AiTemplateFileDto file) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("path", file.getPath());
        data.put("action", file.getAction());
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_FILE, toJson(data));
    }

    private void sendDone(SseChannel channel, String summary) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("summary", summary);
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_DONE, toJson(data));
    }

    private void sendError(SseChannel channel, String message) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("message", message);
        sendEvent(channel, AiTemplateConstants.SSE_EVENT_ERROR, toJson(data));
    }

    private void sendEvent(SseChannel channel, String eventName, String data) {
        channel.send(eventName, data);
    }

    /**
     * 统一 JSON 序列化（Jackson 3，异常为 unchecked，直接向上抛由既有异常路径处理）
     */
    private static String toJson(Object value) {
        return JSON_MAPPER.writeValueAsString(value);
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

    /**
     * 将工作目录内容原子化应用到目标模板目录
     *
     * <p>旧实现"先 deleteDirectory 再 copyDirectory"，拷贝中途失败（磁盘满/权限）会留下
     * 半成品模板目录且原模板已丢失。这里改为四步：
     * <ol>
     *     <li>工作目录完整拷贝到 staging 临时目录（拷贝过程不触碰正式目录）</li>
     *     <li>已存在的目标目录改名备份为 {@code <模板名>.bak}（同分区 rename，原子）</li>
     *     <li>staging rename 到目标路径（同分区，原子替换）</li>
     *     <li>成功后清理备份；第 3 步失败则回滚——清掉不完整目标、恢复备份</li>
     * </ol></p>
     *
     * <p>staging 建在目标目录同级（模板根目录下），保证与目标同分区，rename 原子生效。</p>
     *
     * <p>调用方须持有同名模板锁（{@link #APPLY_LOCKS}），本方法自身不做并发控制。</p>
     */
    private void applyWorkDirToTarget(Path workDir, Path targetPath) throws IOException {
        Path staging = Files.createTempDirectory(targetPath.getParent(), "fastcms-template-staging");
        Path backup = targetPath.resolveSibling(targetPath.getFileName() + ".bak");
        try {
            // ① 完整拷贝到 staging（失败不触碰正式目录）
            copyDirectory(workDir, staging);
            // ② 备份现有目标目录（先清掉上一轮失败可能遗留的旧备份）
            if (Files.exists(targetPath)) {
                deleteDirectory(backup);
                Files.move(targetPath, backup);
            }
            // ③ 原子替换目标
            try {
                Files.move(staging, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                // ④ 失败回滚：清掉不完整目标，恢复备份
                if (Files.exists(backup)) {
                    deleteDirectory(targetPath);
                    Files.move(backup, targetPath);
                }
                throw moveEx;
            }
            // 成功：清理备份
            deleteDirectory(backup);
        } finally {
            // staging 正常路径下已被 move 走，此处兜底清理失败遗留
            deleteDirectory(staging);
        }
    }

    // ==================== 模板源码目录镜像（已移除） ====================
    // templates 模块已从 Maven 编译链移除，dev/prod 模板目录均直接指向持久化文件目录
    // （dev=templates/src/main/resources，prod=部署目录/htmls），AI 写入天然持久化，无需镜像。

}
