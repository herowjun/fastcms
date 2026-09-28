package com.fastcms.ai.template;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * AI 模板预览控制器：用真实 FreeMarker 引擎 + 演示数据渲染预览目录中的模板
 *
 * <p>模板中的 fastcms 内置指令（menuTag、articleListTag、seoTag 等）真实实现全部查数据库，
 * 而 AI 新模板对应的站点在库里没有配套数据（菜单未配置、文章为空），直接复用真实指令
 * 预览出来的是空白或错乱页面。因此预览环境使用
 * {@link AiTemplatePreviewMockSupport} 提供的 <b>mock 指令集</b>：
 * 与真实指令返回结构完全一致的演示数据，模板代码无需任何修改即可渲染出完整效果。
 *
 * <p>渲染核心在 {@link AiTemplatePreviewRenderer}（与调整型会话的渲染校验共用同一管线）：
 * <ul>
 *     <li>内置指令全部替换为 mock 实现（菜单、文章、分类、标签、单页、分页、SEO 等）</li>
 *     <li>ctx() 覆盖为预览静态资源路径，使 CSS/JS 请求回到本控制器的静态文件分支</li>
 *     <li>页面级变量（article、category、articleVoPage、singlePage）按模板文件名注入演示数据</li>
 *     <li>模板中出现的未知指令（AI 幻觉、插件扩展）自动注册兜底实现，输出注释而非 500</li>
 *     <li>渲染异常时返回带异常信息的错误页，便于回到 AI 对话中让模型修复</li>
 * </ul>
 *
 * <p>文件定位基于会话数据库记录的绝对路径 {@code AiTemplateSession.workDir}，
 * 不依赖进程工作目录（user.dir），避免 IDE 与命令行启动方式不一致导致路径错位。
 * .html 走 FreeMarker 渲染，其余（css/js/图片等）作为静态文件直接返回。
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@RestController
public class AiTemplatePreviewController {

    private static final Logger log = LoggerFactory.getLogger(AiTemplatePreviewController.class);

    /**
     * 路由前缀（与前端 previewUrl 保持一致）
     */
    static final String PREVIEW_URL_PREFIX = "/ai/template/preview/";

    /**
     * 正式模板预览路由前缀（模板编辑页使用，按 templateId 定位模板目录）
     */
    static final String TEMPLATE_PREVIEW_URL_PREFIX = "/template/preview/";

    @jakarta.annotation.Resource
    private IAiTemplateGenService aiTemplateGenService;

    @jakarta.annotation.Resource
    private com.fastcms.core.template.TemplateService templateService;

    @jakarta.annotation.Resource
    private AiTemplatePreviewRenderer previewRenderer;

