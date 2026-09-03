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
package com.fastcms.ai.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PageSpec 渲染引擎：spec → 自包含的 fastcms 模板目录
 *
 * <p>渲染是纯确定性过程（无 AI 参与）：组件 FTL 源码从 {@link ComponentRegistry} 取出，
 * 连同槽位数据（转为 {@code <#assign comp = {...}>}）内联进页面文件；
 * CMS 绑定指令（menuTag/articleListTag 等）原样保留，由真实站点/预览 mock 在渲染期执行。
 * 因此同一个 spec 在预览环境与生产环境产出一致结构。</p>
 *
 * <p>产物布局（对齐 fastcms 模板规范）：</p>
 * <pre>
 * {targetDir}/
 * ├── _pagespec.json          ← spec 落盘（微调/重渲染的事实源）
 * ├── _template.properties    ← 模板注册信息
 * ├── _preview_data.json      ← 预览演示数据（站点名）
 * ├── index.html              ← sections 顺序组装
 * ├── article_list.html       ← 外围 sections + 内置列表正文（footer 类 section 前）
 * ├── article.html            ← 外围 sections + 内置文章正文
 * ├── page.html               ← 外围 sections + 内置单页正文
 * └── static/css/
 *     ├── pack.css            ← 组件包地基（provider 资产复制）
 *     ├── tokens.css          ← TokenEngine 按主色+风格预设生成
 *     └── site.css            ← 内容页正文排版（h2/p/img 等富文本元素）
 * </pre>
 *
 * <p>组件升级 → 存量 _pagespec.json 重渲染即继承新组件，这是"弹药补充"闭环的落地点。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class PageSpecRenderer {

    private static final Logger log = LoggerFactory.getLogger(PageSpecRenderer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 组件包地基资产路径（provider 约定）
     */
    private static final String ASSET_PACK_CSS = "static/pack.css";

    /**
     * 内容页正文骨架的包资产路径前缀（provider 约定：pages/{pageKey}.ftl）
     */
    private static final String PAGE_SKELETON_PREFIX = "pages/";

    private final ComponentRegistry registry;

    private final TokenEngine tokenEngine;

    public PageSpecRenderer(ComponentRegistry registry, TokenEngine tokenEngine) {
        this.registry = registry;
        this.tokenEngine = tokenEngine;
    }

    /**
     * 渲染结果：写出的模板内相对路径清单（供渲染校验 / 前端刷新使用）
     */
    public record RenderResult(List<String> writtenFiles) {
    }

    /**
     * 渲染 PageSpec 到目标模板目录（已存在的同名文件被覆盖，多余文件不清理——
     * 微调场景由上层负责目录状态管理）
     *
     * @throws IllegalArgumentException 组件/变体缺失（应先经 {@link PageSpecValidator}）
     * @throws IOException               写盘失败
     */
    public RenderResult render(PageSpec spec, Path targetDir) throws IOException {
        if (spec == null) {
            throw new IllegalArgumentException("PageSpec 为空");
        }
        List<String> written = new ArrayList<>();

        writeStaticAssets(spec, targetDir, written);
        writePages(spec, targetDir, written);
        writePagespec(spec, targetDir, written);
        writeTemplateProperties(spec, targetDir, written);
        writePreviewData(spec, targetDir, written);

        log.info("PageSpec 渲染完成: {} 个文件 -> {}", written.size(), targetDir);
        return new RenderResult(List.copyOf(written));
    }

    // ==================== 页面组装 ====================

    /**
     * 组装 index / article_list / article / page 四类页面文件
     */
    private void writePages(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        writePage(spec, targetDir, PageSpec.PAGE_INDEX, written);
        writePage(spec, targetDir, PageSpec.PAGE_ARTICLE_LIST, written);
        writePage(spec, targetDir, PageSpec.PAGE_ARTICLE, written);
        writePage(spec, targetDir, PageSpec.PAGE_PAGE, written);
    }

    private void writePage(PageSpec spec, Path targetDir, String pageKey, List<String> written) throws IOException {
        List<SectionSpec> sections = spec.sectionsOf(pageKey);
        if (sections.isEmpty() && !PageSpec.PAGE_INDEX.equals(pageKey)) {
            // 内容页 spec 未配置任何 section（如 navbar/footer）也保留默认骨架，
            // 否则模板缺文件；index 空 sections 属于校验问题，此处同样兜底输出空页面
        }
        String html = buildPageHtml(spec, pageKey, sections);
        Path file = targetDir.resolve(pageKey + ".html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, html, StandardCharsets.UTF_8);
        written.add(pageKey + ".html");
    }

    /**
     * 单页面 HTML：head + sections 顺序组装（内容页在首个 footer 类 section 前插入内置正文）
     */
    private String buildPageHtml(PageSpec spec, String pageKey, List<SectionSpec> sections) {
        StringBuilder body = new StringBuilder();

        boolean contentInserted = false;
        for (SectionSpec section : sections) {
            if (!contentInserted && isContentPage(pageKey) && isFooterSection(section)) {
                body.append(contentSkeleton(pageKey));
                contentInserted = true;
            }
            body.append(renderSection(section));
        }
        // 内容页无 footer section 时正文追加在末尾
        if (isContentPage(pageKey) && !contentInserted) {
            body.append(contentSkeleton(pageKey));
        }

        String title = pageTitle(spec, pageKey);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\" class=\"bg-white text-slate-900 antialiased\">\n");
        html.append("<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<title>").append(title).append("</title>\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<meta name=\"keywords\" content=\"").append(spec.safeSiteName()).append("\">\n");
        html.append("<meta name=\"description\" content=\"${seoTag(\"website_sub_title\")!\"\"}\">\n");
        html.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/pack.css\">\n");
        html.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/tokens.css\">\n");
        html.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/site.css\">\n");
        html.append("</head>\n");
        html.append("<body class=\"font-sans\">\n");
        html.append(body);
        html.append("</body>\n");
        html.append("</html>\n");
        return html.toString();
    }

    /**
     * 页面 title 的 FTL 表达式（运行期取 CMS 数据，静态兜底用 spec 站点名）
     */
    private String pageTitle(PageSpec spec, String pageKey) {
        return switch (pageKey) {
            case PageSpec.PAGE_INDEX -> "${seoTag(\"website_title\")!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
            case PageSpec.PAGE_ARTICLE_LIST -> "${(category.title)!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
            case PageSpec.PAGE_ARTICLE -> "${(article.title)!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
            default -> "${(singlePage.title)!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
        };
    }

    /**
     * 单个 section：槽位数据转 FTL assign + 组件 FTL 源码内联
     *
     * <p>组件源码原样嵌入（保留 menuTag 等运行期指令），槽位数据以
     * {@code <#assign comp = {...}>} 注入——同一文件内后续 section 重复赋值，
     * 各组件只读取自己关心的字段，互不干扰。</p>
     */
    private String renderSection(SectionSpec section) {
        String source = resolveTemplateSource(section);
        StringBuilder sb = new StringBuilder();
        sb.append("<#assign comp = ").append(toFtlLiteral(section.safeData())).append(">\n");
        sb.append(source.strip()).append("\n");
        return sb.toString();
    }

    /**
     * 内容页正文骨架（组件包 pages/ 资产），缺失时抛异常（包不完整）
     */
    private String contentSkeleton(String pageKey) {
        for (ComponentRegistry.RegisteredComponent rc : registry.listComponents()) {
            byte[] asset = rc.provider().getPackAsset(PAGE_SKELETON_PREFIX + pageKey + ".ftl");
            if (asset != null) {
                return new String(asset, StandardCharsets.UTF_8).strip() + "\n";
            }
        }
        throw new IllegalStateException("组件包缺少内容页骨架: " + PAGE_SKELETON_PREFIX + pageKey + ".ftl");
    }

    // ==================== 静态资产 ====================

    private void writeStaticAssets(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        Path cssDir = targetDir.resolve("static/css");
        Files.createDirectories(cssDir);

        // pack.css：取第一个含地基资产的包（P1 只有内置包；多包混用地基已被校验器拒绝）
        byte[] packCss = null;
        for (ComponentRegistry.RegisteredComponent rc : registry.listComponents()) {
            packCss = rc.provider().getPackAsset(ASSET_PACK_CSS);
            if (packCss != null) {
                break;
            }
        }
        if (packCss == null) {
            throw new IllegalStateException("组件包缺少地基资产: " + ASSET_PACK_CSS);
        }
        write(cssDir.resolve("pack.css"), packCss, "static/css/pack.css", written);

        write(cssDir.resolve("tokens.css"),
                tokenEngine.generateTokens(spec.primaryColor(), spec.stylePreset())
                        .getBytes(StandardCharsets.UTF_8),
                "static/css/tokens.css", written);

        write(cssDir.resolve("site.css"), SITE_CSS.getBytes(StandardCharsets.UTF_8),
                "static/css/site.css", written);
    }

    private void write(Path file, byte[] content, String relPath, List<String> written) throws IOException {
        Files.write(file, content);
        written.add(relPath);
    }

    // ==================== 元数据文件 ====================

    private void writePagespec(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        // pretty 输出便于人工检查与 AI patch
        StringWriter sw = new StringWriter();
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(sw, spec);
        Files.writeString(targetDir.resolve("_pagespec.json"), sw.toString(), StandardCharsets.UTF_8);
        written.add("_pagespec.json");
    }

    private void writeTemplateProperties(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        // 手写 UTF-8（历史教训：Properties 默认 ISO-8859-1 读写中文乱码，此处写端保证 UTF-8）
        String name = spec.safeTemplateName();
        StringBuilder sb = new StringBuilder();
        sb.append("template.id=").append(name).append("\n");
        sb.append("template.name=").append(name).append("\n");
        sb.append("template.path=/").append(name).append("/\n");
        sb.append("template.version=0.0.1\n");
        sb.append("template.i18n=").append(name).append("\n");
        sb.append("template.provider=ai-component\n");
        sb.append("template.description=AI 组件化生成模板\n");
        Files.writeString(targetDir.resolve("_template.properties"), sb.toString(), StandardCharsets.UTF_8);
        written.add("_template.properties");
    }

    private void writePreviewData(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        // 预览演示数据：站点名写入 SEO，其余回退内置默认（菜单/文章等）
        String json = "{\"seo\":{\"website_title\":" + jsonString(spec.safeSiteName()) + "}}\n";
        Files.writeString(targetDir.resolve("_preview_data.json"), json, StandardCharsets.UTF_8);
        written.add("_preview_data.json");
    }

    // ==================== 工具 ====================

    private boolean isContentPage(String pageKey) {
        return PageSpec.PAGE_ARTICLE_LIST.equals(pageKey)
                || PageSpec.PAGE_ARTICLE.equals(pageKey)
                || PageSpec.PAGE_PAGE.equals(pageKey);
    }

    private boolean isFooterSection(SectionSpec section) {
        ComponentRegistry.RegisteredComponent rc = registry.find(section.component()).orElse(null);
        return rc != null && ComponentDescriptor.CATEGORY_FOOTER.equals(rc.descriptor().category());
    }

    /**
     * 取组件变体源码（缺省变体取第一个），缺失即抛异常（调用前应已通过校验器）
     */
    private String resolveTemplateSource(SectionSpec section) {
        ComponentRegistry.RegisteredComponent rc = registry.find(section.component())
                .orElseThrow(() -> new IllegalArgumentException(
                        "组件不存在: " + section.component() + "（渲染前应先通过 PageSpecValidator）"));
        String variantId = section.variant();
        if (variantId == null || variantId.isBlank()) {
            List<ComponentVariant> variants = rc.descriptor().variants();
            if (variants != null && !variants.isEmpty()) {
                variantId = variants.get(0).id();
            }
        }
        String source = registry.getTemplateSource(section.component(), variantId);
        if (source == null) {
            throw new IllegalArgumentException(
                    "组件变体不存在: " + section.component() + "/" + variantId);
        }
        return source;
    }

    /**
     * 槽位数据（Jackson 解析出的 POJO）→ FTL 字面量
     *
     * <p>AI 产出的 data 经 _pagespec.json 往返（Map/List/String/Number/Boolean），
     * 本方法将其转为 FreeMarker 表达式字面量供 {@code <#assign comp = ...>} 使用。</p>
     */
    static String toFtlLiteral(Object value) {
        if (value == null) {
            return "''";
        }
        if (value instanceof String s) {
            return "\"" + escapeFtl(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object item : list) {
                sb.append(toFtlLiteral(item)).append(", ");
            }
            if (!list.isEmpty()) {
                sb.setLength(sb.length() - 2);
            }
            return sb.append("]").toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append("\"").append(escapeFtl(String.valueOf(entry.getKey()))).append("\": ")
                        .append(toFtlLiteral(entry.getValue())).append(", ");
            }
            if (!map.isEmpty()) {
                sb.setLength(sb.length() - 2);
            }
            return sb.append("}").toString();
        }
        // 未知类型退化为字符串（防 AI 塞入奇怪结构导致渲染失败）
        return "\"" + escapeFtl(String.valueOf(value)) + "\"";
    }

    /**
     * FTL 字符串转义（反斜杠、引号、换行）
     */
    private static String escapeFtl(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    /**
     * HTML 属性转义（title 静态兜底值）
     */
    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jsonString(String s) {
        return MAPPER.writeValueAsString(s);
    }

    /**
     * 内容页正文排版样式（富文本 contentHtml 无 Tailwind 类可用，手写基础排版）
     */
    private static final String SITE_CSS = """
            /* 内容页正文排版（renderHtml 富文本元素，非组件输出） */
            .article-content { color: #334155; font-size: 1rem; line-height: 1.9; }
            .article-content h1, .article-content h2, .article-content h3,
            .article-content h4 { color: #0f172a; font-weight: 600; line-height: 1.4; margin: 2em 0 .8em; }
            .article-content h2 { font-size: 1.5rem; }
            .article-content h3 { font-size: 1.25rem; }
            .article-content p { margin: 1em 0; }
            .article-content a { color: var(--color-primary-600, #2563eb); text-decoration: underline; }
            .article-content a:hover { color: var(--color-primary-700, #1d4ed8); }
            .article-content img { border-radius: .75rem; margin: 1.5em 0; max-width: 100%; }
            .article-content ul, .article-content ol { margin: 1em 0; padding-left: 1.5em; }
            .article-content ul { list-style: disc; }
            .article-content ol { list-style: decimal; }
            .article-content li { margin: .4em 0; }
            .article-content blockquote {
              border-left: 4px solid var(--color-primary-300, #93c5fd);
              background: #f8fafc; border-radius: 0 .5rem .5rem 0;
              margin: 1.5em 0; padding: .8em 1.2em; color: #475569;
            }
            .article-content pre {
              background: #0f172a; border-radius: .75rem; color: #e2e8f0;
              margin: 1.5em 0; overflow-x: auto; padding: 1em 1.25em;
              font-family: var(--font-mono, monospace); font-size: .875rem; line-height: 1.7;
            }
            .article-content code {
              background: #f1f5f9; border-radius: .25rem;
              font-family: var(--font-mono, monospace); font-size: .875em; padding: .15em .4em;
            }
            .article-content pre code { background: none; padding: 0; }
            .article-content table { border-collapse: collapse; margin: 1.5em 0; width: 100%; }
            .article-content th, .article-content td {
              border: 1px solid #e2e8f0; padding: .5em .75em; text-align: left;
            }
            .article-content th { background: #f8fafc; font-weight: 600; }
            """;

}