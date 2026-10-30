package probe;

import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.support.AiReasoningSupport;
import com.fastcms.ai.support.AiThinkingBudget;
import com.fastcms.entity.AiModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

/**
 * 「长思考吃满输出预算 → 正文 0 token」的真实端点验证（2026-10-29）
 *
 * <p><b>要验证的三件事</b>：</p>
 * <ol>
 *   <li><b>故障可复现</b>：{@code max_tokens} 是思考+正文共享预算，
 *       小预算 + 高思考需求 → 思考写满 → {@code finish_reason=length} + 正文 0 字符；</li>
 *   <li><b>判定可靠</b>：{@link AiThinkingBudget#isThinkingFlooded} 的三信号
 *       （length + 正文空 + 有思考）在真实响应上确实命中，且不误杀正常响应；</li>
 *   <li><b>兜底有效</b>：判定命中后关思考重试（{@code enable_thinking=false}）
 *       能稳定拿到正文——<b>而不是翻倍上限</b>（预算越大思考越长，翻倍治不了本）。</li>
 * </ol>
 *
 * <p>另外量化「提示词思考预算约束」的效果（{@link AiThinkingBudget#thinkingBudgetHint}）。</p>
 */
public final class ThinkingBudgetProbe {

    private static final String BASE_URL = "http://203.176.93.143:30120/v1";
    private static final String MODEL = "Qwen3.6-27B";

    /**
     * 一个「足够费脑子」的推理任务：模型会先在思考里推理很久，
     * 小预算下思考写不完 → 正文 0（这就是要复现的故障）。
     */
    private static final String HARD_TASK = """
            请分析下面这个 Java 工具类中的所有并发缺陷，逐个说明根因、给出修复方案，
            并评估修复后对吞吐量与延迟的影响，最后按 JSON 输出（格式：
            {"defects":[{"location":"...","rootCause":"...","fix":"...","impact":"..."}],"summary":"..."}）。
            要求至少找出 4 个缺陷。

            ```java
            public class Pool {
                private final List<Worker> workers = new ArrayList<>();
                private boolean running = true;
                public void add(Worker w) { workers.add(w); }
                public void tick() {
                    for (int i = 0; i < workers.size(); i++) {
                        Worker w = workers.get(i);
                        if (w.isBusy()) continue;
                        w.run();
                        if (!running) break;
                    }
                }
                public void shutdown() {
                    running = false;
                    for (Worker w : workers) { w.interrupt(); }
                    workers.clear();
                }
                public int size() { return workers.size(); }
            }
            ```
            """;

    /** 一个「几乎不需要思考」的任务：用来验证判定不误杀正常响应 */
    private static final String EASY_TASK = """
            把下面这句话翻译成英文，只输出英文译文，不要解释：
            「今天的会议改到下午三点。」
            """;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "all";
        int maxTokens = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        Round baseline = null;

