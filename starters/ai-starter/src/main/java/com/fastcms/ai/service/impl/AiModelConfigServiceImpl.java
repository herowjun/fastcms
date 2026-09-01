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
package com.fastcms.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.ai.service.IAiModelConfigService;
import com.fastcms.entity.AiModelConfig;
import com.fastcms.mapper.AiModelConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置服务实现
 *
 * <p>放在 ai-starter 模块，因为它依赖 Spring AI 的 {@link ChatModel}、
 * {@link OpenAiChatModel} 等 API；而 ai-starter 本身依赖 fastcms-service，
 * 这样不会形成循环依赖（service 不应反向依赖 ai-starter）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiModelConfigServiceImpl extends ServiceImpl<AiModelConfigMapper, AiModelConfig> implements IAiModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfigServiceImpl.class);

    /**
     * 前端传回 apiKey 时若为该占位符，表示未修改，保留原值
     */
    private static final String API_KEY_MASK = "********";

    @Override
    public AiModelConfig getActiveConfig() {
        AiModelConfig active = getOne(Wrappers.<AiModelConfig>lambdaQuery()
                .eq(AiModelConfig::getActive, true)
                .orderByAsc(AiModelConfig::getSortNum)
                .last("limit 1"));
        if (active != null) {
            return active;
        }
        // 没有激活的，返回最新的一条
        return getOne(Wrappers.<AiModelConfig>lambdaQuery()
                .orderByDesc(AiModelConfig::getId)
                .last("limit 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activate(Long id) {
        AiModelConfig config = getById(id);
        if (config == null) {
            throw new IllegalArgumentException("配置不存在: " + id);
        }
        // 原子化激活：两条 UPDATE 在同一事务内完成，避免并发下出现多个激活配置
        // （旧的"先全部置 false 再置 true"两步操作在并发时可能留下中间状态）
        baseMapper.deactivateOthers(id);
        int rows = baseMapper.activateById(id);
        if (rows == 0) {
            throw new IllegalStateException("激活失败: 配置不存在或已并发变更, id=" + id);
        }
        log.info("AI 模型配置激活: id={}, name={}, model={}", id, config.getName(), config.getModel());
    }

    @Override
    public String testConnection(Long id) {
        AiModelConfig config = getById(id);
        if (config == null) {
            return "配置不存在";
        }
        try {
            ChatModel chatModel = buildChatModel(config);
            String reply = chatModel.call(new Prompt("hi"))
                    .getResult()
                    .getOutput()
                    .getText();
            log.info("AI 模型测试连接成功: name={}, model={}, reply={}", config.getName(), config.getModel(), reply);
            return "ok";
        } catch (Exception e) {
            log.warn("AI 模型测试连接失败: name={}, model={}", config.getName(), config.getModel(), e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelConfig saveConfig(AiModelConfig config) {
        // apiKey 脱敏处理：如果前端传回 ******** 表示未修改
        if (config.getId() != null && API_KEY_MASK.equals(config.getApiKey())) {
            AiModelConfig old = getById(config.getId());
            if (old != null) {
                config.setApiKey(old.getApiKey());
            }
        }
        // 如果是新增且 active 未指定，默认 false
        if (config.getId() == null && config.getActive() == null) {
            config.setActive(false);
        }
        // 如果本次激活，先把其他全部取消激活（原子 UPDATE，避免并发出现多个激活配置）
        if (Boolean.TRUE.equals(config.getActive())) {
            if (config.getId() != null) {
                baseMapper.deactivateOthers(config.getId());
            } else {
                baseMapper.deactivateAll();
            }
        }
        saveOrUpdate(config);
        log.info("AI 模型配置保存: id={}, name={}, model={}", config.getId(), config.getName(), config.getModel());
        return config;
    }

    @Override
    public List<AiModelConfig> listAll() {
        return list(Wrappers.<AiModelConfig>lambdaQuery()
                .orderByAsc(AiModelConfig::getSortNum)
                .orderByAsc(AiModelConfig::getId));
    }

    /**
     * apiKey 占位符：本地网关（Ollama / vLLM / 自定义）不配置 API Key 时使用。
     * <p>Spring AI 2.x 底层 OpenAI 客户端强制要求至少一个 credential（apiKey / workloadIdentity /
     * adminApiKey），apiKey 传 null 会抛 {@code IllegalStateException: At least one credential source
     * must be specified}。本地端点通常忽略 Authorization 头，占位值不会造成问题。</p>
     */
    private static final String API_KEY_PLACEHOLDER = "no-api-key";

    /**
     * 根据配置构建 OpenAiChatModel（运行时动态切换模型用）
     *
     * <p>每次调用都新建一个 ChatModel 实例。如果后续要做连接池/缓存，
     * 可以用 config id 作为 key 缓存（监听配置变更失效）。</p>
     *
     * <p>支持通过 extraHeaders 字段注入自定义请求头（JSON 格式），
     * 用于私有部署、企业网关、Ollama API Key 等场景。</p>
     */
    public static ChatModel buildChatModel(AiModelConfig config) {
        return OpenAiChatModel.builder()
                .options(baseOptionsBuilder(config).build())
                .build();
    }

    /**
     * 从模型配置构建 options 基底（默认 options 与 runtime 覆盖 options 共用）。
     *
     * <p>关键教训：Spring AI 传入 runtime options 后，默认 options 中的请求级字段
     * <b>不会</b>自动合并到 runtime options——model 漏设会 404（SDK 回退 gpt-5-mini），
     * timeout 漏设会断流（SDK 回退 60s callTimeout，长推理必挂"Stream failed"）。
     * 因此任何 runtime 覆盖 options 都必须从本基底出发构建，保证
     * apiKey/baseUrl/timeout/customHeaders 等字段永远在场。</p>
     */
    public static OpenAiChatOptions.Builder baseOptionsBuilder(AiModelConfig config) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .apiKey(StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : API_KEY_PLACEHOLDER)
                .baseUrl(config.getBaseUrl())
                .model(config.getModel())
                // OpenAI Java SDK（OkHttp）默认 callTimeout=60s，模板生成等长流式响应会被强制断流
                // （表现：SSE 60 秒后报 "AI 调用失败: Stream failed"，底层 InterruptedIOException: timeout）。
                // 模板生成含长推理阶段，实测 5 分钟仍可能不足，这里放宽到 10 分钟，
                // 与 AiTemplateController 的 SSE_TIMEOUT 对齐。
                .timeout(java.time.Duration.ofMinutes(10));
        if (config.getTemperature() != null) {
            optionsBuilder.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            optionsBuilder.maxTokens(config.getMaxTokens());
        }
        // 解析自定义请求头（JSON 对象，如 {"X-Tenant":"abc"}），空/非法 JSON 时忽略
        Map<String, String> customHeaders = parseExtraHeaders(config.getExtraHeaders());
        if (!customHeaders.isEmpty()) {
            optionsBuilder.customHeaders(customHeaders);
        }
        return optionsBuilder;
    }

    /**
     * 解析 extraHeaders JSON 字符串为请求头 Map
     *
     * @param json JSON 对象字符串，如 {"X-Tenant":"abc"}
     * @return 请求头 Map；空或解析失败返回空 Map
     */
    private static Map<String, String> parseExtraHeaders(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Map<String, String> result = new java.util.HashMap<>(raw.size());
            raw.forEach((k, v) -> {
                if (k != null && v != null) {
                    result.put(k, String.valueOf(v));
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("解析 extraHeaders 失败，已忽略: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

}
