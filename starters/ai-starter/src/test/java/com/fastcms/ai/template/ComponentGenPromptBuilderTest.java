package com.fastcms.ai.template;

import com.fastcms.ai.component.BuiltinTailwindPackProvider;
import com.fastcms.ai.component.ComponentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ComponentGenPromptBuilder 测试：system prompt 注入组件菜单与 schema 契约
 */
class ComponentGenPromptBuilderTest {

    private final ComponentGenPromptBuilder builder =
            new ComponentGenPromptBuilder(new ComponentRegistry(List.of(new BuiltinTailwindPackProvider())));

    @Test
    void systemPromptShouldContainManifestAndContract() {
        String prompt = builder.buildSystemPrompt();
        // 组件菜单注入
        assertTrue(prompt.contains("[tw:hero]"), "应包含组件菜单");
        assertTrue(prompt.contains("[tw:navbar]"));
        // schema 与输出契约
        assertTrue(prompt.contains("pagespec"));
        assertTrue(prompt.contains(BuiltinTailwindPackProvider.FOUNDATION));
        assertTrue(prompt.contains("stylePreset"));
        // 风格预设清单
        for (String preset : List.of("minimal", "corporate", "warm", "bold", "elegant")) {
            assertTrue(prompt.contains(preset), "缺少风格预设 " + preset);
        }
    }

    @Test
    void promptsShouldCarryKeyRequirements() {
        assertTrue(builder.buildFirstGenPrompt("demo", "餐饮网站")
                .contains("demo"));
        assertTrue(builder.buildRefinePrompt("换主色", "{}").contains("换主色"));
        assertTrue(builder.buildFixPrompt(List.of("组件不存在: tw:not-exist"))
                .contains("tw:not-exist"));
    }
}