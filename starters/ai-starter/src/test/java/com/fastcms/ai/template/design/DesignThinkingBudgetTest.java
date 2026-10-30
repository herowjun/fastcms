package com.fastcms.ai.template.design;

import com.fastcms.ai.support.AiThinkingBudget;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计段（S2）思考预算与关思考兜底的行为验证。
 *
 * <p>背景（2026-10-29 自建 Qwen 端点实测）：{@code max_tokens} 是<b>思考 + 正文共享</b>
 * 预算，且端点<b>预算越大模型思考越长</b> → 「思考吃满 → 正文 0 字符」是真实故障。
 * 设计段是 S2 场景（low 档），此前<b>缺</b>提示词思考预算约束与<b>缺</b>关思考兜底，
 * 本次补齐。</p>
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *   <li>设计轮注入「思考预算约束」，骨架轮（已关思考）跳过；</li>
 *   <li>「思考吃满」判定三信号（与全链路同口径）；</li>
 *   <li>兜底轮沿用<b>原上限</b>（不翻倍）。</li>
 * </ol>
 */
class DesignThinkingBudgetTest {

    private static OpenAiChatOptions designOptions() {
        // 设计轮：推理档（low），思考未被关闭
        return OpenAiChatOptions.builder()
                .model("Qwen3.6-27B")
                .reasoningEffort("low")
                .maxTokens(24000)
                .build();
    }

    private static OpenAiChatOptions skeletonOptions() {
        // 骨架轮：确定性档（off）→ Qwen 系走 chat_template_kwargs
        return OpenAiChatOptions.builder()
                .model("Qwen3.6-27B")
                .extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)))
                .maxTokens(4096)
                .build();
    }

    // ===== 提示词思考预算约束注入 =====
    // 说明：设计段不走 AiTemplateGenServiceImpl.callModelRound（那是管线专用），
    // 所以这里直接验证公共组件 AiThinkingBudget.applyBudgetHint 的行为——
    // S2 调的正是这个公共方法。

    @Test
    void designRoundGetsThinkingBudgetHint() {
        List<Message> messages = List.of(
                new SystemMessage("设计契约"),
                new UserMessage("为首页写设计稿"));

        List<Message> result = AiThinkingBudget.applyBudgetHint(messages, designOptions());

        assertThat(result.get(1).getText())
                .startsWith("为首页写设计稿")
                .contains(AiThinkingBudget.HINT_MARKER);
    }

    @Test
    void skeletonRoundSkipsHintBecauseThinkingAlreadyOff() {
        List<Message> messages = List.of(
                new SystemMessage("设计契约"),
                new UserMessage("规划页面骨架"));

        List<Message> result = AiThinkingBudget.applyBudgetHint(messages, skeletonOptions());

        // 已关思考 → 没有思考可约束 → 原样返回（骨架轮不会被约束文本污染）
        assertThat(result).isSameAs(messages);
        assertThat(result.get(1).getText()).doesNotContain(AiThinkingBudget.HINT_MARKER);
    }

    @Test
    void hintInjectionIsIdempotentAcrossRounds() {
        List<Message> messages = List.of(new UserMessage("需求"));
        List<Message> once = AiThinkingBudget.applyBudgetHint(messages, designOptions());
        List<Message> twice = AiThinkingBudget.applyBudgetHint(once, designOptions());

        int markerCount = twice.get(0).getText().split(AiThinkingBudget.HINT_MARKER, -1).length - 1;
        assertThat(markerCount).isEqualTo(1);
    }

    // ===== 「思考吃满」判定（S2 与全链路同口径） =====

    @Test
    void designRoundDetectsThinkingFlood() {
        AiThinkingBudget.RoundDiagnostics diagnostics = new AiThinkingBudget.RoundDiagnostics();
        // 端点返回大写 LENGTH
        diagnostics.finishReason = "LENGTH";
        diagnostics.reasoningChars = 2067;

        assertThat(diagnostics.thinkingFlooded()).isTrue();
    }

    @Test
    void designRoundDoesNotMisjudgeNormalCompletion() {
        AiThinkingBudget.RoundDiagnostics diagnostics = new AiThinkingBudget.RoundDiagnostics();
        diagnostics.finishReason = "STOP";
        diagnostics.reasoningChars = 2233;

        assertThat(diagnostics.thinkingFlooded()).isFalse();
    }

    @Test
    void designRoundDoesNotMisjudgeTruncatedButProducedBody() {
        // 有正文（哪怕也撞了上限）→ 是"正文被截断"而不是"思考吃满"
        AiThinkingBudget.RoundDiagnostics diagnostics = new AiThinkingBudget.RoundDiagnostics();
        diagnostics.finishReason = "length";
        diagnostics.reasoningChars = 3000;
        diagnostics.contentLength = 128;

        assertThat(diagnostics.thinkingFlooded()).isFalse();
        assertThat(AiThinkingBudget.isThinkingFlooded("length", 128, 3000, null)).isFalse();
    }

    @Test
    void designRoundDetectsFloodByReasoningTokensWhenEndpointReportsThem() {
        AiThinkingBudget.RoundDiagnostics diagnostics = new AiThinkingBudget.RoundDiagnostics();
        diagnostics.finishReason = "length";
        diagnostics.reasoningTokens = 1024;

        assertThat(diagnostics.thinkingFlooded()).isTrue();
    }

    @Test
    void contentLengthMustBeFilledToAvoidMisjudgingTruncatedBody() {
        // 回归：诊断对象默认 contentLength=0，若调用点忘记回填正文长度，
        // 「正文被截断但已产出内容」会被误判成思考吃满 → 错误丢弃已写出的正文
        AiThinkingBudget.RoundDiagnostics diagnostics = new AiThinkingBudget.RoundDiagnostics();
        diagnostics.finishReason = "length";
        diagnostics.reasoningChars = 3000;
        // 未回填 contentLength（默认 0）→ 判定为吃满（调用点必须回填）
        assertThat(diagnostics.thinkingFlooded()).isTrue();
        // 回填真实正文长度后 → 不再误判
        diagnostics.contentLength = 1;
        assertThat(diagnostics.thinkingFlooded()).isFalse();
    }
}