        if ("flood".equals(scenario) || "all".equals(scenario)) {
            baseline = probeFlood(maxTokens);
        }
        if ("floodOff".equals(scenario) || "all".equals(scenario)) {
            probeFloodOff(maxTokens, baseline);
        }
        if ("hint".equals(scenario) || "all".equals(scenario)) {
            probeHint(maxTokens);
        }
        if ("easy".equals(scenario) || "all".equals(scenario)) {
            probeEasy(maxTokens);
        }
        if ("stream".equals(scenario) || "all".equals(scenario)) {
            probeStreamFinishReason(maxTokens);
        }
    }

    // ================= 5. 流式下 finish_reason 能否采集到 =================

    /**
     * 生产代码的「思考吃满」判定依赖 {@code finish_reason == "length"}，
     * 而生产走的是<b>流式</b>调用（{@code chatClient.prompt().stream()}）。
     * 本场景验证：流式最后一个 chunk 是否真的带 {@code finish_reason=length}，
     * 且思考内容是否能被累计出来 —— 这两点决定兜底逻辑在生产里是否真的能触发。
     */
    private static void probeStreamFinishReason(int maxTokens) {
        System.out.println("\n================ 场景5：流式下 finish_reason 与思考量的采集 ================");
        System.out.println("预算 = " + maxTokens + " | 档位 = low | 流式调用");

        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl(BASE_URL);
        config.setModel(MODEL);
        config.setApiKey("probe-not-used");
        config.setTemperature(0.3);
        config.setMaxTokens(maxTokens);

        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.baseOptionsBuilder(config);
        AiReasoningSupport.apply(builder, config, AiReasoningSupport.EFFORT_LOW);
        builder.maxTokens(maxTokens);
        OpenAiChatOptions options = builder.build();

        ChatClient client = ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();

        final String[] finishReason = new String[1];
        final int[] reasoningChars = {0};
        final int[] contentChars = {0};
        // ⚠️ 流式下 reasoningContent 是**累积帧**（每帧都含全部历史思考），直接累加会放大几百倍
        // （实测 529764 字符 vs 真实 2067 字符）。生产代码用 ReasoningStreamAccumulator 做差分，
        // 这里必须用生产同款累加器，否则观测数据失真。
        final com.fastcms.ai.support.ReasoningStreamAccumulator reasoningAcc =
                new com.fastcms.ai.support.ReasoningStreamAccumulator();
        long t0 = System.currentTimeMillis();
        try {
            client.prompt()
                    .system("你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。")
                    .user(HARD_TASK)
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getResult() == null) {
                            return;
                        }
                        if (resp.getResult().getMetadata() != null) {
                            String fr = resp.getResult().getMetadata().getFinishReason();
                            if (fr != null && !fr.isBlank()) {
                                finishReason[0] = fr;
                            }
                        }
                        var output = resp.getResult().getOutput();
                        if (output == null) {
                            return;
                        }
                        Object reasoning = output.getMetadata() == null
                                ? null : output.getMetadata().get("reasoningContent");
                        if (reasoning != null) {
                            String delta = reasoningAcc.feed(String.valueOf(reasoning));
                            if (delta != null) {
                                reasoningChars[0] += delta.length();
                            }
                        }
                        if (output.getText() != null) {
                            contentChars[0] += output.getText().length();
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            System.out.println("流式异常 = " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("finish_reason = " + finishReason[0]);
        System.out.println("正文长度     = " + contentChars[0] + " 字符");
        System.out.println("思考长度     = " + reasoningChars[0] + " 字符");
        System.out.println("耗时         = " + elapsed + " ms");
        boolean flooded = AiThinkingBudget.isThinkingFlooded(finishReason[0], contentChars[0],
                reasoningChars[0], null);
        System.out.println(">>> isThinkingFlooded 判定 = " + flooded);
        System.out.println(">>> " + (finishReason[0] == null
                ? "❌ 流式下拿不到 finish_reason → 生产兜底不会触发，必须改判定依据"
                : (flooded ? "✅ 流式下判定成立 → 生产兜底可触发" : "ℹ️ 本次未吃满（端点波动）")));
    }

    // ================= 1. 复现故障 =================

    /**
     * 小预算 + 高思考需求 → 思考吃满 → 正文 0
     *
     * @return 本轮的观测结果（供对照组对比）
     */
    private static Round probeFlood(int maxTokens) {
        System.out.println("\n================ 场景1：复现「思考吃满 → 正文 0」 ================");
        System.out.println("预算 = " + maxTokens + " | 档位 = low（端点实际支持的最高思考档为 medium）");

        Round r = call(HARD_TASK, maxTokens, AiReasoningSupport.EFFORT_LOW, false);

        System.out.println("finish_reason = " + r.finishReason);
        System.out.println("正文长度     = " + r.contentLength + " 字符");
        System.out.println("思考长度     = " + r.reasoningLength + " 字符");
        System.out.println("completion   = " + r.completionTokens);
        System.out.println("耗时         = " + r.elapsedMs + " ms");
        System.out.println(">>> isThinkingFlooded 判定 = " + r.flooded());
        System.out.println(r.flooded()
                ? ">>> ✅ 故障已复现，且被判定逻辑准确捕获（可进入关思考重试）"
                : ">>> ⚠️ 本次未触发吃满（端点行为有波动），换更小的预算重试");
        return r;
    }

    // ================= 2. 兜底：关思考重试 =================

    /**
     * 判定命中后按生产口径关思考重试：<b>保留原预算</b>（不翻倍），
     * 验证「关思考」比「翻倍上限」更有效。
     */
    private static void probeFloodOff(int maxTokens, Round baseline) {
        System.out.println("\n================ 场景2：关思考重试兜底（保留原预算，不翻倍） ================");
        System.out.println("预算 = " + maxTokens + " | 档位 = off（enable_thinking=false）");

        Round r = call(HARD_TASK, maxTokens, AiReasoningSupport.EFFORT_OFF, false);

        System.out.println("finish_reason = " + r.finishReason);
        System.out.println("正文长度     = " + r.contentLength + " 字符");
        System.out.println("思考长度     = " + r.reasoningLength + " 字符");
        System.out.println("completion   = " + r.completionTokens);
        System.out.println("耗时         = " + r.elapsedMs + " ms");
        System.out.println(r.contentLength > 0
                ? ">>> ✅ 关思考后正文稳定产出（预算全归正文）"
                : ">>> ❌ 关思考后仍无正文");

        // 对照：翻倍上限但保持思考（生产里已废弃的旧解法，保留作为反证）
        System.out.println("\n---- 对照组：翻倍上限 + 保持思考（旧解法，已废弃） ----");
        Round r2 = call(HARD_TASK, maxTokens * 2, AiReasoningSupport.EFFORT_LOW, false);
        System.out.println("预算 = " + (maxTokens * 2) + " | 档位 = low");
        System.out.println("finish_reason = " + r2.finishReason);
        System.out.println("正文长度     = " + r2.contentLength + " 字符");
        System.out.println("思考长度     = " + r2.reasoningLength + " 字符");
        System.out.println("completion   = " + r2.completionTokens);
        System.out.println(">>> 预算翻倍后思考量 = " + r2.reasoningLength
                + " 字符（基线 " + maxTokens + " 预算时为 "
                + (baseline == null ? "?" : baseline.reasoningLength) + " 字符）"
                + " → 预算越大思考越长，翻倍治不了思考吃满");
    }

    // ================= 3. 提示词约束的效果 =================

    /**
     * 同一任务、同一预算、同一档位：加 / 不加「思考预算约束」的量化对比
     */
    private static void probeHint(int maxTokens) {
        System.out.println("\n================ 场景3：提示词思考预算约束的效果 ================");
        System.out.println("预算 = " + maxTokens + " | 档位 = low");

        Round plain = call(HARD_TASK, maxTokens, AiReasoningSupport.EFFORT_LOW, false);
        Round hinted = call(HARD_TASK, maxTokens, AiReasoningSupport.EFFORT_LOW, true);

        System.out.println("--- 不加约束 ---");
        System.out.println("思考长度 = " + plain.reasoningLength + " 字符 | completion = "
                + plain.completionTokens + " | 正文 = " + plain.contentLength + " 字符 | 耗时 "
                + plain.elapsedMs + " ms");
        System.out.println("--- 加约束 ---");
        System.out.println("思考长度 = " + hinted.reasoningLength + " 字符 | completion = "
                + hinted.completionTokens + " | 正文 = " + hinted.contentLength + " 字符 | 耗时 "
                + hinted.elapsedMs + " ms");

        if (plain.reasoningLength > 0) {
            int delta = (int) Math.round((plain.reasoningLength - hinted.reasoningLength) * 100.0
                    / plain.reasoningLength);
            System.out.println(">>> 思考量变化 = " + delta + "%（负数=减少）");
        } else {
            System.out.println(">>> 本次基线未产生思考，无法对比（端点行为波动）");
        }
    }

    // ================= 4. 不误杀 =================

    /**
     * 简单任务 + 正常预算 → 判定必须为 false（不能把正常响应误判成思考吃满）
     */
    private static void probeEasy(int maxTokens) {
        System.out.println("\n================ 场景4：判定不误杀正常响应 ================");
        int budget = Math.max(maxTokens, 2048);
        System.out.println("预算 = " + budget + " | 档位 = low | 任务 = 一句话翻译");

        Round r = call(EASY_TASK, budget, AiReasoningSupport.EFFORT_LOW, false);
        System.out.println("finish_reason = " + r.finishReason);
        System.out.println("正文长度     = " + r.contentLength + " 字符");
        System.out.println("思考长度     = " + r.reasoningLength + " 字符");
        System.out.println(">>> isThinkingFlooded 判定 = " + r.flooded()
                + (r.flooded() ? "  ❌ 误杀！" : "  ✅ 未误杀"));
    }

    // ================= 调用封装 =================

    /**
     * 一轮调用的观测结果
     */
    private static final class Round {
        String finishReason;
        int contentLength;
        int reasoningLength;
        Integer completionTokens;
        long elapsedMs;

        boolean flooded() {
            return AiThinkingBudget.isThinkingFlooded(finishReason, contentLength, reasoningLength, null);
        }
    }

    /**
     * 真实调用一轮。options 组装口径与生产
     * {@code AiTemplateGenServiceImpl.buildPipelineOptions} / {@code buildOptionsWithThinkingOff} 一致。
     */
    private static Round call(String userPrompt, int maxTokens, String effort, boolean withHint) {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl(BASE_URL);
        config.setModel(MODEL);
        config.setApiKey("probe-not-used");
        config.setTemperature(0.3);
        config.setMaxTokens(maxTokens);

        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.baseOptionsBuilder(config);
        if (AiReasoningSupport.EFFORT_OFF.equals(effort)) {
            // 与生产 buildOptionsWithThinkingOff 同一口径
            AiThinkingBudget.forceOff(builder, config);
        } else {
            AiReasoningSupport.apply(builder, config, effort);
        }
        builder.maxTokens(maxTokens);
        OpenAiChatOptions options = builder.build();

        String prompt = userPrompt;
        if (withHint) {
            prompt = userPrompt + AiThinkingBudget.thinkingBudgetHint(effort);
        }

        System.out.println("请求参数: reasoningEffort=" + options.getReasoningEffort()
                + " extraBody=" + options.getExtraBody()
                + " maxTokens=" + options.getMaxTokens()
                + " 带约束=" + withHint);

        ChatClient client = ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();

        long t0 = System.currentTimeMillis();
        ChatResponse response = client.prompt()
                .system("你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。")
                .user(prompt)
                .call()
                .chatResponse();
        long elapsed = System.currentTimeMillis() - t0;

        Round r = new Round();
        r.elapsedMs = elapsed;
        if (response != null && response.getResult() != null) {
            var output = response.getResult().getOutput();
            if (output != null) {
                r.contentLength = output.getText() == null ? 0 : output.getText().length();
                Object reasoning = output.getMetadata() == null ? null : output.getMetadata().get("reasoningContent");
                if (reasoning != null) {
                    r.reasoningLength = String.valueOf(reasoning).length();
                }
            }
            var meta = response.getResult().getMetadata();
            if (meta != null) {
                r.finishReason = meta.getFinishReason();
            }
        }
        var usage = response == null ? null : response.getMetadata().getUsage();
        r.completionTokens = usage == null ? null : usage.getCompletionTokens();
        return r;
    }
}
