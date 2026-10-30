package com.fastcms.ai.service.impl;

import com.fastcms.ai.support.AiThinkingBudget;
import com.fastcms.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「思考吃满 → 关思考重试」兜底 options 的行为验证。
 *
 * <p>2026-10-29 端点实测：{@code max_tokens} 是思考 + 正文共享预算，
 * 且<b>预算越大模型思考越长</b> → 「翻倍上限重试」是错的解法（实测 1024 预算
 * 保持思考 → 思考 4075 字符、正文仍 0）。唯一可靠解是<b>关思考</b>
 * （{@code enable_thinking=false} → 思考恒 0，正文必定产出）。</p>
 *
 * <p>本测试覆盖 S9（文章生成/续写）与 S2（设计稿设计段轮）两条不走
 * {@code callModelRound} 的链路共用的兜底 options 构造。</p>
 */
class ThinkingOffOptionsTest {

    private static AiModelConfig qwenConfig() {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setProvider("custom");
        cfg.setModel("Qwen3.6-27B");
        cfg.setBaseUrl("http://127.0.0.1:30120/v1");
        cfg.setApiKey("sk-test");
        return cfg;
    }

    @Test
    void thinkingOffOptionsDisablesThinkingOnQwenEndpoint() {
        OpenAiChatOptions options = AiModelConfigServiceImpl.thinkingOffOptions(qwenConfig(), null);

        assertThat(options).isNotNull();
        assertThat(options.getExtraBody()).containsKey("chat_template_kwargs");
        @SuppressWarnings("unchecked")
        Map<String, Object> kwargs = (Map<String, Object>) options.getExtraBody().get("chat_template_kwargs");
        assertThat(kwargs).containsEntry("enable_thinking", Boolean.FALSE);
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void thinkingOffOptionsKeepsOriginalBudgetAndTemperature() {
        // 不翻倍上限：保留原 maxTokens（翻倍只会给思考更多空间，正文照样 0）
        OpenAiChatOptions origin = OpenAiChatOptions.builder()
                .model("Qwen3.6-27B")
                .reasoningEffort("low")
                .maxTokens(24000)
                .temperature(0.7)
                .build();

        OpenAiChatOptions options = AiModelConfigServiceImpl.thinkingOffOptions(qwenConfig(), origin);

        assertThat(options.getMaxTokens()).isEqualTo(24000);
        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void thinkingOffOptionsKeepsBaseUrlAndApiKeyPresent() {
        // 请求级 options 整体替换默认 options（不合并）→ 漏设 apiKey/baseUrl 会 404
        OpenAiChatOptions options = AiModelConfigServiceImpl.thinkingOffOptions(qwenConfig(), null);

        assertThat(options.getBaseUrl()).isEqualTo("http://127.0.0.1:30120/v1");
        assertThat(options.getApiKey()).isNotBlank();
        assertThat(options.getTimeout()).isNotNull();
    }

    @Test
    void thinkingOffOptionsUsesMinimalEffortForNonQwenEndpoint() {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setProvider("openai");
        cfg.setModel("gpt-5-mini");

        OpenAiChatOptions options = AiModelConfigServiceImpl.thinkingOffOptions(cfg, null);

        assertThat(options.getReasoningEffort()).isEqualTo("minimal");
        assertThat(AiThinkingBudget.isThinkingOff(options)).isTrue();
    }

    @Test
    void thinkingOffOptionsReturnsNullWhenConfigMissing() {
        assertThat(AiModelConfigServiceImpl.thinkingOffOptions(null, null)).isNull();
    }
}
