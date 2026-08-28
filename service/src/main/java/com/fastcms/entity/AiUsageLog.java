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
 * AI 调用审计日志
 *
 * <p>每次 AI 模型调用（模板生成/调整、文章生成/改写等场景）落一条记录，
 * 用于成本核算与按日聚合的配额统计。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@TableName("ai_usage_log")
public class AiUsageLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 触发用户
     */
    private Long userId;

    /**
     * 场景: TEMPLATE_GEN / TEMPLATE_ADJUST / ARTICLE_GEN / ARTICLE_REWRITE / ARTICLE_FIELD
     */
    private String scene;

    /**
     * 关联会话ID（无状态场景为空）
     */
    private String sessionId;

    /**
     * 使用的模型名
     */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /**
     * 耗时毫秒
     */
    private Long durationMs;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 失败原因
     */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }
}
