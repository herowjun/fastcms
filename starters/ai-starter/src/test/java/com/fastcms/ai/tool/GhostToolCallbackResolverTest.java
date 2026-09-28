package com.fastcms.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幽灵工具兜底回归：模型拼错工具名（如 search_template_files 幻觉成单数）时
 * 不再抛 IllegalStateException 崩掉整轮流式任务，而是回流纠错提示
 * （实测事故：Qwen3.6-27B 拼错工具名导致 247 秒样式升级轮全部作废）
 */
class GhostToolCallbackResolverTest {

    /** 构造真实工具（模拟 TemplateContextToolFactory 的注册形态） */
    private static ToolCallback namedTool(String name) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name).description("test")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return "ok:" + name;
            }
        };
    }

    /** 模拟模型返回一次对未注册名的工具调用 */
    private static ChatResponse chatResponseWithToolCall(String toolName) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", toolName, "{}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        return new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(assistant)));
    }

    @Test
    void shouldResolveRegisteredToolAsIs() {
        ToolCallbackResolver resolver = new GhostToolCallbackResolver(
                new StaticToolCallbackResolver(List.of(namedTool("search_template_files"))));
        ToolCallback resolved = resolver.resolve("search_template_files");
        assertNotNull(resolved, "注册过的名字应原样解析");
        assertEquals("search_template_files", resolved.getToolDefinition().name());
        assertEquals("ok:search_template_files", resolved.call("{}"));
    }

    @Test
    void shouldFallbackToGhostForUnknownName() {
        ToolCallbackResolver resolver = new GhostToolCallbackResolver(
                new StaticToolCallbackResolver(List.of(namedTool("search_template_files"))));
        ToolCallback ghost = resolver.resolve("search_template_file"); // 模型拼错的单数形式
        assertNotNull(ghost, "未注册名应返回幽灵回调而非 null");
        String result = ghost.call("{}");
        assertTrue(result.contains("search_template_file"), "提示应包含拼错的名字: " + result);
        assertTrue(result.contains("不存在"), "提示应说明工具不存在: " + result);
        assertTrue(result.contains("重试"), "提示应引导重试: " + result);
    }

    /**
     * 端到端断言（复刻事故场景）：DefaultToolCallingManager + fallback 开启 + 幽灵 resolver，
     * 模型调用未注册名 search_template_file → 不抛异常，工具结果回流纠错提示
     */
    @Test
    void managerShouldNotThrowOnUnknownToolName() {
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new GhostToolCallbackResolver(
                        new StaticToolCallbackResolver(List.of(namedTool("search_template_files")))))
                .resolutionFallbackEnabled(true)
                .build();
        Prompt prompt = new Prompt("test");
        ChatResponse response = chatResponseWithToolCall("search_template_file");
        var result = manager.executeToolCalls(prompt, response);
        Object toolResults = result.conversationHistory().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> ((ToolResponseMessage) m).getResponses())
                .findFirst().orElse(null);
        assertNotNull(toolResults, "应有工具结果回流（而非抛异常）");
        String text = String.valueOf(toolResults);
        assertTrue(text.contains("不存在"), "回流内容应为纠错提示: " + text);
    }

    /** 对照组：幽灵 resolver 缺席时（纯静态 resolver），同样输入仍抛异常——证明修复是幽灵回调带来的 */
    @Test
    void managerStillThrowsWithoutGhostResolver() {
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(namedTool("search_template_files"))))
                .resolutionFallbackEnabled(true)
                .build();
        Prompt prompt = new Prompt("test");
        ChatResponse response = chatResponseWithToolCall("search_template_file");
        assertThrows(IllegalStateException.class, () -> manager.executeToolCalls(prompt, response));
    }
}
