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
import com.fastcms.ai.tool.AiToolCallbackProvider;
import com.fastcms.ai.tool.AiToolRegister;
import com.fastcms.ai.tool.AiToolRegistry;
import com.fastcms.service.IAiUsageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Configuration
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(prefix = "fastcms.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FastcmsAiProperties.class)
public class FastcmsAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FastcmsAiAutoConfiguration.class);

    private final FastcmsAiProperties properties;

    public FastcmsAiAutoConfiguration(FastcmsAiProperties properties) {
        this.properties = properties;
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

}
