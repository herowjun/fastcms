package com.fastcms.ai.template.design;

import com.fastcms.ai.component.SiteContentSpec;
import com.fastcms.ai.template.AiTemplatePreviewRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * custom nav 菜单化（R3）测试：2026-09-25 扩展后的两级扫描 + active 高亮 + 子菜单
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *     <li>div.nav-links &gt; a 链接组结构（landing 类设计稿主流写法，此前未命中导致菜单全静态）
 *         → 命中项替换为 menuTag 动态块，未命中项与首页项静态保留</li>
 *     <li>ul &gt; li &gt; a 结构回归（原有 R3 行为不被破坏）</li>
 *     <li>物化产物过 FreeMarker 真实渲染（预览 mock 的 menuTag）：菜单数据生效 + active 高亮命中
 *         —— 防止生成的 FTL 存在语法错误或前缀匹配口径错误</li>
 * </ol>
 *
 * <p>{@code doMenuifyNav} 等已改为无状态 static 包可见方法，本测试无需构造 Spring Bean。</p>
 */
class MockupConverterNavMenuifyTest {

    private final AiTemplatePreviewRenderer previewRenderer = new AiTemplatePreviewRenderer();

    @TempDir
    Path tempDir;

    /** 站点信息架构：首页 / 新闻动态（带子菜单）/ 关于我们 */
    private static List<SiteContentSpec.NavItem> navItems() {
        return List.of(
                new SiteContentSpec.NavItem("首页", SiteContentSpec.NavItem.TYPE_INDEX, null, List.of()),
                new SiteContentSpec.NavItem("新闻动态", SiteContentSpec.NavItem.TYPE_ARTICLE_LIST, "news",
                        List.of(new SiteContentSpec.NavItem("公司新闻",
                                SiteContentSpec.NavItem.TYPE_ARTICLE_LIST, "news", List.of()))),
                new SiteContentSpec.NavItem("关于我们", SiteContentSpec.NavItem.TYPE_PAGE, "about", List.of()));
    }

    /**
     * div.nav-links &gt; a 链接组（fastcms0924001 同款结构）：命中项菜单化、
     * 未命中锚点与首页项静态保留、产物含 active 判断与子菜单
     */
    @Test
    void linkGroupBecomesMenuTag() {
        String navHtml = "<nav class=\"nav\"><div class=\"wrap\">"
                + "<a class=\"logo\" href=\"/\">FastCMS</a>"
                + "<div class=\"nav-links\">"
                + "<a href=\"/\">首页</a>"
                + "<a href=\"#news\">新闻动态</a>"
                + "<a href=\"#about\">关于我们</a>"
                + "<a href=\"#stack\">技术架构</a>"
                + "</div></div></nav>";

        MockupConverter.MenuifyResult result = MockupConverter.doMenuifyNav(navHtml, navItems());
        String html = result.html();

        // 命中 2 项（新闻动态 / 关于我们），锚文本总数 4（logo 是非直接子 a，不计入）
        assertTrue(result.matched() >= 2, "应至少命中 2 个菜单项，实际: " + result.matched());
        assertTrue(html.contains("<@menuTag>"), "命中后必须替换为 menuTag 动态块");
        assertTrue(html.contains("</@menuTag>"), "menuTag 块必须闭合");
        // 未命中锚点静态保留并记账
        assertTrue(html.contains("技术架构"), "未命中项应静态保留");
        assertTrue(result.misses().contains("技术架构"), "未命中项应进 misses 账本");
        // active 前缀匹配 + 子菜单
        assertTrue(html.contains("_navUri?starts_with"), "menuTag 项应输出 active 前缀匹配判断");
        assertTrue(html.contains("<ul class=\"submenu\">"), "子菜单应渲染为 ul.submenu");
        assertTrue(html.contains("${item.children}") || html.contains("item.children"),
                "子菜单应遍历 item.children");
        // 容器属性保真
        assertTrue(html.contains("class=\"nav-links\""), "容器原属性应保留");
        assertTrue(html.contains("class=\"logo\""), "容器内非菜单链接（logo）应原样保留");
    }

    /** ul &gt; li &gt; a 结构回归：原有 R3 行为保持（命中即菜单化，首页项静态保留并注入高亮） */
    @Test
    void ulStructureAlsoMenuified() {
        String navHtml = "<ul class=\"site-nav\">"
                + "<li class=\"item\"><a class=\"link\" href=\"/\">首页</a></li>"
                + "<li class=\"item\"><a class=\"link\" href=\"/news\">新闻动态</a></li>"
                + "<li class=\"item\"><a class=\"link\" href=\"/about\">关于我们</a></li>"
                + "</ul>";

        MockupConverter.MenuifyResult result = MockupConverter.doMenuifyNav(navHtml, navItems());
        String html = result.html();

        assertTrue(html.contains("<@menuTag>"), "ul 结构应菜单化");
        assertTrue(html.contains("class=\"item<#if"), "首个命中项的 li 类名应作为模板类名");
        // 请求上下文 assign 必须出现在片段最前（静态项高亮条件引用 _navUri）
        assertTrue(html.indexOf("_navCp") < html.indexOf("class=\"item"),
                "请求上下文 assign 应先于静态项/动态项输出");
        // 首页项静态保留 + 注入首页 active 判断
        assertTrue(html.contains("_navUri == _navCp"), "静态首页项应注入首页高亮判断");
        assertTrue(html.contains("<ul class=\"submenu\">"), "子菜单应渲染");
    }

