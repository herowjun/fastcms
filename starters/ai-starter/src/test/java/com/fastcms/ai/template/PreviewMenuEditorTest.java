package com.fastcms.ai.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览条目删除（引导页「删除该条目」的落地实现）测试
 *
 * <p>覆盖两条容易出错的规则：</p>
 * <ul>
 *     <li><b>真源分流</b>：{@code _preview_data.json} 对组件化模板是派生文件，
 *         有 {@code _pagespec.json} 时必须同时改真源，否则用户删完下一次 AI 调整就"复活"</li>
 *     <li><b>索引漂移防护</b>：两份文件的同一下标可能因手工编辑而对不上，名称不一致时只改派生文件；
 *         且比对必须在删除<b>之前</b>完成——删除是原地操作，删完同一下标已是"顺延上来的下一条"</li>
 * </ul>
 */
class PreviewMenuEditorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREVIEW = AiTemplateConstants.FILE_PREVIEW_DATA;
    private static final String PAGESPEC = AiTemplateConstants.FILE_PAGESPEC;

    private final PreviewMenuEditor editor = new PreviewMenuEditor();

    @TempDir
    Path workDir;

    private void write(String name, String json) throws Exception {
        Files.writeString(workDir.resolve(name), json, StandardCharsets.UTF_8);
    }

    private JsonNode read(String name) throws Exception {
        return MAPPER.readTree(Files.readString(workDir.resolve(name), StandardCharsets.UTF_8));
    }

    private JsonNode itemsOf(String file, String... path) throws Exception {
        JsonNode node = read(file);
        for (String seg : path) {
            node = node.path(seg);
        }
        return node;
    }

    // ==================== 真源分流 ====================

    /** 无 _pagespec.json（存量/手工模板）：_preview_data.json 本身就是真源，只改它 */
    @Test
    void withoutPagespecOnlyPreviewDataChanges() throws Exception {
        write(PREVIEW, """
                { "menus": [
                    { "name": "新闻动态", "type": "article_list", "suffix": "news", "children": [] },
                    { "name": "产品中心", "type": "article_list", "suffix": "products", "children": [] }
                ] }
                """);

        PreviewMenuEditor.Result result = editor.removeItem(workDir, "m0");

        assertTrue(result.changed());
        assertEquals(List.of(PREVIEW), result.files(), "无真源时应只改派生文件");
        JsonNode menus = itemsOf(PREVIEW, "menus");
        assertEquals(1, menus.size());
        assertEquals("产品中心", menus.get(0).path("name").asString());
    }

    /**
     * 有 _pagespec.json：两份文件同步删除，条目不会在下一轮 AI 渲染时"复活"
     *
     * <p><b>删最后一条</b>是本用例的重点：删除是原地操作，若在删除<b>之后</b>才比对名称，
     * 最后一条删完同一下标已越界（名称取不到），真源永远改不动——用户的删除动作会在
     * 下一次 AI 调整时被 {@code PageSpecRenderer.writePreviewData} 全量重写还原。</p>
     */
    @Test
    void withPagespecRemovesFromBothFilesEvenForLastItem() throws Exception {
        write(PREVIEW, """
                { "menus": [
                    { "name": "新闻动态", "type": "article_list", "suffix": "news", "children": [] },
                    { "name": "产品中心", "type": "article_list", "suffix": "products", "children": [] }
                ] }
                """);
        write(PAGESPEC, """
                { "specVersion": "1.2", "site": { "menus": [
                    { "name": "新闻动态", "type": "article_list", "suffix": "news", "children": [] },
                    { "name": "产品中心", "type": "article_list", "suffix": "products", "children": [] }
                ] } }
                """);

        PreviewMenuEditor.Result result = editor.removeItem(workDir, "m1");

        assertEquals(List.of(PREVIEW, PAGESPEC), result.files(), "派生文件与真源都应被修改");
        assertEquals(1, itemsOf(PREVIEW, "menus").size());
        assertEquals(1, itemsOf(PAGESPEC, "site", "menus").size(), "真源必须同步删除，否则条目会复活");
        assertEquals("新闻动态", itemsOf(PAGESPEC, "site", "menus").get(0).path("name").asString());
    }

    /** 索引漂移（同一下标处名称不一致）：只改派生文件，绝不动真源，防删错条目 */
    @Test
    void nameMismatchLeavesPagespecUntouched() throws Exception {
        write(PREVIEW, """
                { "menus": [ { "name": "新闻动态", "type": "article_list", "suffix": "news", "children": [] } ] }
                """);
        write(PAGESPEC, """
                { "site": { "menus": [
                    { "name": "关于我们", "type": "page", "suffix": "about", "children": [] }
                ] } }
                """);

        PreviewMenuEditor.Result result = editor.removeItem(workDir, "m0");

        assertEquals(List.of(PREVIEW), result.files(), "名称对不上时真源不应被修改");
        assertEquals(0, itemsOf(PREVIEW, "menus").size());
        assertEquals(1, itemsOf(PAGESPEC, "site", "menus").size(), "真源条目应原样保留");
        assertEquals("关于我们", itemsOf(PAGESPEC, "site", "menus").get(0).path("name").asString());
    }

    // ==================== 多级菜单与不同条目种类 ====================

    /** 二级菜单 ref（m0-1）逐层下钻到 menus[0].children[1] */
    @Test
    void removesNestedMenuChild() throws Exception {
        write(PREVIEW, """
                { "menus": [
                    { "name": "新闻动态", "type": "article_list", "children": [
                        { "name": "公司新闻", "type": "article_list", "suffix": "company", "children": [] },
                        { "name": "行业资讯", "type": "article_list", "suffix": "industry", "children": [] }
                    ] }
                ] }
                """);

        PreviewMenuEditor.Result result = editor.removeItem(workDir, "m0-1");

        assertTrue(result.changed());
        JsonNode children = itemsOf(PREVIEW, "menus").get(0).path("children");
        assertEquals(1, children.size());
        assertEquals("公司新闻", children.get(0).path("name").asString());
    }

    /** 分类与单页条目同样支持删除（预览数据与真源各自字段） */
    @Test
    void removesCategoryAndSinglePageFromBothFiles() throws Exception {
        write(PREVIEW, """
                {
                  "categories": [ { "title": "产品动态", "suffix": "news" } ],
                  "singlePages": [ { "title": "联系我们", "suffix": "contact" } ]
                }
                """);
        write(PAGESPEC, """
                { "site": {
                  "categories": [ { "title": "产品动态", "suffix": "news" } ],
                  "singlePages": [ { "title": "联系我们", "suffix": "contact" } ]
                } }
                """);

        assertEquals(List.of(PREVIEW, PAGESPEC), editor.removeItem(workDir, "c0").files());
        assertEquals(0, itemsOf(PREVIEW, "categories").size());
        assertEquals(0, itemsOf(PAGESPEC, "site", "categories").size());

        assertEquals(List.of(PREVIEW, PAGESPEC), editor.removeItem(workDir, "s0").files());
        assertEquals(0, itemsOf(PREVIEW, "singlePages").size());
        assertEquals(0, itemsOf(PAGESPEC, "site", "singlePages").size());
    }

    /**
     * 文章条目不可删：suffix 存于 articles.suffixes 平行数组，与 titles 一一对应，
     * 删除标题会让 suffix 整体错位（超出范围的文章会挂上别人的 suffix）
     */
    @Test
    void articleRefIsRefused() throws Exception {
        write(PREVIEW, """
                { "articles": { "titles": ["散养土鸡的日常"], "summaries": ["摘要"], "suffixes": ["farm"] } }
                """);

        PreviewMenuEditor.Result result = editor.removeItem(workDir, "a0");

        assertFalse(result.changed(), "文章条目不支持整项删除");
        assertEquals(1, itemsOf(PREVIEW, "articles", "titles").size());
        assertEquals(1, itemsOf(PREVIEW, "articles", "suffixes").size());
    }

    // ==================== 非法输入：一律不落盘 ====================

    /** ref 越界 / 未知前缀 / 空串：changed=false，文件字节数不变 */
    @Test
    void invalidRefsChangeNothing() throws Exception {
        String json = """
                { "menus": [ { "name": "关于我们", "type": "page", "suffix": "about", "children": [] } ] }
                """;
        write(PREVIEW, json);

        assertFalse(editor.removeItem(workDir, "m9").changed(), "越界下标");
        assertFalse(editor.removeItem(workDir, "m0-9").changed(), "二级下标越界");
        assertFalse(editor.removeItem(workDir, "x0").changed(), "未知前缀");
        assertFalse(editor.removeItem(workDir, "").changed(), "空 ref");
        assertFalse(editor.removeItem(workDir, null).changed(), "null ref");
        assertFalse(editor.removeItem(null, "m0").changed(), "null 目录");

        assertEquals(json, Files.readString(workDir.resolve(PREVIEW), StandardCharsets.UTF_8),
                "非法 ref 不得改动文件（连格式化都不应发生）");
    }

    /** 预览数据文件缺失：不凭空创建文件，回执失败由调用方提示用户 */
    @Test
    void missingPreviewDataDoesNotCreateFile() throws Exception {
        write(PAGESPEC, """
                { "site": { "menus": [ { "name": "关于我们", "type": "page", "suffix": "about", "children": [] } ] } }
                """);

        PreviewMenuEditor.Result result = editor.removeItem(workDir, "m0");

        assertFalse(result.changed());
        assertTrue(result.files().isEmpty());
        assertFalse(Files.exists(workDir.resolve(PREVIEW)), "不应凭空创建预览数据文件");
    }
}
