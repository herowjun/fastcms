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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fastcms AI 配置属性
 *
 * 配置示例（application.yml）：
 * <pre>
 * fastcms:
 *   ai:
 *     enabled: true
 *     # 是否在模板中自动注册 &lt;@aiChat /&gt; 标签
 *     register-directive: true
 *     # 默认系统提示词
 *     default-system-prompt: "你是 fastcms 站点的 AI 助手"
 *     # 单次会话最大记忆轮数（0=不启用 memory）
 *     chat-memory-window: 10
 *     # 每用户每天 token 配额（0=不限）
 *     daily-token-quota: 100000
 *     # 审计日志开关
 *     audit-enabled: true
 * </pre>
 *
 * 模型供应商的 API Key、Base URL、Model 名称等配置直接走 Spring AI 原生
 * `spring.ai.openai.*` 属性，不在本类重复声明。
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@ConfigurationProperties(prefix = "fastcms.ai")
public class FastcmsAiProperties {

    /**
     * 是否启用 fastcms AI 能力（关闭后自动配置整体不生效）
     */
    private boolean enabled = true;

    /**
     * 是否自动注册 FreeMarker 的 &lt;@aiChat /&gt; 等标签
     */
    private boolean registerDirective = true;

    /**
     * 默认系统提示词
     */
    private String defaultSystemPrompt = "你是 fastcms 站点的 AI 助手，请用中文回答用户问题。";

    /**
     * 单次会话保留的最大历史轮数，0 表示不启用 ChatMemory
     */
    private int chatMemoryWindow = 10;

    /**
     * 每用户每天 token 配额，0 表示不限制
     */
    private long dailyTokenQuota = 0L;

    /**
     * 是否开启 AI 调用审计日志
     */
    private boolean auditEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRegisterDirective() {
        return registerDirective;
    }

    public void setRegisterDirective(boolean registerDirective) {
        this.registerDirective = registerDirective;
    }

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public int getChatMemoryWindow() {
        return chatMemoryWindow;
    }

    public void setChatMemoryWindow(int chatMemoryWindow) {
        this.chatMemoryWindow = chatMemoryWindow;
    }

    public long getDailyTokenQuota() {
        return dailyTokenQuota;
    }

    public void setDailyTokenQuota(long dailyTokenQuota) {
        this.dailyTokenQuota = dailyTokenQuota;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

}
