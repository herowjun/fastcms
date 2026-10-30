package probe;

import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.support.AiReasoningSupport;
import com.fastcms.ai.support.AiThinkingBudget;
import com.fastcms.ai.support.ReasoningStreamAccumulator;
import com.fastcms.entity.AiModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * S2（设计段）/ S9（文章段）思考兜底链路的真实端点验证（2026-10-29）
 *
 * <p>这两条链路<b>不走</b> {@code AiTemplateGenServiceImpl.callModelRound}（那是管线专用），
 * 各自有自己的流式执行器。本探针验证它们新增的三层防线在真实端点上确实有效：</p>
 * <ol>
 *   <li><b>提示词思考预算约束</b>（{@link AiThinkingBudget#applyBudgetHint}）能压低思考量；</li>
 *   <li><b>三信号判定</b>（{@code finish_reason=length} + 正文 0 + 有思考）在流式下能命中；</li>
 *   <li><b>关思考兜底</b>（{@link AiModelConfigServiceImpl#thinkingOffOptions}，
 *       S2/S9 现在共用的生产方法）能稳定产出正文——且<b>保留原上限不翻倍</b>。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp ... probe.ThinkingOffFallbackProbe [scenario] [maxTokens]}</p>
 */
public final class ThinkingOffFallbackProbe {

    private static final String BASE_URL = "http://203.176.93.143:30120/v1";
    private static final String MODEL = "Qwen3.6-27B";

    /** 设计段形态的提示词（§FILE:=== 文件块协议） */
    private static final String DESIGN_TASK = """
            请为「极简科技风」的首页写一份完整设计稿，输出为 ===FILE: design/index.html=== 文件块，
            包含导航、主视觉（含一句主标题与副标题）、三栏特性介绍、页脚四个区块。
            文件块格式：
            ===FILE: <相对路径>===
            <文件内容>

            要求：HTML 结构完整，含 <head>、<meta viewport>、语义化标签。
            """;

    /** 文章段形态的提示词 */
    private static final String ARTICLE_TASK = """
            请写一篇 800 字的产品解读文章《轻量级 CMS 的取舍》，结构为：引言、三个取舍点（各含
            一段说明与一个具体例子）、结语。只输出文章正文，不要用 markdown 围栏。
            """;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "all";
        int maxTokens = args.length > 1 ? Integer.parseInt(args[1]) : 512;

        if ("design".equals(scenario) || "all".equals(scenario)) {
            probeDesignFallback(maxTokens);
        }
        if ("article".equals(scenario) || "all".equals(scenario)) {
            probeArticleFallback(maxTokens);
        }
        if ("hint".equals(scenario) || "all".equals(scenario)) {
            probeHintEffect(maxTokens);
        }
    }

    // ================= S2 设计段兜底 =================

    /**
     * 复刻 S2 的完整兜底链路：
     * <ol>
     *   <li>首轮：设计段 options（推理档 low）+ 注入思考预算约束</li>
     *   <li>判定：{@code finish_reason=length} + 正文 0 + 有思考</li>
     *   <li>兜底：{@code thinkingOffOptions}（保留原上限）重跑一次</li>
     * </ol>
     */
    private static void probeDesignFallback(int maxTokens) throws Exception {
        System.out.println("\n================ S2 设计段：思考吃满 → 关思考兜底 ================");
        System.out.println("预算 = " + maxTokens + " | 档位 = low（设计段默认）");

        AiModelConfig config = baseConfig(maxTokens);

        // 首轮：与 MockupDesignService.buildDesignOptions 同口径（推理档 + 原上限）
        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.optionsBuilder(config,
                AiReasoningSupport.SCENE_REASONING);
        builder.maxTokens(maxTokens);
        OpenAiChatOptions designOptions = builder.build();

        // 注入思考预算约束（与 MockupDesignService.callDesignModel 同口径）
        List<Message> messages = AiThinkingBudget.applyBudgetHint(
                List.of(new SystemMessage("你是资深前端设计师，只输出文件块，不要解释。"),
                        new UserMessage(DESIGN_TASK)), designOptions);

        Round first = stream(messages, designOptions);
        printRound("首轮（注入思考预算约束）", first);

        boolean flooded = AiThinkingBudget.isThinkingFlooded(first.finishReason, first.contentLength,
                first.reasoningLength, first.reasoningTokens);
        System.out.println(">>> 三信号判定 = " + flooded);

        if (!flooded) {
            System.out.println(">>> ℹ️ 本轮未吃满（端点波动或提示词约束已压住思考），"
                    + "兜底分支未被触发 —— 仍跑一次兜底轮做对照，验证兜底本身有效");
        }

        // 兜底轮：S2 现在走 AiModelConfigServiceImpl.thinkingOffOptions（保留原上限，不翻倍）
        OpenAiChatOptions offOptions = AiModelConfigServiceImpl.thinkingOffOptions(config, designOptions);
        System.out.println(">>> 兜底 options: reasoningEffort=" + offOptions.getReasoningEffort()
                + " extraBody=" + offOptions.getExtraBody()
                + " maxTokens=" + offOptions.getMaxTokens() + "（原上限，未翻倍）");

        Round retry = stream(messages, offOptions);
        printRound("兜底轮（关思考，保留原上限）", retry);

        System.out.println(retry.contentLength > 0
                ? ">>> ✅ S2 兜底有效：关思考后正文稳定产出（预算全归正文）"
                : ">>> ❌ S2 兜底无效：关思考后仍无正文");
    }

    // ================= S9 文章段兜底 =================

    /**
     * 复刻 S9 的完整兜底链路（结构同 S2，任务形态不同）
     */
    private static void probeArticleFallback(int maxTokens) throws Exception {
        System.out.println("\n================ S9 文章段：思考吃满 → 关思考兜底 ================");
        System.out.println("预算 = " + maxTokens + " | 档位 = low（文章段默认）");

        AiModelConfig config = baseConfig(maxTokens);

        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.optionsBuilder(config,
                AiReasoningSupport.SCENE_REASONING);
        builder.maxTokens(maxTokens);
        OpenAiChatOptions runtimeOptions = builder.build();

        List<Message> messages = AiThinkingBudget.applyBudgetHint(
                List.of(new SystemMessage("你是资深技术作者，只输出文章正文。"),
                        new UserMessage(ARTICLE_TASK)), runtimeOptions);

        Round first = stream(messages, runtimeOptions);
        printRound("首轮（注入思考预算约束）", first);

        boolean flooded = AiThinkingBudget.isThinkingFlooded(first.finishReason, first.contentLength,
                first.reasoningLength, first.reasoningTokens);
        System.out.println(">>> 三信号判定 = " + flooded);

        if (!flooded) {
            System.out.println(">>> ℹ️ 本轮未吃满（端点波动或提示词约束已压住思考），"
                    + "兜底分支未被触发 —— 仍跑一次兜底轮做对照，验证兜底本身有效");
        }

        OpenAiChatOptions offOptions = AiModelConfigServiceImpl.thinkingOffOptions(config, runtimeOptions);
        System.out.println(">>> 兜底 options: reasoningEffort=" + offOptions.getReasoningEffort()
                + " extraBody=" + offOptions.getExtraBody()
                + " maxTokens=" + offOptions.getMaxTokens() + "（原上限，未翻倍）");

        Round retry = stream(messages, offOptions);
        printRound("兜底轮（关思考，保留原上限）", retry);

        System.out.println(retry.contentLength > 0
                ? ">>> ✅ S9 兜底有效：关思考后正文稳定产出（预算全归正文）"
                : ">>> ❌ S9 兜底无效：关思考后仍无正文");
    }

    // ================= 提示词约束的效果 =================

    /**
     * 量化「提示词思考预算约束」在 S2/S9 形态下的效果
     */
    private static void probeHintEffect(int maxTokens) throws Exception {
        System.out.println("\n================ 提示词思考预算约束的效果（S2/S9 形态） ================");
        AiModelConfig config = baseConfig(maxTokens);
        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.optionsBuilder(config,
                AiReasoningSupport.SCENE_REASONING);
        builder.maxTokens(maxTokens);
        OpenAiChatOptions options = builder.build();

        List<Message> plain = List.of(
                new SystemMessage("你是资深前端设计师，只输出文件块，不要解释。"),
                new UserMessage(DESIGN_TASK));
        List<Message> hinted = AiThinkingBudget.applyBudgetHint(plain, options);

        Round withoutHint = stream(plain, options);
        Round withHint = stream(hinted, options);

        System.out.println("--- 不加约束 ---");
        System.out.println("思考 = " + withoutHint.reasoningLength + " 字符 | 正文 = "
                + withoutHint.contentLength + " 字符 | finish = " + withoutHint.finishReason
                + " | 耗时 " + withoutHint.elapsedMs + " ms");
        System.out.println("--- 加约束 ---");
        System.out.println("思考 = " + withHint.reasoningLength + " 字符 | 正文 = "
                + withHint.contentLength + " 字符 | finish = " + withHint.finishReason
                + " | 耗时 " + withHint.elapsedMs + " ms");

        if (withoutHint.reasoningLength > 0) {
            int delta = (int) Math.round((withoutHint.reasoningLength - withHint.reasoningLength) * 100.0
                    / withoutHint.reasoningLength);
            System.out.println(">>> 思考量变化 = " + delta + "%（负数=减少）");
        }
        System.out.println(">>> 正文从 " + withoutHint.contentLength + " → " + withHint.contentLength
                + " 字符（思考吃满时正文为 0，约束后恢复产出即为收益）");
    }

    // ================= 调用封装 =================

    private static AiModelConfig baseConfig(int maxTokens) {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl(BASE_URL);
        config.setModel(MODEL);
        config.setApiKey("probe-not-used");
        config.setTemperature(0.3);
        config.setMaxTokens(maxTokens);
        return config;
    }

    /**
     * 一轮流式调用的观测结果（流式形态与生产一致）
     */
    private static final class Round {
        String finishReason;
        int contentLength;
        int reasoningLength;
        Integer reasoningTokens;
        long elapsedMs;
    }

    /**
     * 流式调用一轮（与生产 S2/S9 的流式执行器同口径：差分累加器 + finish_reason 采集）
     */
    private static Round stream(List<Message> messages, OpenAiChatOptions options) {
        ChatClient client = ChatClient.builder(
                OpenAiChatModel.builder().options(options).build()).build();

        ReasoningStreamAccumulator reasoningAcc = new ReasoningStreamAccumulator();
        Round r = new Round();
        int[] reasoningChars = {0};
        int[] contentChars = {0};

        long t0 = System.currentTimeMillis();
        ChatResponse last = null;
        try {
            last = client.prompt(new org.springframework.ai.chat.prompt.Prompt(messages, options))
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getResult() != null && resp.getResult().getMetadata() != null) {
                            String fr = resp.getResult().getMetadata().getFinishReason();
                            if (fr != null && !fr.isBlank()) {
                                r.finishReason = fr;
                            }
                        }
                        if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                            return;
                        }
                        var output = resp.getResult().getOutput();
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
        r.elapsedMs = System.currentTimeMillis() - t0;
        r.contentLength = contentChars[0];
        r.reasoningLength = reasoningChars[0];

        // 思考 token 数（端点若上报）
        if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
            var usage = last.getMetadata().getUsage();
            if (usage.getNativeUsage() != null) {
                r.reasoningTokens = AiThinkingBudget.extractReasoningTokens(usage.getNativeUsage());
            }
        }
        return r;
    }

    private static void printRound(String label, Round r) {
        System.out.println("--- " + label + " ---");
        System.out.println("finish_reason = " + r.finishReason);
        System.out.println("正文长度     = " + r.contentLength + " 字符");
        System.out.println("思考长度     = " + r.reasoningLength + " 字符");
        if (r.reasoningTokens != null) {
            System.out.println("思考 token   = " + r.reasoningTokens);
        }
        System.out.println("耗时         = " + r.elapsedMs + " ms");
    }
}
