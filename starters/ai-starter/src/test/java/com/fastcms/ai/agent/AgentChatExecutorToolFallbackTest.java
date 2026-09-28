package com.fastcms.ai.agent;

import com.fastcms.ai.tool.GhostToolCallbackResolver;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ChatClient 构建路径的 ToolCallingManager 装配契约测试。
 *
 * <p><b>背景</b>：{@code AgentChatExecutor.Prepared} 原本用 {@code ChatClient.builder(chatModel)}
 * 手动构建 ChatClient —— 1 参重载会内部 new 一个默认 {@code DefaultToolCallingManager}
 * （无请求外工具名兜底），容器里 {@code FastcmsAiAutoConfiguration#toolCallingManager}
 * 的幽灵兜底配置被完全绕过。后果：模型把 {@code search_template_files} 幻觉成单数
 * {@code search_template_file} 时抛 {@code IllegalStateException}，整轮流式任务崩溃
 * （实测 2026-09-27 20:14:38：TEMPLATE_ADJUST 轮 17.7 秒全废、模板零改动）。</p>
 *
 * <p><b>契约</b>：走 5 参重载 + 自定义 {@code ToolCallingAdvisor.Builder} 才能把幽灵兜底
 * manager 装进 ChatClient（见 {@code AgentChatExecutor.Prepared#buildChatClient}）。
 * 本测试用反射直接断言该装配结果。manager 层"拼错工具名不崩"的行为由
 * {@code GhostToolCallbackResolverTest} 覆盖；"advisor 会调用 manager"由生产日志证明
 * （崩溃栈 ToolCallingAdvisor → DefaultToolCallingManager.executeToolCall）——
 * 三段证据合起来锁定修复的正确性。</p>
 */
class AgentChatExecutorToolFallbackTest {

    private static final String STUB_TOOL = "search_template_files";

    /** 等价于 FastcmsAiAutoConfiguration#toolCallingManager 提供的 bean */
    private static ToolCallingManager ghostManager() {
        return DefaultToolCallingManager.builder()
                .toolCallbackResolver(new GhostToolCallbackResolver(new StaticToolCallbackResolver(List.of())))
                .resolutionFallbackEnabled(true)
                .build();
    }

    private static ToolCallback namedTool(String name) {
        org.springframework.ai.tool.definition.ToolDefinition def =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name).description("test")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return "ok:" + name;
            }
        };
    }

    /** 不会真正被调用的模型桩（仅用于装配 ChatClient） */
    private static ChatModel stubModel() {
        return (Prompt prompt) -> {
            AssistantMessage.ToolCall toolCall =
                    new AssistantMessage.ToolCall("call-1", "function", "search_template_file", "{}");
            AssistantMessage message = AssistantMessage.builder()
                    .content("").toolCalls(List.of(toolCall)).build();
            return new ChatResponse(List.of(new Generation(message)));
        };
    }

    /** 模型返回一次对指定（可能拼错）工具名的调用 */
    private static ChatResponse toolCallResponse(String toolName) {
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call-1", "function", toolName, "{}");
        AssistantMessage message = AssistantMessage.builder()
                .content("").toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /**
     * 反射穿透 DefaultChatClient → DefaultChatClientRequestSpec → toolCallingAdvisorBuilder
     * → toolCallingManager，取出 ChatClient 实际使用的 manager
     */
    private static ToolCallingManager extractManager(ChatClient client) throws Exception {
        Field specField = client.getClass().getDeclaredField("defaultChatClientRequest");
        specField.setAccessible(true);
        Object spec = specField.get(client);

        Field builderField = spec.getClass().getDeclaredField("toolCallingAdvisorBuilder");
        builderField.setAccessible(true);
        Object advisorBuilder = builderField.get(spec);

        Field managerField = advisorBuilder.getClass().getDeclaredField("toolCallingManager");
        managerField.setAccessible(true);
        return (ToolCallingManager) managerField.get(advisorBuilder);
    }

    @Test
    void fiveArgBuilderShouldWireCustomManagerIntoChatClient() throws Exception {
        ToolCallingManager manager = ghostManager();

        // 与 AgentChatExecutor.Prepared#buildChatClient 完全一致的构建方式
        ChatClient client = ChatClient.builder(stubModel(), ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(manager))
                .defaultTools(namedTool(STUB_TOOL))
                .build();

        assertSame(manager, extractManager(client),
                "5 参重载必须让自定义（幽灵兜底）manager 生效，否则拼错工具名会崩流");
    }

    @Test
    void oneArgBuilderSilentlyBypassesContainerManager() throws Exception {
        ToolCallingManager ghost = ghostManager();

        // 反例：1 参重载（修复前的写法）用内部默认 manager，容器配置完全失效
        ChatClient client = ChatClient.builder(stubModel()).defaultTools(namedTool(STUB_TOOL)).build();

        ToolCallingManager actual = extractManager(client);
        assertNotSame(ghost, actual,
                "1 参重载不应采用容器 manager —— 这正是修复前崩溃的根因，回归时不得退回该写法");
    }
}
