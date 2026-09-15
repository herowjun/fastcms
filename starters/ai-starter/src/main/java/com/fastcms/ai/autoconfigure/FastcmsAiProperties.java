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

    /**
     * 模板调整轮的文件注入模式：
     * <ul>
     *     <li>focus（默认）：聚焦注入——当前页依赖闭包（L0）+ 全站文件清单（L1）常驻，
     *     其余文件由模型通过 read_template_file / search_template_files 工具按需查看（L2）</li>
     *     <li>full：全量注入（旧行为）——预算内注入全部模板文件，无按需工具</li>
     * </ul>
     * 聚焦模式 prefill 从 ~60k tokens 降到 ~15k；模型不支持工具调用时可切回 full。
     */
    private String adjustInjectMode = "focus";

    /**
     * API Key 加密主密钥（用于 ai_model_config 表 api_key 字段的 AES-GCM 加密）。
     * <p>为空时自动生成随机密钥并保存到 ~/fastcms/ai-api-key.secret；
     * 多实例部署需各实例配置相同值（SHA-256 派生密钥），否则已加密的 API Key 无法跨实例解密。</p>
     */
    private String apiKeySecret;

    /**
     * 技能库配置（Agent Skills 标准：SKILL.md 文件驱动）
     */
    private final Skill skill = new Skill();

    /**
     * AI 模板生成配置（双模式：pipeline 组件管线 / design 设计稿先行）
     */
    private final Template template = new Template();

    public Skill getSkill() {
        return skill;
    }

    public Template getTemplate() {
        return template;
    }

    /**
     * 技能库（磁盘文件源）配置
     */
    public static class Skill {

        /**
         * 磁盘技能库根目录（绝对路径优先；放技能目录即安装，记事本可改）。
         * 与 ai-template-preview 同模式：绝对路径避免工作目录依赖。
         */
        private String root = System.getProperty("user.home") + java.io.File.separator
                + "fastcms" + java.io.File.separator + "skills";

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    /**
     * AI 模板生成配置
     */
    public static class Template {

        /**
         * 设计稿先行模式（design）配置
         */
        private final Design design = new Design();

        /**
         * HTML 导入模式（import）配置
         */
        private final Import importConfig = new Import();

        public Design getDesign() {
            return design;
        }

        public Import getImportConfig() {
            return importConfig;
        }

        /**
         * HTML 导入模式配置（见 doc/wiki/html-import-to-template-design.md §7）
         *
         * <pre>
         * fastcms.ai.template.import:
         *   max-zip-size: 20971520   # 上传包大小上限（字节，默认 20MB）
         *   max-files: 200           # 解压后文件数上限
         *   allow-exts: html,htm,css,js,png,svg,jpg,webp,ico   # 扩展名白名单
         * </pre>
         */
        public static class Import {

            /**
             * 上传文件（zip 包）大小上限（字节）
             */
            private long maxZipSize = 20L * 1024 * 1024;

            /**
             * 解压后文件数上限（含资源文件，防解压炸弹）
             */
            private int maxFiles = 200;

            /**
             * 允许的文件扩展名白名单（小写、不含点）
             */
            private java.util.List<String> allowExts = java.util.Arrays.asList(
                    "html", "htm", "css", "js", "png", "svg", "jpg", "jpeg", "webp", "gif", "ico", "woff", "woff2", "ttf", "eot", "map", "txt");

            public long getMaxZipSize() {
                return maxZipSize;
            }

            public void setMaxZipSize(long maxZipSize) {
                this.maxZipSize = maxZipSize;
            }

            public int getMaxFiles() {
                return maxFiles;
            }

            public void setMaxFiles(int maxFiles) {
                this.maxFiles = maxFiles;
            }

            public java.util.List<String> getAllowExts() {
                return allowExts;
            }

            public void setAllowExts(java.util.List<String> allowExts) {
                this.allowExts = allowExts;
            }
        }

        /**
         * 设计稿先行模式配置（见 doc/wiki/ai-template-two-mode-design.md §7.5）
         *
         * <pre>
         * fastcms.ai.template.design:
         *   enabled: false            # 总开关（灰度用），默认关
         *   max-audit-rounds: 2       # 审计→修正轮上限（对齐升级管线 MAX_AUDIT_ROUNDS）
         *   max-format-rounds: 3      # 设计格式校验轮上限
         *   design-max-tokens: 24000  # 单页设计稿输出上限（防大输出失控）
         *   section-confidence-threshold: 0.7   # 组件映射置信阈值（低于→custom_macro）
         * </pre>
         */
        public static class Design {

            /**
             * 设计稿模式总开关（灰度用），默认关。关闭时 design 会话入口全关，
             * 代码层面等价于"该功能不存在"；管线模式不受影响
             */
            private boolean enabled = false;

            /**
             * 审计→修正轮上限（对齐升级管线 MAX_AUDIT_ROUNDS=2），
             * 仍失败进 AWAITING_CONFIRM 人工兜底
             */
            private int maxAuditRounds = 2;

            /**
             * 设计稿格式校验（V1~V6）轮上限，超出降级为占位页/单页重出
             */
            private int maxFormatRounds = 3;

            /**
             * 单页设计稿输出 token 上限（防大输出失控，沿用 callModelRound 保险丝机制）
             */
            private int designMaxTokens = 24000;

            /**
             * 组件映射置信阈值：AI 映射 confidence 低于该值强制 custom_macro（宁可自定义宏，不可错配组件）
             */
            private double sectionConfidenceThreshold = 0.7;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getMaxAuditRounds() {
                return maxAuditRounds;
            }

            public void setMaxAuditRounds(int maxAuditRounds) {
                this.maxAuditRounds = maxAuditRounds;
            }

            public int getMaxFormatRounds() {
                return maxFormatRounds;
            }

            public void setMaxFormatRounds(int maxFormatRounds) {
                this.maxFormatRounds = maxFormatRounds;
            }

            public int getDesignMaxTokens() {
                return designMaxTokens;
            }

            public void setDesignMaxTokens(int designMaxTokens) {
                this.designMaxTokens = designMaxTokens;
            }

            public double getSectionConfidenceThreshold() {
                return sectionConfidenceThreshold;
            }

            public void setSectionConfidenceThreshold(double sectionConfidenceThreshold) {
                this.sectionConfidenceThreshold = sectionConfidenceThreshold;
            }
        }
    }

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

    public String getApiKeySecret() {
        return apiKeySecret;
    }

    public void setApiKeySecret(String apiKeySecret) {
        this.apiKeySecret = apiKeySecret;
    }

    public String getAdjustInjectMode() {
        return adjustInjectMode;
    }

    public void setAdjustInjectMode(String adjustInjectMode) {
        this.adjustInjectMode = adjustInjectMode;
    }

}
