package com.fastcms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.entity.AttachmentDirectory;

import java.util.List;

/**
 * 附件目录服务
 *
 * <p>目录为全站共享的分类体系：管理员维护，附件通过 directory_id 归属，
 * 0 表示未分类。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
public interface IAttachmentDirectoryService extends IService<AttachmentDirectory> {

    /**
     * 获取目录树（含每个目录的附件数量统计）
     *
     * @param userId  当前用户ID（附件计数与列表接口一致：非管理员只统计自己的附件）
     * @param isAdmin 是否管理员（true 统计全部附件）
     * @return 根节点列表（已按 sortNum、id 排序，children 已组装）
     */
    List<AttachmentDirectory> getTree(Long userId, boolean isAdmin);

    /**
     * 保存目录（新增或重命名）
     *
     * <p>id 为空新增；id 非空为重命名/改排序。同父目录下名称不可重复。</p>
     *
     * @param directory 目录（parentId 必填，0 为根）
     * @return 保存后的目录
     */
    AttachmentDirectory saveDirectory(AttachmentDirectory directory);

    /**
     * 删除目录
     *
     * <p>删除后：目录下的附件移回"未分类"（directoryId=0），子目录提升到被删目录的父级。
     * 文件本身不删除。</p>
     *
     * @param id 目录ID
     */
    void deleteDirectory(Long id);

    /**
     * 批量移动附件到目标目录
     *
     * @param attachmentIds 附件ID列表
     * @param directoryId   目标目录ID（0=未分类）
     */
    void moveAttachments(List<Long> attachmentIds, Long directoryId);

    /**
     * 获取"AI 生成"预置目录（不存在时自动创建）
     *
     * <p>AI 生图/修图的结果统一归档到该目录。</p>
     *
     * @return 目录实体
     */
    AttachmentDirectory getOrCreateAiGeneratedDir();

    interface AttachmentDirectoryI18n {
        String ATTACHMENT_DIRECTORY_NOT_EXIST = "fastcms.attachment.directory.not.exist";
        String ATTACHMENT_DIRECTORY_NAME_EXISTS = "fastcms.attachment.directory.name.exists";
        String ATTACHMENT_DIRECTORY_NAME_REQUIRED = "fastcms.attachment.directory.name.required";
    }
}
