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
package com.fastcms.ai.template;

import com.fastcms.entity.AiTemplateSession;

/**
 * AI 模板生成相关常量
 *
 * <p>集中管理 fastcms 模板规范的关键常量，供提示词构建器、响应解析器、Service 复用。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class AiTemplateConstants {

    private AiTemplateConstants() {
    }

    /**
     * 会话创建模式——对外两种（见 doc/wiki/ai-template-two-mode-design.md §2.2）：
     *
     * <ul>
     *     <li>pipeline（默认）：组件编排——PageSpec → 组件渲染，既有行为</li>
     *     <li>design：AI 自主设计——AI 自主设计 HTML 设计稿 → 机器审计 → 确定性转化为组件化模板；
     *         可选上传参考 HTML/zip（design 会话调 uploadReference 端点），AI 按其仿写</li>
     * </ul>
     *
     * <p>{@code import} 为<b>血统标记</b>而非对外选项：design 会话上传参考文件 ingest 成功后
     * create_mode 归一为 import（编排器 importMode 分支全量生效：页面来自 plan.json、
     * 转化后 postConvertWiring、plan 丢失提示重传、FAILED 续传回 CONVERTING）。
     * 兼容口径：新建接口白名单仍接受 import（旧客户端/脚本直传，行为与 design+上传等价），
     * 存量 import 会话全部照常工作。</p>
     */
    public static final String CREATE_MODE_PIPELINE = "pipeline";
    public static final String CREATE_MODE_DESIGN = "design";
    public static final String CREATE_MODE_IMPORT = "import";

    /**
     * 判断会话是否为 AI 自主设计（design）模式。
     *
     * <p><b>全代码唯一出口</b>：create_mode 的判空逻辑（null/"pipeline"=管线模式）只允许出现在这里，
     * 禁止各处散落 equals 判断（存量会话 create_mode=NULL 必须视为管线模式）。</p>
     *
     * @param session 会话实体（可空，null 视为管线模式）
     */
    public static boolean isDesignMode(AiTemplateSession session) {
        return session != null && CREATE_MODE_DESIGN.equalsIgnoreCase(session.getCreateMode());
    }

    /**
     * 判断会话是否为导入血统（import）——design 会话上传参考文件 ingest 后归一、
     * 或旧客户端直传 import 创建。
     *
     * <p>与 {@link #isDesignMode} 同口径：全代码唯一出口，禁止散落 equals 判断。</p>
     *
     * @param session 会话实体（可空，null 视为非导入模式）
     */
    public static boolean isImportMode(AiTemplateSession session) {
        return session != null && CREATE_MODE_IMPORT.equalsIgnoreCase(session.getCreateMode());
    }


    /**
     * 会话状态
     */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_CLOSED = "closed";

    /**
     * 消息角色
     */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    /**
     * 失败消息统一前缀：模型调用失败时落库的 assistant 消息以此开头。
     * 前端据此展示失败态与"重新生成"入口；后端构建对话历史时跳过此类消息
     * （错误文本对模型是无意义上下文）
     */
    public static final String MSG_FAIL_PREFIX = "生成失败：";

    /**
     * 文件操作类型
     */
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_MODIFY = "modify";
    public static final String ACTION_DELETE = "delete";

    /**
     * fastcms 模板必备文件
     */
    public static final String FILE_TEMPLATE_PROPERTIES = "_template.properties";
    public static final String FILE_LAYOUT = "_layout.html";
    public static final String FILE_INDEX = "index.html";
    public static final String FILE_ARTICLE = "article.html";
    public static final String FILE_ARTICLE_LIST = "article_list.html";
    public static final String FILE_PAGE = "page.html";

    /**
     * 预览演示数据文件（可选，模板目录根下，缺失时预览回退内置默认数据）
     */
    public static final String FILE_PREVIEW_DATA = "_preview_data.json";

    /**
     * 文章分页宏文件（规范产物：_layout.html 顶层 include，页面以 <@layout._articlePage/> 调用；
     * 渲染器（PageSpecRenderer）与合规校验器（TemplateComplianceChecker）共用此单一内容源，防两处漂移）
     */
    public static final String FILE_ARTICLE_PAGE = "_articlePage.html";

    /**
     * 文章分页宏文件内容（分页条样式/判空逻辑与 components/tw/pages/article_list.ftl 原内联版逐字一致）
     */
    public static final String ARTICLE_PAGE_HTML = """
            <#-- 文章列表分页宏（fastcms 规范文件）：页面用 <@layout._articlePage/> 调用 -->
            <#macro _articlePage>
              <@articlePageTag>
                <#if data??>
                  <nav class="mt-12 flex flex-wrap items-center justify-center gap-2" aria-label="文章分页">
                    <#if data.prev?? && (data.prev.url)?? && ((data.prev.url)!'')?has_content>
                      <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
                         href="${data.prev.url}">${(data.prev.text)!'上一页'}</a>
                    </#if>
                    <#if data.list?? && data.list?is_sequence>
                      <#list data.list as page>
                        <#if page?? && page?is_hash>
                          <#if (page.url)?? && ((page.url)!'')?has_content>
                            <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
                               href="${page.url}">${(page.text)!}</a>
                          <#else>
                            <span class="rounded-lg border border-primary-600 bg-primary-600 px-4 py-2 text-sm text-white"
                                  aria-current="page">${(page.text)!}</span>
                          </#if>
                        </#if>
                      </#list>
                    </#if>
                    <#if data.next?? && (data.next.url)?? && ((data.next.url)!'')?has_content>
                      <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
                         href="${data.next.url}">${(data.next.text)!'下一页'}</a>
                    </#if>
                  </nav>
                </#if>
              </@articlePageTag>
            </#macro>
            """;

    /**
     * 静态资源目录
     */
    public static final String DIR_STATIC = "static";
    public static final String DIR_STATIC_CSS = "static/css";
    public static final String DIR_STATIC_JS = "static/js";
    public static final String DIR_STATIC_IMAGES = "static/images";

    /**
     * 预览会话目录名前缀（避免与正式模板目录冲突）
     */
    public static final String PREVIEW_DIR_PREFIX = "_preview_";

    /**
     * SSE 事件类型
     */
    public static final String SSE_EVENT_MESSAGE = "message";
    /**
     * 推理模型思考过程增量（Spring AI 透传 reasoning_content，仅推理模型返回）
     */
    public static final String SSE_EVENT_REASONING = "reasoning";
    public static final String SSE_EVENT_FILE = "file";
    /**
     * 分批流水线进度快照（全量推送文件清单及各自状态：done/current/pending）
     */
    public static final String SSE_EVENT_PROGRESS = "progress";
    public static final String SSE_EVENT_DONE = "done";
    public static final String SSE_EVENT_ERROR = "error";
    /**
     * 阶段性状态提示（如"正在接收文件内容…"），前端展示为进行中的状态行，
     * 区别于 message（正文内容）
     */
    public static final String SSE_EVENT_STATUS = "status";
    /**
     * 预览页面自动切换（调整/升级轮流式期间识别到 AI 正在处理的首个可路由 HTML 即推送，
     * 前端实时预览立即切到该页面，无需等文件写盘）
     */
    public static final String SSE_EVENT_SWITCH_FILE = "switch-file";
    /**
     * 本轮 token 用量（done 之后推送，跨轮次聚合：含工具调用/修复轮），
     * 前端挂在最后一条 assistant 消息上 hover 展示
     */
    public static final String SSE_EVENT_USAGE = "usage";
    /**
     * 设计稿模式：等待人工确认（data 结构 {state:"AWAITING_CONFIRM", issues:[...], previewUrl:...}）。
     * 仅 design 会话会推送；旧前端按"未知事件忽略"原则处理（SSE 事件按名字分发）
     */
    public static final String SSE_EVENT_CONFIRM_REQUEST = "confirm_request";

    /**
     * 会话运行态通知（data 结构 {running:false}）：stream 续连端点发现无运行任务时推送，
     * 前端据此走终态恢复（loadMessages / refreshFiles）——区别于直接断流（可能是网络问题）
     */
    public static final String SSE_EVENT_RUN_STATUS = "run-status";

}
