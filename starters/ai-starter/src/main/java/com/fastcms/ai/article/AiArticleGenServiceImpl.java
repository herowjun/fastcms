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
package com.fastcms.ai.article;

import com.fastcms.ai.audit.AiQuotaChecker;
import com.fastcms.ai.audit.AiQuotaExceededException;
import com.fastcms.ai.audit.AiUsageRecorder;
import com.fastcms.ai.service.IAiModelConfigService;
import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.support.ReplyStreamExtractor;
import com.fastcms.ai.tool.AiToolCallbackProvider;
import com.fastcms.entity.AiModelConfig;
import com.fastcms.service.IAiUsageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 文章内容生产服务实现（无状态）
 *
 * <p>三个能力：全文生成（SSE 流式）、划词改写（SSE 流式）、单字段候选（同步）。
 * 每次请求独立，不建会话；配额检查在模型调用前，审计落库在调用后。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiArticleGenServiceImpl implements IAiArticleGenService {

    private static final Logger log = LoggerFactory.getLogger(AiArticleGenServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * SSE 流式调用的专用线程池（避免阻塞 Servlet 容器线程）
     */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-article-sse");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private IAiModelConfigService modelConfigService;

    @Autowired
    private AiQuotaChecker quotaChecker;

    @Autowired
    private AiUsageRecorder usageRecorder;

    @Autowired
    private AiToolCallbackProvider toolCallbackProvider;

    @Autowired
    private com.fastcms.service.IAiArticleOpLogService articleOpLogService;

    // ==================== 全文生成 ====================

    @Override
    public void generate(AiArticleGenRequest request, Long userId, SseEmitter emitter) {
        sseExecutor.execute(() -> {
            try {
                doGenerate(request, userId, emitter);
            } catch (Exception e) {
                log.error("AI 文章生成异常", e);
                sendError(emitter, e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                complete(emitter);
            }
        });
    }

    private void doGenerate(AiArticleGenRequest request, Long userId, SseEmitter emitter) {
        if (!StringUtils.hasText(request.getTopic())) {
            sendError(emitter, "请输入文章主题");
            return;
        }

        AiModelConfig modelConfig = modelConfigService.getActiveConfig();
        if (modelConfig == null) {
            sendError(emitter, "未配置 AI 模型，请先在模型管理中添加并激活一个配置");
            return;
        }

        long startTime = System.currentTimeMillis();
        Usage[] lastUsage = {null};
        try {
            quotaChecker.check(userId);

            String systemPrompt = "你是专业的内容编辑，为 CMS 站点撰写文章。"
                    + "输出严格的 JSON 对象（不要 markdown 代码块包裹），字段如下：\n"
                    + "{\n"
                    + "  \"reply\": \"生成过程的一句话说明（20字内）\",\n"
                    + "  \"title\": \"文章标题，30字以内，含主关键词\",\n"
                    + "  \"summary\": \"文章摘要，100字以内\",\n"
                    + "  \"content\": \"正文，HTML 格式片段（只用 h2/h3/p/ul/ol/li/strong/em/blockquote/table 等常见标签，"
                    + "不要 html/head/body 包裹），800-2000字，结构清晰有小标题\",\n"
                    + "  \"seoKeywords\": \"SEO关键词，英文逗号分隔，3-6个\",\n"
                    + "  \"seoDescription\": \"SEO描述，120字以内\"\n"
                    + "}\n"
                    + "JSON 字符串值内的引号必须转义。reply 字段放在最前面。";

            StringBuilder userPrompt = new StringBuilder("文章主题：").append(request.getTopic());
            if (StringUtils.hasText(request.getKeywords())) {
                userPrompt.append("\n关键词：").append(request.getKeywords());
            }
            if (StringUtils.hasText(request.getInstruction())) {
                userPrompt.append("\n补充要求：").append(request.getInstruction());
            }

            ChatModel chatModel = AiModelConfigServiceImpl.buildChatModel(modelConfig);
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultTools(toolCallbackProvider.getToolCallbacks())
                    .build();

            StringBuilder responseBuffer = new StringBuilder();
            ReplyStreamExtractor replyExtractor = new ReplyStreamExtractor();
            StringBuilder reasoningBuf = new StringBuilder();

            chatClient.prompt(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt.toString()))))
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null
                                && resp.getMetadata().getUsage().getTotalTokens() != null) {
                            lastUsage[0] = resp.getMetadata().getUsage();
                        }
                        if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                            return;
                        }
                        org.springframework.ai.chat.messages.AssistantMessage output = resp.getResult().getOutput();
                        // 推理模型思考过程（累积值差分推送）
                        Object reasoning = output.getMetadata() == null
                                ? null : output.getMetadata().get("reasoningContent");
                        if (reasoning != null && StringUtils.hasText(String.valueOf(reasoning))) {
                            String rc = String.valueOf(reasoning);
                            String prev = reasoningBuf.toString();
                            if (rc.length() > prev.length() && rc.startsWith(prev)) {
                                String delta = rc.substring(prev.length());
                                if (StringUtils.hasText(delta)) {
                                    sendEvent(emitter, "reasoning", delta);
                                }
                            }
                            reasoningBuf.setLength(0);
                            reasoningBuf.append(rc);
                        }
                        String chunk = output.getText();
                        if (StringUtils.hasText(chunk)) {
                            responseBuffer.append(chunk);
                            String replyDelta = replyExtractor.feed(chunk);
                            if (StringUtils.hasText(replyDelta)) {
                                sendEvent(emitter, "message", replyDelta);
                            }
                        }
                    })
                    .blockLast();

            String fullResponse = responseBuffer.toString();
            if (!StringUtils.hasText(fullResponse)) {
                sendError(emitter, "AI 返回空响应");
                return;
            }

            // 解析结构化结果（容错提取 JSON）
            JsonNode node = extractJson(fullResponse);
            Map<String, String> article = new LinkedHashMap<>();
            if (node != null && node.isObject()) {
                for (String key : new String[]{"reply", "title", "summary", "content", "seoKeywords", "seoDescription"}) {
                    JsonNode v = node.get(key);
                    if (v != null && v.isTextual() && StringUtils.hasText(v.asString())) {
                        article.put(key, v.asString());
                    }
                }
            }

            if (!article.containsKey("content")) {
                sendError(emitter, "AI 响应未包含文章内容，请重试或换个主题描述");
                return;
            }

            // 操作历史落库（用户输入/生成结果/思考过程），done 事件携带 logId 供前端绑定文章
            Long opLogId = null;
            try {
                com.fastcms.entity.AiArticleOpLog opLog = new com.fastcms.entity.AiArticleOpLog();
                opLog.setUserId(userId);
                opLog.setArticleId(request.getArticleId());
                opLog.setOperation("generate");
                opLog.setOriginalText(userPrompt.toString());
                opLog.setRewrittenText(article.get("content"));
                opLog.setReasoning(reasoningBuf.length() == 0 ? null : reasoningBuf.toString());
                opLog.setModel(modelConfig.getModel());
                opLog.setDurationMs(System.currentTimeMillis() - startTime);
                opLogId = articleOpLogService.record(opLog);
            } catch (Exception logEx) {
                log.warn("AI 文章生成记录落库失败（不影响生成结果）", logEx);
            }

            // done 事件携带完整结构化结果（含 logId），前端按字段提供应用按钮
            Map<String, Object> doneData = new LinkedHashMap<>(article);
            doneData.put("logId", opLogId);
            sendDone(emitter, MAPPER.writeValueAsString(doneData));
            log.info("AI 文章生成完成: userId={}, title={}", userId, article.get("title"));
        } catch (AiQuotaExceededException e) {
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("AI 文章生成失败: userId={}", userId, e);
            sendError(emitter, "AI 调用失败: " + e.getMessage());
        } finally {
            recordUsage(userId, IAiUsageLogService.Scene.ARTICLE_GEN, null,
                    modelConfig == null ? null : modelConfig.getModel(), lastUsage[0], startTime, null);
        }
    }

    // ==================== 划词改写 ====================

    @Override
    public void rewrite(AiArticleRewriteRequest request, Long userId, SseEmitter emitter) {
        sseExecutor.execute(() -> {
            try {
                doRewrite(request, userId, emitter);
            } catch (Exception e) {
                log.error("AI 文章改写异常", e);
                sendError(emitter, e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                complete(emitter);
            }
        });
    }

    private void doRewrite(AiArticleRewriteRequest request, Long userId, SseEmitter emitter) {
        if (!StringUtils.hasText(request.getText())) {
            sendError(emitter, "未选中要处理的文本");
            return;
        }

        AiModelConfig modelConfig = modelConfigService.getActiveConfig();
        if (modelConfig == null) {
            sendError(emitter, "未配置 AI 模型，请先在模型管理中添加并激活一个配置");
            return;
        }

        long startTime = System.currentTimeMillis();
        Usage[] lastUsage = {null};
        try {
            quotaChecker.check(userId);

            String operationDesc = switch (request.getOperation() == null ? "" : request.getOperation()) {
                case AiArticleRewriteRequest.OP_EXPAND -> "扩写这段内容（保持原意，从多个角度补充细节、例证、数据或背景说明，"
                        + "输出篇幅至少为原文的 2-3 倍，内容要充实具体，不要泛泛而谈）";
                case AiArticleRewriteRequest.OP_POLISH -> "润色这段内容（修正语病、提升表达，不改变原意与篇幅）";
                case AiArticleRewriteRequest.OP_TRANSLATE -> "翻译这段内容（中文译英文，英文译中文）";
                default -> "改写这段内容（换个表达方式，保持原意与篇幅）";
            };

            String systemPrompt = "你是专业的文字编辑。只输出处理后的文本，"
                    + "保留原有 HTML 标签结构（如 h2/p/ul/strong），不要任何解释、不要代码块包裹。"
                    + "若提供了前后文，处理结果需与前后文在文风、语气、语义上自然衔接。";

            StringBuilder userPrompt = new StringBuilder("任务：").append(operationDesc).append("\n");
            if (StringUtils.hasText(request.getInstruction())) {
                userPrompt.append("补充要求：").append(request.getInstruction()).append("\n");
            }
            if (StringUtils.hasText(request.getArticleTitle())) {
                userPrompt.append("所属文章标题：").append(request.getArticleTitle()).append("\n");
            }
            // 选中内容的前后文摘录（JSON：{"before":"...","after":"..."}），帮助模型保持文风一致
            if (StringUtils.hasText(request.getContext())) {
                userPrompt.append("上文（衔接参考，不要改写）：")
                        .append(extractContextPart(request.getContext(), "before")).append("\n");
                userPrompt.append("下文（衔接参考，不要改写）：")
                        .append(extractContextPart(request.getContext(), "after")).append("\n");
            }
            userPrompt.append("\n内容：\n").append(request.getText());

            ChatModel chatModel = AiModelConfigServiceImpl.buildChatModel(modelConfig);
            ChatClient chatClient = ChatClient.builder(chatModel).build();

            StringBuilder rewritten = new StringBuilder();
            StringBuilder reasoningBuf = new StringBuilder();
            chatClient.prompt(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt.toString()))))
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null
                                && resp.getMetadata().getUsage().getTotalTokens() != null) {
                            lastUsage[0] = resp.getMetadata().getUsage();
                        }
                        if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                            return;
                        }
                        org.springframework.ai.chat.messages.AssistantMessage output = resp.getResult().getOutput();
                        Object reasoning = output.getMetadata() == null
                                ? null : output.getMetadata().get("reasoningContent");
                        if (reasoning != null && StringUtils.hasText(String.valueOf(reasoning))) {
                            String rc = String.valueOf(reasoning);
                            String prev = reasoningBuf.toString();
                            if (rc.length() > prev.length() && rc.startsWith(prev)) {
                                String delta = rc.substring(prev.length());
                                if (StringUtils.hasText(delta)) {
                                    sendEvent(emitter, "reasoning", delta);
                                }
                            }
                            reasoningBuf.setLength(0);
                            reasoningBuf.append(rc);
                        }
                        // 直接流式推送改写文本增量
                        String chunk = output.getText();
                        if (StringUtils.hasText(chunk)) {
                            rewritten.append(chunk);
                            sendEvent(emitter, "message", chunk);
                        }
                    })
                    .blockLast();

            if (rewritten.length() == 0) {
                sendError(emitter, "AI 返回空响应");
                return;
            }
            // 操作历史落库（原文/结果/思考过程），前端 done 事件拿 logId 供后续绑定文章
            Long opLogId = null;
            try {
                com.fastcms.entity.AiArticleOpLog opLog = new com.fastcms.entity.AiArticleOpLog();
                opLog.setUserId(userId);
                opLog.setArticleId(request.getArticleId());
                opLog.setOperation(request.getOperation() == null ? AiArticleRewriteRequest.OP_REWRITE : request.getOperation());
                opLog.setOriginalText(request.getText());
                opLog.setRewrittenText(rewritten.toString());
                opLog.setReasoning(reasoningBuf.length() == 0 ? null : reasoningBuf.toString());
                opLog.setModel(modelConfig.getModel());
                opLog.setDurationMs(System.currentTimeMillis() - startTime);
                opLogId = articleOpLogService.record(opLog);
            } catch (Exception logEx) {
                log.warn("AI 划词操作记录落库失败（不影响改写结果）", logEx);
            }
            try {
                Map<String, Object> doneData = new LinkedHashMap<>();
                doneData.put("content", rewritten.toString());
                doneData.put("logId", opLogId);
                sendDone(emitter, MAPPER.writeValueAsString(doneData));
            } catch (Exception e) {
                sendDone(emitter, rewritten.toString());
            }
        } catch (AiQuotaExceededException e) {
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("AI 文章改写失败: userId={}", userId, e);
            sendError(emitter, "AI 调用失败: " + e.getMessage());
        } finally {
            recordUsage(userId, IAiUsageLogService.Scene.ARTICLE_REWRITE, null,
                    modelConfig == null ? null : modelConfig.getModel(), lastUsage[0], startTime, null);
        }
    }

    // ==================== 单字段生成 ====================

    @Override
    public void generateField(AiArticleFieldRequest request, Long userId, SseEmitter emitter) {
        sseExecutor.execute(() -> {
            try {
                doGenerateField(request, userId, emitter);
            } catch (Exception e) {
                log.error("AI 字段生成异常", e);
                sendError(emitter, e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                complete(emitter);
            }
        });
    }

    private void doGenerateField(AiArticleFieldRequest request, Long userId, SseEmitter emitter) {
        AiModelConfig modelConfig = modelConfigService.getActiveConfig();
        if (modelConfig == null) {
            sendError(emitter, "未配置 AI 模型，请先在模型管理中添加并激活一个配置");
            return;
        }

        long startTime = System.currentTimeMillis();
        Usage[] lastUsage = {null};
        try {
            quotaChecker.check(userId);

            String fieldDesc = switch (request.getField() == null ? "" : request.getField()) {
                case AiArticleFieldRequest.FIELD_TITLE -> "文章标题（30字以内，含主关键词，5个候选）";
                case AiArticleFieldRequest.FIELD_SUMMARY -> "文章摘要（100字以内，5个候选）";
                case AiArticleFieldRequest.FIELD_SEO_KEYWORDS -> "SEO关键词（英文逗号分隔，3-6个，5个候选）";
                case AiArticleFieldRequest.FIELD_SEO_DESCRIPTION -> "SEO描述（120字以内，5个候选）";
                default -> throw new IllegalArgumentException("不支持的字段: " + request.getField());
            };

            String systemPrompt = "你是 SEO 专家。输出严格的 JSON 数组（不要 markdown 代码块包裹），"
                    + "数组元素为字符串候选，如 [\"候选1\",\"候选2\"]。不要输出任何解释。";

            StringBuilder userPrompt = new StringBuilder("基于以下文章生成").append(fieldDesc).append("。\n");
            if (StringUtils.hasText(request.getTitle())) {
                userPrompt.append("文章标题：").append(request.getTitle()).append("\n");
            }
            userPrompt.append("文章正文（可能被截断）：\n")
                    .append(truncateForPrompt(request.getContent(), 4000));

            ChatModel chatModel = AiModelConfigServiceImpl.buildChatModel(modelConfig);
            ChatClient chatClient = ChatClient.builder(chatModel).build();

            StringBuilder responseBuffer = new StringBuilder();
            StringBuilder reasoningBuf = new StringBuilder();
            chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt), new UserMessage(userPrompt.toString()))))
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null
                                && resp.getMetadata().getUsage().getTotalTokens() != null) {
                            lastUsage[0] = resp.getMetadata().getUsage();
                        }
                        if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                            return;
                        }
                        org.springframework.ai.chat.messages.AssistantMessage output = resp.getResult().getOutput();
                        // 推理模型思考过程（累积值差分推送）
                        Object reasoning = output.getMetadata() == null
                                ? null : output.getMetadata().get("reasoningContent");
                        if (reasoning != null && StringUtils.hasText(String.valueOf(reasoning))) {
                            String rc = String.valueOf(reasoning);
                            String prev = reasoningBuf.toString();
                            if (rc.length() > prev.length() && rc.startsWith(prev)) {
                                String delta = rc.substring(prev.length());
                                if (StringUtils.hasText(delta)) {
                                    sendEvent(emitter, "reasoning", delta);
                                }
                            }
                            reasoningBuf.setLength(0);
                            reasoningBuf.append(rc);
                        }
                        String chunk = output.getText();
                        if (StringUtils.hasText(chunk)) {
                            responseBuffer.append(chunk);
                        }
                    })
                    .blockLast();

            List<String> candidates = parseCandidates(responseBuffer.toString());
            if (candidates.isEmpty()) {
                sendError(emitter, "AI 未返回有效候选，请重试");
                return;
            }

            // 操作历史落库（字段类型 + 候选列表 + 思考过程）
            Long opLogId = null;
            try {
                com.fastcms.entity.AiArticleOpLog opLog = new com.fastcms.entity.AiArticleOpLog();
                opLog.setUserId(userId);
                opLog.setArticleId(request.getArticleId());
                opLog.setOperation("field_" + (request.getField() == null ? "" : request.getField()));
                opLog.setOriginalText("字段：" + fieldDesc);
                opLog.setRewrittenText(String.join("\n", candidates));
                opLog.setReasoning(reasoningBuf.length() == 0 ? null : reasoningBuf.toString());
                opLog.setModel(modelConfig.getModel());
                opLog.setDurationMs(System.currentTimeMillis() - startTime);
                opLogId = articleOpLogService.record(opLog);
            } catch (Exception logEx) {
                log.warn("AI 字段候选记录落库失败（不影响生成结果）", logEx);
            }

            // done 事件携带候选列表与操作记录ID
            Map<String, Object> doneData = new LinkedHashMap<>();
            doneData.put("candidates", candidates);
            doneData.put("logId", opLogId);
            sendDone(emitter, MAPPER.writeValueAsString(doneData));
        } catch (AiQuotaExceededException e) {
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("AI 字段生成失败: userId={}, field={}", userId, request.getField(), e);
            sendError(emitter, "AI 调用失败: " + e.getMessage());
        } finally {
            recordUsage(userId, IAiUsageLogService.Scene.ARTICLE_FIELD, null,
                    modelConfig == null ? null : modelConfig.getModel(), lastUsage[0], startTime, null);
        }
    }

    // ==================== 通用工具 ====================

    /**
     * 记录审计（成功时记录 token 用量；异常时调用方已把错误信息返回用户，此处仅记成功调用的用量）
     */
    private void recordUsage(Long userId, String scene, String sessionId, String model, Usage usage, long startTime, String error) {
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        int totalTokens = usage == null || usage.getTotalTokens() == null
                ? promptTokens + completionTokens : usage.getTotalTokens();
        if (error == null) {
            usageRecorder.record(userId, scene, sessionId, model, promptTokens, completionTokens, totalTokens,
                    System.currentTimeMillis() - startTime);
        } else {
            usageRecorder.recordError(userId, scene, sessionId, model, System.currentTimeMillis() - startTime, error);
        }
    }

    /**
     * 从前端传来的上下文 JSON 中提取指定部分（before/after），解析失败返回空串
     */
    private String extractContextPart(String contextJson, String part) {
        try {
            JsonNode node = MAPPER.readTree(contextJson);
            JsonNode value = node == null ? null : node.get(part);
            return value == null ? "" : value.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 容错提取 JSON：支持被 markdown 代码块包裹、前后有解释文字的响应
     */
    private JsonNode extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        // 剥离 markdown 代码块
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            if (firstLineEnd > 0) {
                json = json.substring(firstLineEnd + 1);
            }
            int fenceEnd = json.lastIndexOf("```");
            if (fenceEnd >= 0) {
                json = json.substring(0, fenceEnd);
            }
            json = json.trim();
        }
        int braceStart = json.indexOf('{');
        int braceEnd = json.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1);
        }
        int bracketStart = json.indexOf('[');
        int bracketEnd = json.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            json = json.substring(bracketStart, bracketEnd + 1);
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析候选列表（JSON 数组字符串）
     */
    private List<String> parseCandidates(String response) {
        List<String> result = new ArrayList<>();
        JsonNode node = extractJson(response);
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && StringUtils.hasText(item.asString())) {
                    result.add(item.asString().trim());
                }
            });
        }
        return result;
    }

    /**
     * 截断正文用于 prompt（防止超上下文）
     */
    private String truncateForPrompt(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "\n...(正文已截断)";
    }

    private void sendDone(SseEmitter emitter, String data) {
        sendEvent(emitter, "done", data);
    }

    private void sendError(SseEmitter emitter, String message) {
        sendEvent(emitter, "error", message);
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("SSE 推送失败: event={}, {}", eventName, e.getMessage());
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
