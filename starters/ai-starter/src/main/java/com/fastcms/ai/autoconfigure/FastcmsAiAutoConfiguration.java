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
package com.fastcms.ai.autoconfigure;

import com.fastcms.ai.audit.AiQuotaChecker;
import com.fastcms.ai.audit.AiUsageRecorder;
import com.fastcms.ai.support.AiApiKeyCipher;
import com.fastcms.ai.tool.AiToolCallbackProvider;
import com.fastcms.ai.tool.AiToolPluginRegister;
import com.fastcms.ai.tool.AiToolRegister;
import com.fastcms.ai.tool.AiToolRegistry;
import com.fastcms.ai.tool.GhostToolCallbackResolver;
import com.fastcms.plugin.FastcmsPluginManager;
import com.fastcms.service.IAiUsageLogService;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fastcms AI 主自动配置
 *
 * <p>启用条件：classpath 存在 {@link ChatClient} 且 {@code fastcms.ai.enabled=true}（默认 true）</p>
 *
 * <p>本配置只做三件事：</p>
 * <ol>
 *     <li>加载 {@link FastcmsAiProperties}，业务模块通过依赖注入读取配置</li>
 *     <li>暴露 {@link AiToolRegistry} 给插件和业务模块使用</li>
 *     <li>日志告知 AI 已启用</li>
 * </ol>
 *
 * <p><b>故意不覆盖 Spring AI 自身的 ChatClient.Builder / ChatClient 自动配置</b>：
 * Spring AI 2.0 已经做了完善的自动配置（基于 spring.ai.openai.* 等属性），
 * fastcms 不重复发明轮子，业务模块直接注入 {@code ChatClient.Builder} 或 {@code ChatClient} 即可。</p>
 *
 * <p>Advisor 链、默认系统提示词等增强能力在后续 AdvisorAutoConfiguration 里独立提供，
 * 通过 Spring 容器自动被 ChatClient.Builder 收集。</p>
 *
 * <p><b>ToolCallingManager 覆盖</b>：本配置先于 Spring AI 的 ToolCallingAutoConfiguration
 * 处理（@AutoConfigureBefore 按 name 引用，类不在 classpath 时安全跳过），用
 * {@code resolutionFallbackEnabled(true)} + {@link GhostToolCallbackResolver} 重建 manager——
 * 模型拼错工具名（如 search_template_files 幻觉成单数）时不再抛
 * {@code IllegalStateException} 崩掉整轮流式任务，而是回流纠错提示让模型自我纠正
 * （实测：Qwen3.6-27B 拼错一次工具名导致 247 秒的样式升级轮全部作废）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Configuration
