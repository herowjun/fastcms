package com.fastcms.ai.template.design;

import com.fastcms.ai.component.BuiltinTailwindPackProvider;
import com.fastcms.ai.component.ComponentDescriptor;
import com.fastcms.ai.component.ComponentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计稿 CMS 数据区语义标记 → 组件确定性映射测试（2026-09-25 新增）
 *
 * <p>设计契约第 7 条要求：CMS 数据区必须用受控 {@code data-block} 语义名声明。转化段据此
 * 在 AI 映射之前做确定性映射——本测试覆盖语义表口径、解析规则（命中/未命中/组件缺失/
 * 页型不符）与 appliesTo 守卫。</p>
 */
class MockupConverterSemanticTest {

    private final ComponentRegistry registry =
            new ComponentRegistry(List.of(new BuiltinTailwindPackProvider()));
    private final Function<String, ComponentDescriptor> lookup = registry::getDescriptor;

    @Test
    void semanticTableCoversContractNames() {
        assertEquals("tw:article-list", MockupConverter.SEMANTIC_TARGETS.get("article-list"));
        assertEquals("tw:category-list", MockupConverter.SEMANTIC_TARGETS.get("category-list"));
        assertEquals("tw:tag-cloud", MockupConverter.SEMANTIC_TARGETS.get("tag-cloud"));
        assertEquals("tw:single-page-list", MockupConverter.SEMANTIC_TARGETS.get("single-page-list"));
        assertEquals(MockupConverter.MAP_CONTENT_BODY, MockupConverter.SEMANTIC_TARGETS.get("content-body"));
    }

    @Test
    void resolveSemanticTargetHitsAndMisses() {
        assertEquals("tw:article-list",
                MockupConverter.resolveSemanticTarget("article-list", "index", lookup));
        // 大小写与空白不敏感
        assertEquals("tw:category-list",
                MockupConverter.resolveSemanticTarget("  CATEGORY-LIST ", "index", lookup));
        // 非受控语义名（自由文本）与空值 → 不映射，交回 AI 判定
        assertNull(MockupConverter.resolveSemanticTarget("features", "index", lookup));
        assertNull(MockupConverter.resolveSemanticTarget(null, "index", lookup));
        assertNull(MockupConverter.resolveSemanticTarget("   ", "index", lookup));
        // 组件不存在 → 不映射（宁可交 AI，不静默错配）
        assertNull(MockupConverter.resolveSemanticTarget("article-list", "index", id -> null));
    }

    @Test
    void contentBodyMarkerOnlyAllowedOnContentPages() {
        assertEquals(MockupConverter.MAP_CONTENT_BODY,
                MockupConverter.resolveSemanticTarget("content-body", "article_list", lookup));
        assertEquals(MockupConverter.MAP_CONTENT_BODY,
                MockupConverter.resolveSemanticTarget("content-body", "page", lookup));
        assertNull(MockupConverter.resolveSemanticTarget("content-body", "index", lookup));
    }

    @Test
    void appliesToGuardRejectsPageTypeMismatch() {
        // 仅适用 index 的组件：内容页应被拦下，首页放行
        ComponentDescriptor indexOnly = new ComponentDescriptor(
                "index-only", "仅首页组件", "测试用", ComponentDescriptor.CATEGORY_CONTENT,
                null, List.of("index"), List.of(), List.of(), List.of());
        assertNull(MockupConverter.resolveSemanticTarget("article-list", "article_list", id -> indexOnly));
        assertEquals("tw:article-list",
                MockupConverter.resolveSemanticTarget("article-list", "index", id -> indexOnly));
        // appliesTo 为空 = 全页型 → 放行
        ComponentDescriptor anyPage = new ComponentDescriptor(
                "any", "全页组件", "测试用", ComponentDescriptor.CATEGORY_CONTENT,
                null, List.of(), List.of(), List.of(), List.of());
        assertEquals("tw:tag-cloud",
                MockupConverter.resolveSemanticTarget("tag-cloud", "article", id -> anyPage));
    }

    @Test
    void everySemanticTargetResolvesToRegisteredComponentOrBuiltinMarker() {
        for (String block : MockupConverter.SEMANTIC_TARGETS.keySet()) {
            String target = MockupConverter.SEMANTIC_TARGETS.get(block);
            if (MockupConverter.MAP_CONTENT_BODY.equals(target)) {
                continue;
            }
            assertTrue(registry.getDescriptor(target) != null,
                    "语义标记 " + block + " 指向的组件 " + target + " 必须已注册");
        }
    }
}
