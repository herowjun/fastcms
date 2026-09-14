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
package com.fastcms.web.controller.admin;

import com.fastcms.ai.template.AiTemplateChatRequest;
import com.fastcms.ai.template.AiTemplateSessionRequest;
import com.fastcms.ai.template.IAiTemplateGenService;
import com.fastcms.common.auth.ActionTypes;
import com.fastcms.common.auth.Secured;
import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.core.auth.AuthUtils;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import com.fastcms.entity.AiTemplateFile;
import com.fastcms.entity.AiTemplateMessage;
import com.fastcms.entity.AiTemplateSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static com.fastcms.service.IResourceService.ResourceI18n.*;

/**
 * AI 模板生成器
 *
 * <p>提供会话管理、SSE 流式对话、文件查询、应用模板等接口。
 * 对话接口为 POST（input 走请求体），前端使用 fetch + ReadableStream
 * 监听 SSE 事件，实现"对话即生成"的体验。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@RestController
@RequestMapping(FastcmsConstants.ADMIN_MAPPING + "/ai/template")
public class AiTemplateController {

    /**
     * SSE 超时时间：60 分钟（分批流水线逐文件生成，推理模型单文件可达 3-4 分钟，
     * 10 个文件全程可能超 30 分钟，因此整体放宽。断流后任务后台续跑，重开页面可经
     * stream 端点续看；单轮流式调用超时由 AiModelConfigServiceImpl 的 callTimeout + Reactor 兜底控制）
     */
    private static final long SSE_TIMEOUT = 60 * 60 * 1000L;

    @Autowired
    private IAiTemplateGenService templateGenService;

    /**
     * AI 配置（设计稿模式总开关：关闭时前端隐藏模式选项，等价于"功能不存在"，§7.5）
     */
    @Autowired
    private com.fastcms.ai.autoconfigure.FastcmsAiProperties aiProperties;

    /**
     * 加载会话并校验属主：会话属于创建者本人，其他管理员（即使拥有 ai:template 权限）
     * 不可查看/操作他人会话（水平越权防护：apply/rollback/delete 会改动模板目录）
     *
     * @return 属主校验通过的会话；会话不存在或非属主时返回 null（调用方统一返回"会话不存在"，
     *         不区分两种情况，避免向非属主泄露会话是否存在）
     */
    private AiTemplateSession requireOwnedSession(String sessionId) {
        AiTemplateSession session = templateGenService.getSession(sessionId);
        if (session == null || !java.util.Objects.equals(session.getUserId(), AuthUtils.getUserId())) {
            return null;
        }
        return session;
    }

    /**
     * 列出当前用户的会话
     */
    @GetMapping("sessions")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<List<AiTemplateSession>> listSessions() {
        return RestResultUtils.success(templateGenService.listSessions(AuthUtils.getUserId()));
    }

    /**
     * 获取会话详情
     */
    @GetMapping("sessions/{sessionId}")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<AiTemplateSession> getSession(@PathVariable("sessionId") String sessionId) {
        AiTemplateSession session = requireOwnedSession(sessionId);
        if (session == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(session);
    }

    /**
     * 创建会话
     */
    @PostMapping("sessions")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CREATE, resource = "ai:template:create", action = ActionTypes.WRITE)
    public RestResult<AiTemplateSession> createSession(@RequestBody AiTemplateSessionRequest request) {
        try {
            return RestResultUtils.success(templateGenService.createSession(request, AuthUtils.getUserId()));
        } catch (IllegalArgumentException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * HTML 导入（zip 站包 / 单 HTML 文件）
     *
     * <p>createMode=import 会话专用：同步完成 ingest（解压防 slip + pageKey 推导 +
     * 归一化落盘 + 资产归位 + plan.json 生成，状态 CONVERTING 起步）；
     * 转化由前端随后走既有 chat 端点触发（编排器 CONVERTING 起步复用转化引擎）。</p>
     *
     * <p>multipart 表单，字段名 file；报告含 pageCount/assetCount/notes（显式标注）。</p>
     */
    @PostMapping("sessions/{sessionId}/import")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.WRITE)
    public RestResult<java.util.Map<String, Object>> importHtml(
            @PathVariable("sessionId") String sessionId,
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(
                    templateGenService.importHtml(sessionId, file, AuthUtils.getUserId()));
        } catch (IllegalArgumentException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 设计稿先行模式选项（新建模板对话框"生成模式"数据源）
     *
     * <p>enabled=设计稿模式总开关（关闭时前端隐藏模式选项，§7.5 灰度语义）；
     * directions=方向资产清单（key/name/summary，轮换池顺序，插件挂载的方向自动出现在尾部）。
     * 读取端点复用 ai:template:list 资源点（打开对话框即可见，无独立权限域）。</p>
     */
    @GetMapping("design-options")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<java.util.Map<String, Object>> designOptions() {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("enabled", aiProperties.getTemplate().getDesign().isEnabled());
        data.put("directions", com.fastcms.ai.component.DesignDirectionLibrary.listAssets());
        return RestResultUtils.success(data);
    }

    /**
     * 删除会话
     */
    @PostMapping("sessions/{sessionId}/delete")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_DELETE, resource = "ai:template:delete", action = ActionTypes.WRITE)
    public RestResult<Boolean> deleteSession(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        templateGenService.deleteSession(sessionId);
        return RestResultUtils.success(true);
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("sessions/{sessionId}/messages")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<List<AiTemplateMessage>> listMessages(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.listMessages(sessionId));
    }

    /**
     * 获取会话生成的文件列表
     */
    @GetMapping("sessions/{sessionId}/files")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_FILES, resource = "ai:template:files", action = ActionTypes.READ)
    public RestResult<List<AiTemplateFile>> listFiles(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.listFiles(sessionId));
    }

    /**
     * SSE 流式对话（POST）
     *
     * <p>对话输入通过 JSON 请求体传递（{@link AiTemplateChatRequest#getInput()}），
     * 相比原 GET + query 参数方式，不再受 URL 长度与 query 编码限制，
     * 且 Authorization 头可正常携带。</p>
     *
     * <p>前端使用 fetch 监听 SSE 流：
     * <pre>
     * const resp = await fetch(`/fastcms/api/admin/ai/template/sessions/${sessionId}/chat`, {
     *     method: 'POST',
     *     headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
     *     body: JSON.stringify({ input })
     * });
     * // 解析 resp.body（text/event-stream），按事件类型分发：message / file / done / error
     * </pre>
     */
    @PostMapping(value = "sessions/{sessionId}/chat", produces = "text/event-stream;charset=UTF-8")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.WRITE)
    public SseEmitter chat(@PathVariable("sessionId") String sessionId,
                           @RequestBody AiTemplateChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        if (requireOwnedSession(sessionId) == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"会话不存在\"}"));
                emitter.complete();
            } catch (java.io.IOException ignored) {
            }
            return emitter;
        }
        templateGenService.chatStream(sessionId, request == null ? null : request.getInput(),
                request == null ? null : request.getCurrentFile(),
                request == null ? null : request.getFocusSectionId(),
                request == null ? null : request.getFocusElementHint(),
                request != null && Boolean.TRUE.equals(request.getStyleUpgrade()),
                request != null && Boolean.TRUE.equals(request.getDeepRefresh()),
                request != null && Boolean.TRUE.equals(request.getFullRefresh()),
                request != null && Boolean.TRUE.equals(request.getFullInject()),
                request == null ? null : request.getFeedback(),
                request == null ? null : request.getConfirmAction(),
                emitter);
        return emitter;
    }

    // ==================== 任务运行态（关页后台续跑 + 重开续看） ====================

    /**
     * 会话运行态探测
     *
     * <p>前端打开会话时调用：任务仍在跑（页面关闭后后台续跑）则续连 stream 端点，
     * 已结束则走终态恢复（loadMessages / refreshFiles）。</p>
     */
    @GetMapping("sessions/{sessionId}/run-status")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.READ)
    public RestResult<IAiTemplateGenService.RunStatus> runStatus(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.getRunStatus(sessionId));
    }

    /**
     * 续看运行中的任务（SSE）
     *
     * <p>回放 since 之后的历史事件（含已发生的思考过程），再实时续接。
     * 事件携带 SSE 标准 id（事件 seq），前端记录 lastSeq 供断线再次续连时增量回放。</p>
     */
    @GetMapping(value = "sessions/{sessionId}/stream", produces = "text/event-stream;charset=UTF-8")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.READ)
    public SseEmitter stream(@PathVariable("sessionId") String sessionId,
                             @RequestParam(value = "since", defaultValue = "0") long since) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        if (requireOwnedSession(sessionId) == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"会话不存在\"}"));
                emitter.complete();
            } catch (java.io.IOException ignored) {
            }
            return emitter;
        }
        templateGenService.observeStream(sessionId, since, emitter);
        return emitter;
    }

    /**
     * 显式停止运行中的任务（断开连接不触发取消——关页面任务后台续跑，停止只能显式点击）
     */
    @PostMapping("sessions/{sessionId}/stop")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.WRITE)
    public RestResult<Boolean> stop(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.stopRun(sessionId));
    }

    /**
     * 应用模板
     *
     * <p>将预览工作目录的文件复制到正式模板目录，并刷新模板注册。
     * 调整型会话（绑定正式模板）不支持应用——其 AI 输出已直接写入正式模板目录。</p>
     *
     * @return ApplyResult（message：应用结果描述；templateId：应用后正式模板 ID，
     *         前端据此从会话编辑模式无缝切换到正式模板编辑）
     */
    @PostMapping("sessions/{sessionId}/apply")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_APPLY, resource = "ai:template:apply", action = ActionTypes.WRITE)
    public RestResult<IAiTemplateGenService.ApplyResult> apply(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.applyTemplate(sessionId));
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    // ==================== 会话工作目录文件编辑（生成型会话，应用前的手工打磨） ====================

    /**
     * 会话工作目录文件树（与正式模板文件树同构：filePath 以模板目录名开头）
     */
    @GetMapping("sessions/{sessionId}/files/tree")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_FILES, resource = "ai:template:files/tree", action = ActionTypes.READ)
    public RestResult<List<com.fastcms.core.template.TemplateService.FileTreeNode>> sessionFileTree(
            @PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.getSessionFileTree(sessionId));
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 读取会话工作目录的文本文件内容
     */
    @GetMapping("sessions/{sessionId}/file/get")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_FILES, resource = "ai:template:file/get", action = ActionTypes.READ)
    public RestResult<String> getSessionFile(@PathVariable("sessionId") String sessionId,
                                             @RequestParam("filePath") String filePath) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.getSessionFile(sessionId, filePath));
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 保存（或新建）会话工作目录的文本文件（仅未应用的生成型会话可写）
     */
    @PostMapping("sessions/{sessionId}/file/save")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_FILE_EDIT, resource = "ai:template:file/save", action = ActionTypes.WRITE)
    public RestResult<Boolean> saveSessionFile(@PathVariable("sessionId") String sessionId,
                                               @RequestParam("filePath") String filePath,
                                               @RequestParam("fileContent") String fileContent) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            templateGenService.saveSessionFile(sessionId, filePath, fileContent);
            return RestResultUtils.success(true);
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 删除会话工作目录的文件（仅未应用的生成型会话可删）
     */
    @PostMapping("sessions/{sessionId}/file/delete")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_FILE_EDIT, resource = "ai:template:file/delete", action = ActionTypes.WRITE)
    public RestResult<Boolean> deleteSessionFile(@PathVariable("sessionId") String sessionId,
                                                 @RequestParam("filePath") String filePath) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            templateGenService.deleteSessionFile(sessionId, filePath);
            return RestResultUtils.success(true);
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 上传文件到会话工作目录（仅未应用的生成型会话可传）
     *
     * @param dirName 目标子目录（以模板目录名开头，与文件树路径约定一致）
     */
    @PostMapping("sessions/{sessionId}/files/upload")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_FILE_EDIT, resource = "ai:template:files/upload", action = ActionTypes.WRITE)
    public RestResult<List<String>> uploadSessionFiles(@PathVariable("sessionId") String sessionId,
                                                       @RequestParam(value = "dirName", required = false) String dirName,
                                                       @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.uploadSessionFiles(sessionId, dirName, files));
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 回滚最近一轮 AI 修改（仅调整型会话支持）
     *
     * <p>将最近一轮对话修改过的文件恢复到该轮修改前的状态：
     * 修改过的文件恢复旧内容、AI 新建的文件删除、AI 删除的文件重建。
     * 回滚完成后该轮备份被清除，再次调用将作用于更早一轮。</p>
     */
    @PostMapping("sessions/{sessionId}/rollback")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_ROLLBACK, resource = "ai:template:rollback", action = ActionTypes.WRITE)
    public RestResult<String> rollback(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.rollbackLast(sessionId));
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 旧模板升级状态（前端据此展示「样式组件化升级」横幅）
     *
     * <p>判定标准：会话工作目录有 html 页面、无 _pagespec.json（组件化标志物）
     * 且样式升级未完成（_style_upgrade.json 的 pending 不为空）。
     * 返回进度数据（pendingCount/doneCount/totalFiles），横幅区分
     * "未升级"与"上次升级未完成，可断点续传"。</p>
     */
    @GetMapping("sessions/{sessionId}/legacy-status")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeStatusInfo> legacyStatus(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.getLegacyUpgradeStatus(sessionId));
    }

    /**
     * 设计稿先行模式确认状态（前端刷新后恢复确认卡片用）
     *
     * <p>plan.json 处于 AWAITING_CONFIRM 时返回确认卡片数据
     * （{state, issues[], previewUrl, confirmAuto}，与 confirm_request SSE 事件同构），
     * 其余状态返回 null——前端据此判断是否在会话历史末尾重建"确认转化/驳回修改"卡片。</p>
     */
    @GetMapping("sessions/{sessionId}/design-status")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<java.util.Map<String, Object>> designStatus(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.getDesignConfirmCard(sessionId));
    }

    /**
     * 更新图片槽位（预览页点选换图，不经 AI 对话）
     *
     * <p>调整页开启"换图"模式后点选带 data-ai-slot 标记的图片，
     * 前端回传 sectionId/slot + 附件库图片 ID（搜库/生成/上传三个来源均可归一为附件 ID），
     * 服务端完成 spec 槽位替换 → 校验 → 重渲染 → 产物持久化。</p>
     *
     * @return 本次写出的模板内相对路径清单（前端据此刷新预览）
     */
    @PostMapping("sessions/{sessionId}/image-slot")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.WRITE)
    public RestResult<List<String>> updateImageSlot(@PathVariable("sessionId") String sessionId,
                                                    @RequestBody ImageSlotUpdateRequest request) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        if (request == null) {
            return RestResultUtils.failed("缺少请求参数");
        }
        try {
            return RestResultUtils.success(templateGenService.updateImageSlot(
                    sessionId, request.getSectionId(), request.getSlot(), request.getAttachmentId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 图片槽位更新请求体（sectionId/slot 来自预览页 data-ai-section/data-ai-slot 标记）
     */
    public static class ImageSlotUpdateRequest {
        private String sectionId;
        private String slot;
        private Long attachmentId;

        public String getSectionId() {
            return sectionId;
        }

        public void setSectionId(String sectionId) {
            this.sectionId = sectionId;
        }

        public String getSlot() {
            return slot;
        }

        public void setSlot(String slot) {
            this.slot = slot;
        }

        public Long getAttachmentId() {
            return attachmentId;
        }

        public void setAttachmentId(Long attachmentId) {
            this.attachmentId = attachmentId;
        }
    }

    /**
     * 更新预览演示图片（预览页点选无槽位标记的 mock 图片换图，不经 AI 对话）
     *
     * <p>与 image-slot 的区别：槽位图改 _pagespec.json（模板资产，正式生效）；
     * 演示图改 _preview_data.json 的 imageOverrides 映射（文章封面等 mock 数据），
     * 仅预览渲染生效，正式环境的图片由数据库文章数据决定。</p>
     */
    @PostMapping("sessions/{sessionId}/preview-image")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_CHAT, resource = "ai:template:chat", action = ActionTypes.WRITE)
    public RestResult<Boolean> updatePreviewImage(@PathVariable("sessionId") String sessionId,
                                                  @RequestBody ImagePreviewUpdateRequest request) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        if (request == null) {
            return RestResultUtils.failed("缺少请求参数");
        }
        try {
            templateGenService.updatePreviewImage(sessionId, request.getImageUrl(), request.getAttachmentId());
            return RestResultUtils.success(true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 预览演示图片更新请求体（imageUrl 为模板渲染输出的原样图片地址，作为替换映射 key）
     */
    public static class ImagePreviewUpdateRequest {
        private String imageUrl;
        private Long attachmentId;

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public Long getAttachmentId() {
            return attachmentId;
        }

        public void setAttachmentId(Long attachmentId) {
            this.attachmentId = attachmentId;
        }
    }

}
