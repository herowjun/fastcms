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
package com.fastcms.ai.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体运行时画像（AgentProfile）
 *
 * <p>多智能体架构的统一抽象：内置智能体（代码注册）、自定义智能体（ai_agent 表）、
 * 未来的插件贡献智能体（ExtensionPoint）在运行时都归一为本形态。
 * AgentCore 装配 ChatClient 时按 agentId 取本对象：systemPrompt + skills/tools 白名单 +
 * 模型参数覆盖。</p>
 *
 * <p>执行模式的二分是本架构的关键：</p>
 * <ul>
 *     <li>chat：模型自主循环（agent loop），AI 自己决定调用什么工具</li>
 *     <li>pipeline：系统驱动的多轮管线（如模板生成），骨架是 Java 代码，AI 是被调用的环节</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public class AgentProfile {

    /** 执行模式：对话循环（模型自主决定工具调用） */
    public static final String MODE_CHAT = "chat";
    /** 执行模式：管线驱动（系统代码驱动，AI 是生成环节） */
    public static final String MODE_PIPELINE = "pipeline";

    /** 来源：内置（代码注册，只读 + 复制） */
    public static final String SOURCE_BUILTIN = "builtin";
    /** 来源：自定义（ai_agent 表，可编辑/删除） */
    public static final String SOURCE_CUSTOM = "custom";
    /** 来源：插件贡献（预留，随插件升级，只读 + 复制） */
    public static final String SOURCE_PLUGIN = "plugin";

    /** 智能体业务ID（builtin.xxx / custom-xxx / 插件前缀） */
    private final String agentId;
    /** 自定义智能体的数据库ID（内置/插件为 null） */
    private final Long dbId;
    private final String name;
    private final String description;
    /** chat / pipeline */
    private final String executionMode;
    /** builtin / custom / plugin */
    private final String source;
    private final String systemPrompt;
    /** 绑定的模型配置ID（null=继承当前激活的对话模型） */
    private final Long modelConfigId;
    /** 温度（null=继承模型配置默认值） */
    private final Double temperature;
    /** MaxTokens（null=继承模型配置默认值） */
    private final Integer maxTokens;
    /** skill 白名单（来自 SkillRegistry） */
    private final List<String> skills;
    /** 工具白名单（来自 AiToolRegistry） */
    private final List<String> tools;
    /** 日 token 配额（0/null=不限） */
    private final Long dailyTokenQuota;
    private final Integer sortNum;
    /** 1启用 0停用 */
    private final Integer status;
    /** 复制来源智能体ID */
    private final String baseAgentId;

    /**
     * 当日已消耗 token（全体用户合计，来自 ai_usage_log 按日聚合）。
     * <b>运行时展示字段</b>：不参与 builder/持久化/配额判断，由 listAgents 查询用量后填充，
     * 仅供界面展示；写入共享的内置实例时并发请求间可能读到彼此的写入（值域相同：当日累计），可接受。
     */
    private Long todayTokens;

    private AgentProfile(Builder builder) {
        this.agentId = builder.agentId;
        this.dbId = builder.dbId;
        this.name = builder.name;
        this.description = builder.description;
        this.executionMode = builder.executionMode;
        this.source = builder.source;
        this.systemPrompt = builder.systemPrompt;
        this.modelConfigId = builder.modelConfigId;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.skills = builder.skills == null ? new ArrayList<>() : builder.skills;
        this.tools = builder.tools == null ? new ArrayList<>() : builder.tools;
        this.dailyTokenQuota = builder.dailyTokenQuota;
        this.sortNum = builder.sortNum;
        this.status = builder.status;
        this.baseAgentId = builder.baseAgentId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getAgentId() { return agentId; }
    public Long getDbId() { return dbId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getExecutionMode() { return executionMode; }
    public String getSource() { return source; }
    public String getSystemPrompt() { return systemPrompt; }
    public Long getModelConfigId() { return modelConfigId; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public List<String> getSkills() { return skills; }
    public List<String> getTools() { return tools; }
    public Long getDailyTokenQuota() { return dailyTokenQuota; }
    public Integer getSortNum() { return sortNum; }
    public Integer getStatus() { return status; }
    public String getBaseAgentId() { return baseAgentId; }

    /** 当日已消耗 token（仅展示；见字段注释） */
    public Long getTodayTokens() { return todayTokens; }

    public void setTodayTokens(Long todayTokens) { this.todayTokens = todayTokens; }

    /** 是否可在界面上编辑（仅自定义智能体可编辑） */
    public boolean isEditable() { return SOURCE_CUSTOM.equals(source); }

    /** 是否 pipeline 型（只读 + 复制，编辑入口仅 chat 型） */
    public boolean isPipeline() { return MODE_PIPELINE.equals(executionMode); }

    public static class Builder {
        private String agentId;
        private Long dbId;
        private String name;
        private String description;
        private String executionMode = MODE_CHAT;
        private String source = SOURCE_CUSTOM;
        private String systemPrompt;
        private Long modelConfigId;
        private Double temperature;
        private Integer maxTokens;
        private List<String> skills;
        private List<String> tools;
        private Long dailyTokenQuota;
        private Integer sortNum = 0;
        private Integer status = 1;
        private String baseAgentId;

        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder dbId(Long dbId) { this.dbId = dbId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder executionMode(String executionMode) { this.executionMode = executionMode; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder modelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder skills(List<String> skills) { this.skills = skills; return this; }
        public Builder tools(List<String> tools) { this.tools = tools; return this; }
        public Builder dailyTokenQuota(Long dailyTokenQuota) { this.dailyTokenQuota = dailyTokenQuota; return this; }
        public Builder sortNum(Integer sortNum) { this.sortNum = sortNum; return this; }
        public Builder status(Integer status) { this.status = status; return this; }
        public Builder baseAgentId(String baseAgentId) { this.baseAgentId = baseAgentId; return this; }

        public AgentProfile build() {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agentId 不能为空");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("智能体名称不能为空: " + agentId);
            }
            return new AgentProfile(this);
        }
    }
}