@AutoConfigureBefore(name = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(prefix = "fastcms.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FastcmsAiProperties.class)
public class FastcmsAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FastcmsAiAutoConfiguration.class);

    private final FastcmsAiProperties properties;

    public FastcmsAiAutoConfiguration(FastcmsAiProperties properties) {
        this.properties = properties;
        // 启动即初始化 API Key 加密主密钥（配置项优先，否则密钥文件；见 AiApiKeyCipher）
        AiApiKeyCipher.init(properties.getApiKeySecret());
    }

    /**
     * AI 工具注册中心：插件通过此注册中心暴露 @AiTool 方法给 ChatClient
     * <p>主工程和插件都可注入此 bean 调用 {@link AiToolRegistry#register} / {@link AiToolRegistry#unregister}</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public AiToolRegistry aiToolRegistry() {
        log.info("Fastcms AI 启用，defaultSystemPrompt={}, chatMemoryWindow={}, auditEnabled={}",
                properties.getDefaultSystemPrompt(), properties.getChatMemoryWindow(), properties.isAuditEnabled());
        return new AiToolRegistry();
    }

    /**
     * AI 工具扫描器：Spring 容器所有单例 bean 初始化完成后，扫描 @AiTool 注解方法
     * 注册到 {@link AiToolRegistry}。覆盖主工程 bean 和插件注册的 bean。
     */
    @Bean
    @ConditionalOnMissingBean
    public AiToolRegister aiToolRegister(AiToolRegistry aiToolRegistry) {
        return new AiToolRegister(aiToolRegistry);
    }

    /**
     * AI 工具桥接：把 {@link AiToolRegistry} 中的 @AiTool 工具转换为 Spring AI
     * 的 ToolCallback，供 ChatClient.defaultTools() 挂载，模型可在对话中自主调用
     */
    @Bean
    @ConditionalOnMissingBean
    public AiToolCallbackProvider aiToolCallbackProvider(AiToolRegistry aiToolRegistry) {
        return new AiToolCallbackProvider(aiToolRegistry);
    }

    /**
     * 插件 AI 工具注册器：跟随插件安装/卸载生命周期注册与清理 @AiTool 工具。
     * 注册为 Spring bean 后由 FastcmsPluginManager 在启动时自动收集（见其 onApplicationEvent）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(FastcmsPluginManager.class)
    public AiToolPluginRegister aiToolPluginRegister(AiToolRegistry aiToolRegistry, FastcmsPluginManager pluginManager) {
        return new AiToolPluginRegister(pluginManager, aiToolRegistry);
    }

    /**
     * AI 用量记录器：各 AI 场景服务调用结束后落审计（fastcms.ai.audit-enabled=false 时跳过）
     */
    @Bean
    @ConditionalOnMissingBean
    public AiUsageRecorder aiUsageRecorder(IAiUsageLogService usageLogService) {
        return new AiUsageRecorder(usageLogService, properties);
    }

    /**
     * AI 配额检查器：模型调用前检查当日 token 消耗是否超限（fastcms.ai.daily-token-quota，0=不限）
     */
    @Bean
    @ConditionalOnMissingBean
    public AiQuotaChecker aiQuotaChecker(IAiUsageLogService usageLogService) {
        return new AiQuotaChecker(usageLogService, properties);
    }

    /**
     * 工具调用管理器（覆盖 Spring AI 默认）：开启请求外工具名解析兜底 +
     * 幽灵回调（未注册名 → 纠错提示回流，不抛异常崩流）。
     *
     * <p><b>顺序说明</b>：本配置类先于 Spring AI 的 ToolCallingAutoConfiguration 处理
     * （@AutoConfigureBefore），本 bean 注册后其同名 bean 自动让位（@ConditionalOnMissingBean）。
     * resolver 经 ObjectProvider 惰性注入——配置处理期 spring-ai 的 resolver 尚未注册
     * （不能用 @ConditionalOnBean 判定），实例化期按类型正常解析；极端场景（spring-ai
     * 自动配置未生效）回落到空 DelegatingToolCallbackResolver，幽灵兜底依然可用。</p>
     *
     * <p>组件与 Spring AI 自动配置保持一致（resolver / exceptionProcessor /
     * observationRegistry / observationConvention 均注入既有 bean）；调用次数上限用
     * builder 默认值（单工具 40 / 总量 150，与 ToolCallingProperties 未配置时的默认相同）。
     * 预期宿主不配置 spring.ai.tools.limits.*；若确有配置需求，可自行定义
     * ToolCallingManager bean 覆盖本 bean（@ConditionalOnMissingBean 让位）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(DefaultToolCallingManager.class)
    public ToolCallingManager toolCallingManager(
            ObjectProvider<ToolCallbackResolver> toolCallbackResolver,
            ObjectProvider<ToolExecutionExceptionProcessor> toolExecutionExceptionProcessor,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ToolCallingObservationConvention> observationConvention) {
        ToolCallbackResolver delegate = toolCallbackResolver.getIfAvailable(
                () -> new DelegatingToolCallbackResolver(java.util.List.of()));
        ToolExecutionExceptionProcessor processor = toolExecutionExceptionProcessor.getIfAvailable(
                () -> org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor.builder().build());
        DefaultToolCallingManager manager = DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .toolCallbackResolver(new GhostToolCallbackResolver(delegate))
                .toolExecutionExceptionProcessor(processor)
                .resolutionFallbackEnabled(true)
                .build();
        ToolCallingObservationConvention convention = observationConvention.getIfAvailable();
        if (convention != null) {
            manager.setObservationConvention(convention);
        }
        log.info("ToolCallingManager 已启用幽灵工具兜底（未注册工具名回流纠错提示，不再抛异常崩流）");
        return manager;
    }

}
