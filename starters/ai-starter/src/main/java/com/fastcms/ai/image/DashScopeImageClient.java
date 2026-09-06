/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * http://www.xjd2020.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DashScope 生图客户端（qwen-image 系列，文生图/修图）
 *
 * <p>使用 DashScope 原生多模态生图接口（异步模式）：</p>
 * <pre>
 * 提交：POST {baseUrl}/services/aigc/multimodal-generation/generation（Header X-DashScope-Async: enable）
 * 轮询：GET  {baseUrl}/tasks/{task_id}
 * </pre>
 *
 * <p>使用 JDK 内置 HttpClient，无额外依赖；一次性实例（每次生图新建，量小无缓存必要）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
public class DashScopeImageClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeImageClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 轮询间隔（生图通常 10~60 秒出图）
     */
    private static final long POLL_INTERVAL_MS = 2000L;

    /**
     * 单任务最长等待时间（超时视为失败）
     */
    private static final long TASK_TIMEOUT_MS = 300_000L;

    /**
     * DashScope 基础端点（如 https://dashscope.aliyuncs.com/api/v1）
     */
    private final String baseUrl;

    /**
     * API Key（明文，调用方负责解密）
     */
    private final String apiKey;

    private final HttpClient httpClient;

    public DashScopeImageClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 文生图
     *
     * @param model  模型名（如 qwen-image）
     * @param prompt 提示词
     * @param size   尺寸 宽*高（如 1664*928），空则服务端默认
     * @param n      生成张数 1-4
     * @return 图片 URL 列表
     */
    public List<String> generate(String model, String prompt, String size, int n) {
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("text", prompt);
        contents.add(text);
        return execute(model, contents, size, n);
    }

    /**
     * 修图（原图以 base64 data URI 内联）
     *
     * @param model       模型名（如 qwen-image-edit）
     * @param prompt      修改指令
     * @param imageBytes  原图字节
     * @param imageSuffix 原图后缀（png/jpg，用于 data URI mime）
     * @param size        尺寸 宽*高，空则跟随原图
     * @param n           生成张数 1-4
     * @return 图片 URL 列表
     */
    public List<String> edit(String model, String prompt, byte[] imageBytes, String imageSuffix, String size, int n) {
        String mime = "jpg".equalsIgnoreCase(imageSuffix) || "jpeg".equalsIgnoreCase(imageSuffix)
                ? "image/jpeg" : "image/png";
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("image", dataUri);
        contents.add(image);
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("text", prompt);
        contents.add(text);
        return execute(model, contents, size, n);
    }

    /**
     * 提交异步生图任务并轮询直至完成
     */
    private List<String> execute(String model, List<Map<String, Object>> contents, String size, int n) {
        // 请求体：input.messages[0].content = contents
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", contents);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", List.of(message));
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (size != null && !size.isBlank()) {
            parameters.put("size", size);
        }
        if (n > 0) {
            parameters.put("n", n);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        JsonNode submitted = post(body);
        JsonNode output = submitted.path("output");
        if (output.isMissingNode() || output.path("task_id").isMissingNode()) {
            throw new IllegalStateException("DashScope 提交生图任务失败: " + extractErrorMessage(submitted));
        }
        String taskId = output.path("task_id").asString();
        log.info("DashScope 生图任务已提交: taskId={}, model={}, status={}", taskId, model, output.path("task_status").asString());

        return pollTask(taskId);
    }

    /**
     * 轮询任务直到 SUCCEEDED / FAILED / 超时
     */
    private List<String> pollTask(String taskId) {
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            JsonNode task = get(baseUrl + "/tasks/" + taskId);
            JsonNode output = task.path("output");
            String status = output.path("task_status").asString();
            switch (status) {
                case "SUCCEEDED":
                    List<String> urls = new ArrayList<>();
                    for (JsonNode result : output.path("results")) {
                        String url = result.path("url").asString();
                        if (!url.isBlank()) {
                            urls.add(url);
                        }
                    }
                    if (urls.isEmpty()) {
                        throw new IllegalStateException("DashScope 生图成功但未返回图片 URL");
                    }
                    return urls;
                case "FAILED":
                case "CANCELED":
                case "UNKNOWN":
                    String code = output.path("code").asString();
                    String message = output.path("message").asString();
                    throw new IllegalStateException("DashScope 生图任务失败: " + code + " " + message);
                default:
                    // PENDING / RUNNING 继续轮询
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("生图任务轮询被中断", e);
            }
        }
        throw new IllegalStateException("生图任务超时（" + (TASK_TIMEOUT_MS / 1000) + "秒）: taskId=" + taskId);
    }

    private JsonNode post(Map<String, Object> body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/services/aigc/multimodal-generation/generation"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        return exchange(request);
    }

    private JsonNode get(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return exchange(request);
    }

    private JsonNode exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("DashScope HTTP " + response.statusCode() + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("调用 DashScope 接口异常: " + e.getMessage(), e);
        }
    }

    /**
     * 提取 DashScope 错误响应中的错误信息（code/message 字段）
     */
    private static String extractErrorMessage(JsonNode node) {
        String code = node.path("code").asString();
        String message = node.path("message").asString();
        return code.isBlank() && message.isBlank() ? node.toString() : code + " " + message;
    }

}
