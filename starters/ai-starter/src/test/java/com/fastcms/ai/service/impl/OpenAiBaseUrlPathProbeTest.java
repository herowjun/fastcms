package com.fastcms.ai.service.impl;

import com.fastcms.entity.AiModelConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 实测并钉住 Spring AI 2.0.1（底层 openai-java SDK）在给定 baseUrl 下
 * 到底往哪个路径发 chat/completions 请求。
 *
 * <p>背景：fastcms 的 {@code ai_model_config.base_url} 是用户可填的自由字段，
 * 运行时会直接透传给 {@code OpenAiChatOptions.baseUrl(...)}。要新增一个
 * OpenAI 兼容的自建网关（官网 AI 中转端点）就必须知道客户端会把 baseUrl
 * 拼成什么完整 URL，否则网关路由必然 404。</p>
 *
 * <p>方法：起一个本地 HttpServer 记录首个请求的 path，再分别用
 * {@code /proxy} 与 {@code /proxy/v1} 两种 baseUrl 各请求一次，观察差异。
 * 只看路径，不关心响应内容是否能解析（模型调用失败一律吞掉）。</p>
 *
 * @author wjun_java@163.com
 */
class OpenAiBaseUrlPathProbeTest {

    /** 探针：返回 SDK 实际请求的 path（去掉 query） */
    private String probePath(String baseUrl) throws Exception {
        final List<String> paths = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().getPath());
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

            AiModelConfig config = new AiModelConfig();
            // id 留空：绕过 MODEL_CACHE，保证每次都是新建客户端
            config.setId(null);
            config.setBaseUrl(baseUrl.replace("{port}", String.valueOf(server.getAddress().getPort())));
            config.setApiKey("probe-key");
            config.setModel("probe-model");

            ChatModel chatModel = AiModelConfigServiceImpl.buildChatModel(config);
            try {
                chatModel.call(new Prompt("hi"));
            } catch (Exception ignored) {
                // 只看请求路径，响应解析失败无所谓
            }
        } finally {
            server.stop(0);
        }

        System.out.println("[probe] baseUrl=" + baseUrl + "  ->  requestPath=" + paths);
        if (paths.isEmpty()) {
            throw new IllegalStateException("未捕获到任何请求，baseUrl=" + baseUrl);
        }
        return paths.get(0);
    }

    @Test
    void baseUrlIsConcatenatedVerbatimWithoutVersionNormalization() throws Exception {
        String baseUrlWithoutVersion = probePath("http://127.0.0.1:{port}/proxy");
        String baseUrlWithVersion = probePath("http://127.0.0.1:{port}/proxy/v1");

        System.out.println("================ baseUrl 拼接规则实测结果 ================");
        System.out.println("baseUrl=/proxy     -> " + baseUrlWithoutVersion);
        System.out.println("baseUrl=/proxy/v1  -> " + baseUrlWithVersion);
        System.out.println("=========================================================");

        // 这条规则是自建 OpenAI 兼容网关（官网 AI 中转端点）的路由依据，必须钉死：
        // 若某次升级后 SDK 改成"自动补 /v1"或"强制带版本"，下方断言会失败 ——
        // 这正是我们要的告警，否则网关会静默 404，而现象只是"AI 用不了"，极难定位。
        assertEquals("/proxy/chat/completions", baseUrlWithoutVersion,
                "baseUrl 不含版本段时 SDK 应只追加 /chat/completions");
        assertEquals("/proxy/v1/chat/completions", baseUrlWithVersion,
                "baseUrl 已含版本段时 SDK 应原样拼接，不得再插入一层 /v1");
    }
}
