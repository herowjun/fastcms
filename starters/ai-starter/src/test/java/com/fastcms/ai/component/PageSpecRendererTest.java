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

        // 产物齐全
        for (String file : List.of("index.html", "article_list.html", "article.html", "page.html",
                "static/css/pack.css", "static/css/tokens.css", "static/css/site.css",
                "_pagespec.json", "_template.properties", "_preview_data.json")) {
            assertTrue(Files.isRegularFile(dir.resolve(file)), "缺少产物: " + file);
        }
        assertEquals(10, result.writtenFiles().size());

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

        // 全部 html（含 suffix 页）通过预览同管线渲染校验
        List<String> htmlFiles = result.writtenFiles().stream()
                .filter(p -> p.endsWith(".html")).toList();
        assertEquals(expectedPages.size(), htmlFiles.size());
        List<String> errors = previewRenderer.checkRenderedFiles(dir, htmlFiles);
        assertEquals(List.of(), errors, "渲染校验应通过: " + errors);
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