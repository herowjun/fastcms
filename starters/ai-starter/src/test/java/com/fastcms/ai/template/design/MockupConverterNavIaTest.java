package com.fastcms.ai.template.design;

import com.fastcms.ai.component.SiteContentSpec;
import com.fastcms.ai.template.AiTemplatePreviewRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计稿导航 → 信息架构（2026-09-25 新增）测试
 *
 * <p>覆盖四件事：</p>
 * <ol>
 *     <li>真实上传站导航（fastcms0924001：logo + nav-links 4 项 + nav-cta 2 个按钮）派生出的栏目
 *         ——品牌与 CTA 被剔除，slug 取自 href</li>
 *     <li>派生名单喂回菜单化：真实 nav 不再是"6 个锚点全部对不上"，而是命中即变 menuTag</li>
 *     <li>与规划信息架构同名的栏目不重复派生（如"文章列表"）</li>
 *     <li>端到端：派生名单 → _preview_data.json → 栏目页 → 预览渲染出菜单且链接指向真实栏目页</li>
 * </ol>
 */
class MockupConverterNavIaTest {

    private final AiTemplatePreviewRenderer previewRenderer = new AiTemplatePreviewRenderer();

    @TempDir
    Path tempDir;

    /** fastcms0924001 的真实 nav（上传站落地页结构） */
    private static final String REAL_NAV = """
            <nav class="nav" id="nav">
             <div class="wrap">
              <a class="logo" href="#top"><span class="logo-mark">F</span>FastCMS <small>AI 原生 CMS</small></a>
              <div class="nav-links">
               <a href="#features">核心能力</a> <a href="#market">插件市场</a> <a href="#stack">技术架构</a> <a href="#docs">文档</a>
              </div>
              <div class="nav-cta">
               <a class="btn btn-line btn-sm" href="#docs">源码下载</a> <a class="btn btn-primary btn-sm" href="#cta">免费开始使用</a>
              </div>
              <button class="nav-toggle" id="navToggle"><span></span><span></span><span></span></button>
             </div>
            </nav>
            """;

    /** 规划阶段的信息架构（import 模式典型结果：只出标准页） */
    private static List<SiteContentSpec.NavItem> plannedMenus() {
        return List.of(
                new SiteContentSpec.NavItem("首页", SiteContentSpec.NavItem.TYPE_INDEX, null, List.of()),
                new SiteContentSpec.NavItem("文章列表", SiteContentSpec.NavItem.TYPE_ARTICLE_LIST,
                        "article_list", List.of()));
    }

    private static Element realNav() {
        return Jsoup.parseBodyFragment(REAL_NAV).selectFirst("nav");
    }

    private static List<String> namesOf(List<SiteContentSpec.NavItem> items) {
        return items.stream().map(SiteContentSpec.NavItem::name).toList();
    }

    private static List<String> suffixesOf(List<SiteContentSpec.NavItem> items) {
        return items.stream().map(SiteContentSpec.NavItem::suffix).toList();
    }

    private static List<SiteContentSpec.NavItem> derive(Set<String> taken) {
        return MockupConverter.deriveNavMenus(realNav(), plannedMenus(), taken);
    }

    /** 真实导航 → 4 个栏目；brand/CTA 剔除；slug 取自 href fragment */
    @Test
    void derivesDesignNavIntoInformationArchitecture() {
        Set<String> taken = new HashSet<>(Set.of("article_list"));

        List<SiteContentSpec.NavItem> derived = derive(taken);

        assertEquals(List.of("核心能力", "插件市场", "技术架构", "文档"), namesOf(derived),
                "nav-links 的 4 个栏目应全部派生");
        assertEquals(List.of("features", "market", "stack", "docs"), suffixesOf(derived),
                "#features 形式的锚点应取 fragment 作 slug");
        assertTrue(derived.stream()
                        .allMatch(i -> SiteContentSpec.NavItem.TYPE_PAGE.equals(i.safeType())),
                "派生栏目一律按单页栏目处理");
        // 品牌 logo（class=logo / href=#top）与 CTA 按钮（class=btn）不得进信息架构
        assertFalse(namesOf(derived).contains("FastCMS AI 原生 CMS"), "品牌 logo 不应成为栏目");
        assertFalse(namesOf(derived).contains("源码下载"), "CTA 按钮不应成为栏目");
        assertFalse(namesOf(derived).contains("免费开始使用"), "CTA 按钮不应成为栏目");
        // suffix 必须落进 validator 契约 [a-zA-Z0-9_-]+
        derived.forEach(i -> assertTrue(i.suffix().matches("[a-zA-Z0-9_-]+"),
                "suffix 必须为 ASCII: " + i.suffix()));
    }

