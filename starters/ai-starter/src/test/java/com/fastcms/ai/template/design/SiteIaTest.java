package com.fastcms.ai.template.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点信息架构解析测试（AI 输出 → SiteIa 的健壮性）
 *
 * <p>模型输出是不受控文本：可能带 markdown 围栏、前后解释、造出非法页型、给中文 slug、
 * 或产出超量栏目。这里逐条钉住兜底行为——解析失败必须是"返回 null 由调用方降级"，
 * 而不是抛异常把整条导入链路打断。</p>
 */
class SiteIaTest {

    private static final Set<String> RESERVED = Set.of("index", "article_list", "article", "page");

    @Test
    @DisplayName("标准输出：栏目清单、页型、首页数据区文案全部解析到位")
    void parsesStandardOutput() {
        String raw = """
                {
                  "siteName": "FastCMS",
                  "summary": "一个插件化的开源 CMS",
                  "homeSectionHeading": "最新动态",
                  "homeSectionIntro": "看看团队最近在做什么",
                  "pages": [
                    {"title":"核心能力","pageType":"page","slug":"features","description":"展示三大核心能力"},
                    {"title":"新闻动态","pageType":"article_list","slug":"news","description":"文章列表"}
                  ]
                }
                """;
        SiteIa ia = SiteIa.parse(raw, RESERVED);

        assertNotNull(ia);
        assertEquals("FastCMS", ia.siteName());
        assertEquals("最新动态", ia.safeHomeSectionHeading());
        assertEquals(2, ia.menuItems().size());
        assertEquals("features", ia.menuItems().get(0).slug());
        assertEquals("核心能力", ia.menuItems().get(0).title());
        assertEquals("展示三大核心能力", ia.menuItems().get(0).description());
    }

    @Test
    @DisplayName("markdown 围栏与前后解释文字不影响解析")
    void toleratesFencesAndProse() {
        String raw = """
                好的，我分析完了这个站点，结构如下：
                ```json
                {"siteName":"X","pages":[{"title":"关于我们","pageType":"page","slug":"about"}]}
                ```
                以上是站点清单，请确认。
                """;
        SiteIa ia = SiteIa.parse(raw, RESERVED);

        assertNotNull(ia);
        assertEquals("X", ia.siteName());
        assertEquals(1, ia.menuItems().size());
    }

    @Test
    @DisplayName("非法页型归 page（不因模型造词丢页面）")
    void fallsBackToPageTypeForUnknownType() {
        String raw = "{\"pages\":[{\"title\":\"产品\",\"pageType\":\"product\",\"slug\":\"products\"}]}";
        SiteIa ia = SiteIa.parse(raw, RESERVED);

        assertNotNull(ia);
        assertEquals(SiteIa.PAGE_TYPE_PAGE, ia.menuItems().get(0).pageType());
        assertFalse(ia.menuItems().get(0).isArticleList());
    }

    @Test
    @DisplayName("中文/空 slug 退化为 nav-N（中文不能当文件名，suffix 必须 ASCII）")
    void degradesNonAsciiSlug() {
        String raw = """
                {"pages":[
                  {"title":"核心能力","pageType":"page","slug":"核心能力"},
                  {"title":"插件市场","pageType":"page"}
                ]}
                """;
        SiteIa ia = SiteIa.parse(raw, RESERVED);

        assertNotNull(ia);
        List<SiteIa.SitePage> pages = ia.menuItems();
        assertEquals("nav-1", pages.get(0).slug());
        assertEquals("nav-2", pages.get(1).slug());
        // 菜单文案是中文（正确），slug 是 ASCII（可当文件名）
        assertEquals("核心能力", pages.get(0).title());
    }

    @Test
    @DisplayName("slug 与标准页重名或彼此冲突时追加序号（不会覆盖标准页）")
    void uniquifiesSlugAgainstReservedAndPeers() {
        String raw = """
                {"pages":[
                  {"title":"单页","pageType":"page","slug":"page"},
                  {"title":"功能","pageType":"page","slug":"features"},
                  {"title":"能力","pageType":"page","slug":"features"}
                ]}
                """;
        SiteIa ia = SiteIa.parse(raw, RESERVED);

        assertNotNull(ia);
        List<SiteIa.SitePage> pages = ia.menuItems();
        assertEquals("page-2", pages.get(0).slug());
        assertEquals("features", pages.get(1).slug());
        assertEquals("features-2", pages.get(2).slug());
    }

