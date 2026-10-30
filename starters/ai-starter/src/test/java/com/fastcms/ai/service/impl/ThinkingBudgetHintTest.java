package com.fastcms.ai.service.impl;

import com.fastcms.ai.support.AiThinkingBudget;
import com.fastcms.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「思考预算约束」注入与思考吃满降档的行为验证。
 *
 * <p>对应 2026-10-29 端点实测的故障：{@code max_tokens} 是思考 + 正文共享预算，
 * 且预算越大思考越长 → 思考吃满即正文 0 token。</p>
 */
class ThinkingBudgetHintTest {

    private static OpenAiChatOptions lowEffort() {
        return OpenAiChatOptions.builder().model("Qwen3.6-27B").reasoningEffort("low").build();
    }

    private static OpenAiChatOptions thinkingOff() {
        return OpenAiChatOptions.builder()
                .model("Qwen3.6-27B")
                .extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)))
                .build();
    }

    @Test
    void appendsHintToLastUserMessage() {
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("系统提示"),
                new UserMessage("第一轮需求"),
                new UserMessage("本轮需求")));

        List<Message> result = AiTemplateGenServiceImpl.applyThinkingBudgetHint(messages, lowEffort());

        assertThat(result).isNotSameAs(messages);
        assertThat(result.get(1).getText()).doesNotContain(AiThinkingBudget.HINT_MARKER);
        assertThat(result.get(2).getText())
                .startsWith("本轮需求")
                .contains(AiThinkingBudget.HINT_MARKER);
    }

    @Test
    void skipsWhenThinkingAlreadyDisabled() {
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("本轮需求")));

        List<Message> result = AiTemplateGenServiceImpl.applyThinkingBudgetHint(messages, thinkingOff());

        assertThat(result).isSameAs(messages);
        assertThat(result.get(0).getText()).doesNotContain(AiThinkingBudget.HINT_MARKER);
    }

    @Test
    void isIdempotentWhenSameMessagesReused() {
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("本轮需求")));

        List<Message> once = AiTemplateGenServiceImpl.applyThinkingBudgetHint(messages, lowEffort());
        List<Message> twice = AiTemplateGenServiceImpl.applyThinkingBudgetHint(once, lowEffort());

        // 同一份 messages 被反复复用（重试 / 修复轮重建上下文）时不重复追加
        assertThat(twice.get(0).getText()).isEqualTo(once.get(0).getText());
        int markerCount = twice.get(0).getText().split(AiThinkingBudget.HINT_MARKER, -1).length - 1;
        assertThat(markerCount).isEqualTo(1);
    }

    @Test
    void handlesImmutableMessageList() {
        // List.of(...) 不可变 → 必须返回新列表而不是就地修改
        List<Message> messages = List.of(new SystemMessage("系统"), new UserMessage("需求"));

        List<Message> result = AiTemplateGenServiceImpl.applyThinkingBudgetHint(messages, lowEffort());

        assertThat(result).isNotSameAs(messages);
        assertThat(result.get(1).getText()).contains(AiThinkingBudget.HINT_MARKER);
    }

    @Test
    void keepsOriginalMessagesUntouched() {
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("需求")));

        AiTemplateGenServiceImpl.applyThinkingBudgetHint(messages, lowEffort());

        assertThat(messages.get(0).getText()).isEqualTo("需求");
    }

    @Test
    void toleratesNullAndEmptyInputs() {
        assertThat(AiTemplateGenServiceImpl.applyThinkingBudgetHint(null, lowEffort())).isNull();
        assertThat(AiTemplateGenServiceImpl.applyThinkingBudgetHint(List.of(), lowEffort())).isEmpty();
    }

    @Test
    void skipsWhenNoUserMessagePresent() {
        List<Message> messages = List.of(new SystemMessage("只有系统提示"));

        List<Message> result = AiTemplateGenServiceImpl.applyThinkingBudgetHint(messages, lowEffort());

        assertThat(result).isSameAs(messages);
    }

    // ===== 生效档位解析 =====

    @Test
    void effectiveEffortPrefersConfiguredValue() {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setReasoningEffort("xhigh");
        assertThat(AiTemplateGenServiceImpl.effectiveEffort(cfg, "low")).isEqualTo("xhigh");

        cfg.setReasoningEffort("auto");
        assertThat(AiTemplateGenServiceImpl.effectiveEffort(cfg, "low")).isEqualTo("low");

        cfg.setReasoningEffort(null);
        assertThat(AiTemplateGenServiceImpl.effectiveEffort(cfg, "medium")).isEqualTo("medium");

        assertThat(AiTemplateGenServiceImpl.effectiveEffort(null, "low")).isEqualTo("low");
    }

    // ===== 单文件轮兜底重试策略（按失败原因分流） =====

    @Test
    void singleFileRetryDoesNotDoubleBudgetWhenThinkingFlooded() {
        // 思考吃满 → 不翻倍（预算越大思考越长，翻倍只会让思考更长、正文照样 0）
        AiModelConfig cfg = new AiModelConfig();
        cfg.setMaxTokens(8192);
        assertThat(AiTemplateGenServiceImpl.resolveSingleFileRetryMaxTokens(cfg, true)).isNull();
    }

    @Test
    void singleFileRetryDoublesBudgetOnRealTruncation() {
        // 确系内容过多被截断 → 翻倍上限（至少 32768）
        AiModelConfig cfg = new AiModelConfig();
        cfg.setMaxTokens(8192);
        assertThat(AiTemplateGenServiceImpl.resolveSingleFileRetryMaxTokens(cfg, false)).isEqualTo(32768);

        cfg.setMaxTokens(32768);
        assertThat(AiTemplateGenServiceImpl.resolveSingleFileRetryMaxTokens(cfg, false)).isEqualTo(65536);
    }

    @Test
    void singleFileRetryKeepsNullWhenNoConfiguredBudget() {
        AiModelConfig cfg = new AiModelConfig();
        assertThat(AiTemplateGenServiceImpl.resolveSingleFileRetryMaxTokens(cfg, false)).isNull();
        assertThat(AiTemplateGenServiceImpl.resolveSingleFileRetryMaxTokens(null, false)).isNull();

        cfg.setMaxTokens(0);
        assertThat(AiTemplateGenServiceImpl.resolveSingleFileRetryMaxTokens(cfg, false)).isNull();
    }
}
