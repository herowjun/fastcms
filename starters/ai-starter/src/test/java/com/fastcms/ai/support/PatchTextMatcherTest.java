package com.fastcms.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补丁片段匹配器单元测试（行尾符无关匹配）
 *
 * <p>覆盖 2026-10-28 活体实测暴露的真实 bug：模型输出 LF、磁盘文件 CRLF → 逐字匹配必失败。</p>
 */
class PatchTextMatcherTest {

    @Test
    @DisplayName("逐字匹配命中（LF 文件，最常见情况）")
    void exactMatchOnLfFile() {
        String content = "line1\nline2\nline3\n";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "line2");
        assertEquals(6, m.start());
        assertEquals(5, m.length());
        assertFalse(m.normalized());
    }

    @Test
    @DisplayName("CRLF 文件 + LF search → 归一化口径命中（真实 bug 回归）")
    void crlfFileWithLfSearch() {
        String content = "      <a href=\"plugins.html\">插件市场</a>\r\n"
                + "      <a href=\"index.html#pricing\">定价</a>\r\n";
        String search = "      <a href=\"plugins.html\">插件市场</a>\n"
                + "      <a href=\"index.html#pricing\">定价</a>";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, search);
        assertTrue(m.start() >= 0, "应命中");
        assertTrue(m.normalized(), "应走归一化口径");
        // 替换后内容正确
        String patched = content.substring(0, m.start()) + "XX" + content.substring(m.start() + m.length());
        assertTrue(patched.contains("XX"));
        assertFalse(patched.contains("插件市场"));
    }

    @Test
    @DisplayName("LF 文件 + CRLF search → 归一化口径命中（反向场景）")
    void lfFileWithCrlfSearch() {
        String content = "a\nb\nc\n";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "a\r\nb");
        assertTrue(m.start() >= 0, "应命中");
        assertTrue(m.normalized());
        assertEquals(0, m.start());
        assertEquals(3, m.length());
    }

    @Test
    @DisplayName("归一化命中时长度映射回原始坐标正确（含 CRLF 计数）")
    void normalizedLengthMapsBackCorrectly() {
        // 3 行 CRLF，第二行是要替换的目标（搜索片段跨行 → 必须走归一化口径）
        String content = "aaa\r\nbbb\r\nccc\r\n";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "aaa\nbbb");
        assertTrue(m.start() >= 0);
        assertTrue(m.normalized());
        assertEquals(0, m.start());
        assertEquals(8, m.length(), "'aaa\\r\\nbbb' 原文长度 = 3+2+3");
        assertEquals("aaa\r\nbbb", content.substring(m.start(), m.start() + m.length()));
    }

    @Test
    @DisplayName("多行片段跨 CRLF 边界的起点映射正确")
    void multiLineStartMapping() {
        String content = "first\r\nsecond\r\nthird\r\n";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "first\nsecond");
        assertTrue(m.start() >= 0);
        assertEquals(0, m.start());
        assertEquals(13, m.length(), "两行 CRLF 原文长度 = 5+2+6");
        assertEquals("first\r\nsecond", content.substring(m.start(), m.start() + m.length()));
    }

    @Test
    @DisplayName("归一化后多处命中 → 判歧义（不静默改错地方）")
    void ambiguousNormalizedMatch() {
        String content = "dup\r\ndup\r\n";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "dup");
        assertSame(PatchTextMatcher.AMBIGUOUS, m);
    }

    @Test
    @DisplayName("归一化后未命中 → NOT_FOUND")
    void notFoundNormalized() {
        String content = "aaa\r\nbbb\r\n";
        assertSame(PatchTextMatcher.NOT_FOUND, PatchTextMatcher.find(content, "zzz"));
    }

    @Test
    @DisplayName("内容无 CRLF 时不做无谓归一化")
    void noCrlfNoNormalization() {
        String content = "aaa\nbbb\n";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "zzz");
        assertSame(PatchTextMatcher.NOT_FOUND, m);
        assertFalse(m.normalized());
    }

    @Test
    @DisplayName("空 search / 空 content 安全返回 NOT_FOUND")
    void emptyInputs() {
        assertSame(PatchTextMatcher.NOT_FOUND, PatchTextMatcher.find("abc", ""));
        assertSame(PatchTextMatcher.NOT_FOUND, PatchTextMatcher.find("abc", null));
        assertSame(PatchTextMatcher.NOT_FOUND, PatchTextMatcher.find(null, "abc"));
        assertSame(PatchTextMatcher.NOT_FOUND, PatchTextMatcher.find("", "abc"));
    }

    @Test
    @DisplayName("行尾风格对齐：CRLF 文件的替换内容转成 CRLF")
    void alignNewlinesToCrlf() {
        String content = "a\r\nb\r\n";
        assertEquals("x\r\ny", PatchTextMatcher.alignNewlines("x\ny", content));
    }

    @Test
    @DisplayName("行尾风格对齐：LF 文件保持 LF")
    void alignNewlinesKeepsLf() {
        String content = "a\nb\n";
        assertEquals("x\ny", PatchTextMatcher.alignNewlines("x\ny", content));
    }

    @Test
    @DisplayName("行尾风格对齐：无换行的替换内容原样返回")
    void alignNewlinesNoop() {
        assertEquals("xy", PatchTextMatcher.alignNewlines("xy", "a\r\nb"));
        assertEquals("x\ny", PatchTextMatcher.alignNewlines("x\ny", "no newline here"));
    }

    @Test
    @DisplayName("单独 \\r（旧 Mac 换行）也能归一化")
    void loneCarriageReturn() {
        String content = "aaa\rbbb";
        PatchTextMatcher.Match m = PatchTextMatcher.find(content, "aaa\nbbb");
        assertTrue(m.start() >= 0);
        assertTrue(m.normalized());
        assertEquals(0, m.start());
        assertEquals(7, m.length());
    }
}