    @GetMapping("/ai/template/preview/{sessionId}/{templateName}/**")
    public void preview(@PathVariable("sessionId") String sessionId,
                        @PathVariable("templateName") String templateName,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {

        // 从 URI 提取 /ai/template/preview/{sessionId}/{templateName}/ 之后的相对路径
        String prefix = PREVIEW_URL_PREFIX + sessionId + "/" + templateName + "/";
        String requestUri = request.getRequestURI();
        String relPath = requestUri.length() > prefix.length()
                ? URLDecoder.decode(requestUri.substring(prefix.length()), StandardCharsets.UTF_8)
                : "";

        // 会话工作目录（数据库绝对路径，跨启动方式稳定；
        // 调整型会话按模板 ID 实时解析并自愈迁移后的路径）
        com.fastcms.entity.AiTemplateSession session = aiTemplateGenService.getSession(sessionId);
        if (session == null || !StringUtils.hasText(session.getWorkDir())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览会话不存在: " + sessionId);
            return;
        }

        Path workDir = aiTemplateGenService.resolveEffectiveWorkDir(session);
        if (!Files.isDirectory(workDir)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览目录不存在: " + session.getWorkDir());
            return;
        }

        // 兼容文件树路径约定：会话编辑模式的文件树返回的 filePath 以模板目录名开头（如 mytpl/index.html），
        // 与 previewTemplate 分支的截取规则保持一致，截掉后再按相对路径解析
        if (relPath.startsWith(templateName + "/")) {
            relPath = relPath.substring(templateName.length() + 1);
        }

        servePreview(workDir, PREVIEW_URL_PREFIX + sessionId + "/" + templateName, templateName,
                templateName, relPath, response);
    }

    /**
     * 正式模板预览：按模板 ID 渲染当前已安装的模板（模板编辑页「预览」按钮）
     *
     * <p>与 AI 会话预览共用同一套 mock 指令集与渲染管线，区别仅在于工作目录
     * 来自 {@link TemplateService} 注册的正式模板目录，页面内点击跳转的相对链接
     * 会回到本路由（按模板文件名映射路由），静态资源走本路由的静态文件分支。</p>
     */
    @GetMapping("/template/preview/{templateId}/**")
    public void previewTemplate(@PathVariable("templateId") String templateId,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {

        String prefix = TEMPLATE_PREVIEW_URL_PREFIX + templateId + "/";
        String requestUri = request.getRequestURI();
        String relPath = requestUri.length() > prefix.length()
                ? URLDecoder.decode(requestUri.substring(prefix.length()), StandardCharsets.UTF_8)
                : "";

        com.fastcms.core.template.Template template = templateService.getTemplate(templateId);
        if (template == null || template.getTemplatePath() == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "模板不存在: " + templateId);
            return;
        }

        Path workDir = template.getTemplatePath();
        if (!Files.isDirectory(workDir)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "模板目录不存在: " + workDir);
            return;
        }

        // 兼容文件树路径约定：文件树返回的 filePath 以模板目录名开头（如 xjd2022/index.html），
        // 与 TemplateController.getFilePath 的前缀截取规则保持一致，截掉后再按相对路径解析
        String pathName = template.getPathName();
        if (StringUtils.hasText(pathName) && relPath.startsWith(pathName + "/")) {
            relPath = relPath.substring(pathName.length() + 1);
        }

        servePreview(workDir, TEMPLATE_PREVIEW_URL_PREFIX + templateId, templateId,
                template.getPathName(), relPath, response);
    }

    /**
     * 预览公共管线：定位文件 → html 走 FreeMarker mock 渲染 / 其余按静态文件返回
     *
     * @param workDir     模板根目录
     * @param urlPrefix   预览 URL 前缀（不含结尾斜杠），用于覆盖 ctx() 的静态资源基路径
     * @param displayName 错误页展示的模板标识
     * @param dirPrefix   页面链接的目录前缀（文件树 filePath 约定的模板目录名；
     *                    会话预览 = templateName，正式模板预览 = pathName），null/空不加前缀
     * @param relPath     模板内相对路径
     */
    private void servePreview(Path workDir, String urlPrefix, String displayName, String dirPrefix,
                              String relPath, HttpServletResponse response) throws IOException {
        if (!StringUtils.hasText(relPath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "缺少预览文件路径");
            return;
        }

        // 缺页面引导页：菜单/分类/标签/单页指向的 {type}_{suffix}.html 不存在时，
        // mock 数据把它们指向此路径段（而非静默回退到基础页）。此处不是真实文件，
        // 必须在路径穿越/可读性校验之前拦截。
        String missingPrefix = AiTemplatePreviewMockSupport.MISSING_PAGE_SEGMENT + "/";
        if (relPath.startsWith(missingPrefix)) {
            writeMissingPageGuide(workDir, relPath.substring(missingPrefix.length()), response);
            return;
        }

        // 防路径穿越：解析后必须仍在工作目录内
        Path target = workDir.resolve(relPath).normalize();
        if (!target.startsWith(workDir) || !Files.isReadable(target)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览文件不存在: " + relPath);
            return;
        }

        if (relPath.toLowerCase().endsWith(".html")) {
            renderTemplate(urlPrefix, displayName, dirPrefix, workDir, relPath, response);
        } else {
            serveStaticFile(target, response);
        }
    }

