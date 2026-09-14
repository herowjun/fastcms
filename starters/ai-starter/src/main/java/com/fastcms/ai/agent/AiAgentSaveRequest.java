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

import java.util.List;

/**
 * 智能体保存请求（仅自定义 chat 型；内置智能体只读）
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public class AiAgentSaveRequest {

    /** 数据库ID（null=新建） */
    private Long id;

    /** 智能体名称（必填） */
    private String name;

    private String description;

    /** 系统提示词（chat 型必填，支持 {{site.name}} 等站点变量） */
    private String systemPrompt;

    /** 绑定的模型配置ID（null=继承当前激活的对话模型） */
    private Long modelConfigId;

    /** 温度（null=继承模型配置默认值） */
    private Double temperature;

    /** MaxTokens（null=继承模型配置默认值） */
    private Integer maxTokens;

    /** skill 白名单（须存在于 SkillRegistry） */
    private List<String> skills;

    /** 工具白名单（须存在于 AiToolRegistry） */
    private List<String> tools;

    /** 日 token 配额（0=不限） */
    private Long dailyTokenQuota;

    private Integer sortNum;

    /** 1启用 0停用 */
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }

    public Long getDailyTokenQuota() { return dailyTokenQuota; }
    public void setDailyTokenQuota(Long dailyTokenQuota) { this.dailyTokenQuota = dailyTokenQuota; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
