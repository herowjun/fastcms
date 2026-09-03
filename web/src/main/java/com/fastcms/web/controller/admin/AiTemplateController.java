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
     * 10 个文件全程可能超 30 分钟；超时断流后后端仍会继续完成落盘，但前端看不到进度，
     * 因此整体放宽。单轮流式调用超时由 AiModelConfigServiceImpl 的 callTimeout + Reactor 兜底控制）
     */
    private static final long SSE_TIMEOUT = 60 * 60 * 1000L;

    @Autowired
    private IAiTemplateGenService templateGenService;

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
                request == null ? null : request.getCurrentFile(), emitter);
        return emitter;
    }

    /**
     * 应用模板
     *
     * <p>将预览工作目录的文件复制到正式模板目录，并刷新模板注册。
     * 调整型会话（绑定正式模板）不支持应用——其 AI 输出已直接写入正式模板目录。</p>
     */
    @PostMapping("sessions/{sessionId}/apply")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_APPLY, resource = "ai:template:apply", action = ActionTypes.WRITE)
    public RestResult<String> apply(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.applyTemplate(sessionId));
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
     * 旧模板升级状态（前端据此展示「升级为组件版」按钮）
     *
     * <p>判定标准：会话工作目录有 html 页面且无 _pagespec.json（组件化标志物）。
     * 已组件化的模板返回 false，按钮隐藏。</p>
     */
    @GetMapping("sessions/{sessionId}/legacy-status")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_LIST, resource = "ai:template:list", action = ActionTypes.READ)
    public RestResult<Boolean> legacyStatus(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        return RestResultUtils.success(templateGenService.isLegacyTemplate(sessionId));
    }

    /**
     * 旧模板确定性升级为组件化模板（不经 AI）
     *
     * <p>从 _preview_data.json 提取站点名等内容资产 → 默认 PageSpec（导航+首屏+文章流+页脚）
     * → 校验 → 旧文本文件备份 → 渲染 → 清理（二进制资源保留）。
     * 秒级完成；升级后可直接对话微调（换主色/加组件/改文案）。</p>
     */
    @PostMapping("sessions/{sessionId}/upgrade")
    @Secured(name = RESOURCE_NAME_AI_TEMPLATE_APPLY, resource = "ai:template:apply", action = ActionTypes.WRITE)
    public RestResult<String> upgrade(@PathVariable("sessionId") String sessionId) {
        if (requireOwnedSession(sessionId) == null) {
            return RestResultUtils.failed("会话不存在");
        }
        try {
            return RestResultUtils.success(templateGenService.upgradeLegacyTemplate(sessionId));
        } catch (Exception e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

}