    /**
     * FreeMarker 渲染模板并输出（mock 指令集 + 页面级演示数据）
     *
     * <p>渲染核心委托给 {@link AiTemplatePreviewRenderer}（与渲染校验共用同一管线），
     * 每次渲染重新加载模板目录下的 {@code _preview_data.json}（存在时），
     * 手工编辑或 AI 修改该文件后刷新预览立即生效，无需重启。</p>
     */
    private void renderTemplate(String urlPrefix, String displayName, String dirPrefix, Path workDir,
                                String relPath, HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            String html = previewRenderer.renderPage(urlPrefix, dirPrefix, workDir, relPath);
            if (!previewRenderer.hasMenuConfig(workDir)) {
                html = injectMenuNotice(html);
            }
            response.getWriter().write(html);
            response.getWriter().flush();
        } catch (Exception e) {
            log.error("AI 模板预览渲染失败: {}/{}", displayName, relPath, e);
            writeErrorPage(response, displayName, relPath, e);
        }
    }

    /**
     * 直接返回静态资源（css/js/图片等），ctx() 覆盖后模板内静态资源 URL 会回到本分支
     */
    private void serveStaticFile(Path target, HttpServletResponse response) throws IOException {
        MediaType mediaType = MediaTypeFactory.getMediaType(target.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        response.setContentType(mediaType.toString());
        Files.copy(target, response.getOutputStream());
        response.getOutputStream().flush();
    }

    /**
     * 渲染失败时返回可读的错误页（含异常信息，方便定位 AI 生成模板的语法问题）
     */
    private void writeErrorPage(HttpServletResponse response, String templateName, String relPath, Exception e)
            throws IOException {
        try {
            response.reset();
        } catch (IllegalStateException ignored) {
            // 响应已提交则无法重置，尽力写入错误页
        }
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("text/html;charset=UTF-8");

        String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
        // FreeMarker 解析错误信息中包含模板名与行号，直接展示
        String safeMsg = message.replace("<", "&lt;").replace(">", "&gt;");
        String safeTpl = templateName == null ? "" : templateName.replace("<", "&lt;");
        String safeFile = relPath == null ? "" : relPath.replace("<", "&lt;");
        String page = "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
                + "<title>模板预览失败</title>"
                + "<style>body{font-family:'Microsoft YaHei',sans-serif;max-width:860px;margin:40px auto;padding:0 16px;color:#333}"
                + "h1{font-size:20px;color:#d03050}pre{background:#f5f5f5;padding:12px;border-radius:6px;"
                + "white-space:pre-wrap;word-break:break-all;font-size:13px}</style></head><body>"
                + "<h1>模板预览失败</h1>"
                + "<p>模板 <b>" + safeTpl + "/" + safeFile + "</b> 渲染出错，通常是模板语法问题，可回到 AI 对话中描述错误让模型修复。</p>"
                + "<pre>" + safeMsg + "</pre></body></html>";
        try {
            response.getWriter().write(page);
            response.getWriter().flush();
        } catch (IllegalStateException ignored) {
            // 响应已提交，错误页写不进去，日志中已有异常堆栈
        }
    }

    // ==================== 预览缺口引导（缺页面 / 菜单未配置） ====================

    /**
     * 渲染"缺页面引导页"
     *
     * <p>菜单/分类/标签/单页指向的 {@code {type}_{suffix}.html} 在模板目录中不存在时，
     * 预览<b>不再静默回退</b>到该类型的基础页——那会让多个条目悄悄并到同一页
     * （"新闻动态""产品中心"都跳 article_list.html），用户完全看不出配置已失效。
     * 此处落到本页说明原因，并给出两个出口：让 AI 生成该页面 / 从预览数据中删除该条目。</p>
     *
     * @param workDir 模板根目录（用于回读预览数据，取条目的展示信息）
     * @param ref     条目引用（见 {@code AiTemplatePreviewMockSupport.REF_MENU}）
     */
    private void writeMissingPageGuide(Path workDir, String ref, HttpServletResponse response) throws IOException {
        AiTemplatePreviewMockSupport.PreviewDataConfig config =
                AiTemplatePreviewMockSupport.loadPreviewDataConfig(workDir);
        AiTemplatePreviewMockSupport.MissingItem item =
                AiTemplatePreviewMockSupport.findMissingItem(config, ref);
        response.setContentType("text/html;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(missingPageGuideHtml(item, ref));
        response.getWriter().flush();
    }

    private String missingPageGuideHtml(AiTemplatePreviewMockSupport.MissingItem item, String rawRef) {
        String ref = item == null ? rawRef : item.ref();
        String label = item == null ? "条目" : item.kindLabel();
        String name = item == null ? rawRef : item.name();
        String expected = item == null ? "" : item.expectedFile();
        boolean deletable = item != null && item.deletable();
        String type = item == null ? "" : item.type();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>预览提示 · 暂无对应页面</title><style>")
                .append("body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;")
                .append("background:#f5f6f8;color:#1f2937;font:14px/1.7 -apple-system,'Microsoft YaHei','PingFang SC',sans-serif}")
                .append(".card{width:min(580px,calc(100% - 48px));background:#fff;border:1px solid #e5e7eb;")
                .append("border-radius:12px;padding:28px 28px 20px;box-shadow:0 2px 12px rgba(15,23,42,.06)}")
                .append(".tag{display:inline-block;font-size:12px;color:#b45309;background:#fef3c7;")
                .append("border-radius:999px;padding:2px 10px;margin-bottom:14px}")
                .append("h1{font-size:18px;font-weight:600;margin:0 0 12px}")
                .append("p{margin:0 0 12px;color:#4b5563}")
                .append("code{background:#f3f4f6;border-radius:4px;padding:1px 6px;color:#111827;")
                .append("font:13px ui-monospace,Consolas,monospace}")
                .append(".actions{display:flex;flex-wrap:wrap;gap:10px;margin:20px 0 10px}")
                .append("button{font:inherit;cursor:pointer;border-radius:8px;padding:8px 16px;")
                .append("border:1px solid #d1d5db;background:#fff;color:#374151}")
                .append("button.primary{border-color:#2563eb;background:#2563eb;color:#fff}")
                .append("button.danger{border-color:#fca5a5;color:#b91c1c}")
                .append(".hint{font-size:12px;color:#6b7280;min-height:18px;margin:0}")
                .append("</style></head><body><div class=\"card\">")
                .append("<span class=\"tag\">模板预览</span>")
                .append("<h1>「").append(escapeHtml(name)).append("」暂无对应的模板页面</h1>")
                .append("<p>预览数据 <code>_preview_data.json</code> 中该").append(escapeHtml(label))
                .append("指向 <code>").append(escapeHtml(expected)).append("</code>，但模板目录里没有这个文件。</p>")
                .append("<p>为避免把多个").append(escapeHtml(label))
                .append("悄悄指向同一个页面，此处不会自动回退到其他页面。</p>")
                .append("<div class=\"actions\">")
                .append("<button class=\"primary\" id=\"__ai_gen__\">让 AI 生成这个页面</button>")
                .append("<button class=\"danger\" id=\"__ai_del__\">删除该").append(escapeHtml(label)).append("</button>")
                .append("<button id=\"__ai_back__\">返回上一页</button></div>")
                .append("<p class=\"hint\" id=\"__ai_hint__\"></p></div>")
                .append("<div id=\"__ai_missing__\" hidden")
                .append(" data-ref=\"").append(escapeHtml(ref)).append("\"")
                .append(" data-name=\"").append(escapeHtml(name)).append("\"")
                .append(" data-expected=\"").append(escapeHtml(expected)).append("\"")
                .append(" data-type=\"").append(escapeHtml(type)).append("\"")
                .append(" data-label=\"").append(escapeHtml(label)).append("\"")
                .append(" data-deletable=\"").append(deletable).append("\"></div>")
                .append("<script>(function(){")
                .append("var box=document.getElementById('__ai_missing__'),hint=document.getElementById('__ai_hint__');")
                .append("function send(action){")
                .append("var msg={type:'").append(MSG_MISSING_PAGE).append("',action:action,")
                .append("ref:box.dataset.ref,name:box.dataset.name,expectedFile:box.dataset.expected,")
                .append("pageType:box.dataset.type,kindLabel:box.dataset.label};")
                .append("try{window.parent.postMessage(msg,window.location.origin);}catch(e){}")
                .append("hint.textContent=action==='remove'")
                .append("?'已请求删除该").append(escapeJs(label)).append("，处理后预览会自动刷新…'")
                .append(":'已发送到 AI 对话，请留意右侧面板的思考与生成过程…';}")
                .append("var gen=document.getElementById('__ai_gen__');")
                .append("if(gen){gen.addEventListener('click',function(){send('generate');});}")
                .append("var del=document.getElementById('__ai_del__');")
                .append("if(del){if(box.dataset.deletable==='true'){del.addEventListener('click',function(){send('remove');});}")
                .append("else{del.style.display='none';}}")
                .append("var back=document.getElementById('__ai_back__');")
                .append("if(back){back.addEventListener('click',function(){history.back();});}")
                .append("})();</script></body></html>");
        return sb.toString();
    }

    /**
     * 向渲染结果注入"菜单未配置"引导条（无 {@code _preview_data.json} 或 menus 为空时）
     *
     * <p>导航区为空不是渲染故障，而是信息架构尚未确定：{@code AiTemplatePreviewMockSupport}
     * 不再回退硬编码的默认栏目（那些栏目在模板里没有对应页面，只能全部挤到基础页上），
     * 因此需要明确告诉用户"为什么导航是空的、下一步做什么"，而不是留一个空白区块让人困惑。</p>
     */
    private String injectMenuNotice(String html) {
        int idx = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (idx < 0) {
            return html + menuNoticeHtml();
        }
        return html.substring(0, idx) + menuNoticeHtml() + html.substring(idx);
    }

    private String menuNoticeHtml() {
        return "<div id=\"__ai_menu_notice__\">"
                + "<span>当前模板还没有配置导航菜单，预览的导航区为空。菜单属于站点信息架构，"
                + "需要由你确认，预览不会代为编造。</span>"
                + "<button id=\"__ai_menu_plan__\">让 AI 规划导航菜单</button>"
                + "<button id=\"__ai_menu_close__\" title=\"暂时隐藏\">×</button></div>"
                + "<style>#__ai_menu_notice__{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;"
                + "display:flex;align-items:center;gap:12px;padding:10px 16px;color:#1f2937;background:#fff7e6;"
                + "border-top:1px solid #f0d9a8;font:13px/1.6 -apple-system,'Microsoft YaHei','PingFang SC',sans-serif}"
                + "#__ai_menu_notice__ span{flex:1}"
                + "#__ai_menu_notice__ button{font:inherit;cursor:pointer;border-radius:8px;padding:6px 14px;"
                + "border:1px solid #d1d5db;background:#fff;color:#374151}"
                + "#__ai_menu_plan__{border-color:#2563eb;background:#2563eb;color:#fff}"
                + "#__ai_menu_close__{padding:6px 10px}</style>"
                + "<script>(function(){var bar=document.getElementById('__ai_menu_notice__');"
                + "var plan=document.getElementById('__ai_menu_plan__');"
                + "if(plan){plan.addEventListener('click',function(){"
                + "try{window.parent.postMessage({type:'" + MSG_PLAN_MENU + "'},window.location.origin);}catch(e){}"
                + "var s=bar.querySelector('span');if(s){s.textContent='已发送到 AI 对话，请留意右侧面板…';}});}"
                + "var close=document.getElementById('__ai_menu_close__');"
                + "if(close){close.addEventListener('click',function(){bar.remove();});}"
                + "})();</script>";
    }

    /** 引导页/引导条向宿主预览面板回传消息的类型（前端 usePreviewIframeHooks 按此识别） */
    private static final String MSG_MISSING_PAGE = "ai:missing-page";
    private static final String MSG_PLAN_MENU = "ai:plan-menu";

    /** HTML 文本转义（引导页文案取材于预览数据，属用户/AI 可写内容） */
    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 内联脚本字符串字面量转义（仅用于把中文文案拼进 JS 单引号串） */
    private static String escapeJs(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "").replace("\n", "").replace("<", "\\u003c");
    }

}
