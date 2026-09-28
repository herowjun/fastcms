package com.fastcms.ai.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览菜单 URL 三态解析 + 缺页面条目定位测试（2026-09-28）
 *
 * <p>历史缺陷：{@code resolveUrl} 在 suffix 对应的模板文件不存在时<b>静默回退</b>到该类型默认 URL，
 * 使"新闻动态""产品中心"最终都指向同一个 article_list.html、"解决方案""关于我们""联系我们"
 * 都指向 page.html——预览看起来导航饱满，实际每个链接都是假的，用户无从察觉配置已失效。</p>
 *
 * <p>现行为（三态）：suffix 为空 → 类型基础页；suffix 有且文件存在 → {@code {type}_{suffix}.html}；
 * suffix 有但文件缺失 → {@code __missing_page__/{ref}} 引导页，由用户在引导页决定
 * "让 AI 生成该页面"或"删除该条目"。无菜单配置时不再回退硬编码默认栏目。</p>
 */
class AiTemplatePreviewMenuTest {

    private final AiTemplatePreviewRenderer renderer = new AiTemplatePreviewRenderer();

    @TempDir
    Path workDir;

    /** 只渲染菜单的极简页面（等价于导航组件的 menuTag 用法；含两级菜单） */
    private static final String NAV_PAGE = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="utf-8"><title>t</title></head><body>
            <@menuTag>
              <ul>
              <#list data as m>
                <li><a href="${m.url}">${m.menuName}</a>
                  <#if m.children?? && (m.children?size gt 0)>
                    <ul><#list m.children as c><li><a href="${c.url}">${c.menuName}</a></li></#list></ul>
                  </#if>
                </li>
              </#list>
              </ul>
            </@menuTag>
            </body></html>
            """;

    private void writePage(String name) throws Exception {
        Files.writeString(workDir.resolve(name), NAV_PAGE, StandardCharsets.UTF_8);
    }

    private void writePreviewData(String json) throws Exception {
        Files.writeString(workDir.resolve(AiTemplateConstants.FILE_PREVIEW_DATA), json, StandardCharsets.UTF_8);
    }

    private String renderIndex() throws Exception {
        return renderer.renderPage("/preview", null, workDir, "index.html");
    }

    /** 读取模板目录的预览数据配置（拆包给断言用） */
    private AiTemplatePreviewMockSupport.PreviewDataConfig config() {
        return AiTemplatePreviewMockSupport.loadPreviewDataConfig(workDir);
    }

    // ==================== 三态 URL 解析 ====================

    /** 无菜单配置：不再伪造默认栏目（历史缺陷的另一半——那些栏目在模板里没有对应页面） */
    @Test
    void noMenuConfigRendersEmptyNav() throws Exception {
        writePage("index.html");

        String rendered = renderIndex();

        assertFalse(rendered.contains("新闻动态"), "无菜单配置时不应回退硬编码默认栏目「新闻动态」");
        assertFalse(rendered.contains("产品中心"), "无菜单配置时不应回退硬编码默认栏目「产品中心」");
        assertFalse(rendered.contains("联系我们"), "无菜单配置时不应回退硬编码默认栏目「联系我们」");
        assertFalse(renderer.hasMenuConfig(workDir), "无 _preview_data.json 应判定为菜单未配置（预览层据此提示）");
    }

    /** suffix 为空：指向该类型基础页（正常形态，不受本次改动影响） */
    @Test
    void menuWithoutSuffixLinksToBasePage() throws Exception {
        writePage("index.html");
        writePage("article_list.html");
        writePreviewData("""
                { "menus": [ { "name": "新闻动态", "type": "article_list", "children": [] } ] }
                """);

        String rendered = renderIndex();

        assertTrue(rendered.contains("/preview/article_list.html"),
                "suffix 为空的菜单应指向 article_list.html，实际: " + rendered);
        assertTrue(renderer.hasMenuConfig(workDir), "配置了 menus 应判定为已配置");
    }

    /** suffix 有且文件存在：指向专属页面 */
    @Test
    void menuWithExistingSuffixFileLinksToThatFile() throws Exception {
        writePage("index.html");
        writePage("article_list.html");
        writePage("article_list_products.html");
        writePreviewData("""
                { "menus": [ { "name": "产品中心", "type": "article_list", "suffix": "products", "children": [] } ] }
                """);

        String rendered = renderIndex();

        assertTrue(rendered.contains("/preview/article_list_products.html"),
                "suffix 对应文件存在时应指向该专属页面，实际: " + rendered);
    }

    /** suffix 有但文件缺失：指向缺页面引导页，绝不静默回退到基础页 */
    @Test
    void menuWithMissingPageLinksToGuidePage() throws Exception {
        writePage("index.html");
        writePage("article_list.html");
        writePreviewData("""
                { "menus": [ { "name": "产品中心", "type": "article_list", "suffix": "products", "children": [] } ] }
                """);

        String rendered = renderIndex();

        assertTrue(rendered.contains("/preview/__missing_page__/m0"),
                "缺页面菜单应指向引导页（带条目引用 m0），实际: " + rendered);
        assertFalse(rendered.contains("/preview/article_list.html"),
                "缺页面时不得静默回退到 article_list.html（这正是多栏目跳同一页的根因）");
    }

    /**
     * 核心回归：两个都缺页面的菜单不再收敛到同一个 URL
     *
     * <p>这正是用户报出的现象——"新闻动态""产品中心"都跳 article_list.html。修复后二者
     * 各自指向自己的缺页面引导页，且能据此分别生成或删除。</p>
     */
    @Test
    void missingPageMenusNoLongerCollapseToSameUrl() throws Exception {
        writePage("index.html");
        writePage("article_list.html");
        writePage("page.html");
        writePreviewData("""
                { "menus": [
                    { "name": "新闻动态", "type": "article_list", "suffix": "news", "children": [] },
                    { "name": "产品中心", "type": "article_list", "suffix": "products", "children": [] },
                    { "name": "解决方案", "type": "page", "suffix": "solutions", "children": [] },
                    { "name": "关于我们", "type": "page", "suffix": "about", "children": [] }
                ] }
                """);

        String rendered = renderIndex();

        assertTrue(rendered.contains("/preview/__missing_page__/m0"), "新闻动态应指向自己的引导页");
        assertTrue(rendered.contains("/preview/__missing_page__/m1"), "产品中心应指向自己的引导页");
        assertTrue(rendered.contains("/preview/__missing_page__/m2"), "解决方案应指向自己的引导页");
        assertTrue(rendered.contains("/preview/__missing_page__/m3"), "关于我们应指向自己的引导页");
        // 四个条目分属两个类型，但都不能落到同一个基础页上
        assertFalse(rendered.contains("/preview/article_list.html"), "不应有栏目落到 article_list.html");
        assertFalse(rendered.contains("/preview/page.html"), "不应有栏目落到 page.html");
    }

    /** 二级菜单同样参与三态解析，引导页引用带父级路径（m0-1） */
    @Test
    void nestedMenuCarriesParentIndexInRef() throws Exception {
        writePage("index.html");
        writePage("article_list.html");
        writePreviewData("""
                { "menus": [
                    { "name": "新闻动态", "type": "article_list", "children": [
                        { "name": "公司新闻", "type": "article_list", "suffix": "company", "children": [] }
                    ] }
                ] }
                """);

        String rendered = renderIndex();

        assertTrue(rendered.contains("/preview/__missing_page__/m0-0"),
                "二级菜单引导页引用应为 m0-0，实际: " + rendered);
    }

    // ==================== 条目定位（引导页与删除接口共用） ====================

    /** 按 ref 定位菜单条目并给出期望文件名与条目标签 */
    @Test
    void findMissingItemResolvesMenuByRef() throws Exception {
        writePreviewData("""
                { "menus": [
                    { "name": "关于我们", "type": "page", "suffix": "about", "children": [] }
                ] }
                """);

        AiTemplatePreviewMockSupport.MissingItem item =
                AiTemplatePreviewMockSupport.findMissingItem(
                        config(), "m0");

        assertNotNull(item, "m0 应能定位到菜单条目");
        assertEquals("关于我们", item.name());
        assertEquals("page", item.type());
        assertEquals("about", item.suffix());
        assertEquals("page_about.html", item.expectedFile(), "期望文件名应为 {type}_{suffix}.html");
        assertEquals("栏目", item.kindLabel());
        assertTrue(item.deletable(), "菜单条目应支持删除");
    }

    /**
     * 非法元素被跳过后 ref 仍与文件下标对齐（ref 取原始 JSON 下标）
     *
     * <p>这是删除操作的安全前提：若 ref 按"解析成功的顺序"编号，跳过非法元素后
     * 删除动作会误删相邻条目。</p>
     */
    @Test
    void refKeepsRawJsonIndexWhenInvalidItemSkipped() throws Exception {
        writePreviewData("""
                { "menus": [
                    { "type": "page", "suffix": "ghost", "children": [] },
                    { "name": "关于我们", "type": "page", "suffix": "about", "children": [] }
                ] }
                """);

        AiTemplatePreviewMockSupport.PreviewDataConfig cfg = config();

        assertNull(AiTemplatePreviewMockSupport.findMissingItem(cfg, "m0"),
                "下标 0 是无名非法元素，不应定位到条目");
        AiTemplatePreviewMockSupport.MissingItem item =
                AiTemplatePreviewMockSupport.findMissingItem(cfg, "m1");
        assertNotNull(item, "合法条目位于 JSON 下标 1，其 ref 应为 m1");
        assertEquals("关于我们", item.name());
    }

    /** 分类/标签/单页同样纳入引导范围（suffix 无对应页面文件时同样可定位） */
    @Test
    void findMissingItemResolvesCatalogEntries() throws Exception {
        writePreviewData("""
                {
                  "categories": [ { "title": "产品动态", "suffix": "news" } ],
                  "tags": [ { "title": "土鸡", "suffix": "chicken" } ],
                  "singlePages": [ { "title": "联系我们", "suffix": "contact" } ]
                }
                """);
        AiTemplatePreviewMockSupport.PreviewDataConfig cfg = config();

        assertEquals("分类", AiTemplatePreviewMockSupport.findMissingItem(cfg, "c0").kindLabel());
        assertEquals("article_list_news.html",
                AiTemplatePreviewMockSupport.findMissingItem(cfg, "c0").expectedFile());
        assertEquals("标签", AiTemplatePreviewMockSupport.findMissingItem(cfg, "t0").kindLabel());
        AiTemplatePreviewMockSupport.MissingItem page =
                AiTemplatePreviewMockSupport.findMissingItem(cfg, "s0");
        assertEquals("单页", page.kindLabel());
        assertEquals("page_contact.html", page.expectedFile());
        assertTrue(page.deletable(), "单页条目应支持删除");
    }

    /** 文章条目：suffix 存于平行数组，只支持"生成页面"（不支持整项删除） */
    @Test
    void articleEntryIsNotDeletable() throws Exception {
        writePreviewData("""
                {
                  "articles": { "titles": ["散养土鸡的日常"], "summaries": ["摘要"], "suffixes": ["farm"] }
                }
                """);

        AiTemplatePreviewMockSupport.MissingItem item = AiTemplatePreviewMockSupport.findMissingItem(
                config(), "a0");

        assertNotNull(item, "a0 应定位到文章条目");
        assertEquals("文章", item.kindLabel());
        assertEquals("article_farm.html", item.expectedFile());
        assertFalse(item.deletable(), "文章 suffix 与标题一一对应，不支持整项删除");
    }

    /** ref 非法/越界：一律返回 null（删除接口据此回执失败，不得误删） */
    @Test
    void unknownRefResolvesToNull() throws Exception {
        writePreviewData("""
                { "menus": [ { "name": "关于我们", "type": "page", "suffix": "about", "children": [] } ] }
                """);
        AiTemplatePreviewMockSupport.PreviewDataConfig cfg = config();

        assertNull(AiTemplatePreviewMockSupport.findMissingItem(cfg, "m9"), "越界下标不应定位到条目");
        assertNull(AiTemplatePreviewMockSupport.findMissingItem(cfg, "x0"), "未知前缀不应定位到条目");
        assertNull(AiTemplatePreviewMockSupport.findMissingItem(cfg, ""), "空 ref 不应定位到条目");
    }
}