    /**
     * 关键回归：此前该 nav 的 6 个锚点全部对不上信息架构（matched=0，菜单永远静态）。
     * 派生名单并入后，nav-links 的 4 项应全部命中并替换为 menuTag；
     * CTA 按钮保留静态且不计入菜单项口径。
     */
    @Test
    void derivedMenusMakeRealNavFullyMenuified() {
        Set<String> taken = new HashSet<>(Set.of("article_list"));
        List<SiteContentSpec.NavItem> menus = new ArrayList<>(plannedMenus());
        menus.addAll(1, derive(taken));

        MockupConverter.MenuifyResult result = MockupConverter.doMenuifyNav(REAL_NAV, menus);

        assertEquals(4, result.matched(), "nav-links 4 项应全部命中，实际 miss: " + result.misses());
        assertEquals(4, result.total(), "品牌/CTA 不计入菜单项口径");
        assertTrue(result.misses().isEmpty(), "不应再有未匹配菜单项，实际: " + result.misses());
        assertTrue(result.html().contains("<@menuTag>"), "命中后应替换为 menuTag 动态块");
        // CTA 按钮保真（结构不能丢）
        assertTrue(result.html().contains("btn btn-primary btn-sm"), "CTA 按钮结构应原样保留");
        assertTrue(result.html().contains("class=\"logo\""), "品牌 logo 应原样保留");
    }

    /** 已命中规划信息架构的栏目不重复派生 */
    @Test
    void skipsNavItemsAlreadyInInformationArchitecture() {
        List<SiteContentSpec.NavItem> existing = new ArrayList<>(plannedMenus());
        existing.add(new SiteContentSpec.NavItem("关于我们",
                SiteContentSpec.NavItem.TYPE_PAGE, "about", List.of()));
        String navHtml = "<div class=\"nav-links\">"
                + "<a href=\"#about\">关于我们</a><a href=\"#pricing\">价格方案</a>"
                + "</div>";

        List<SiteContentSpec.NavItem> derived = MockupConverter.deriveNavMenus(
                Jsoup.parseBodyFragment(navHtml).selectFirst("div"), existing, new HashSet<>());

        assertEquals(List.of("价格方案"), namesOf(derived), "已有页面（含同义匹配）的栏目不应重复派生");
        assertEquals(List.of("pricing"), suffixesOf(derived));
    }

    /** slug 净化与退化：非 ASCII 锚点退化为 nav-序号；与已占用 suffix 冲突时追加序号 */
    @Test
    void navSlugSanitizesAndFallsBack() {
        assertEquals("features", MockupConverter.navSlug("#features", 1, new HashSet<>()));
        assertEquals("products", MockupConverter.navSlug("/products.html?a=1", 2, new HashSet<>()));
        assertEquals("docs", MockupConverter.navSlug("./docs/", 3, new HashSet<>()));
        // 纯中文锚点 → 净化后为空 → 退化
        assertEquals("nav-4", MockupConverter.navSlug("#核心能力", 4, new HashSet<>()));
        assertEquals("nav-5", MockupConverter.navSlug("", 5, new HashSet<>()));
        // 冲突追加序号（validator 要求菜单内 suffix 唯一）
        Set<String> taken = new HashSet<>(Set.of("about"));
        assertEquals("about-2", MockupConverter.navSlug("#about", 6, taken));
        assertEquals("about-3", MockupConverter.navSlug("#about", 7, taken));

        assertEquals("about-us", MockupConverter.sanitizeSlug("About Us"));
        assertEquals("a-b", MockupConverter.sanitizeSlug("a -- b"));
        assertEquals("", MockupConverter.sanitizeSlug("核心能力"));
    }

