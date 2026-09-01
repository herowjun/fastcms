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
 * AI 模板生成会话
 *
 * <p>每个会话对应一次模板生成任务，sessionId 在前端流转，
 * 工作目录文件持久化到 {@code ai_template_file} 表，重启后可恢复。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@TableName("ai_template_session")
public class AiTemplateSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话唯一ID（UUID）
     */
    private String sessionId;

    /**
     * 模板目录名（英文，将作为 fastcms 模板的 pathName）
     */
    private String templateName;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 用户初始需求描述
     */
    private String requirement;

    /**
     * 会话状态: active / applied / closed
     */
    private String status;

    /**
     * 创建用户ID
     */
    private Long userId;

    /**
     * 会话工作目录绝对路径
     *
     * <p>生成型会话指向预览工作目录；调整型会话（templateId 非空）指向正式模板目录。</p>
     */
    private String workDir;

    /**
     * 绑定的正式模板ID（非空表示调整型会话：AI 输出直接写入正式模板目录，修改前自动备份）
     */
    private String templateId;

    /**
     * 分批流水线的规划文件清单（JSON 数组字符串，如 ["index.html","static/css/base.css"]）
     *
     * <p>持久化的目的：刷新页面后前端可重算进度卡；生成中断后下一次对话
     * 对比 {@code ai_template_file} 已有文件，只补齐缺失部分（断点续传）。</p>
     */
    private String planFiles;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updated;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getPlanFiles() { return planFiles; }
    public void setPlanFiles(String planFiles) { this.planFiles = planFiles; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }

    public LocalDateTime getUpdated() { return updated; }
    public void setUpdated(LocalDateTime updated) { this.updated = updated; }
}