    @Test
    @DisplayName("栏目数超上限截断（防模型失控产出几十个页面）")
    void truncatesBeyondLimit() {
        StringBuilder sb = new StringBuilder("{\"pages\":[");
        for (int i = 0; i < SiteIa.MAX_COLUMN_PAGES + 8; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"title\":\"栏目").append(i).append("\",\"pageType\":\"page\",\"slug\":\"col-").append(i).append("\"}");
        }
        sb.append("]}");
        SiteIa ia = SiteIa.parse(sb.toString(), RESERVED);

        assertNotNull(ia);
        assertEquals(SiteIa.MAX_COLUMN_PAGES, ia.menuItems().size());
    }

    @Test
    @DisplayName("缺 pages / 非 JSON / 全空 → 返回 null 交由调用方降级")
    void returnsNullWhenUnusable() {
        assertNull(SiteIa.parse(null, RESERVED));
        assertNull(SiteIa.parse("", RESERVED));
        assertNull(SiteIa.parse("模型这次什么也没输出", RESERVED));
        assertNull(SiteIa.parse("{\"siteName\":\"X\"}", RESERVED));
        assertNull(SiteIa.parse("{\"pages\":[]}", RESERVED));
        // 有 pages 但每项都缺 title → 无可用的栏目
        assertNull(SiteIa.parse("{\"pages\":[{\"pageType\":\"page\",\"slug\":\"a\"}]}", RESERVED));
    }

    @Test
    @DisplayName("列表型栏目不进设计页清单，但仍留在菜单里")
    void separatesTemplatePagesFromMenuItems() {
        String raw = """
                {"pages":[
                  {"title":"核心能力","pageType":"page","slug":"features"},
                  {"title":"新闻动态","pageType":"article_list","slug":"news"},
                  {"title":"关于我们","pageType":"page","slug":"about"}
                ]}
                """;
        SiteIa ia = SiteIa.parse(raw, RESERVED);

        assertNotNull(ia);
        assertEquals(3, ia.menuItems().size());
        assertEquals(2, ia.templatePages().size());
        assertTrue(ia.templatePages().stream().noneMatch(SiteIa.SitePage::isArticleList));
    }

    @Test
    @DisplayName("toJson / fromJson 往返一致（转化段消费落盘 IA 的前提）")
    void jsonRoundTrip() {
        String raw = """
                {
                  "siteName":"X","summary":"S",
                  "homeSectionHeading":"最新动态","homeSectionIntro":"副标题",
                  "pages":[{"title":"核心能力","pageType":"page","slug":"features","description":"D"}]
                }
                """;
        SiteIa original = SiteIa.parse(raw, RESERVED);
        assertNotNull(original);

        SiteIa restored = SiteIa.fromJson(original.toJson());
        assertNotNull(restored);
        assertEquals(original.siteName(), restored.siteName());
        assertEquals(original.summary(), restored.summary());
        assertEquals(original.safeHomeSectionHeading(), restored.safeHomeSectionHeading());
        assertEquals(original.homeSectionIntro(), restored.homeSectionIntro());
        assertEquals(1, restored.menuItems().size());
        assertEquals("features", restored.menuItems().get(0).slug());
        assertEquals("D", restored.menuItems().get(0).description());
    }

    @Test
    @DisplayName("首页数据区标题缺失时兜底默认标题")
    void fallsBackToDefaultHomeSectionHeading() {
        SiteIa ia = SiteIa.parse("{\"pages\":[{\"title\":\"关于\",\"pageType\":\"page\",\"slug\":\"about\"}]}", RESERVED);

        assertNotNull(ia);
        assertEquals(SiteIa.DEFAULT_HOME_SECTION_HEADING, ia.safeHomeSectionHeading());
    }

    @Test
    @DisplayName("slugify：中文折叠为中划线后清尾，纯中文归空")
    void slugifyKeepsAsciiOnly() {
        assertEquals("features", SiteIa.slugify("Features"));
        assertEquals("my-page-2", SiteIa.slugify("My Page 2"));
        assertEquals("", SiteIa.slugify("核心能力"));
        assertEquals("", SiteIa.slugify(null));
    }
}