    /** 外链不参与信息架构（NavItem 表达不了自定义外链，落库会生成错误 URL） */
    @Test
    void skipsExternalLinks() {
        String navHtml = "<div class=\"nav\">"
                + "<a href=\"https://gitee.com/xjd2020/fastcms\">源码仓库</a>"
                + "<a href=\"#docs\">文档</a>"
                + "</div>";

        List<SiteContentSpec.NavItem> derived = MockupConverter.deriveNavMenus(
                Jsoup.parseBodyFragment(navHtml).selectFirst("div"), plannedMenus(), new HashSet<>());

        assertEquals(List.of("文档"), namesOf(derived));
    }

    /**
     * 端到端：派生栏目（含中文栏目名 + ASCII slug）→ _preview_data.json → 栏目页文件 →
     * 预览渲染：菜单名与设计稿一致、链接指向真实栏目页、无 FTL 泄漏
     */
    @Test
    void derivedMenusRenderInPreviewWithRealLinks() throws Exception {
        Set<String> taken = new HashSet<>(Set.of("article_list"));
        List<SiteContentSpec.NavItem> derived = derive(taken);

        List<SiteContentSpec.NavItem> menus = new ArrayList<>(plannedMenus());
        menus.addAll(1, derived);
        String nav = MockupConverter.doMenuifyNav(REAL_NAV, menus).html();

        // _preview_data.json（PageSpecRenderer 的口径：name/type/suffix/children）
        StringBuilder items = new StringBuilder();
        for (SiteContentSpec.NavItem item : menus) {
            if (!items.isEmpty()) {
                items.append(",\n");
            }
            items.append("    { \"name\": \"").append(item.name()).append("\", \"type\": \"")
                    .append(item.safeType()).append("\", \"children\": []");
            if (item.suffix() != null && !item.suffix().isBlank()) {
                items.append(", \"suffix\": \"").append(item.suffix()).append("\"");
            }
            items.append(" }");
        }
        Files.writeString(tempDir.resolve("_preview_data.json"),
                "{\n  \"menus\": [\n" + items + "\n  ],\n  \"seo\": { \"website_title\": \"导航信息架构测试站\" }\n}\n",
                StandardCharsets.UTF_8);

        String page = "<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "</head><body>\n" + nav + "\n</body></html>\n";
        List<String> files = new ArrayList<>(List.of("index.html", "article_list.html", "page.html"));
        for (SiteContentSpec.NavItem item : derived) {
            files.add("page_" + item.suffix() + ".html");
        }
        for (String f : files) {
            Files.writeString(tempDir.resolve(f), page, StandardCharsets.UTF_8);
        }

        List<String> errors = previewRenderer.checkRenderedFiles(tempDir, files);
        assertTrue(errors.isEmpty(), "菜单化产物应通过渲染校验，实际错误: " + errors);

        String rendered = previewRenderer.renderPage("/preview", tempDir, "index.html");
        // 菜单名与设计稿导航一致（含派生栏目）
        assertTrue(rendered.contains("核心能力"), "派生栏目应出现在渲染菜单里");
        assertTrue(rendered.contains("插件市场"), "派生栏目应出现在渲染菜单里");
        assertTrue(rendered.contains("文档"), "派生栏目应出现在渲染菜单里");
        // 链接指向真实栏目页（预览内可整站跳转）
        assertTrue(rendered.contains("page_features.html"), "栏目链接应指向派生出的栏目页");
        assertTrue(rendered.contains("article_list.html"), "规划页菜单项链接应保留");
        // CTA 与品牌保真，FTL 不泄漏
        assertTrue(rendered.contains("免费开始使用"), "CTA 按钮应保留");
        assertFalse(rendered.contains("<@menuTag>"), "FTL 指令不应泄漏到渲染结果");
        assertFalse(rendered.contains("<#if"), "FTL 指令不应泄漏到渲染结果");
    }
}
