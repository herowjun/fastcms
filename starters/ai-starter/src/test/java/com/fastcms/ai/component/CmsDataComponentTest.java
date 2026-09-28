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
 * CMS 数据组件测试（2026-09-25 新增）
 *
 * <p>背景：设计稿的 CMS 数据区（受控 {@code data-block} 语义名）需要确定性映射到标签驱动组件。
 * 本测试保证四个组件（article-list / category-list / tag-cloud / single-page-list）：</p>
 * <ol>
 *     <li>被内置组件包发现，且适用于全部页面类型（首页与内容页都要能放数据区）</li>
 *     <li>源码由 fastcms 内置指令渲染（articleListTag/categoryList/tagList/singlePageList），
 *         而不是静态 HTML——这是"内容接 CMS"的硬性判据</li>
 *     <li>渲染进模板目录后，在预览管线（含指令 mock）中可无损执行、无残留 FTL 指令</li>
 * </ol>
 */
class CmsDataComponentTest {

    private final ComponentRegistry registry =
            new ComponentRegistry(List.of(new BuiltinTailwindPackProvider()));
    private final PageSpecValidator validator = new PageSpecValidator(registry);
    private final PageSpecRenderer renderer = new PageSpecRenderer(registry, new TokenEngine());
    private final AiTemplatePreviewRenderer previewRenderer = new AiTemplatePreviewRenderer();

    @TempDir
    Path tempDir;

    /** 组件全名 → 该组件源码必须包含的 fastcms 指令 */
    private static final Map<String, String> CMS_COMPONENT_TAGS = new LinkedHashMap<>() {{
        put("tw:article-list", "<@articleListTag");
        put("tw:category-list", "<@categoryList");
        put("tw:tag-cloud", "<@tagList");
        put("tw:single-page-list", "<@singlePageList");
    }};

    @Test
    void shouldRegisterAllCmsDataComponentsForEveryPageType() {
        List<String> ids = registry.listComponents().stream()
                .map(ComponentRegistry.RegisteredComponent::fullId).toList();
        for (String fullId : CMS_COMPONENT_TAGS.keySet()) {
            assertTrue(ids.contains(fullId), "内置包应注册 " + fullId + "，实际: " + ids);
            List<String> appliesTo = registry.getDescriptor(fullId).safeAppliesTo();
            assertTrue(appliesTo.containsAll(List.of("index", "article_list", "article", "page")),
                    fullId + " 应适用于全部页面类型，实际: " + appliesTo);
        }
    }

    @Test
    void cmsComponentSourcesMustBeTagDriven() {
        for (Map.Entry<String, String> e : CMS_COMPONENT_TAGS.entrySet()) {
            ComponentDescriptor d = registry.getDescriptor(e.getKey());
            assertNotNull(d, e.getKey() + " 描述符缺失");
            assertFalse(d.safeVariants().isEmpty(), e.getKey() + " 应至少有一个变体");
            for (ComponentVariant v : d.safeVariants()) {
                String src = registry.getTemplateSource(e.getKey(), v.id());
                assertNotNull(src, e.getKey() + "#" + v.id() + " 源码缺失");
                assertTrue(src.contains(e.getValue()), e.getKey() + "#" + v.id()
                        + " 应由 " + e.getValue() + " 渲染（不得是静态 HTML）");
            }
        }
    }

    @Test
    void shouldRenderCmsDataSectionsAndPreviewCleanly() throws Exception {
        PageSpec spec = cmsSpec();
        assertEquals(List.of(), validator.validate(spec), "spec 应通过校验");

        Path dir = tempDir.resolve("cms-template");
        renderer.render(spec, dir);

        for (String file : List.of(
                "_components/tw__article-list__cards.ftl",
                "_components/tw__category-list__chips.ftl",
                "_components/tw__tag-cloud__cloud.ftl",
                "_components/tw__single-page-list__links.ftl")) {
            assertTrue(Files.isRegularFile(dir.resolve(file)), "缺少组件产物: " + file);
        }
        String index = Files.readString(dir.resolve("index.html"));
        for (String ref : List.of("tw__article-list__cards.ftl", "tw__category-list__chips.ftl",
                "tw__tag-cloud__cloud.ftl", "tw__single-page-list__links.ftl")) {
            assertTrue(index.contains(ref), "首页应引用 " + ref);
        }

        // 预览管线（menuTag/articleListTag/categoryList/tagList/singlePageList 全由 mock 提供）渲染通过
        List<String> errors = previewRenderer.checkRenderedFiles(dir, List.of("index.html"));
        assertEquals(List.of(), errors, "预览渲染应通过: " + errors);

        // 渲染输出：组件槽位与 CMS 数据都可见，且无残留 FTL 指令
        String html = previewRenderer.renderPage("", dir, "index.html");
        assertTrue(html.contains("最新动态"), "应渲染 article-list 组件标题槽位");
        assertFalse(html.contains("<@"), "渲染结果不应残留 FTL 指令");
    }

    /**
     * 首页同时编排四个 CMS 数据组件（文章/分类/标签/单页）——即"整站内容接 CMS"的最小完整形态
     */
    private PageSpec cmsSpec() {
        Map<String, Object> nav = Map.of("brand", "FastCMS");
        Map<String, Object> footer = Map.of("brand", "FastCMS");
        Map<String, Object> hero = new LinkedHashMap<>();
        hero.put("title", "内容驱动的官方网站");
        hero.put("subtitle", "文章、分类、标签与单页全部由 CMS 数据渲染");
        hero.put("ctaLabel", "查看全部文章");
        hero.put("ctaHref", "/article/category/1");
        hero.put("ctaSecondaryLabel", "了解更多");
        hero.put("ctaSecondaryHref", "/page/about");

        return new PageSpec(
                PageSpec.SPEC_VERSION, BuiltinTailwindPackProvider.FOUNDATION,
                "ai-cms-data-demo", "CMS 数据组件演示", "corporate-site", "corporate", "#2563eb", null,
                Map.of(
                        PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", nav),
                                new SectionSpec("hero", "tw:hero", "centered", hero),
                                new SectionSpec("articles", "tw:article-list", "cards",
                                        Map.of("title", "最新动态", "count", 6)),
                                new SectionSpec("cats", "tw:category-list", "chips",
                                        Map.of("title", "文章分类")),
                                new SectionSpec("tags", "tw:tag-cloud", "cloud",
                                        Map.of("title", "热门标签")),
                                new SectionSpec("pages", "tw:single-page-list", "links",
                                        Map.of("title", "关于与帮助")),
                                new SectionSpec("footer", "tw:footer", "simple", footer))),
                        PageSpec.PAGE_ARTICLE_LIST, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", nav),
                                new SectionSpec("cats", "tw:category-list", "chips",
                                        Map.of("title", "文章分类")),
                                new SectionSpec("footer", "tw:footer", "simple", footer))),
                        PageSpec.PAGE_ARTICLE, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", nav),
                                new SectionSpec("footer", "tw:footer", "simple", footer))),
                        PageSpec.PAGE_PAGE, new PageSpecPage(List.of(
                                new SectionSpec("nav", "tw:navbar", "sticky", nav),
                                new SectionSpec("footer", "tw:footer", "simple", footer)))),
                null);
    }
}
