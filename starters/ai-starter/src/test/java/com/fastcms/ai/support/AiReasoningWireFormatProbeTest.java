package com.fastcms.ai.support;

import com.fastcms.entity.AiModelConfig;
import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考档位的<b>线上格式</b>探针：钉死 Spring AI 2.0.1 到底把档位发成什么请求体字段。
 *
 * <p>为什么必须钉死：档位适配层（{@link AiReasoningSupport}）依赖两个 SDK 行为——
 * {@code OpenAiChatOptions.reasoningEffort} 被映射成请求体 {@code reasoning_effort}，
 * 以及 {@code OpenAiChatOptions.extraBody} 被映射成请求体<b>顶层</b>字段
 * （{@code additionalBodyProperties}）。这两个都是 SDK 内部实现，升级可能变；
 * 一旦静默失效，现象是「档位没生效、模型又变慢」，极难定位（上游只会忽略未知字段）。</p>
 *
 * <p>方法：起本地 HttpServer 抓真实请求体，分别验证「关思考档」与「低思考档」的字段。</p>
 */
class AiReasoningWireFormatProbeTest {

    /**
     * 抓取一次请求的真实 body（模型调用失败无所谓，只看请求体）
     */
    private String captureBody(AiModelConfig config, String sceneDefault) throws Exception {
        final List<String> bodies = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = ("{\"id\":\"probe\",\"object\":\"chat.completion\",\"created\":1,"
                        + "\"model\":\"probe\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            config.setId(null);   // 绕过 MODEL_CACHE，保证每次都是新建客户端
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setApiKey("probe-key");
            if (config.getModel() == null) {
                config.setModel("Qwen3.6-27B");
            }

            // 用与生产链路同源的 options 构建入口（配置基底 + 思考档位），确保探到的是真实格式
            ChatModel chatModel = org.springframework.ai.openai.OpenAiChatModel.builder()
                    .options(AiModelConfigServiceImpl.optionsBuilder(config, sceneDefault).build())
                    .build();
            try {
                chatModel.call(new Prompt("hi"));
            } catch (Exception ignored) {
                // 只看请求体，响应解析失败无所谓
            }
        } finally {
            server.stop(0);
        }

        assertThat(bodies).isNotEmpty();
        return bodies.get(0);
    }

    private AiModelConfig qwenConfig(String effort) {
        AiModelConfig c = new AiModelConfig();
        c.setProvider("qwen");
        c.setModel("Qwen3.6-27B");
        c.setReasoningEffort(effort);
        return c;
    }

    @Test
    void off档必须发出顶层chatTemplateKwargs关思考() throws Exception {
        String body = captureBody(qwenConfig(AiReasoningSupport.EFFORT_OFF),
                AiReasoningSupport.SCENE_REASONING);
        System.out.println("[probe] off 档请求体: " + body);

        assertThat(body).contains("chat_template_kwargs");
        assertThat(body).contains("enable_thinking");
        assertThat(body).contains("false");
        // 关思考档不应同时发 reasoning_effort（避免与端点语义打架）
        assertThat(body).doesNotContain("reasoning_effort");
    }

    @Test
    void 场景确定性档未显式配置时也发出关思考参数() throws Exception {
        // 配置列留空（auto）→ 场景默认确定性档 → 仍应发出 chat_template_kwargs
        String body = captureBody(qwenConfig(null), AiReasoningSupport.SCENE_DETERMINISTIC);
        System.out.println("[probe] 场景确定性档请求体: " + body);

        assertThat(body).contains("chat_template_kwargs");
        assertThat(body).contains("enable_thinking");
    }

    @Test
    void 低思考档发出reasoningEffort字段() throws Exception {
        String body = captureBody(qwenConfig(AiReasoningSupport.EFFORT_LOW),
                AiReasoningSupport.SCENE_REASONING);
        System.out.println("[probe] low 档请求体: " + body);

        assertThat(body).contains("reasoning_effort");
        assertThat(body).contains("low");
    }

    @Test
    void 非Qwen端点的关思考档退化为minimal() throws Exception {
        AiModelConfig c = new AiModelConfig();
        c.setProvider("openai");
        c.setModel("gpt-5-mini");
        c.setReasoningEffort(AiReasoningSupport.EFFORT_OFF);
        String body = captureBody(c, AiReasoningSupport.SCENE_REASONING);
        System.out.println("[probe] 非 Qwen off 档请求体: " + body);

        assertThat(body).contains("reasoning_effort");
        assertThat(body).contains("minimal");
        assertThat(body).doesNotContain("chat_template_kwargs");
    }
}
