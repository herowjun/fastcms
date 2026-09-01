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

        servePreview(workDir, PREVIEW_URL_PREFIX + sessionId + "/" + templateName, templateName, relPath, response);
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

        servePreview(workDir, TEMPLATE_PREVIEW_URL_PREFIX + templateId, templateId, relPath, response);
    }

    /**
     * 预览公共管线：定位文件 → html 走 FreeMarker mock 渲染 / 其余按静态文件返回
     *
     * @param workDir     模板根目录
     * @param urlPrefix   预览 URL 前缀（不含结尾斜杠），用于覆盖 ctx() 的静态资源基路径
     * @param displayName 错误页展示的模板标识
     * @param relPath     模板内相对路径
     */
    private void servePreview(Path workDir, String urlPrefix, String displayName,
                              String relPath, HttpServletResponse response) throws IOException {
        if (!StringUtils.hasText(relPath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "缺少预览文件路径");
            return;
        }

        // 防路径穿越：解析后必须仍在工作目录内
        Path target = workDir.resolve(relPath).normalize();
        if (!target.startsWith(workDir) || !Files.isReadable(target)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览文件不存在: " + relPath);
            return;
        }

        if (relPath.toLowerCase().endsWith(".html")) {
            renderTemplate(urlPrefix, displayName, workDir, relPath, response);
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
    private void renderTemplate(String urlPrefix, String displayName, Path workDir,
                                String relPath, HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            String html = previewRenderer.renderPage(urlPrefix, workDir, relPath);
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

}
