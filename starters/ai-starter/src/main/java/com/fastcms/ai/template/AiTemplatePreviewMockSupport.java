package com.fastcms.ai.template;

import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.ObjectWrapper;
import freemarker.template.TemplateDirectiveModel;
import freemarker.template.TemplateMethodModelEx;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>所有演示 URL 均指向预览路由下真实存在的模板文件（由控制器按文件名前缀解析，
 * 如 article.html / article_list.html / page*.html / index.html），页面内的菜单、文章、
 * 分类、标签、单页、分页链接可在预览内闭环跳转，实现整站模拟导航。
 * 缩略图使用 SVG data URI（不产生外部请求）。
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
final class AiTemplatePreviewMockSupport {

    private static final ObjectWrapper OBJECT_WRAPPER =
            new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_25).build();

    private static final String DEFAULT_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

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
     * 构建 mock 指令集（sharedVariables）
     *
     * @param staticBase ctx() 返回的静态资源根路径（指向预览控制器的静态资源分支）
     * @param pageUrls   整站导航 URL（key: index/article_list/article/page，值为预览路由下的绝对路径），
     *                   由控制器按模板目录实际文件解析，保证链接指向的页面一定存在
     */
    static Map<String, Object> buildSharedVariables(String staticBase, Map<String, String> pageUrls) {
        Map<String, Object> vars = new LinkedHashMap<>();

        String articleUrl = pageUrls.getOrDefault("article", pageUrls.get("index"));
        String articleListUrl = pageUrls.getOrDefault("article_list", pageUrls.get("index"));
        String pageUrl = pageUrls.getOrDefault("page", pageUrls.get("index"));

        vars.put("menuTag", dataDirective(p -> menus(articleListUrl, pageUrl)));
        vars.put("articleListTag", dataDirective(p -> articles(countOf(p), articleUrl)));
        vars.put("articlePageTag", dataDirective(p -> pagination(articleListUrl)));
        vars.put("categoryList", dataDirective(p -> categories(articleListUrl)));
        vars.put("tagList", dataDirective(p -> tags(articleListUrl)));
        vars.put("singlePageList", dataDirective(p -> singlePages(pageUrl)));
        vars.put("prevArticleTag", dataDirective(p -> article(1, articleUrl)));
        vars.put("nextArticleTag", dataDirective(p -> article(2, articleUrl)));
        vars.put("relatedArticleList", dataDirective(p -> articles(3, articleUrl)));
        vars.put("formatTime", formatTimeDirective());

        vars.put("seoTag", (TemplateMethodModelEx) AiTemplatePreviewMockSupport::seoValue);
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
     * @param pageUrls 整站导航 URL（页面内记录的 url 指向预览路由，保证可跳转）
     */
    static Map<String, Object> buildPageModel(String relPath, Map<String, String> pageUrls) {
        Map<String, Object> model = new LinkedHashMap<>();

        String name = relPath;
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }

        String articleUrl = pageUrls.getOrDefault("article", pageUrls.get("index"));
        String articleListUrl = pageUrls.getOrDefault("article_list", pageUrls.get("index"));
        String pageUrl = pageUrls.getOrDefault("page", pageUrls.get("index"));

        if (name.startsWith("article_list")) {
            model.put("category", category(articleListUrl));
            model.put("articleVoPage", articleVoPage(articleUrl));
        } else if (name.startsWith("article")) {
            model.put("article", articleDetail(articleListUrl));
        } else if (name.startsWith("page")) {
            model.put("singlePage", singlePageDetail(pageUrl));
        }
        return model;
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
     * seoTag 函数：常见 SEO 配置项的演示值
     */
    private static String seoValue(List args) {
        String key = args == null || args.isEmpty() || args.get(0) == null
                ? "" : args.get(0).toString().trim();
        return switch (key) {
            case "website_title" -> "FastCMS 演示站点";
            case "website_sub_title" -> "基于 Spring Boot 4 的开源内容管理系统";
            case "website_seo" -> "FastCMS,内容管理系统,CMS,AI模板生成";
            case "public_website_domain" -> "https://demo.fastcms.cn";
            default -> "";
        };
    }

    // ==================== 演示数据 ====================

    private static List<Map<String, Object>> menus(String articleListUrl, String pageUrl) {
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

    private static List<Map<String, Object>> categories(String articleListUrl) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(category(1L, "科技前沿", articleListUrl));
        list.add(category(2L, "产品动态", articleListUrl));
        list.add(category(3L, "开发实践", articleListUrl));
        list.add(category(4L, "行业观察", articleListUrl));
        return list;
    }

    private static Map<String, Object> category(String articleListUrl) {
        return category(3L, "开发实践", articleListUrl);
    }

    private static Map<String, Object> category(Long id, String title, String url) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("title", title);
        c.put("url", url);
        return c;
    }

    private static List<Map<String, Object>> tags(String articleListUrl) {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] names = {"Java", "Spring Boot", "AI", "前端", "开源"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", (long) (i + 1));
            t.put("name", names[i]);
            t.put("url", articleListUrl);
            list.add(t);
        }
        return list;
    }

    private static List<Map<String, Object>> singlePages(String pageUrl) {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] names = {"关于我们", "联系方式", "服务条款", "隐私政策"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", (long) (i + 1));
            s.put("title", names[i]);
            s.put("url", pageUrl);
            list.add(s);
        }
        return list;
    }

    /**
     * 文章列表 mock（articleListTag / relatedArticleList）
     *
     * @param count 指令传入的 count 参数，null 时默认 10
     * @param url   文章详情页 URL（预览路由）
     */
    private static List<Map<String, Object>> articles(Integer count, String url) {
        int n = count == null || count <= 0 ? ARTICLE_TITLES.length : Math.min(count, ARTICLE_TITLES.length);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(article(i, url));
        }
        return list;
    }

    private static Map<String, Object> article(int index, String url) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", (long) (index + 1));
        a.put("title", ARTICLE_TITLES[index % ARTICLE_TITLES.length]);
        a.put("summary", ARTICLE_SUMMARIES[index % ARTICLE_SUMMARIES.length]);
        a.put("thumbnail", THUMBNAIL_SVG);
        a.put("url", url);
        a.put("created", LocalDateTime.now().minusDays(index).withNano(0));
        a.put("viewCount", 420 - index * 37);
        return a;
    }

    /**
     * 文章详情 mock（article.html 页面上下文 / prevArticleTag / nextArticleTag）
     */
    private static Map<String, Object> articleDetail(String articleListUrl) {
        Map<String, Object> a = article(0, articleListUrl);
        a.put("contentHtml",
                "<p>这是一篇用于模板预览的演示文章。正式应用模板后，此处将展示站点的真实文章内容，"
                        + "支持富文本、图文混排等常见排版元素。</p>"
                        + "<h2>为什么需要演示数据</h2>"
                        + "<p>模板在正式使用前，需要一套稳定的演示数据来检验列表、详情、分页、菜单等区块的"
                        + "渲染效果，避免因数据缺失导致的误判。</p>"
                        + "<h2>预览说明</h2>"
                        + "<ul><li>当前页面所有动态内容均为演示数据</li>"
                        + "<li>菜单、文章、分类、标签、单页均可点击跳转预览</li>"
                        + "<li>分页、上一篇下一篇为演示导航</li></ul>"
                        + "<p>如需调整样式或布局，可回到 AI 对话中继续描述修改需求。</p>");
        a.put("seoKeywords", "FastCMS,演示文章,模板预览");
        a.put("seoDescription", "这是用于模板预览的演示文章内容。");
        return a;
    }

    /**
     * 单页详情 mock（page.html 页面上下文）
     */
    private static Map<String, Object> singlePageDetail(String pageUrl) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", 1L);
        s.put("title", "关于我们");
        s.put("summary", "这是单页示例摘要，正式应用后展示真实内容。");
        s.put("contentHtml",
                "<p>这是用于模板预览的演示单页。正式应用模板后，此处将展示站点真实的单页内容。</p>"
                        + "<h2>团队介绍</h2><p>演示文案：我们致力于打造开源、易用、可扩展的内容管理系统。</p>"
                        + "<h2>联系方式</h2><p>演示文案：contact@example.com</p>");
        s.put("seoKeywords", "关于我们,FastCMS");
        s.put("seoDescription", "这是用于模板预览的演示单页内容。");
        s.put("url", pageUrl);
        return s;
    }

    /**
     * 分页对象 mock（articleVoPage 页面上下文，字段与 MyBatis-Plus Page 对齐）
     */
    private static Map<String, Object> articleVoPage(String articleUrl) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", articles(10, articleUrl));
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
