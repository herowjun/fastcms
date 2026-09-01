package com.fastcms.ai.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import freemarker.template.Configuration;

/**
 * AI 模板预览渲染引擎：与 {@link AiTemplatePreviewController} 共用的 FreeMarker 渲染管线
 *
 * <p>从控制器抽取的渲染核心（mock 指令集 + 演示数据 + 未知指令兜底 + 根链接重写），
 * 同时为 {@code AiTemplateGenServiceImpl} 提供渲染校验能力
 * （{@link #checkRenderedFiles}）——调整型会话 AI 写盘后立即校验，
 * 把 FreeMarker 错误反馈给模型自动修复，形成"改完即可见"的闭环。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class AiTemplatePreviewRenderer {

    private static final Logger log = LoggerFactory.getLogger(AiTemplatePreviewRenderer.class);

    /**
     * 扫描模板中的自定义指令调用（<@xxx ...>），用于未知指令兜底注册。
     * 仅匹配指令名（字母开头、字母数字下划线），宏调用（<@layout.header>）会匹配到
     * namespace 名（layout），但 namespace 调用不走 sharedVariable，注册了也不影响。
     */
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile("<@([a-zA-Z][a-zA-Z0-9_]*)");

    /**
     * 渲染校验用的 URL 前缀（链接不用于跳转，仅为满足变量注入）
     */
    private static final String CHECK_URL_PREFIX = "/render-check";

    /**
     * 单轮渲染校验的文件数上限（防止极端情况下一轮改动过多导致校验耗时过长）
     */
    private static final int CHECK_MAX_FILES = 12;

    /**
     * 渲染指定模板文件并返回完整 HTML（含根链接重写）
     *
     * @param urlPrefix 预览 URL 前缀（不含结尾斜杠），用于 ctx() 静态资源基路径与页面链接
     * @param workDir   模板根目录
     * @param relPath   模板内相对路径
     * @throws Exception 渲染失败（FreeMarker 解析/执行异常等）
     */
    public String renderPage(String urlPrefix, Path workDir, String relPath) throws Exception {
        List<String> htmlFiles = scanHtmlFiles(workDir);
        Map<String, String> pageUrls = resolvePageUrls(urlPrefix, htmlFiles);
        AiTemplatePreviewMockSupport.PreviewContext ctx = new AiTemplatePreviewMockSupport.PreviewContext(
                urlPrefix, pageUrls, Set.copyOf(htmlFiles));
        AiTemplatePreviewMockSupport.PreviewDataConfig config =
                AiTemplatePreviewMockSupport.loadPreviewDataConfig(workDir);
        Configuration cfg = getConfiguration(urlPrefix + "/static", workDir, ctx, config);
        freemarker.template.Template template = cfg.getTemplate(relPath);
        StringWriter sw = new StringWriter();
        template.process(AiTemplatePreviewMockSupport.buildPageModel(relPath, ctx, config), sw);
        return rewriteRootLinks(sw.toString(), ctx);
    }

    /**
     * 渲染校验：对给定模板文件逐个走完整渲染管线，返回失败文件的错误清单
     *
     * <p>调整型会话 AI 写盘后调用，把错误反馈给模型自动修复。
     * 校验与预览共用同一渲染逻辑，预览能过的页面校验必过（反之亦然）。</p>
     *
     * @param workDir  模板根目录
     * @param relPaths 待校验的模板内相对路径（.html）
     * @return 错误清单（文件路径 + 摘要信息），全部通过时为空列表
     */
    public List<String> checkRenderedFiles(Path workDir, List<String> relPaths) {
        List<String> errors = new ArrayList<>();
        int checked = 0;
        for (String relPath : relPaths) {
            if (checked >= CHECK_MAX_FILES) {
                break;
            }
            if (relPath == null || !relPath.toLowerCase().endsWith(".html")) {
                continue;
            }
            checked++;
            try {
                renderPage(CHECK_URL_PREFIX, workDir, relPath);
            } catch (Exception e) {
                errors.add(relPath + ": " + summarizeError(e));
                log.warn("渲染校验失败: {}/{}", workDir, relPath, e);
            }
        }
        return errors;
    }

    /**
     * 摘取异常的关键信息（优先取最内层 FreeMarker 异常消息，含文件/行号）
     */
    private String summarizeError(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause().getMessage() == null) {
            t = t.getCause();
        }
        String msg = (t.getMessage() == null || t.getMessage().isBlank())
                ? t.getClass().getSimpleName() : t.getMessage();
        msg = msg.replace('\n', ' ').trim();
        return msg.length() > 400 ? msg.substring(0, 400) + "…" : msg;
    }

    /**
     * 将渲染结果中指向站点根的链接（href="/"）重写为预览路径下的 index.html
     *
     * <p>fastcms 模板约定首页链接写死为站点根（生产环境首页即 /，真实模板与 AI 生成模板均如此），
     * 预览时该链接会跳出预览路由指向站点根。此处将其重写为预览 index.html，
     * 使"首页/Logo"等根链接在预览内闭环跳转。仅当模板目录存在 index.html 时重写，
     * 兼容双引号与单引号写法；href="/xxx" 等非根路径不受影响。</p>
     */
    private String rewriteRootLinks(String html, AiTemplatePreviewMockSupport.PreviewContext ctx) {
        String indexUrl = ctx.pageUrls().get("index");
        if (indexUrl == null || indexUrl.equals(ctx.urlPrefix())) {
            return html;
        }
        return html
                .replace("href=\"/\"", "href=\"" + indexUrl + "\"")
                .replace("href='/'", "href='" + indexUrl + "'");
    }

    /**
     * 扫描模板目录根下的 html 文件（排除 _ 前缀的布局/分页宏文件），按文件名排序
     */
    private List<String> scanHtmlFiles(Path workDir) {
        try (Stream<Path> stream = Files.list(workDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".html"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("扫描模板目录失败，导航 URL 回退首页: {}", workDir, e);
            return List.of();
        }
    }

    /**
     * 解析整站导航 URL：为 index/文章列表/文章详情/单页四类页面定位模板目录中真实存在的文件，
     * mock 数据中的链接均指向这些 URL，实现预览内闭环跳转。
     *
     * <p>解析规则：优先精确文件名（index.html/article_list.html/article.html/page.html），
     * 其次按前缀取排序后的第一个（article 排除 article_list* 前缀），都找不到时回退首页，
     * 保证链接指向的页面一定可渲染。</p>
     */
    private Map<String, String> resolvePageUrls(String urlPrefix, List<String> htmlFiles) {
        String indexFile = pickPageFile(htmlFiles, "index", null);
        String articleListFile = pickPageFile(htmlFiles, "article_list", null);
        // article 前缀包含 article_list*，需显式排除
        String articleFile = pickPageFile(htmlFiles, "article", "article_list");
        String pageFile = pickPageFile(htmlFiles, "page", null);

        Map<String, String> urls = new LinkedHashMap<>();
        String indexUrl = indexFile != null ? urlPrefix + "/" + indexFile : urlPrefix;
        urls.put("index", indexUrl);
        urls.put("article_list", articleListFile != null ? urlPrefix + "/" + articleListFile : indexUrl);
        urls.put("article", articleFile != null ? urlPrefix + "/" + articleFile : indexUrl);
        urls.put("page", pageFile != null ? urlPrefix + "/" + pageFile : indexUrl);
        return urls;
    }

    /**
     * 在模板目录的 html 文件中定位页面文件：精确名优先，其次前缀匹配（可排除另一个前缀）
     *
     * @param name          页面类型名（如 article、article_list、page、index）
     * @param excludePrefix 需要排除的文件名前缀（如定位 article 时排除 article_list），null 表示不排除
     * @return 文件名，找不到返回 null（由调用方回退首页）
     */
    private String pickPageFile(List<String> htmlFiles, String name, String excludePrefix) {
        if (htmlFiles.contains(name + ".html")) {
            return name + ".html";
        }
        return htmlFiles.stream()
                .filter(f -> f.startsWith(name))
                .filter(f -> excludePrefix == null || !f.startsWith(excludePrefix))
                .findFirst()
                .orElse(null);
    }

    /**
     * 构建指定会话模板的 FreeMarker 配置
     *
     * <p>注册的指令集 = mock 指令（内置指令的演示数据实现）+ 未知指令兜底（输出注释），
     * 不注册任何真实指令 bean，预览渲染完全不查数据库。
     * 演示数据优先取模板目录的 {@code _preview_data.json}（ctx/config），缺失回退内置默认。</p>
     *
     * <p><b>不做配置缓存</b>：预览目录的文件随时会被 AI 重新生成（新增/修改/删除），
     * 缓存失效判断（目录 mtime、文件 mtime、未知指令扫描结果）任何一项过期都会导致
     * 预览读到旧内容或漏注册兜底指令。预览是低频操作，每次请求重建配置（毫秒级），
     * 同时设置 templateUpdateDelay=0 让模板文件修改立即生效。</p>
     */
    private Configuration getConfiguration(String staticBase, Path workDir,
                                            AiTemplatePreviewMockSupport.PreviewContext ctx,
                                            AiTemplatePreviewMockSupport.PreviewDataConfig config)
            throws IOException, freemarker.template.TemplateModelException {

        Configuration cfg = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
        cfg.setDirectoryForTemplateLoading(workDir.toFile());
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        cfg.setNumberFormat("0");
        // 模板文件修改后立即生效（AI 微调模板后无需等待缓存过期）
        cfg.setTemplateUpdateDelay(0);

        // mock 指令集：内置指令的演示数据实现 + ctx() 指向预览静态资源分支
        Map<String, Object> shared = AiTemplatePreviewMockSupport.buildSharedVariables(staticBase, ctx, config);

        // 兜底：扫描模板中出现的其余指令名，注册占位实现，避免未知指令导致渲染 500
        for (String name : scanDirectiveNames(workDir)) {
            shared.computeIfAbsent(name, AiTemplatePreviewMockSupport::unknownDirective);
        }

        for (Map.Entry<String, Object> entry : shared.entrySet()) {
            cfg.setSharedVariable(entry.getKey(), entry.getValue());
        }

        return cfg;
    }

    /**
     * 扫描工作目录下所有模板文件中出现的自定义指令名
     *
     * <p>不止扫描当前渲染的文件：<#import>/<#include> 引入的 _layout.html、_articlePage.html
     * 中的指令同样会在渲染时执行。</p>
     */
    private Set<String> scanDirectiveNames(Path workDir) {
        Set<String> names = new HashSet<>();
        try (Stream<Path> stream = Files.walk(workDir)) {
            stream.filter(p -> p.toString().toLowerCase().endsWith(".html") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            Matcher matcher = DIRECTIVE_PATTERN.matcher(content);
                            while (matcher.find()) {
                                names.add(matcher.group(1));
                            }
                        } catch (IOException e) {
                            log.warn("扫描模板指令失败，跳过文件: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描预览目录失败: {}", workDir, e);
        }
        return names;
    }
}
