package com.fastcms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 生图任务（文生图/修图异步任务）
 *
 * <p>任务提交后立即落库（pending），由执行线程更新状态；
 * 前端通过任务状态接口轮询。生成结果统一转存附件库，
 * resultPaths 记录 [{filePath, attachmentId}]（JSON 数组，由上层解析填充 results 视图）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
@TableName("ai_image_task")
public class AiImageTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务状态：待执行
     */
    public static final String STATUS_PENDING = "pending";
    /**
     * 任务状态：执行中
     */
    public static final String STATUS_RUNNING = "running";
    /**
     * 任务状态：成功
     */
    public static final String STATUS_SUCCESS = "success";
    /**
     * 任务状态：失败
     */
    public static final String STATUS_FAILED = "failed";

    /**
     * 任务类型：文生图
     */
    public static final String TYPE_T2I = "t2i";
    /**
     * 任务类型：修图
     */
    public static final String TYPE_EDIT = "edit";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联模板会话ID（媒体库生图为 NULL）
     */
    private String sessionId;

    /**
     * 发起用户ID
     */
    private Long userId;

    /**
     * 任务类型: t2i-文生图 edit-修图
     */
    private String taskType;

    /**
     * 生图模型名称（如 qwen-image / qwen-image-edit）
     */
    private String model;

    /**
     * 提示词（业务语义描述）
     */
    private String prompt;

    /**
     * 修图原图附件ID
     */
    private Long sourceAttachmentId;

    /**
     * 修图源/回写目标模板ID（模板 static 图片修图场景）
     */
    private String templateId;

    /**
     * 修图源/回写目标模板内文件路径（回写前原图自动备份为 .bak）
     */
    private String templateFilePath;

    /**
     * 生成尺寸 宽*高（如 1664*928）
     */
    private String size;

    /**
     * 生成张数 1-4
     */
    private Integer num;

    /**
     * 状态: pending/running/success/failed
     */
    private String status;

    /**
     * 结果 JSON: [{filePath, attachmentId}]
     */
    private String resultPaths;

    /**
     * 失败原因
     */
    private String error;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updated;

    /**
     * 结果列表（非表字段，resultPaths 解析后的视图）
     */
    @TableField(exist = false)
    private List<TaskResult> results = new ArrayList<>();

    /**
     * 任务结果项
     */
    public static class TaskResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private String filePath;
        private Long attachmentId;
        private String url;

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public Long getAttachmentId() { return attachmentId; }
        public void setAttachmentId(Long attachmentId) { this.attachmentId = attachmentId; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public Long getSourceAttachmentId() { return sourceAttachmentId; }
    public void setSourceAttachmentId(Long sourceAttachmentId) { this.sourceAttachmentId = sourceAttachmentId; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getTemplateFilePath() { return templateFilePath; }
    public void setTemplateFilePath(String templateFilePath) { this.templateFilePath = templateFilePath; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public Integer getNum() { return num; }
    public void setNum(Integer num) { this.num = num; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResultPaths() { return resultPaths; }
    public void setResultPaths(String resultPaths) { this.resultPaths = resultPaths; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }

    public LocalDateTime getUpdated() { return updated; }
    public void setUpdated(LocalDateTime updated) { this.updated = updated; }

    public List<TaskResult> getResults() { return results; }
    public void setResults(List<TaskResult> results) { this.results = results; }

    @Override
    public String toString() {
        return "AiImageTask{" +
                "id=" + id +
                ", sessionId=" + sessionId +
                ", userId=" + userId +
                ", taskType='" + taskType + '\'' +
                ", model='" + model + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
