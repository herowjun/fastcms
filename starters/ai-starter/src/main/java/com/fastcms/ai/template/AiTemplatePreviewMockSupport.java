package com.fastcms.ai.template;

import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.ObjectWrapper;
import freemarker.template.TemplateDirectiveModel;
import freemarker.template.TemplateMethodModelEx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 模板预览 mock 数据支持
 *
 * <p>预览的核心问题：AI 生成的模板全部通过 fastcms 内置指令（menuTag、articleListTag 等）
 * 渲染动态内容，这些指令真实实现查数据库。新模板对应的站点在库里没有配套数据
 * （菜单未配置、文章为空、SEO 未填写），直接复用真实指令预览出来的是空白或错乱页面。
 *
 * <p>本类为每个内置指令提供 <b>与真实指令返回结构一致</b> 的演示数据实现：
 * <ul>
 *     <li>数据注入协议与 {@code BaseDirective} 相同：返回值包装后写入 env 的 {@code data} 变量，
 *         再渲染指令体，模板代码无需任何修改</li>
 *     <li>字段名严格对齐 {@link TemplateGenPromptBuilder} 中约定给 AI 的指令契约</li>
 *     <li>页面级上下文变量（article/category/articleVoPage/singlePage）按模板文件名注入</li>
 * </ul>
 *
 * <p>支持模板级配置：模板目录下的 {@code _preview_data.json} 可覆盖演示数据
 * （菜单/分类/标签/单页/文章标题/SEO，见 {@link #loadPreviewDataConfig}），
 * 文件缺失或解析失败时自动回退内置默认数据，存量模板行为不变。</p>
 *
 * <p>所有演示 URL 均指向预览路由下真实存在的模板文件（由控制器按文件名前缀解析，
 * 如 article.html / article_list.html / page*.html / index.html），页面内的菜单、文章、
 * 分类、标签、单页、分页链接可在预览内闭环跳转，实现整站模拟导航。
 * 配置了 suffix 的条目按 fastcms 真实路由约定解析
 * （page_{suffix}.html / article_{suffix}.html / article_list_{suffix}.html）。
 * 缩略图使用 SVG data URI（不产生外部请求）。
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
final class AiTemplatePreviewMockSupport {

    private static final Logger log = LoggerFactory.getLogger(AiTemplatePreviewMockSupport.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ObjectWrapper OBJECT_WRAPPER =
            new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_25).build();

    private static final String DEFAULT_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 配置数量上限（防 AI 输出失控撑爆预览页面，超长静默截断）
     */
    private static final int MAX_MENUS = 8;
    private static final int MAX_MENU_CHILDREN = 6;
    private static final int MAX_CATEGORIES = 10;
    private static final int MAX_TAGS = 10;
    private static final int MAX_SINGLE_PAGES = 8;
    private static final int MAX_ARTICLES = 12;

    /**
     * suffix 合法字符（对应模板文件名 page_{suffix}.html 等，防路径注入）
     */
    private static final String SUFFIX_PATTERN = "[a-zA-Z0-9_-]+";

    /**
     * 演示缩略图：内联 SVG data URI，避免外链图片加载失败影响预览
     */
    private static final String THUMBNAIL_SVG = "data:image/svg+xml;charset=utf-8,"
            + "%3Csvg xmlns='http://www.w3.org/2000/svg' width='640' height='360'%3E"
            + "%3Crect width='100%25' height='100%25' fill='%23e8eaf0'/%3E"
            + "%3Ctext x='50%25' y='50%25' font-size='26' fill='%23909aa8' text-anchor='middle' "
            + "dominant-baseline='middle' font-family='sans-serif'%3E演示图片 640x360%3C/text%3E"
            + "%3C/svg%3E";

    private static final String[] ARTICLE_TITLES = {
            "FastCMS 0.2.0 正式发布：AI 模板生成能力全面上线",
            "深入理解 PF4J 插件化架构的设计与实现",
            "Spring Boot 4.1 升级实践：从 3.x 到 4.x 的完整迁移指南",
            "用 OAuth2 授权服务器保护你的企业 API 安全",
            "内容静态化技术演进：从动态渲染到预生成 HTML",
            "FreeMarker 高级技巧：自定义指令开发实战",
            "站点 SEO 优化清单：让内容管理系统产出更友好的页面",
            "响应式设计实践：一套模板适配桌面与移动端",
            "微信生态集成：公众号、小程序与支付打通方案",
            "Lucene 全文检索在 CMS 中的应用与调优",
    };

    private static final String[] ARTICLE_SUMMARIES = {
            "本次版本带来 AI 模板生成器，通过自然语言描述即可生成完整的站点模板，支持在线预览与一键应用。",
            "插件系统是 FastCMS 的核心能力之一，本文拆解插件的加载、注册与扩展点设计。",
            "梳理升级过程中的依赖变更、废弃 API 替换与自动配置迁移的完整步骤与常见坑。",
            "从令牌签发到资源服务器校验，完整介绍 FastCMS 内置授权服务器的配置与使用。",
            "对比动态渲染与静态化的性能差异，分析静态化时机、失效策略与增量更新方案。",
            "通过自定义指令封装数据查询逻辑，让模板专注展示，实现数据与视图解耦。",
            "从语义化 URL、元信息输出到结构化数据，一份可落地的 CMS SEO 优化清单。",
            "以弹性布局为核心，讲解栅格、断点与图片自适应在模板中的最佳实践。",
            "公众号消息、小程序登录与支付回落在 CMS 业务中的统一集成方案。",
            "索引设计、分词策略与查询优化，让站内搜索既快又准。",
    };

    private AiTemplatePreviewMockSupport() {
    }

    /**
     * 指令数据供应商：根据指令参数返回演示数据
     */
    @FunctionalInterface
    interface MockDataSupplier {
        Object supply(Map params);
    }

    /**
     * 预览上下文：URL 前缀 + 四类页面默认导航 URL + 模板目录 html 文件名集合
     *
     * <p>htmlFiles 用于 suffix 解析（如 page_about.html），保证带 suffix 的链接
     * 只在对应模板文件真实存在时才指向它，否则回退该类型的默认 URL。</p>
     */
    record PreviewContext(String urlPrefix, Map<String, String> pageUrls, Set<String> htmlFiles) {

        String indexUrl() {
            return pageUrls.getOrDefault("index", urlPrefix);
        }

        String articleUrl() {
            return pageUrls.getOrDefault("article", indexUrl());
        }

        String articleListUrl() {
            return pageUrls.getOrDefault("article_list", indexUrl());
        }

        String pageUrl() {
            return pageUrls.getOrDefault("page", indexUrl());
        }
    }

    /**
     * 预览数据配置（_preview_data.json 的解析结果），全部字段可 null，null 表示该项回退默认数据
     */
    record PreviewDataConfig(List<MenuConfig> menus, List<ItemConfig> categories, List<ItemConfig> tags,
                             List<ItemConfig> singlePages, ArticleConfig articles, Map<String, String> seo) {
    }

    /**
     * 菜单配置项：type ∈ index/article_list/article/page（缺省 article_list），
     * suffix 对应模板文件 {type}_{suffix}.html（可空）
     */
    record MenuConfig(String name, String type, String suffix, List<MenuConfig> children) {
    }

    /**
     * 分类/标签/单页配置项：title + 可选 suffix
     */
    record ItemConfig(String title, String suffix) {
    }

    /**
     * 文章配置：titles/summaries/suffixes 平行数组（suffixes 可空，元素空串表示用默认文章 URL）
     */
    record ArticleConfig(List<String> titles, List<String> summaries, List<String> suffixes) {
    }

    /**
     * 加载模板目录下的预览数据配置 {@code _preview_data.json}
     *
     * <p>每次预览请求都会重新读取（控制器不做配置缓存），手工编辑文件后刷新预览立即生效。
     * 文件不存在、内容非法（非 JSON 对象/字段类型错误）一律返回 null 回退默认数据，
     * 预览渲染不能因演示数据文件失败而中断。</p>
     *
     * @param workDir 模板根目录
     * @return 解析后的配置，全部字段为空时也返回 null
     */
    static PreviewDataConfig loadPreviewDataConfig(Path workDir) {
        Path file = workDir.resolve(AiTemplateConstants.FILE_PREVIEW_DATA);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                log.warn("预览数据文件不是 JSON 对象，忽略并回退默认演示数据: {}", file);
                return null;
            }
            List<MenuConfig> menus = parseMenus(root.get("menus"), MAX_MENUS);
            List<ItemConfig> categories = parseItems(root.get("categories"), MAX_CATEGORIES);
            List<ItemConfig> tags = parseItems(root.get("tags"), MAX_TAGS);
            List<ItemConfig> singlePages = parseItems(root.get("singlePages"), MAX_SINGLE_PAGES);
            ArticleConfig articles = parseArticles(root.get("articles"));
            Map<String, String> seo = parseSeo(root.get("seo"));
            if (menus == null && categories == null && tags == null
                    && singlePages == null && articles == null && seo == null) {
                return null;
            }
            return new PreviewDataConfig(menus, categories, tags, singlePages, articles, seo);
        } catch (Exception e) {
            log.warn("预览数据文件解析失败，回退默认演示数据: {}", file, e);
            return null;
        }
    }

    // ==================== 配置解析 ====================

    /**
     * 解析菜单数组：仅两级（顶层 + children），超限截断，全部非法返回 null
     */
    private static List<MenuConfig> parseMenus(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<MenuConfig> list = new ArrayList<>();
        for (JsonNode elem : node) {
            if (list.size() >= max) {
                break;
            }
            MenuConfig menu = parseMenu(elem, false);
            if (menu != null) {
                list.add(menu);
            }
        }
        return list.isEmpty() ? null : list;
    }

    private static MenuConfig parseMenu(JsonNode elem, boolean child) {
        if (elem == null || !elem.isObject()) {
            return null;
        }
        String name = textOf(elem, "name", "menuName", "title");
        if (name == null) {
            return null;
        }
        String type = textOf(elem, "type");
        if (type == null) {
            type = "article_list";
        }
        List<MenuConfig> children = child ? null : parseMenus(elem.get("children"), MAX_MENU_CHILDREN);
        return new MenuConfig(name, type, suffixOf(elem.get("suffix")), children);
    }

    /**
     * 解析分类/标签/单页数组：元素为字符串（无 suffix）或 {title/name, suffix} 对象
     */
    private static List<ItemConfig> parseItems(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<ItemConfig> list = new ArrayList<>();
        for (JsonNode elem : node) {
            if (list.size() >= max) {
                break;
            }
            if (elem != null && elem.isTextual()) {
                String title = elem.asString().trim();
                if (!title.isEmpty()) {
                    list.add(new ItemConfig(title, null));
                }
            } else if (elem != null && elem.isObject()) {
                String title = textOf(elem, "title", "name");
                if (title != null) {
                    list.add(new ItemConfig(title, suffixOf(elem.get("suffix"))));
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    private static ArticleConfig parseArticles(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> titles = stringList(node.get("titles"), MAX_ARTICLES);
        if (titles == null) {
            return null;
        }
        return new ArticleConfig(titles, stringList(node.get("summaries"), MAX_ARTICLES),
                stringList(node.get("suffixes"), MAX_ARTICLES));
    }

    private static Map<String, String> parseSeo(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        Map<String, String> seo = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                String text = value.asString().trim();
                if (!text.isEmpty()) {
                    seo.put(entry.getKey(), text);
                }
            }
        });
        return seo.isEmpty() ? null : seo;
    }

    private static List<String> stringList(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode elem : node) {
            if (list.size() >= max) {
                break;
            }
            if (elem != null && elem.isTextual()) {
                String text = elem.asString().trim();
                if (!text.isEmpty()) {
                    list.add(text);
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * 读取第一个非空文本字段
     */
    private static String textOf(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual()) {
                String text = value.asString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * 校验并规范化 suffix：仅允许字母数字下划线中划线，非法值视为未配置
     */
    private static String suffixOf(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String suffix = node.asString().trim();
        return suffix.matches(SUFFIX_PATTERN) ? suffix : null;
    }

    /**
     * 按 type + suffix 解析演示 URL（对齐真实系统的 page_{suffix}.html 路由约定）
     *
     * <p>suffix 为空 → 该类型默认 URL；suffix 非空且对应模板文件存在 → 指向该文件；
     * 文件不存在（AI 没生成对应模板）→ 回退默认 URL，保证链接始终可跳转。</p>
     */
    private static String resolveUrl(PreviewContext ctx, String type, String suffix) {
        String fallback = switch (type == null ? "" : type) {
            case "article" -> ctx.articleUrl();
            case "page" -> ctx.pageUrl();
            case "index" -> ctx.indexUrl();
            default -> ctx.articleListUrl();
        };
        if (suffix == null || suffix.isBlank()) {
            return fallback;
        }
        String file = type + "_" + suffix.trim() + ".html";
        return ctx.htmlFiles().contains(file) ? ctx.urlPrefix() + "/" + file : fallback;
    }

    /**
     * 构建 mock 指令集（sharedVariables）
     *
     * @param staticBase ctx() 返回的静态资源根路径（指向预览控制器的静态资源分支）
     * @param ctx        预览上下文（URL 前缀、四类页面默认导航 URL、html 文件集合）
     * @param config     模板级预览数据配置（可为 null，null 时全部使用内置默认数据）
     */
    static Map<String, Object> buildSharedVariables(String staticBase, PreviewContext ctx, PreviewDataConfig config) {
        Map<String, Object> vars = new LinkedHashMap<>();

        vars.put("menuTag", dataDirective(p -> menus(ctx, config)));
        vars.put("articleListTag", dataDirective(p -> articles(countOf(p), ctx, config)));
        vars.put("articlePageTag", dataDirective(p -> pagination(ctx.articleListUrl())));
        vars.put("categoryList", dataDirective(p -> categories(ctx, config)));
        vars.put("tagList", dataDirective(p -> tags(ctx, config)));
        vars.put("singlePageList", dataDirective(p -> singlePages(ctx, config)));
        vars.put("prevArticleTag", dataDirective(p -> article(1, ctx, config == null ? null : config.articles())));
        vars.put("nextArticleTag", dataDirective(p -> article(2, ctx, config == null ? null : config.articles())));
        vars.put("relatedArticleList", dataDirective(p -> articles(3, ctx, config)));
        vars.put("formatTime", formatTimeDirective());

        vars.put("seoTag", (TemplateMethodModelEx) args -> seoValue(args, config));
        vars.put("i18n", (TemplateMethodModelEx) args -> "");
        vars.put("fieldValue", (TemplateMethodModelEx) args -> "");
        vars.put("ctx", (TemplateMethodModelEx) args -> staticBase);

        return vars;
    }

    /**
     * 未知指令的兜底实现：输出 HTML 注释占位，避免渲染 500
     * （AI 幻觉出的指令、插件扩展指令等在预览环境无数据可查）
     */
    static TemplateDirectiveModel unknownDirective(String name) {
        return (env, params, loopVars, body) ->
                env.getOut().write("<!-- 预览模式：指令 " + name + " 无演示数据，此处内容已跳过 -->");
    }

    /**
     * 按模板文件名注入页面级上下文变量（对应真实路由注入的变量）
     *
     * <p>按前缀而非精确文件名匹配：真实系统中单页/文章可指定任意命名的模板文件
     * （如 page_about.html、article_news_detail.html），与默认文件共享同一套页面变量。
     * 注意 article_list 前缀需先于 article 判断。</p>
     *
     * <ul>
     *     <li>article*.html → article（文章详情）</li>
     *     <li>article_list*.html → category + articleVoPage（分类与分页）</li>
     *     <li>page*.html → singlePage（单页详情）</li>
     * </ul>
     *
     * @param relPath 模板内相对路径
     * @param ctx     预览上下文（URL 前缀、四类页面默认导航 URL、html 文件集合）
     * @param config  模板级预览数据配置（可为 null，null 时全部使用内置默认数据）
     */
    static Map<String, Object> buildPageModel(String relPath, PreviewContext ctx, PreviewDataConfig config) {
        Map<String, Object> model = new LinkedHashMap<>();

        String name = relPath;
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }

        if (name.startsWith("article_list")) {
            model.put("category", categoryFor(suffixOfFileName(name), ctx, config));
            model.put("articleVoPage", articleVoPage(ctx, config));
        } else if (name.startsWith("article")) {
            model.put("article", articleDetail(ctx, config));
        } else if (name.startsWith("page")) {
            model.put("singlePage", singlePageDetail(suffixOfFileName(name), ctx, config));
        }
        return model;
    }

    /**
     * 从模板文件名提取 suffix：article_list_products.html → "products"；
     * 基础页（article_list.html）返回 null
     */
    private static String suffixOfFileName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        int idx;
        if (base.startsWith("article_list_")) {
            idx = "article_list_".length();
        } else if (base.startsWith("article_")) {
            idx = "article_".length();
        } else if (base.startsWith("page_")) {
            idx = "page_".length();
        } else {
            return null;
        }
        return base.substring(idx);
    }

    /**
     * article_list_{suffix}.html 页面的当前分类：优先匹配配置中 suffix 相同的分类，
     * 匹配不到（基础页或未配置）回退默认取第 3 项逻辑
     */
    private static Map<String, Object> categoryFor(String suffix, PreviewContext ctx, PreviewDataConfig config) {
        List<ItemConfig> items = config == null ? null : config.categories();
        if (suffix != null && items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (suffix.equals(items.get(i).suffix())) {
                    return category((long) (i + 1), items.get(i).title(),
                            resolveUrl(ctx, "article_list", items.get(i).suffix()));
                }
            }
        }
        return category(ctx, config);
    }
    // ==================== 指令协议 ====================

    /**
     * 标准 mock 指令：数据写入 env 的 data 变量后渲染指令体（与 BaseDirective 协议一致）
     */
    private static TemplateDirectiveModel dataDirective(MockDataSupplier supplier) {
        return (env, params, loopVars, body) -> {
            env.setVariable("data", OBJECT_WRAPPER.wrap(supplier.supply(params)));
            if (body != null) {
                body.render(env.getOut());
            }
        };
    }

    /**
     * formatTime 指令：把 value（LocalDateTime 的 ISO 字符串）按 format 格式化后直接输出
     */
    private static TemplateDirectiveModel formatTimeDirective() {
        return (env, params, loopVars, body) -> {
            Object value = params.get("value");
            String format = scalarOf(params.get("format"), DEFAULT_TIME_PATTERN);
            String iso = value == null ? null : value.toString();
            String out;
            try {
                out = iso == null || iso.isEmpty() ? "" : LocalDateTime.parse(iso)
                        .format(DateTimeFormatter.ofPattern(format));
            } catch (Exception e) {
                // 非 ISO 格式（AI 生成的字符串日期等）原样输出
                out = iso == null ? "" : iso;
            }
            env.getOut().write(out);
        };
    }

    /**
     * seoTag 函数：常见 SEO 配置项的演示值（配置优先，未配置项走内置默认）
     */
    private static String seoValue(List args, PreviewDataConfig config) {
        String key = args == null || args.isEmpty() || args.get(0) == null
                ? "" : args.get(0).toString().trim();
        if (config != null && config.seo() != null) {
            String value = config.seo().get(key);
            if (value != null) {
                return value;
            }
        }
        return switch (key) {
            case "website_title" -> "FastCMS 演示站点";
            case "website_sub_title" -> "基于 Spring Boot 4 的开源内容管理系统";
            case "website_seo" -> "FastCMS,内容管理系统,CMS,AI模板生成";
            case "public_website_domain" -> "https://demo.fastcms.cn";
            default -> "";
        };
    }

    // ==================== 演示数据 ====================

    /**
     * 菜单列表：配置了 menus 时按配置构建（URL 按 type+suffix 解析），否则回退默认菜单
     */
    private static List<Map<String, Object>> menus(PreviewContext ctx, PreviewDataConfig config) {
        List<MenuConfig> items = config == null ? null : config.menus();
        if (items != null && !items.isEmpty()) {
            List<Map<String, Object>> menus = new ArrayList<>();
            for (MenuConfig item : items) {
                menus.add(menu(item, ctx));
            }
            return menus;
        }
        return defaultMenus(ctx.articleListUrl(), ctx.pageUrl());
    }

    private static Map<String, Object> menu(MenuConfig item, PreviewContext ctx) {
        List<Map<String, Object>> children = new ArrayList<>();
        if (item.children() != null) {
            for (MenuConfig child : item.children()) {
                children.add(menu(child, ctx));
            }
        }
        return menu(item.name(), resolveUrl(ctx, item.type(), item.suffix()), children);
    }

    private static List<Map<String, Object>> defaultMenus(String articleListUrl, String pageUrl) {
        List<Map<String, Object>> menus = new ArrayList<>();
        menus.add(menu("新闻动态", articleListUrl, List.of(
                menu("公司新闻", articleListUrl, List.of()), menu("行业资讯", articleListUrl, List.of()))));
        menus.add(menu("产品中心", articleListUrl, List.of(
                menu("内容管理", articleListUrl, List.of()),
                menu("插件市场", articleListUrl, List.of()),
                menu("模板引擎", articleListUrl, List.of()))));
        menus.add(menu("解决方案", pageUrl, List.of()));
        menus.add(menu("关于我们", pageUrl, List.of()));
        menus.add(menu("联系我们", pageUrl, List.of()));
        return menus;
    }

    private static Map<String, Object> menu(String name, String url, List<Map<String, Object>> children) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("menuName", name);
        m.put("url", url);
        m.put("target", "_self");
        m.put("children", children);
        return m;
    }

    /**
     * 分类列表：配置优先（URL 按 article_list + suffix 解析），否则回退默认分类
     */
    private static List<Map<String, Object>> categories(PreviewContext ctx, PreviewDataConfig config) {
        List<ItemConfig> items = config == null ? null : config.categories();
        if (items != null && !items.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItemConfig item = items.get(i);
                list.add(category((long) (i + 1), item.title(),
                        resolveUrl(ctx, "article_list", item.suffix())));
            }
            return list;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(category(1L, "科技前沿", ctx.articleListUrl()));
        list.add(category(2L, "产品动态", ctx.articleListUrl()));
        list.add(category(3L, "开发实践", ctx.articleListUrl()));
        list.add(category(4L, "行业观察", ctx.articleListUrl()));
        return list;
    }

    /**
     * article_list 页面上下文的当前分类：默认取配置分类的第 3 项（无则首项），与默认数据行为对齐
     */
    private static Map<String, Object> category(PreviewContext ctx, PreviewDataConfig config) {
        List<ItemConfig> items = config == null ? null : config.categories();
        if (items != null && !items.isEmpty()) {
            int index = items.size() >= 3 ? 2 : 0;
            ItemConfig item = items.get(index);
            return category((long) (index + 1), item.title(),
                    resolveUrl(ctx, "article_list", item.suffix()));
        }
        return category(3L, "开发实践", ctx.articleListUrl());
    }

    private static Map<String, Object> category(Long id, String title, String url) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("title", title);
        c.put("url", url);
        return c;
    }

    /**
     * 标签列表：配置优先（URL 按 article_list + suffix 解析），否则回退默认标签
     */
    private static List<Map<String, Object>> tags(PreviewContext ctx, PreviewDataConfig config) {
        List<ItemConfig> items = config == null ? null : config.tags();
        if (items != null && !items.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItemConfig item = items.get(i);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", (long) (i + 1));
                t.put("name", item.title());
                t.put("url", resolveUrl(ctx, "article_list", item.suffix()));
                list.add(t);
            }
            return list;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        String[] names = {"Java", "Spring Boot", "AI", "前端", "开源"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", (long) (i + 1));
            t.put("name", names[i]);
            t.put("url", ctx.articleListUrl());
            list.add(t);
        }
        return list;
    }

    /**
     * 单页列表：配置优先（URL 按 page + suffix 解析），否则回退默认单页
     */
    private static List<Map<String, Object>> singlePages(PreviewContext ctx, PreviewDataConfig config) {
        List<ItemConfig> items = config == null ? null : config.singlePages();
        if (items != null && !items.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItemConfig item = items.get(i);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", (long) (i + 1));
                s.put("title", item.title());
                s.put("url", resolveUrl(ctx, "page", item.suffix()));
                list.add(s);
            }
            return list;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        String[] names = {"关于我们", "联系方式", "服务条款", "隐私政策"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", (long) (i + 1));
            s.put("title", names[i]);
            s.put("url", ctx.pageUrl());
            list.add(s);
        }
        return list;
    }

    /**
     * 文章列表 mock（articleListTag / relatedArticleList）
     *
     * <p>配置了 articles.titles 时使用配置标题（摘要缺失项循环使用配置摘要或内置默认），
     * URL 按每篇的 suffix（articles.suffixes 平行数组）解析。</p>
     *
     * @param count 指令传入的 count 参数，null 时取全部标题
     */
    private static List<Map<String, Object>> articles(Integer count, PreviewContext ctx, PreviewDataConfig config) {
        ArticleConfig cfg = config == null ? null : config.articles();
        int total = cfg != null && cfg.titles() != null ? cfg.titles().size() : ARTICLE_TITLES.length;
        int n = count == null || count <= 0 ? total : Math.min(count, total);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(article(i, ctx, cfg));
        }
        return list;
    }

    private static Map<String, Object> article(int index, PreviewContext ctx, ArticleConfig cfg) {
        String title = cfg != null && cfg.titles() != null
                ? cfg.titles().get(index % cfg.titles().size())
                : ARTICLE_TITLES[index % ARTICLE_TITLES.length];
        String summary;
        if (cfg != null && cfg.summaries() != null && !cfg.summaries().isEmpty()) {
            summary = cfg.summaries().get(index % cfg.summaries().size());
        } else {
            summary = ARTICLE_SUMMARIES[index % ARTICLE_SUMMARIES.length];
        }
        // URL：配置了该篇 suffix 且对应模板文件存在 → 指向 article_{suffix}.html，否则默认文章页
        String url = ctx.articleUrl();
        if (cfg != null && cfg.suffixes() != null && index < cfg.suffixes().size()) {
            url = resolveUrl(ctx, "article", cfg.suffixes().get(index));
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", (long) (index + 1));
        a.put("title", title);
        a.put("summary", summary);
        a.put("thumbnail", THUMBNAIL_SVG);
        a.put("url", url);
        a.put("created", LocalDateTime.now().minusDays(index).withNano(0));
        a.put("viewCount", 420 - index * 37);
        return a;
    }

    /**
     * 文章详情 mock（article.html 页面上下文 / prevArticleTag / nextArticleTag）
     */
    private static Map<String, Object> articleDetail(PreviewContext ctx, PreviewDataConfig config) {
        Map<String, Object> a = article(0, ctx, config == null ? null : config.articles());
        a.put("contentHtml", mockArticleBody(
                (String) a.get("title"), (String) a.get("summary"), config));
        a.put("seoKeywords", "FastCMS,演示文章,模板预览");
        a.put("seoDescription", "这是用于模板预览的演示文章内容。");
        return a;
    }

    /**
     * 主题感知的演示正文：结构固定（导语 → 小节 → 引述 → 收尾），
     * 素材取 AI 规划的文章标题与摘要（天然贴合站点主题，土鸡站即土鸡文案），
     * 配置缺失（旧模板/null config）时回退通用演示文案
     */
    private static String mockArticleBody(String title, String summary, PreviewDataConfig config) {
        ArticleConfig cfg = config == null ? null : config.articles();
        List<String> titles = cfg != null && cfg.titles() != null ? cfg.titles() : List.of();
        List<String> summaries = cfg != null && cfg.summaries() != null ? cfg.summaries() : List.of();
        if (titles.size() >= 2 && summaries.size() >= 2) {
            StringBuilder body = new StringBuilder();
            // 导语：本篇摘要
            body.append("<p>").append(escapeHtml(summary)).append("</p>\n");
            // 两个小节：标题取站点其他文章（主题一致），正文取对应摘要
            for (int i = 0; i < 2; i++) {
                int idx = (i + 1) % titles.size();
                body.append("<h2>").append(escapeHtml(titles.get(idx))).append("</h2>\n")
                        .append("<p>").append(escapeHtml(summaries.get(idx))).append("</p>\n");
            }
            // 引述 + 收尾
            body.append("<blockquote><p>")
                    .append(escapeHtml(summaries.get(0)))
                    .append("</p></blockquote>\n")
                    .append("<p>以上内容由演示数据提供，正式应用模板后将展示站点真实文章。</p>");
            return body.toString();
        }
        // 回退：无站点级文章配置时的通用演示文案
        return "<p>这是一篇用于模板预览的演示文章。正式应用模板后，此处将展示站点的真实文章内容，"
                + "支持富文本、图文混排等常见排版元素。</p>"
                + "<h2>为什么需要演示数据</h2>"
                + "<p>模板在正式使用前，需要一套稳定的演示数据来检验列表、详情、分页、菜单等区块的"
                + "渲染效果，避免因数据缺失导致的误判。</p>"
                + "<h2>预览说明</h2>"
                + "<ul><li>当前页面所有动态内容均为演示数据</li>"
                + "<li>菜单、文章、分类、标签、单页均可点击跳转预览</li>"
                + "<li>分页、上一篇下一篇为演示导航</li></ul>"
                + "<p>如需调整样式或布局，可回到 AI 对话中继续描述修改需求。</p>";
    }

    /**
     * HTML 文本转义（演示正文素材来自 AI 输出，防意外的标签注入破坏预览）
     */
    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 单页详情 mock（page.html 页面上下文）：标题/URL 取配置的首个单页，否则默认
     */
    private static Map<String, Object> singlePageDetail(String suffix, PreviewContext ctx, PreviewDataConfig config) {
        List<ItemConfig> items = config == null ? null : config.singlePages();
        ItemConfig first = null;
        if (suffix != null && items != null) {
            // page_{suffix}.html：优先取配置中 suffix 相同的单页
            first = items.stream().filter(i -> suffix.equals(i.suffix())).findFirst().orElse(null);
        }
        if (first == null && items != null && !items.isEmpty()) {
            first = items.get(0);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", 1L);
        String pageTitle = first != null ? first.title() : "关于我们";
        s.put("title", pageTitle);
        s.put("summary", "这是单页示例摘要，正式应用后展示真实内容。");
        s.put("contentHtml",
                "<p>本页围绕「" + escapeHtml(pageTitle) + "」组织内容，正式应用模板后将展示站点真实的单页内容。</p>"
                        + "<h2>内容概览</h2><p>演示文案：这里介绍本页主题相关的背景、理念与核心信息。</p>"
                        + "<h2>了解更多</h2><p>演示文案：如需了解更多，可通过导航浏览其他栏目或返回首页。</p>");
        s.put("seoKeywords", "关于我们,FastCMS");
        s.put("seoDescription", "这是用于模板预览的演示单页内容。");
        s.put("url", first != null ? resolveUrl(ctx, "page", first.suffix()) : ctx.pageUrl());
        return s;
    }

    /**
     * 分页对象 mock（articleVoPage 页面上下文，字段与 MyBatis-Plus Page 对齐）
     */
    private static Map<String, Object> articleVoPage(PreviewContext ctx, PreviewDataConfig config) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", articles(10, ctx, config));
        page.put("size", 10L);
        page.put("current", 2L);
        page.put("total", 95L);
        page.put("pages", 10L);
        return page;
    }

    /**
     * 分页条 mock（articlePageTag 指令，结构对齐 BasePaginationDirective 返回值）
     */
    private static Map<String, Object> pagination(String articleListUrl) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total", 95L);
        p.put("current", 2L);
        p.put("totalPage", 10L);
        p.put("first", pageItem("首页", articleListUrl));
        p.put("prev", pageItem("上一页", articleListUrl));
        p.put("next", pageItem("下一页", articleListUrl));
        p.put("last", pageItem("尾页", articleListUrl));

        List<Map<String, String>> list = new ArrayList<>();
        list.add(pageItem("1", articleListUrl));
        list.add(pageItem("2", articleListUrl));
        list.add(pageItem("3", articleListUrl));
        list.add(pageItem("4", articleListUrl));
        list.add(pageItem("5", articleListUrl));
        list.add(pageItem("...", articleListUrl));
        list.add(pageItem("10", articleListUrl));
        p.put("list", list);
        return p;
    }

    private static Map<String, String> pageItem(String text, String url) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("text", text);
        item.put("url", url);
        return item;
    }

    // ==================== 参数工具 ====================

    /**
     * 读取指令的 count 参数（模板里形如 count=10），null 表示未传
     */
    private static Integer countOf(Map params) {
        Object value = params.get("count");
        if (value instanceof freemarker.template.SimpleNumber number) {
            return number.getAsNumber() == null ? null : number.getAsNumber().intValue();
        }
        return null;
    }

    private static String scalarOf(Object model, String defaultValue) {
        if (model instanceof freemarker.template.SimpleScalar scalar) {
            String value = scalar.getAsString();
            return value == null || value.isEmpty() ? defaultValue : value;
        }
        return defaultValue;
    }

}
