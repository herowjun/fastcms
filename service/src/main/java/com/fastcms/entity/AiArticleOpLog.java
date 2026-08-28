/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
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
 * AI 文章划词操作记录
 *
 * <p>文章编辑页划词改写/扩写/润色/翻译时落一条记录，
 * 保存原文、AI 结果与思考过程，供历史回看。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@TableName("ai_article_op_log")
public class AiArticleOpLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 触发用户
     */
    private Long userId;

    /**
     * 关联文章ID（新建文章保存前为空，保存后由前端触发绑定）
     */
    private Long articleId;

    /**
     * 操作类型: rewrite/expand/polish/translate
     */
    private String operation;

    /**
     * 原选中文本
     */
    private String originalText;

    /**
     * AI 改写结果
     */
    private String rewrittenText;

    /**
     * 思考过程
     */
    private String reasoning;

    /**
     * 使用的模型名
     */
    private String model;

    /**
     * 耗时毫秒
     */
    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }

    public String getRewrittenText() { return rewrittenText; }
    public void setRewrittenText(String rewrittenText) { this.rewrittenText = rewrittenText; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }
}