    /** 全部未命中（纯装饰锚点导航）：原样返回，不改动 HTML */
    @Test
    void unmatchedNavStaysStatic() {
        String navHtml = "<div class=\"nav-links\">"
                + "<a href=\"#features\">核心能力</a><a href=\"#market\">插件市场</a>"
                + "</div>";

        MockupConverter.MenuifyResult result = MockupConverter.doMenuifyNav(navHtml, navItems());
        assertFalse(result.html().contains("<@menuTag>"), "全未命中不应菜单化");
        assertTrue(result.html().contains("核心能力"), "未命中项原样保留");
    }

    /** 首页项高亮注入：有 class 与无 class 两种开标签都能正确注入 */
    @Test
    void injectHomeActiveBothForms() {
        List<SiteContentSpec.NavItem> items = navItems();
        String withClass = MockupConverter.injectHomeActive(
                "<li class=\"item\"><a href=\"/\">首页</a></li>", items);
        assertTrue(withClass.contains("class=\"item<#if"), "有 class 时应追加条件段");

        String withoutClass = MockupConverter.injectHomeActive("<a href=\"/\">首页</a>", items);
        assertTrue(withoutClass.contains("class=\"<#if"), "无 class 时应补 class 属性");

        String notHome = MockupConverter.injectHomeActive(
                "<a href=\"/news\">新闻动态</a>", items);
        assertFalse(notHome.contains("<#if"), "非首页项不应注入高亮判断");
    }

    /**
     * 端到端：物化产物写入模板目录 → 预览渲染引擎真实渲染
     * —— menuTag 数据生效（菜单项来自预览数据配置）+ active 高亮命中 + 子菜单输出
     */
    @Test
    void menuifiedNavRendersWithMenuTag() throws Exception {
        String navHtml = "<nav class=\"nav\"><div class=\"wrap\">"
                + "<div class=\"nav-links\">"
                + "<a href=\"/\">首页</a>"
                + "<a href=\"#news\">新闻动态</a>"
                + "<a href=\"#about\">关于我们</a>"
                + "</div></div></nav>";
        String nav = MockupConverter.doMenuifyNav(navHtml, navItems()).html();

        // 预览数据配置：菜单名单与 navItems 一致（列表页菜单项解析为 article_list.html）
        String previewData = """
                {
                  "menus": [
                    { "name": "首页", "type": "index" },
                    { "name": "新闻动态", "type": "article_list", "suffix": "news",
                      "children": [ { "name": "公司新闻", "type": "article_list", "suffix": "news" } ] },
                    { "name": "关于我们", "type": "page", "suffix": "about" }
                  ],
                  "seo": { "website_title": "菜单化测试站" }
                }
                """;
        Files.writeString(tempDir.resolve("_preview_data.json"), previewData, StandardCharsets.UTF_8);
        String page = "<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "</head><body>\n" + nav + "\n</body></html>\n";
        Files.writeString(tempDir.resolve("index.html"), page, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("article_list.html"), page, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("page.html"), page, StandardCharsets.UTF_8);

        // 渲染校验：全站产物无 FreeMarker 语法错误
        List<String> errors = previewRenderer.checkRenderedFiles(tempDir,
                List.of("index.html", "article_list.html", "page.html"));
        assertTrue(errors.isEmpty(), "菜单化产物应通过渲染校验，实际错误: " + errors);

        // 列表页渲染：当前页 URI 以 article_list.html 结尾，新闻动态菜单项应命中 active
        String rendered = previewRenderer.renderPage("/preview", tempDir, "article_list.html");
        assertTrue(rendered.contains("新闻动态"), "menuTag 数据应渲染出菜单项名");
        assertTrue(rendered.contains("公司新闻"), "子菜单数据应渲染");
        assertTrue(rendered.contains("<ul class=\"submenu\">"), "子菜单结构应输出");
        assertTrue(rendered.contains("active"), "当前页对应菜单项应输出 active 高亮");
        // 首页静态项保留（首页链接在布局里通常硬编码，预览数据口径不含首页）
        assertTrue(rendered.contains("首页"), "静态首页项应保留");
        // FTL 指令不应泄漏到最终 HTML
        assertFalse(rendered.contains("<@menuTag>"), "FTL 指令不应泄漏到渲染结果");
        assertFalse(rendered.contains("<#if"), "FTL 指令不应泄漏到渲染结果");
    }
}
