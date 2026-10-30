package com.fastcms.ai.template;

import com.fastcms.ai.support.FilePatchApplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补丁协议推广到全部「修改型」链路的单元测试（2026-10-29）
 *
 * <p>覆盖两件事：</p>
 * <ol>
 *   <li>{@code unchanged} 协议解析（深度焕新轮的进度推进依据）</li>
 *   <li>{@link FilePatchApplier#applyToContents} —— 修复轮的补丁基准是内存内容而非磁盘</li>
 * </ol>
 */
class PatchProtocolExpansionTest {

    // ==================== unchanged 协议 ====================

    @Test
    @DisplayName("解析 unchanged 字段（深度焕新轮进度推进依据）")
    void parsesUnchanged() {
        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        String raw = "{\"reply\":\"本批改造完成\",\"patches\":[],\"unchanged\":[\"_layout.html\",\"index.html\"]}";
        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);
        assertEquals(List.of("_layout.html", "index.html"), result.getUnchanged());
    }

    @Test
    @DisplayName("unchanged 路径归一化：去 ./ 与 / 前缀、反斜杠转正斜杠、去空白")
    void sanitizesUnchangedPaths() {
        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        String raw = "{\"unchanged\":[\"./a.html\",\"/b.html\",\"  c.html  \",\"d\\\\e.html\",\"\",null,123]}";
        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);
        assertEquals(List.of("a.html", "b.html", "c.html", "d/e.html"), result.getUnchanged());
    }

    @Test
    @DisplayName("无 unchanged 字段时为空列表（不抛异常）")
    void unchangedDefaultsToEmpty() {
        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult result = parser.parseResponse("{\"reply\":\"hi\",\"files\":[]}");
        assertTrue(result.getUnchanged().isEmpty());
    }

    @Test
    @DisplayName("patches 与 unchanged 可同时存在（同一文件不重复出现由提示词约束）")
    void patchesAndUnchangedCoexist() {
        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        String raw = "{\"patches\":[{\"path\":\"a.html\",\"search\":\"x\",\"replace\":\"y\"}],"
                + "\"unchanged\":[\"b.html\"]}";
        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);
        assertEquals(1, result.getPatches().size());
        assertEquals(1, result.getUnchanged().size());
        assertEquals("b.html", result.getUnchanged().get(0));
    }

    // ==================== 内存内容补丁（修复轮） ====================

    @Test
    @DisplayName("applyToContents：基于内存内容替换（修复轮场景）")
    void appliesPatchToInMemoryContent() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("index.html", "<h1>旧标题</h1>\n<p>正文</p>");

        AiTemplatePatchDto patch = new AiTemplatePatchDto();
        patch.setPath("index.html");
        patch.setSearch("<h1>旧标题</h1>");
        patch.setReplace("<h1>新标题</h1>");

        FilePatchApplier.ApplyResult result = new FilePatchApplier().applyToContents(contents, List.of(patch));

        assertTrue(result.failures().isEmpty(), "不应有失败: " + result.failures());
        assertEquals("<h1>新标题</h1>\n<p>正文</p>", result.fileContents().get("index.html"));
        // 入参 Map 不被污染
        assertEquals("<h1>旧标题</h1>\n<p>正文</p>", contents.get("index.html"));
    }

    @Test
    @DisplayName("applyToContents：CRLF 内容 + LF 补丁也能命中（行尾符无关）")
    void appliesPatchWithCrlfBase() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("index.html", "<h1>旧标题</h1>\r\n<p>正文</p>\r\n");

        AiTemplatePatchDto patch = new AiTemplatePatchDto();
        patch.setPath("index.html");
        patch.setSearch("<h1>旧标题</h1>\n<p>正文</p>");
        patch.setReplace("<h1>新标题</h1>\n<p>正文</p>");

        FilePatchApplier.ApplyResult result = new FilePatchApplier().applyToContents(contents, List.of(patch));

        assertTrue(result.failures().isEmpty(), "不应有失败: " + result.failures());
        String patched = result.fileContents().get("index.html");
        assertTrue(patched.contains("新标题"));
        // 行尾风格对齐文件（CRLF）
        assertTrue(patched.contains("\r\n"), "替换内容应按文件风格转成 CRLF");
    }

    @Test
    @DisplayName("applyToContents：文件不在基准内容中 → 失败并给出明确原因")
    void failsWhenFileMissingInBase() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("a.html", "x");

        AiTemplatePatchDto patch = new AiTemplatePatchDto();
        patch.setPath("b.html");
        patch.setSearch("x");
        patch.setReplace("y");

        FilePatchApplier.ApplyResult result = new FilePatchApplier().applyToContents(contents, List.of(patch));

        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("不存在"));
        assertTrue(result.fileContents().isEmpty());
    }

    @Test
    @DisplayName("applyToContents：同文件多补丁串行应用")
    void appliesMultiplePatchesSerially() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("a.html", "<p>1</p>\n<p>2</p>\n<p>3</p>");

        AiTemplatePatchDto p1 = new AiTemplatePatchDto();
        p1.setPath("a.html");
        p1.setSearch("<p>1</p>");
        p1.setReplace("<p>一</p>");
        AiTemplatePatchDto p2 = new AiTemplatePatchDto();
        p2.setPath("a.html");
        p2.setSearch("<p>3</p>");
        p2.setReplace("<p>三</p>");

        FilePatchApplier.ApplyResult result = new FilePatchApplier().applyToContents(contents, List.of(p1, p2));

        assertTrue(result.failures().isEmpty());
        assertEquals("<p>一</p>\n<p>2</p>\n<p>三</p>", result.fileContents().get("a.html"));
    }

    @Test
    @DisplayName("applyToContents：未触及的文件不进产物（不浪费带宽）")
    void skipsUntouchedFiles() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("a.html", "<p>1</p>");
        contents.put("b.html", "<p>2</p>");

        AiTemplatePatchDto p1 = new AiTemplatePatchDto();
        p1.setPath("a.html");
        p1.setSearch("<p>1</p>");
        p1.setReplace("<p>一</p>");

        FilePatchApplier.ApplyResult result = new FilePatchApplier().applyToContents(contents, List.of(p1));

        assertEquals(1, result.fileContents().size());
        assertTrue(result.fileContents().containsKey("a.html"));
        assertFalse(result.fileContents().containsKey("b.html"));
    }

    @Test
    @DisplayName("applyToContents：路径穿越/非法扩展名被拒")
    void rejectsIllegalPath() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("a.html", "x");

        AiTemplatePatchDto p1 = new AiTemplatePatchDto();
        p1.setPath("../evil.html");
        p1.setSearch("x");
        p1.setReplace("y");
        AiTemplatePatchDto p2 = new AiTemplatePatchDto();
        p2.setPath("a.exe");
        p2.setSearch("x");
        p2.setReplace("y");

        FilePatchApplier.ApplyResult result = new FilePatchApplier().applyToContents(contents, List.of(p1, p2));

        assertEquals(2, result.failures().size());
        assertTrue(result.fileContents().isEmpty());
    }

    @Test
    @DisplayName("applyToContents：空补丁列表安全返回")
    void handlesEmptyPatches() {
        FilePatchApplier.ApplyResult result = new FilePatchApplier()
                .applyToContents(new LinkedHashMap<>(Map.of("a.html", "x")), List.of());
        assertTrue(result.fileContents().isEmpty());
        assertTrue(result.failures().isEmpty());
    }

    // ==================== 提示词协议 ====================

    @Test
    @DisplayName("4 条修改型链路的提示词都声明了 patches 通道")
    void promptsDeclarePatchesChannel() {
        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(
                new com.fastcms.ai.skill.SkillRegistry(List.of()));

        String refine = builder.buildRefinePrompt("改标题", "### index.html\n```\n<p>x</p>\n```");
        assertTrue(refine.contains("patches"), "微调轮应声明 patches 通道");
        assertFalse(refine.contains("files 数组中仅输出需要修改或新增的文件"),
                "微调轮不应再要求 files 整文件回写");

        String anchor = builder.buildAnchorFixPrompt(List.of("id:hero"), "### a.html\n```\n<p>x</p>\n```");
        assertTrue(anchor.contains("patches"), "锚点修复轮应声明 patches 通道");

        String audit = builder.buildAuditFixPrompt(
                List.of(new com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue(
                        "a.html", "missing_class", "detail")),
                "### a.html\n```\n<p>x</p>\n```", null);
        assertTrue(audit.contains("patches"), "审计修复轮应声明 patches 通道");

        String upgrade = builder.buildStyleUpgradePrompt(
                "### a.html\n```\n<p>x</p>\n```", List.of(), 0, 1, false, 0, null, null, null, null);
        assertTrue(upgrade.contains("patches"), "深度焕新轮应声明 patches 通道");
        assertTrue(upgrade.contains("unchanged"), "深度焕新轮应声明 unchanged 协议");
        assertFalse(upgrade.contains("也必须把该文件原样完整输出"),
                "深度焕新轮不应再要求原样回吐整文件");
    }

    @Test
    @DisplayName("微调轮注入的是文件内容而非文件清单（模型要写得出 search）")
    void refinePromptInjectsContent() {
        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(
                new com.fastcms.ai.skill.SkillRegistry(List.of()));
        String refine = builder.buildRefinePrompt("改标题", "### index.html\n```\n<p>原文</p>\n```");
        assertTrue(refine.contains("完整内容"), "应声明注入的是完整内容");
        assertTrue(refine.contains("<p>原文</p>"), "应实际包含文件内容");
    }
}
