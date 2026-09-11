package com.fastcms.ai.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * reasoning 差分逻辑（零分配前缀匹配 + 上限缓冲）的等价性验证
 *
 * <p>背景：修复前每 chunk 做 toString/startsWith/整体替换共 5 次全量复制（O(L²)），
 * 推理模型重复循环时把 G1 堆推爆（native OOM 实例）。本测试用反射驱动私有静态方法，
 * 验证修复后的行为与旧逻辑在三种透传模式下等价：累积模式、纯增量模式、重复帧。</p>
 */
class ReasoningDiffLogicTest {

    private static final int MAX_CHARS;

    static {
        try {
            Method m = AiTemplateGenServiceImpl.class.getDeclaredMethod("appendReasoningCapped", StringBuilder.class, String.class);
            m.setAccessible(true);
            // 通过行为反推上限：填充直到停止追加
            StringBuilder probe = new StringBuilder();
            for (long i = 0; i < 100L * 1024 * 1024; i += 1024) {
                m.invoke(null, probe, repeat('x', 1024));
            }
            MAX_CHARS = probe.length();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean startsWithBuf(String s, StringBuilder buf) throws Exception {
        Method m = AiTemplateGenServiceImpl.class.getDeclaredMethod("startsWithBuf", String.class, StringBuilder.class, int.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, s, buf, buf.length());
    }

    private static void appendCapped(StringBuilder buf, String delta) throws Exception {
        Method m = AiTemplateGenServiceImpl.class.getDeclaredMethod("appendReasoningCapped", StringBuilder.class, String.class);
        m.setAccessible(true);
        m.invoke(null, buf, delta);
    }

    /**
     * 模拟修复后的 doOnNext 差分分支，返回 [推送的 delta, 缓冲快照]
     */
    private static String[] feed(StringBuilder buf, String rc) throws Exception {
        int prevLen = buf.length();
        boolean prefixMatch = rc.length() >= prevLen && startsWithBuf(rc, buf);
        if (prefixMatch && rc.length() > prevLen) {
            String delta = rc.substring(prevLen);
            appendCapped(buf, delta);
            return new String[]{delta};
        } else if (!prefixMatch) {
            appendCapped(buf, rc);
            return new String[]{rc};
        }
        return new String[]{null}; // 重复帧：跳过
    }

    @Test
    void cumulativeMode_pushesDeltasOnly() throws Exception {
        StringBuilder buf = new StringBuilder();
        // 累积模式：chunk1="AB"，chunk2="ABCD"（携带全文），chunk3="ABCDEFG"
        assertEquals("AB", feed(buf, "AB")[0]);
        assertEquals("CD", feed(buf, "ABCD")[0]);
        assertEquals("EFG", feed(buf, "ABCDEFG")[0]);
        assertEquals("ABCDEFG", buf.toString());
    }

    @Test
    void incrementalMode_appendsAll() throws Exception {
        StringBuilder buf = new StringBuilder();
        // 纯增量模式：每 chunk 是全新文本段（不以缓冲开头）
        assertEquals("Hello ", feed(buf, "Hello ")[0]);
        assertEquals("world", feed(buf, "world")[0]);
        assertEquals("!", feed(buf, "!")[0]);
        assertEquals("Hello world!", buf.toString());
    }

    @Test
    void duplicateFrame_skipped() throws Exception {
        StringBuilder buf = new StringBuilder();
        feed(buf, "AB");
        // 重复帧：与缓冲完全相同（累积模式无新增）
        assertNullFrame(feed(buf, "AB"));
        assertEquals("AB", buf.toString());
    }

    private void assertNullFrame(String[] result) {
        assertTrue(result[0] == null, "重复帧应被跳过，实际推送: " + result[0]);
    }

    @Test
    void mixedModeDrift_fallsBackToIncremental() throws Exception {
        StringBuilder buf = new StringBuilder();
        feed(buf, "AB");
        // 模式突变：新 chunk 不以缓冲开头 → 按增量容忍处理
        assertEquals("XY", feed(buf, "XY")[0]);
        assertEquals("ABXY", buf.toString());
    }

    @Test
    void bufferHardCap_stopsGrowingButKeepsPushingDeltas() throws Exception {
        StringBuilder buf = new StringBuilder();
        // 逐块填充至超限：缓冲停在 MAX_CHARS，推送不受影响。
        // 注意每块内容必须互不相同（带序号前缀）：相同内容的连续 chunk 会被
        // 正确判定为"累积模式重复帧"而跳过，走不到缓冲增长路径
        int pushed = 0;
        int blocks = MAX_CHARS / 1024 + 10;
        for (int i = 0; i < blocks; i++) {
            String chunk = String.format("%04d-", i) + repeat('r', 1024 - 5);
            String[] out = feed(buf, chunk);
            pushed += out[0] == null ? 0 : out[0].length();
        }
        assertEquals(MAX_CHARS, buf.length(), "缓冲必须停在上限");
        // 增量模式下每块都推送（前缀不匹配），推送总量不受缓冲上限影响
        assertEquals(blocks * 1024, pushed);
    }

    @Test
    void capBoundary_partialAppend() throws Exception {
        // 上限非整块对齐：最后一块只追加到上限为止
        StringBuilder buf = new StringBuilder();
        appendCapped(buf, repeat('a', MAX_CHARS - 10));
        assertEquals(MAX_CHARS - 10, buf.length());
        appendCapped(buf, "0123456789ABCDEF"); // 只应追加前 10 个
        assertEquals(MAX_CHARS, buf.length());
        assertEquals(repeat('a', MAX_CHARS - 10) + "0123456789", buf.toString());
    }

    @Test
    void startsWithBuf_basics() throws Exception {
        StringBuilder buf = new StringBuilder("abc");
        assertTrue(startsWithBuf("abcdef", buf));
        assertTrue(startsWithBuf("abc", buf));      // 相等也算前缀匹配（重复帧判定依赖）
        assertFalse(startsWithBuf("abx", buf));
        assertFalse(startsWithBuf("ab", buf));      // 短于缓冲
        assertTrue(startsWithBuf("", new StringBuilder())); // 空对空
        assertTrue(startsWithBuf("a", new StringBuilder())); // 空缓冲恒匹配
    }
}
