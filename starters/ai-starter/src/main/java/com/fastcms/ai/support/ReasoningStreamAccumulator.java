package com.fastcms.ai.support;

/**
 * 推理模型思考流（reasoningContent）透传累积器：统一归一三种端点透传形态 + 思考流重启
 *
 * <p>各 OpenAI 兼容端点对流式 {@code reasoning_content} 字段的透传形态不一，本类按 chunk
 * 逐一识别并返回"真实增量"，调用方只管推送返回值：</p>
 * <ul>
 *     <li><b>累积模式</b>：每 chunk 携带从头到当前的全文快照（Spring AI 透传的常见形态）。
 *     识别条件：chunk 以当前缓冲为前缀 → 只取差分追加；</li>
 *     <li><b>纯增量模式</b>：每 chunk 只携带新增片段。识别条件：既不匹配缓冲也不构成重启链
 *     → 整段追加；</li>
 *     <li><b>重复帧</b>：与上一 chunk（或缓冲）完全相同 → 跳过（返回 null）；</li>
 *     <li><b>思考流重启链</b>（本类存在的核心理由）：流式工具调用（如 load_skill）往返后
 *     模型开启新一轮调用，思考流从头开始。新一轮快照既不以旧缓冲为前缀、但延续上一 chunk 的
 *     增长链——若沿用旧的"不匹配即整段追加"策略，每个快照都会被整段追加，缓冲呈平方级膨胀
 *     （实测 16 秒内从正常思考膨胀至 256KB 保险丝误报"思考失控"）。重启链识别把差分基准
 *     切换为上一 chunk，新一轮按正常累积模式处理，多轮思考在缓冲中顺序拼接。</li>
 * </ul>
 *
 * <p><b>内存教训（native OOM 实例）</b>：透传累积值时思考文本增长到 L，逐 chunk 做
 * toString/startsWith/整体替换会产生多次全量复制（O(L²)），L 达 MB 级时海量 humongous
 * 分配使 G1 扩堆失控。本类前缀匹配走零分配 charAt 循环，命中后只追加差分。</p>
 *
 * <p><b>线程模型</b>：非线程安全。设计为单条 Reactor 流的 doOnNext 回调内串行调用
 * （chatResponse() 流按序派发），跨流勿复用。</p>
 */
public final class ReasoningStreamAccumulator {

    private final StringBuilder buf = new StringBuilder();
    private final int maxBufferChars;
    /** 上一 chunk 的 reasoningContent 原始值（重启链差分基准；null=尚未收到任何 chunk） */
    private String lastRc;

    /**
     * @param maxBufferChars 缓冲硬上限（字符数，达到后停止追加但 feed 仍返回增量供推送）；
     *                       {@code <=0} 表示不封顶（依赖调用方保险丝中止失控流）
     */
    public ReasoningStreamAccumulator(int maxBufferChars) {
        this.maxBufferChars = maxBufferChars;
    }

    /** 不封顶构造（调用方以保险丝兜底失控场景） */
    public ReasoningStreamAccumulator() {
        this(0);
    }

    /**
     * 喂入一个 chunk 的 reasoningContent 原始值
     *
     * @return 本 chunk 的真实增量（应作为流式增量推送；null=重复帧/空值，跳过）
     */
    public String feed(String rc) {
        if (rc == null || rc.isEmpty()) {
            return null;
        }
        // 重复帧：与上一 chunk 完全相同（网关重发同一快照）
        if (rc.equals(lastRc)) {
            return null;
        }
        int prevLen = buf.length();
        // 累积模式：chunk 以当前缓冲为前缀（含首 chunk 空缓冲恒匹配）
        if (rc.length() >= prevLen && startsWithBuf(rc, prevLen)) {
            if (rc.length() == prevLen) {
                // 与缓冲等长且前缀匹配 = 与已累积内容完全相同的重复帧
                lastRc = rc;
                return null;
            }
            String delta = rc.substring(prevLen);
            appendCapped(delta);
            lastRc = rc;
            return delta;
        }
        // 重启链：chunk 不匹配旧缓冲，但延续上一 chunk 的增长（工具往返/新一轮模型调用的
        // 思考流从头开始）——差分基准切换为上一 chunk，避免整段快照被重复追加
        if (lastRc != null && rc.length() > lastRc.length() && rc.startsWith(lastRc)) {
            String delta = rc.substring(lastRc.length());
            appendCapped(delta);
            lastRc = rc;
            return delta;
        }
        // 纯增量模式：整段作为增量
        appendCapped(rc);
        lastRc = rc;
        return rc;
    }

    /** 当前缓冲长度（受 maxBufferChars 封顶影响） */
    public int bufferLength() {
        return buf.length();
    }

    /** 归一后的思考全文（已去掉重启链造成的重复快照；受封顶影响可能只保留头部） */
    public String bufferContent() {
        return buf.toString();
    }

    private void appendCapped(String s) {
        if (maxBufferChars <= 0) {
            buf.append(s);
        } else if (buf.length() < maxBufferChars) {
            buf.append(s, 0, Math.min(s.length(), maxBufferChars - buf.length()));
        }
    }

    /** 零分配前缀匹配：s 的前 prefixLen 个字符与缓冲开头是否一致 */
    private boolean startsWithBuf(String s, int prefixLen) {
        for (int i = 0; i < prefixLen; i++) {
            if (s.charAt(i) != buf.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
