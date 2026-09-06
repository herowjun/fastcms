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
 * AI 模型配置
 *
 * <p>支持配置多个 OpenAI 兼容端点（DeepSeek、通义、智谱、Kimi、本地 Ollama 等），
 * 同时只有一个处于激活状态（{@link #isActive}），运行时通过
 * {@code ChatModelFactory} 动态生成 ChatModel。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@TableName("ai_model_config")
public class AiModelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 配置名称（如"DeepSeek-聊天"、"通义-Plus"）
     */
    private String name;

    /**
     * 模型供应商类型，用于前端分组展示
     * <p>如 deepseek / qwen / zhipu / moonshot / openai / ollama / custom</p>
     */
    private String provider;

    /**
     * OpenAI 兼容 API 端点
     * <p>如 https://api.deepseek.com、https://dashscope.aliyuncs.com/compatible-mode/v1</p>
     */
    private String baseUrl;

    /**
     * API Key
     * <p>注意：返回前端时建议脱敏，前端保存时如果传 ******** 表示未修改</p>
     */
    private String apiKey;

    /**
     * 模型名称
     * <p>如 deepseek-chat、qwen-plus、gpt-4o、llama3.1</p>
     */
    private String model;

    /**
     * 用途场景：chat-对话（默认） image-生图
     * <p>同一场景内仅一条激活配置；生图场景用于 qwen-image 等 DashScope 生图模型。</p>
     */
    private String scene;

    /**
     * 温度参数 0.0-2.0
     */
    private Double temperature;

    /**
     * 最大 tokens
     */
    private Integer maxTokens;

    /**
     * 是否激活（同一时刻仅一条记录为 true）
     */
    @TableField("is_active")
    private Boolean active;

    /**
     * 排序
     */
    private Integer sortNum;

    /**
     * 备注
     */
    private String remark;

    /**
     * 自定义请求头（JSON 格式，如 {"X-Tenant":"abc"}）
     * <p>私有部署/企业网关需要额外请求头时使用；Ollama 可通过此字段传 OLLAMA_API_KEY。
     * 返回前端时无需脱敏（只含 header 名与值，值由管理员自行填写），建议长度控制在 1KB 内。</p>
     */
    @TableField("extra_headers")
    private String extraHeaders;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updated;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getExtraHeaders() { return extraHeaders; }
    public void setExtraHeaders(String extraHeaders) { this.extraHeaders = extraHeaders; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }

    public LocalDateTime getUpdated() { return updated; }
    public void setUpdated(LocalDateTime updated) { this.updated = updated; }

    @Override
    public String toString() {
        return "AiModelConfig{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", active=" + active +
                '}';
    }
}
