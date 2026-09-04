package com.fastcms.ai.component;

import com.fastcms.ai.template.AiTemplatePreviewRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PageSpec → 渲染 → FreeMarker 校验的闭环测试
 *
 * <p>手写一份典型 spec（科技企业官网），走完整管线：
 * 校验器通过 → 渲染器产出模板目录 → 预览渲染引擎逐页渲染无错误。
 * 该测试保证：组件 FTL / assign 注入 / CMS 指令保留 / 骨架组装全链路语法正确。</p>
 */
class PageSpecRendererTest {

    private final ComponentRegistry registry =
            new ComponentRegistry(List.of(new BuiltinTailwindPackProvider()));
    private final PageSpecValidator validator = new PageSpecValidator(registry);
    private final PageSpecRenderer renderer = new PageSpecRenderer(registry, new TokenEngine());
    private final AiTemplatePreviewRenderer previewRenderer = new AiTemplatePreviewRenderer();

    @TempDir
    Path tempDir;

    /**
     * 手写典型 spec：navbar + hero + feature-grid + article-list + footer
     */
    private PageSpec sampleSpec() {
        Map<String, Object> heroData = new LinkedHashMap<>();
        heroData.put("title", "让内容管理更简单");
        heroData.put("subtitle", "基于 Spring Boot 4 的开源内容管理系统，插件化架构驱动");
        heroData.put("ctaLabel", "了解更多");
        heroData.put("ctaHref", "/article/category/1");
        heroData.put("ctaSecondaryLabel", "查看文档");
        heroData.put("ctaSecondaryHref", "/page/docs");

        Map<String, Object> featureData = new LinkedHashMap<>();
        featureData.put("title", "核心优势");
        featureData.put("subtitle", "为开发者与企业内容团队而设计");
        featureData.put("items", List.of(
                Map.of("icon", "🔌", "title", "插件化", "desc", "PF4J 插件架构，功能即插即用，商业扩展零侵入"),
                Map.of("icon", "⚡", "title", "高性能", "desc", "静态化页面生成，支撑高并发内容站点"),
                Map.of("icon", "🤖", "title", "AI 加持", "desc", "内置 AI 模板生成，自然语言描述即可建站")));

        Map<String, Object> articleData = new LinkedHashMap<>();
        articleData.put("title", "最新动态");
        articleData.put("count", 6);

        Map<String, Object> navbarData = Map.of("brand", "FastCMS");
        Map<String, Object> footerData = Map.of("brand", "FastCMS");

        return new PageSpec(
                PageSpec.SPEC_VERSION,
                BuiltinTailwindPackProvider.FOUNDATION,
                "ai-component-demo",
                "FastCMS 演示站点",
                "corporate-site",
                "corporate",
                "#2563eb",
                null,
                Map.of(
                        PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("hero", "tw:hero", "centered", heroData),
                                new SectionSpec("features", "tw:feature-grid", "three-col", featureData),
                                new SectionSpec("articles", "tw:article-list", "cards", articleData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData))),
                        PageSpec.PAGE_ARTICLE_LIST, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData))),
                        PageSpec.PAGE_ARTICLE, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData))),
                        PageSpec.PAGE_PAGE, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData)))));
    }

    @Test
    void shouldValidateSampleSpec() {
        List<String> errors = validator.validate(sampleSpec());
        assertEquals(List.of(), errors, "校验应通过: " + errors);
    }

    @Test
    void shouldRejectInvalidSpec() {
        PageSpec spec = new PageSpec(
                PageSpec.SPEC_VERSION, BuiltinTailwindPackProvider.FOUNDATION,
                "bad", "bad", "demo", "minimal", "red", null, // 非法主色（site 为 null）
                Map.of(PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                        new SectionSpec("s1", "tw:not-exist", null, Map.of()),           // 组件不存在
                        new SectionSpec("s2", "tw:hero", "bad-variant", Map.of()),        // 变体不存在
                        new SectionSpec("s3", "tw:hero", "centered", Map.of())))));       // 缺必填 title
        List<String> errors = validator.validate(spec);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("tw:not-exist")), "应提示组件不存在: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("bad-variant")), "应提示变体不存在: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("title")), "应提示必填槽位缺失: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("primaryColor")), "应提示主色非法: " + errors);
    }

    @Test
    void shouldRenderAndPassFreeMarkerCheck() throws Exception {
        Path dir = tempDir.resolve("demo-template");
        PageSpecRenderer.RenderResult result = renderer.render(sampleSpec(), dir);

        // 产物齐全：页面 + 公共布局 + 共享组件源码 + 静态资产 + 元数据
        for (String file : List.of("index.html", "article_list.html", "article.html", "page.html",
                "_layout.html",
                "_components/tw__navbar__sticky.ftl",
                "_components/tw__hero__centered.ftl",
                "_components/tw__feature-grid__three-col.ftl",
                "_components/tw__article-list__cards.ftl",
                "_components/tw__footer__simple.ftl",
                "static/css/pack.css", "static/css/tokens.css", "static/css/site.css",
                "_pagespec.json", "_template.properties", "_preview_data.json")) {
            assertTrue(Files.isRegularFile(dir.resolve(file)), "缺少产物: " + file);
        }
        assertEquals(16, result.writtenFiles().size());

        // 公共布局：骨架 + 导航区（structural）+ 页脚区（footer）+ <#nested>
        String layout = Files.readString(dir.resolve("_layout.html"));
        assertTrue(layout.contains("<#macro page>"), "布局应定义 page 宏");
        assertTrue(layout.contains("${pageTitle!''}"), "布局 head 应引用页面标题");
        assertTrue(layout.contains("_components/tw__navbar__sticky.ftl"), "布局应包含导航区");
        assertTrue(layout.contains("_components/tw__footer__simple.ftl"), "布局应包含页脚区");
        assertTrue(layout.contains("<#nested>"), "布局应有页面内容占位");

        // 页面：引用布局 + 本页专属 sections（导航/页脚不在页面内重复）
        String index = Files.readString(dir.resolve("index.html"));
        assertTrue(index.contains("<#import \"_layout.html\" as layout>"), "页面应引用公共布局");
        assertTrue(index.contains("<@layout.page>"), "页面应使用布局宏");
        assertTrue(index.contains("_components/tw__hero__centered.ftl"), "首页应含 hero 组件引用");
        assertFalse(index.contains("_components/tw__navbar__sticky.ftl"), "页面不应内联导航（由布局提供）");

        // _pagespec.json 可回读（重渲染的事实源）
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        PageSpec reread = mapper.readValue(Files.readString(dir.resolve("_pagespec.json")), PageSpec.class);
        assertNotNull(reread);
        assertEquals("FastCMS 演示站点", reread.safeSiteName());

        // 与预览同管线的 FreeMarker 渲染校验：四个页面全部通过
        List<String> errors = previewRenderer.checkRenderedFiles(dir, List.of(
                "index.html", "article_list.html", "article.html", "page.html"));
        assertEquals(List.of(), errors, "渲染校验应通过: " + errors);
    }

    /**
     * 共享组件源码：手改 _components/ 下的组件文件（不重新渲染），
     * 所有引用该组件的页面预览立即生效——"改一处全站生效"
     */
    @Test
    void shouldShareComponentSourceAcrossPages() throws Exception {
        Path dir = tempDir.resolve("shared-demo");
        renderer.render(sampleSpec(), dir);

        // 手改共享导航组件源码（模拟用户编辑公共文件，不走渲染管线）
        Path navbar = dir.resolve("_components/tw__navbar__sticky.ftl");
        String source = Files.readString(navbar);
        Files.writeString(navbar, source + "\n<div data-testid=\"custom-navbar\">定制导航标记</div>\n");

        // index 与 article 页预览渲染均出现该标记（include 运行期解析）
        String indexHtml = previewRenderer.renderPage("/preview", dir, "index.html");
        String articleHtml = previewRenderer.renderPage("/preview", dir, "article.html");
        assertTrue(indexHtml.contains("定制导航标记"), "首页应反映共享组件修改");
        assertTrue(articleHtml.contains("定制导航标记"), "文章页应反映共享组件修改");
    }

    /**
     * standalone 页面：不使用公共布局，渲染完整 HTML（自带骨架与 navbar/footer）
     */
    @Test
    void shouldRenderStandalonePage() throws Exception {
        Map<String, Object> heroData = new LinkedHashMap<>();
        heroData.put("title", "限时活动落地页");
        heroData.put("subtitle", "独立设计，不带公共导航页脚");
        PageSpec spec = new PageSpec(
                PageSpec.SPEC_VERSION,
                BuiltinTailwindPackProvider.FOUNDATION,
                "standalone-demo",
                "演示站点",
                "corporate-site",
                "minimal",
                "#2563eb",
                null,
                Map.of(
                        PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", Map.of("brand", "Demo")),
                                new SectionSpec("hero", "tw:hero", "centered", heroData),
                                new SectionSpec("footer", "tw:footer", "simple", Map.of("brand", "Demo")))),
                        "page_landing", new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", Map.of("brand", "Demo")),
                                new SectionSpec("hero", "tw:hero", "centered", heroData),
                                new SectionSpec("footer", "tw:footer", "simple", Map.of("brand", "Demo"))), true)));

        Path dir = tempDir.resolve("standalone-template");
        renderer.render(spec, dir);

        String landing = Files.readString(dir.resolve("page_landing.html"));
        assertTrue(landing.contains("<!DOCTYPE html>"), "standalone 页应是完整 HTML");
        assertTrue(landing.contains("_components/tw__navbar__sticky.ftl"), "standalone 页自带导航");
        assertFalse(landing.contains("<@layout.page>"), "standalone 页不应使用公共布局");

        // 普通页面照常走公共布局
        String index = Files.readString(dir.resolve("index.html"));
        assertTrue(index.contains("<@layout.page>"), "普通页面应使用公共布局");

        // 渲染校验通过
        List<String> errors = previewRenderer.checkRenderedFiles(dir, List.of(
                "index.html", "page_landing.html"));
        assertEquals(List.of(), errors, "渲染校验应通过: " + errors);
    }


    /**
     * site 信息架构渲染：带 suffix 的菜单/分类/单页 → 专属页面文件 + 全量预览数据
     */
    @Test
    void shouldRenderSiteArchitecture() throws Exception {
        SiteContentSpec site = new SiteContentSpec(
                List.of(
                        new SiteContentSpec.NavItem("首页", "index", null, List.of()),
                        new SiteContentSpec.NavItem("产品展示", "article_list", "products", List.of(
                                new SiteContentSpec.NavItem("土鸡品类", "article_list", "breeds", List.of()))),
                        new SiteContentSpec.NavItem("养殖环境", "article_list", "farm", List.of()),
                        new SiteContentSpec.NavItem("关于我们", "page", "about", List.of())),
                List.of(
                        new SiteContentSpec.CatalogItem("产品展示", "products"),
                        new SiteContentSpec.CatalogItem("养殖环境", "farm"),
                        new SiteContentSpec.CatalogItem("土鸡品类", "breeds")),
                List.of(new SiteContentSpec.CatalogItem("关于我们", "about")),
                List.of(
                        new SiteContentSpec.PreviewArticle("创新土鸡：山林散养 180 天的五谷喂养标准", "玉米豆粕五谷配比，全程无抗养殖。"),
                        new SiteContentSpec.PreviewArticle("冷链直达到家：48 小时锁鲜配送全流程", "宰后冰鲜处理，全程 0-4℃ 冷链。")));

        Map<String, Object> heroData = new LinkedHashMap<>();
        heroData.put("title", "创新土鸡");
        heroData.put("subtitle", "山林散养，五谷喂养");
        Map<String, Object> navbarData = Map.of("brand", "创新土鸡");
        Map<String, Object> footerData = Map.of("brand", "创新土鸡");

        PageSpec spec = new PageSpec(
                PageSpec.SPEC_VERSION,
                BuiltinTailwindPackProvider.FOUNDATION,
                "turkey-site",
                "创新土鸡",
                "farm-site",
                "warm",
                "#ea580c",
                site,
                Map.of(
                        PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("hero", "tw:hero", "centered", heroData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData))),
                        PageSpec.PAGE_ARTICLE_LIST, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData))),
                        PageSpec.PAGE_ARTICLE, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData))),
                        PageSpec.PAGE_PAGE, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", navbarData),
                                new SectionSpec("footer", "tw:footer", "simple", footerData)))));

        // site 信息架构合法（suffix 全站唯一）
        assertEquals(List.of(), validator.validate(spec), "校验应通过: " + validator.validate(spec));

        Path dir = tempDir.resolve("turkey-template");
        PageSpecRenderer.RenderResult result = renderer.render(spec, dir);

        // 4 基础页 + 3 个 suffix 专属页（products / breeds / farm / about = 4 个）
        List<String> expectedPages = List.of(
                "index.html", "article_list.html", "article.html", "page.html",
                "article_list_products.html", "article_list_breeds.html",
                "article_list_farm.html", "page_about.html");
        for (String file : expectedPages) {
            assertTrue(Files.isRegularFile(dir.resolve(file)), "缺少专属页: " + file);
        }
        assertTrue(result.writtenFiles().containsAll(expectedPages));

        // _preview_data.json 含全量信息架构（menus/categories/singlePages/articles/seo）
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.JsonNode preview = mapper.readTree(
                Files.readString(dir.resolve("_preview_data.json")));
        assertEquals("创新土鸡", preview.path("seo").path("website_title").asString());
        assertEquals(4, preview.path("menus").size());
        assertEquals("产品展示", preview.path("menus").get(1).path("name").asString());
        assertEquals("products", preview.path("menus").get(1).path("suffix").asString());
        assertEquals(3, preview.path("categories").size());
        assertEquals(1, preview.path("singlePages").size());
        assertEquals(2, preview.path("articles").path("titles").size());
        assertTrue(preview.path("articles").path("titles").get(0).asString().contains("土鸡"));

        // 全部页面 html（含 suffix 页）通过预览同管线渲染校验；_layout.html 为布局宏文件不算页面
        List<String> htmlFiles = result.writtenFiles().stream()
                .filter(p -> p.endsWith(".html") && !p.startsWith("_")).toList();
        assertEquals(expectedPages.size(), htmlFiles.size());
        List<String> errors = previewRenderer.checkRenderedFiles(dir, htmlFiles);
        assertEquals(List.of(), errors, "渲染校验应通过: " + errors);

        // 公共布局存在且包含导航/页脚区
        String layout = Files.readString(dir.resolve("_layout.html"));
        assertTrue(layout.contains("_components/tw__navbar__sticky.ftl"), "布局应含导航区");
        assertTrue(layout.contains("_components/tw__footer__simple.ftl"), "布局应含页脚区");
    }

    /**
     * site 校验：非首页菜单缺 suffix、suffix 重复均报错
     */
    @Test
    void shouldRejectInvalidSite() {
        SiteContentSpec site = new SiteContentSpec(
                List.of(
                        new SiteContentSpec.NavItem("首页", "index", null, List.of()),
                        new SiteContentSpec.NavItem("新闻", "article_list", null, List.of()),
                        new SiteContentSpec.NavItem("动态", "article_list", "news", List.of()),
                        new SiteContentSpec.NavItem("资讯", "article_list", "news", List.of())),
                List.of(), List.of(), List.of());
        PageSpec spec = new PageSpec(
                PageSpec.SPEC_VERSION, BuiltinTailwindPackProvider.FOUNDATION,
                "bad-site", "测试", "demo", "minimal", "#2563eb", site,
                Map.of(PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                        new SectionSpec("hero", "tw:hero", "centered",
                                Map.of("title", "t"))))));
        List<String> errors = validator.validate(spec);
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少 suffix")), "应提示菜单缺 suffix: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("重复")), "应提示 suffix 重复: " + errors);
    }
}