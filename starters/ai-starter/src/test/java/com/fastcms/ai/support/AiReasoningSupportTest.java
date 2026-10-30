package com.fastcms.ai.support;

import com.fastcms.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考档位适配层行为验证。
 *
 * <p>背景（2026-10-29 对自建 Qwen 端点实测）：端点前面挂了一层网关，
 * <b>实际只放行 {@code low / medium}</b>（{@code xhigh} 被网关 400、
 * {@code high}/{@code none}/{@code minimal} 被 vLLM 400）；
 * 不传参数 = 端点默认最高档（≈xhigh）；
 * 真正关闭思考只能用 {@code chat_template_kwargs.enable_thinking=false}。</p>
 */
class AiReasoningSupportTest {

    private static AiModelConfig config(String provider, String model, String effort) {
        AiModelConfig c = new AiModelConfig();
        c.setProvider(provider);
        c.setModel(model);
        c.setReasoningEffort(effort);
        return c;
    }

    @Test
    void resolvePrefersConfiguredValueOverSceneDefault() {
        assertThat(AiReasoningSupport.resolve("off", AiReasoningSupport.SCENE_REASONING))
                .isEqualTo("off");
        assertThat(AiReasoningSupport.resolve("MEDIUM", AiReasoningSupport.SCENE_REASONING))
                .isEqualTo("medium");
    }

    @Test
    void resolveFallsBackToSceneDefaultWhenAutoOrBlank() {
        assertThat(AiReasoningSupport.resolve(null, AiReasoningSupport.SCENE_DETERMINISTIC))
                .isEqualTo(AiReasoningSupport.EFFORT_OFF);
        assertThat(AiReasoningSupport.resolve("", AiReasoningSupport.SCENE_REASONING))
                .isEqualTo(AiReasoningSupport.EFFORT_LOW);
        assertThat(AiReasoningSupport.resolve(AiReasoningSupport.EFFORT_AUTO, AiReasoningSupport.SCENE_REASONING))
                .isEqualTo(AiReasoningSupport.EFFORT_LOW);
        // 场景默认也为空时兜底为 low（不裸奔在端点默认的最高思考档）
        assertThat(AiReasoningSupport.resolve(null, null)).isEqualTo(AiReasoningSupport.EFFORT_LOW);
    }

    @Test
    void qwenOffUsesChatTemplateKwargsToDisableThinking() {
        AiModelConfig cfg = config("qwen", "Qwen3.6-27B", "off");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("Qwen3.6-27B");
        AiReasoningSupport.apply(builder, cfg, AiReasoningSupport.SCENE_REASONING);
        OpenAiChatOptions options = builder.build();

        Map<String, Object> extra = options.getExtraBody();
        assertThat(extra).isNotNull();
        assertThat(extra).containsKey("chat_template_kwargs");
        Object kwargs = extra.get("chat_template_kwargs");
        assertThat(kwargs).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> kwargsMap = (Map<String, Object>) kwargs;
        assertThat(kwargsMap).containsEntry("enable_thinking", Boolean.FALSE);
        // 关思考档不再发 reasoning_effort（避免与端点语义打架）
        assertThat(options.getReasoningEffort()).isNull();
    }

    @Test
    void qwenLowMapsToReasoningEffort() {
        AiModelConfig cfg = config("custom", "Qwen3.6-27B", "low");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("Qwen3.6-27B");
        AiReasoningSupport.apply(builder, cfg, AiReasoningSupport.SCENE_REASONING);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void qwenRejectsUnsupportedHighValueByClampingToMaxSupported() {
        // 2026-10-29 端点实测：网关只放行 low/medium（xhigh 被网关 400、high 被 vLLM 400），
        // 因此「最高思考」用 medium 表达，而不是发一个会被拒绝的枚举值。
        AiModelConfig cfg = config("qwen", "Qwen3.6-27B", "high");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("Qwen3.6-27B");
        AiReasoningSupport.apply(builder, cfg, AiReasoningSupport.SCENE_REASONING);
        assertThat(builder.build().getReasoningEffort())
                .isEqualTo(AiReasoningSupport.EFFORT_MAX_SUPPORTED)
                .isEqualTo("medium");
    }

    @Test
    void qwenNormalizesEveryUnsupportedEffortToLowOrMedium() {
        // xhigh / high / minimal / none 在本端点全部 400 → 必须归一到可用集 {low, medium}
        assertThat(AiReasoningSupport.normalize("xhigh", true)).isEqualTo("medium");
        assertThat(AiReasoningSupport.normalize("high", true)).isEqualTo("medium");
        assertThat(AiReasoningSupport.normalize("minimal", true)).isEqualTo("medium");
        assertThat(AiReasoningSupport.normalize("none", true)).isEqualTo("low");
        assertThat(AiReasoningSupport.normalize("medium", true)).isEqualTo("medium");
        assertThat(AiReasoningSupport.normalize("low", true)).isEqualTo("low");
        assertThat(AiReasoningSupport.normalize("off", true)).isEqualTo("low");
    }

    @Test
    void nonQwenKeepsItsOwnEffortVocabulary() {
        // 非 Qwen 端点（OpenAI o 系列）认 minimal/low/medium/high，xhigh 降级为 high
        assertThat(AiReasoningSupport.normalize("xhigh", false)).isEqualTo("high");
        assertThat(AiReasoningSupport.normalize("high", false)).isEqualTo("high");
        assertThat(AiReasoningSupport.normalize("minimal", false)).isEqualTo("minimal");
        assertThat(AiReasoningSupport.normalize("medium", false)).isEqualTo("medium");
    }

    @Test
    void nonQwenOffFallsBackToMinimalEffort() {
        AiModelConfig cfg = config("openai", "gpt-5-mini", "off");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("gpt-5-mini");
        AiReasoningSupport.apply(builder, cfg, AiReasoningSupport.SCENE_REASONING);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getReasoningEffort()).isEqualTo("minimal");
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void sceneDefaultIsUsedWhenConfigNotSet() {
        AiModelConfig cfg = config("qwen", "Qwen3.6-27B", null);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("Qwen3.6-27B");
        AiReasoningSupport.apply(builder, cfg, AiReasoningSupport.SCENE_DETERMINISTIC);
        OpenAiChatOptions options = builder.build();

        // 确定性场景 → 关思考
        assertThat(options.getExtraBody()).containsKey("chat_template_kwargs");
    }

    @Test
    void detectsQwenFamilyByProviderOrModel() {
        assertThat(AiReasoningSupport.isQwenFamily(config("qwen", "qwen-plus", null))).isTrue();
        assertThat(AiReasoningSupport.isQwenFamily(config("custom", "Qwen3.6-27B", null))).isTrue();
        assertThat(AiReasoningSupport.isQwenFamily(config("openai", "gpt-5-mini", null))).isFalse();
        assertThat(AiReasoningSupport.isQwenFamily(null)).isFalse();
    }
}
