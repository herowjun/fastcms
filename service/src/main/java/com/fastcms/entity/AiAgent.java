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
package com.fastcms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 智能体（自定义智能体配置，内置智能体由代码注册不落库）
 *
 * <p>多智能体架构的数据侧：一条记录即一个自定义智能体（AgentProfile 的 DB 形态）。
 * skills/tools 为白名单 JSON 数组，装配时与 SkillRegistry / AiToolRegistry 求交集，
 * 插件卸载导致的失效引用在保存时校验、读取时过滤。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@TableName("ai_agent")
public class AiAgent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 智能体业务ID（自定义智能体为 custom-xxx）
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String name;

    /**
     * 智能体描述
     */
    private String description;

    /**
     * 执行模式: chat-对话循环 / pipeline-管线驱动（自定义智能体仅允许 chat）
     */
    private String executionMode;

    /**
     * 系统提示词（支持 {{site.name}} 等站点变量占位，运行时替换）
     */
    private String systemPrompt;

    /**
     * 绑定的模型配置ID（NULL=继承当前激活的对话模型）
     */
    private Long modelConfigId;

    /**
     * 温度（NULL=继承模型配置默认值）
     */
    private Double temperature;

    /**
     * MaxTokens（NULL=继承模型配置默认值）
     */
    private Integer maxTokens;

    /**
     * 绑定的 skill ID JSON 数组（能力白名单）
     */
    private String skills;

    /**
     * 绑定的工具名 JSON 数组（工具白名单）
     */
    private String tools;

    /**
     * 日 token 配额（0=不限）
     */
    private Long dailyTokenQuota;

    /**
     * 排序（越小越靠前）
     */
    private Integer sortNum;

    /**
     * 状态: 1启用 0停用
     */
    private Integer status;

    /**
     * 复制来源智能体ID（内置升级时不动副本，仅提示可合并）
     */
    private String baseAgentId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updated;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public String getSkills() { return skills; }
    public void setSkills(String skills) { this.skills = skills; }

    public String getTools() { return tools; }
    public void setTools(String tools) { this.tools = tools; }

    public Long getDailyTokenQuota() { return dailyTokenQuota; }
    public void setDailyTokenQuota(Long dailyTokenQuota) { this.dailyTokenQuota = dailyTokenQuota; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getBaseAgentId() { return baseAgentId; }
    public void setBaseAgentId(String baseAgentId) { this.baseAgentId = baseAgentId; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }

    public LocalDateTime getUpdated() { return updated; }
    public void setUpdated(LocalDateTime updated) { this.updated = updated; }
}
