package com.fastcms.ai.support;

import com.fastcms.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考预算控制器行为验证。
 *
 * <p>背景（2026-10-29 自建 Qwen 端点实测）：{@code max_tokens} 是<b>思考 + 正文共享</b>
 * 的总预算，且端点不支持任何单独的思考预算参数；更糟的是<b>预算越大模型思考越长</b>。
 * 于是「思考吃满 → 正文 0 token」是真实故障，需要三层防线：</p>
 * <ol>
 *   <li>提示词约束（{@link AiThinkingBudget#thinkingBudgetHint}）—— 实测思考量 -72%；</li>
 *   <li>档位归一（{@link AiReasoningSupport}）—— 避免被网关 400 / 避免思考爆量；</li>
 *   <li>降档重试（{@link AiThinkingBudget#isThinkingFlooded} + {@code forceOff}）—— 确定性兜底。</li>
 * </ol>
 */
class AiThinkingBudgetTest {

    // ===== 思考吃满判定（三信号必须同时成立） =====

    @Test
    void floodedRequiresFinishReasonLengthEmptyBodyAndRealReasoning() {
        // 三信号齐备 → 判定思考吃满
        assertThat(AiThinkingBudget.isThinkingFlooded("length", 0, 7935, null)).isTrue();
        // 思考 token 上报时同样成立
        assertThat(AiThinkingBudget.isThinkingFlooded("length", 0, 0, 4096)).isTrue();
    }

    @Test
    void notFloodedWhenAnyOfThreeSignalsMissing() {
        // 未撞上限（正常结束）
        assertThat(AiThinkingBudget.isThinkingFlooded("stop", 0, 7935, null)).isFalse();
        assertThat(AiThinkingBudget.isThinkingFlooded(null, 0, 7935, null)).isFalse();
        // 有正文（哪怕也撞了上限，那是"正文被截断"而不是"思考吃满"）
        assertThat(AiThinkingBudget.isThinkingFlooded("length", 128, 7935, null)).isFalse();
        // 完全没有思考（那是模型本身没输出）
        assertThat(AiThinkingBudget.isThinkingFlooded("length", 0, 0, null)).isFalse();
        assertThat(AiThinkingBudget.isThinkingFlooded("length", 0, 0, 0)).isFalse();
    }

    @Test
    void floodedToleratesFinishReasonCasingAndWhitespace() {
        assertThat(AiThinkingBudget.isThinkingFlooded("LENGTH", 0, 100, null)).isTrue();
        assertThat(AiThinkingBudget.isThinkingFlooded(" length ", 0, 100, null)).isTrue();
    }

    // ===== 降档 =====

    @Test
    void degradeStepsDownOneLevelAndStopsAtOff() {
        assertThat(AiThinkingBudget.degradeEffort("medium")).isEqualTo("low");
        assertThat(AiThinkingBudget.degradeEffort("low")).isEqualTo("off");
        // 已是最低的 off，不再降（避免无限重试）
        assertThat(AiThinkingBudget.degradeEffort("off")).isEqualTo("off");
        assertThat(AiThinkingBudget.degradeEffort(null)).isEqualTo("");
    }

    // ===== 思考安全垫 =====

    @Test
    void reserveIsZeroForOffAndSmallForOtherEfforts() {
        // 关思考 → 没有思考要预留
        assertThat(AiThinkingBudget.reserveFor("off")).isZero();
        // 其它档位给少量余量（端点上预算越大思考越长，垫子必须小）
        assertThat(AiThinkingBudget.reserveFor("low")).isEqualTo(AiThinkingBudget.THINKING_RESERVE_TOKENS);
        assertThat(AiThinkingBudget.reserveFor("medium")).isEqualTo(AiThinkingBudget.THINKING_RESERVE_TOKENS_HIGH);
        assertThat(AiThinkingBudget.reserveFor("xhigh")).isEqualTo(AiThinkingBudget.THINKING_RESERVE_TOKENS_HIGH);
        // 未知档位走低档垫子
        assertThat(AiThinkingBudget.reserveFor("whatever")).isEqualTo(AiThinkingBudget.THINKING_RESERVE_TOKENS);
    }

    @Test
    void reserveStaysSmallEnoughNotToProvokeThinkingBloat() {
        // 端点实测：预算越大思考越长 → 垫子必须远小于正文预算（动辄 20000+）
        assertThat(AiThinkingBudget.THINKING_RESERVE_TOKENS_HIGH).isLessThanOrEqualTo(2048);
        assertThat(AiThinkingBudget.THINKING_RESERVE_TOKENS).isLessThanOrEqualTo(1024);
    }

    @Test
    void reserveOutputBudgetAddsMarginOnlyForPositiveNeed() {
        assertThat(AiThinkingBudget.reserveOutputBudget(20000, "low"))
                .isEqualTo(20000 + AiThinkingBudget.THINKING_RESERVE_TOKENS);
        assertThat(AiThinkingBudget.reserveOutputBudget(20000, "off")).isEqualTo(20000);
        assertThat(AiThinkingBudget.reserveOutputBudget(0, "low")).isZero();
        assertThat(AiThinkingBudget.reserveOutputBudget(-1, "low")).isZero();
    }

    @Test
    void applyReserveKeepsNullAndNonPositiveBudgetUntouched() {
        assertThat(AiThinkingBudget.applyReserve(null, "low")).isNull();
        assertThat(AiThinkingBudget.applyReserve(0, "low")).isZero();
        assertThat(AiThinkingBudget.applyReserve(8192, "low"))
                .isEqualTo(8192 + AiThinkingBudget.THINKING_RESERVE_TOKENS);
        assertThat(AiThinkingBudget.applyReserve(8192, "off")).isEqualTo(8192);
    }

    // ===== 提示词约束 =====

    @Test
    void hintIsEmptyWhenThinkingDisabled() {
        assertThat(AiThinkingBudget.thinkingBudgetHint("off")).isEmpty();
        assertThat(AiThinkingBudget.thinkingBudgetHint(null)).isNotEmpty();
    }

    @Test
    void hintCarriesMarkerAndBudgetWarning() {
        String hint = AiThinkingBudget.thinkingBudgetHint("low");
        assertThat(hint).contains(AiThinkingBudget.HINT_MARKER);
        assertThat(hint).contains("500 字");
        // 明确告知"思考与输出共享预算"——模型需要知道约束的原因才会遵守
        assertThat(hint).contains("共用");
    }

    // ===== 是否已关思考（决定要不要拼约束） =====

    @Test
    void detectsThinkingOffFromQwenChatTemplateKwargs() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)))
                .build();
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void detectsThinkingOffFromMinimalReasoningEffort() {
        OpenAiChatOptions options = OpenAiChatOptions.builder().reasoningEffort("minimal").build();
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void notThinkingOffForNormalRequests() {
        assertThat(AiThinkingBudget.isThinkingOff(null)).isFalse();
        assertThat(AiThinkingBudget.isThinkingOff(OpenAiChatOptions.builder().reasoningEffort("low").build()))
                .isFalse();
        assertThat(AiThinkingBudget.isThinkingOff(OpenAiChatOptions.builder().reasoningEffort("medium").build()))
                .isFalse();
        // enable_thinking=true 是"开思考"，不是关
        OpenAiChatOptions on = OpenAiChatOptions.builder()
                .extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", true)))
                .build();
        assertThat(AiThinkingBudget.isThinkingOff(on)).isFalse();
    }

    // ===== 强制关思考 =====

    @Test
    void forceOffSetsChatTemplateKwargsAndClearsReasoningEffort() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model("Qwen3.6-27B")
                .reasoningEffort("medium");
        AiThinkingBudget.forceOff(builder);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getReasoningEffort()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> kwargs = (Map<String, Object>) options.getExtraBody().get("chat_template_kwargs");
        assertThat(kwargs).containsEntry("enable_thinking", Boolean.FALSE);
    }

    @Test
    void forceOffUsesMinimalEffortForNonQwenEndpoints() {
        // 非 Qwen 端点：chat_template_kwargs 对它们无效 → 必须用 reasoning_effort=minimal
        AiModelConfig cfg = new AiModelConfig();
        cfg.setProvider("openai");
        cfg.setModel("gpt-5-mini");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model("gpt-5-mini")
                .reasoningEffort("medium");
        AiThinkingBudget.forceOff(builder, cfg);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getReasoningEffort()).isEqualTo("minimal");
        assertThat(options.getExtraBody()).isNullOrEmpty();
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void forceOffUsesChatTemplateKwargsForQwenEndpoints() {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setProvider("custom");
        cfg.setModel("Qwen3.6-27B");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("Qwen3.6-27B");
        AiThinkingBudget.forceOff(builder, cfg);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getExtraBody()).containsKey("chat_template_kwargs");
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void forceOffKeepsExistingExtraBodyEntries() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .extraBody(Map.of("custom_flag", "keep-me"));
        AiThinkingBudget.forceOff(builder);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getExtraBody()).containsEntry("custom_flag", "keep-me");
        assertThat(options.getExtraBody()).containsKey("chat_template_kwargs");
    }

    @Test
    void forceOffToleratesNullBuilder() {
        AiThinkingBudget.forceOff(null); // 不抛异常
        AiThinkingBudget.forceOff(null, null);
    }

    // ===== 思考 token 提取（usage → reasoning_tokens） =====

    @Test
    void extractsReasoningTokensFromNativeUsage() {
        Integer tokens = AiThinkingBudget.extractReasoningTokens(
                Map.of("completion_tokens_details", Map.of("reasoning_tokens", 1024)));
        assertThat(tokens).isEqualTo(1024);
    }

    @Test
    void returnsNullWhenEndpointDoesNotReportReasoningTokens() {
        // 端点不上报该字段 → 判定退化为「思考字符数 > 0」这一个信号
        assertThat(AiThinkingBudget.extractReasoningTokens(null)).isNull();
        assertThat(AiThinkingBudget.extractReasoningTokens(Map.of())).isNull();
        assertThat(AiThinkingBudget.extractReasoningTokens(
                Map.of("completion_tokens_details", Map.of()))).isNull();
        assertThat(AiThinkingBudget.extractReasoningTokens(
                Map.of("completion_tokens_details", Map.of("reasoning_tokens", "abc")))).isNull();
    }
}
