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
 * 附件目录（附件分类树）
 *
 * <p>parent_id 组织层级结构，0 表示根目录；附件表通过 directory_id 关联，
 * 0 表示"未分类"。目录为全站共享（管理员维护分类体系，普通用户按权限使用）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
@TableName("attachment_directory")
public class AttachmentDirectory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 根目录标识（parent_id = 0）
     */
    public static final long ROOT_PARENT_ID = 0L;

    /**
     * 附件未分类时的 directory_id 值
     */
    public static final long UNCLASSIFIED_ID = 0L;

    /**
     * AI 生图结果的预置目录名（不存在时自动创建）
     */
    public static final String AI_GENERATED_DIR_NAME = "AI 生成";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 父目录ID，0 为根目录
     */
    private Long parentId;

    /**
     * 目录名称
     */
    private String name;

    /**
     * 排序（越小越靠前）
     */
    private Integer sortNum;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updated;

    /**
     * 子目录（树形展示用，非表字段）
     */
    @TableField(exist = false)
    private List<AttachmentDirectory> children = new ArrayList<>();

    /**
     * 目录下附件数量（树形展示用，非表字段）
     */
    @TableField(exist = false)
    private Long attachmentCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public LocalDateTime getCreated() { return created; }
    public void setCreated(LocalDateTime created) { this.created = created; }

    public LocalDateTime getUpdated() { return updated; }
    public void setUpdated(LocalDateTime updated) { this.updated = updated; }

    public List<AttachmentDirectory> getChildren() { return children; }
    public void setChildren(List<AttachmentDirectory> children) { this.children = children; }

    public Long getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(Long attachmentCount) { this.attachmentCount = attachmentCount; }

    @Override
    public String toString() {
        return "AttachmentDirectory{" +
                "id=" + id +
                ", parentId=" + parentId +
                ", name='" + name + '\'' +
                ", sortNum=" + sortNum +
                '}';
    }
}
