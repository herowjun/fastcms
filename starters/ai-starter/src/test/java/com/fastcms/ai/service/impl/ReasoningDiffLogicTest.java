package com.fastcms.ai.service.impl;

import com.fastcms.ai.support.ReasoningStreamAccumulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 思考流差分逻辑（{@link ReasoningStreamAccumulator}）的行为验证
 *
 * <p>历史演进：最初每 chunk 做 toString/startsWith/整体替换共 5 次全量复制（O(L²)），
 * 推理模型重复循环时把 G1 堆推爆（native OOM 实例）——改零分配前缀匹配 + 差分追加。
 * 后实测发现第四种形态<b>思考流重启链</b>：流式工具往返（load_skill）后模型开启新一轮
 * 调用，思考流从头开始。新一轮快照既不以旧缓冲为前缀，旧逻辑"不匹配即整段追加"
 * 使每个快照都被全量追加，缓冲呈平方级膨胀（16 秒内从正常思考膨胀至 256KB 保险丝
 * 误报"思考失控"）。累积器把差分基准切换为上一 chunk，本轮测试覆盖全部四种形态。</p>
 */
class ReasoningDiffLogicTest {

    /** 模拟"喂入一个 chunk"并返回应推送的增量（feed 的直通封装，语义见被测类） */
    private static String feed(ReasoningStreamAccumulator acc, String rc) {
        return acc.feed(rc);
    }

    // ==================== 三种既有形态（回归） ====================

    @Test
    void cumulativeMode_pushesDeltasOnly() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        // 累积模式：每 chunk 携带从头到当前的全文快照（Spring AI 透传的常见形态）
        assertEquals("AB", feed(acc, "AB"));
        assertEquals("CD", feed(acc, "ABCD"));
        assertEquals("EFG", feed(acc, "ABCDEFG"));
        assertEquals("ABCDEFG", acc.bufferContent());
    }

    @Test
    void incrementalMode_appendsAll() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        // 纯增量模式：每 chunk 只携带新增片段（不以缓冲开头、不延续上一 chunk）
        assertEquals("Hello ", feed(acc, "Hello "));
        assertEquals("world", feed(acc, "world"));
        assertEquals("!", feed(acc, "!"));
        assertEquals("Hello world!", acc.bufferContent());
    }

    @Test
    void duplicateFrame_skipped() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        feed(acc, "AB");
        // 重复帧：与上一 chunk 完全相同（网关重发同一快照）
        assertNull(feed(acc, "AB"), "重复帧应被跳过");
        assertEquals("AB", acc.bufferContent());
        // 累积模式下的重复帧：快照与已累积缓冲完全相同
        feed(acc, "ABCD");
        assertNull(feed(acc, "ABCD"), "与缓冲等长且前缀匹配 = 重复帧，应被跳过");
        assertEquals("ABCD", acc.bufferContent());
    }

    @Test
    void mixedModeDrift_fallsBackToIncremental() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        feed(acc, "AB");
        // 模式突变：新 chunk 与缓冲和上一 chunk 均无延续关系 → 按增量容忍处理
        assertEquals("XY", feed(acc, "XY"));
        assertEquals("ABXY", acc.bufferContent());
    }

    // ==================== 重启链（本次修复的核心场景） ====================

    @Test
    void restartChain_noSquareBlowup() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        // 第一轮思考（工具往返前）：正常累积快照
        assertEquals("老思", feed(acc, "老思"));
        assertEquals("考AB", feed(acc, "老思考AB"));

        // 工具往返（load_skill）→ 模型新一轮调用，思考流从头开始：
        // 新轮快照既不以旧缓冲为前缀，也整体重发了多次
        assertEquals("新思", feed(acc, "新思"));
        // 关键断言：第二个新轮快照延续了上一 chunk（而非旧缓冲），
        // 只应推送差分——旧逻辑此处会整段追加"新思考XY"造成重复
        assertEquals("考XY", feed(acc, "新思考XY"));
        assertEquals("更", feed(acc, "新思考XY更"));
        assertEquals("多", feed(acc, "新思考XY更多"));

        // 缓冲 = 旧轮思考 + 新轮思考顺序拼接，无重复快照
        assertEquals("老思考AB新思考XY更多", acc.bufferContent());
    }

    @Test
    void restartChain_firstSnapshot_afterLongOldRound() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        String oldRound = "旧轮思考".repeat(2000);
        // 旧轮累积到相当规模后重启
        feed(acc, oldRound.substring(0, 5000));
        feed(acc, oldRound);
        // 新轮首个快照：不匹配旧缓冲、不延续上一 chunk → 整段作为增量（新思考就是新内容）
        assertEquals("重启", feed(acc, "重启"));
        // 新轮第二个快照延续上一 chunk → 只推差分
        assertEquals("后的思考", feed(acc, "重启后的思考"));
        // 平方级膨胀防护：总缓冲长度 = 两轮思考之和，不含任何快照重复
        assertEquals(5000 + (oldRound.length() - 5000) + "重启后的思考".length(), acc.bufferLength());
    }

    @Test
    void restartChain_duplicateResendOfNewRoundSnapshot() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        feed(acc, "旧思考");
        feed(acc, "新思考A");
        // 网关重发新轮同一快照：与上一 chunk 完全相同 → 重复帧跳过（不落纯增量分支）
        assertNull(feed(acc, "新思考A"));
        feed(acc, "新思考AB");
        assertEquals("旧思考新思考AB", acc.bufferContent());
    }

    // ==================== 缓冲封顶 ====================

    @Test
    void bufferHardCap_stopsGrowingButKeepsReturningDeltas() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator(2048);
        // 纯增量模式灌入超限内容：缓冲停在 2048，feed 仍返回增量供推送
        long pushed = 0;
        for (int i = 0; i < 8; i++) {
            String chunk = String.format("%04d-", i) + "r".repeat(1024 - 5);
            String delta = feed(acc, chunk);
            pushed += delta == null ? 0 : delta.length();
        }
        assertEquals(2048, acc.bufferLength(), "缓冲必须停在上限");
        assertEquals(8 * 1024, pushed, "推送增量不受缓冲上限影响");
    }

    @Test
    void capBoundary_cumulativeModeStillDiffsCorrectly() {
        // 封顶后的累积快照仍以缓冲头部为前缀（缓冲只保留头部），差分计算不受影响
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator(8);
        assertEquals("12345678", feed(acc, "12345678"));
        assertNull(feed(acc, "12345678"), "封顶后等长快照 = 重复帧");
        // 快照超出封顶：前 8 字符仍前缀匹配，差分 = 超出部分
        assertEquals("90", feed(acc, "1234567890"));
        assertEquals(8, acc.bufferLength(), "缓冲保持封顶值");
    }

    @Test
    void blankInputs_skipped() {
        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        assertNull(feed(acc, null));
        assertNull(feed(acc, ""));
        assertEquals(0, acc.bufferLength());
    }
}
