package com.fastcms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.entity.Attachment;
import com.fastcms.entity.AttachmentDirectory;
import com.fastcms.mapper.AttachmentDirectoryMapper;
import com.fastcms.service.IAttachmentDirectoryService;
import com.fastcms.service.IAttachmentService;
import com.fastcms.utils.I18nUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.fastcms.service.IAttachmentDirectoryService.AttachmentDirectoryI18n.ATTACHMENT_DIRECTORY_NAME_EXISTS;
import static com.fastcms.service.IAttachmentDirectoryService.AttachmentDirectoryI18n.ATTACHMENT_DIRECTORY_NAME_REQUIRED;
import static com.fastcms.service.IAttachmentDirectoryService.AttachmentDirectoryI18n.ATTACHMENT_DIRECTORY_NOT_EXIST;

/**
 * 附件目录服务实现
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
@Service
public class AttachmentDirectoryServiceImpl extends ServiceImpl<AttachmentDirectoryMapper, AttachmentDirectory>
        implements IAttachmentDirectoryService {

    @Autowired
    @Lazy
    private IAttachmentService attachmentService;

    @Override
    public List<AttachmentDirectory> getTree(Long userId, boolean isAdmin) {
        List<AttachmentDirectory> all = list(Wrappers.<AttachmentDirectory>lambdaQuery()
                .orderByAsc(AttachmentDirectory::getSortNum)
                .orderByAsc(AttachmentDirectory::getId));

        // 统计各目录附件数（一次分组查询，避免逐目录 count）；
        // 与附件列表接口一致：非管理员只统计自己的附件
        Map<Long, Long> countMap = attachmentService.list(Wrappers.<Attachment>lambdaQuery()
                        .select(Attachment::getDirectoryId)
                        .eq(!isAdmin, Attachment::getCreateUserId, userId))
                .stream()
                .filter(a -> a.getDirectoryId() != null)
                .collect(Collectors.groupingBy(Attachment::getDirectoryId, Collectors.counting()));

        // 组装树
        Map<Long, List<AttachmentDirectory>> byParent = all.stream()
                .collect(Collectors.groupingBy(AttachmentDirectory::getParentId, LinkedHashMap::new, Collectors.toList()));

        List<AttachmentDirectory> roots = byParent.getOrDefault(AttachmentDirectory.ROOT_PARENT_ID, List.of());
        for (AttachmentDirectory dir : all) {
            dir.setChildren(byParent.getOrDefault(dir.getId(), List.of()));
            Long count = countMap.get(dir.getId());
            dir.setAttachmentCount(count == null ? 0L : count);
        }
        // "未分类"（directoryId=0）的数量不在此返回：前端选中未分类节点时
        // 由附件列表接口按 directoryId=0 过滤的 total 提供
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttachmentDirectory saveDirectory(AttachmentDirectory directory) {
        if (StringUtils.isBlank(directory.getName())) {
            throw new IllegalArgumentException(I18nUtils.getMessage(ATTACHMENT_DIRECTORY_NAME_REQUIRED));
        }
        if (directory.getParentId() == null || directory.getParentId() == AttachmentDirectory.ROOT_PARENT_ID) {
            directory.setParentId(AttachmentDirectory.ROOT_PARENT_ID);
        } else {
            AttachmentDirectory parent = getById(directory.getParentId());
            if (parent == null) {
                throw new IllegalArgumentException(I18nUtils.getMessage(ATTACHMENT_DIRECTORY_NOT_EXIST));
            }
        }
        // 同父目录下名称唯一
        Long dup = count(Wrappers.<AttachmentDirectory>lambdaQuery()
                .eq(AttachmentDirectory::getParentId, directory.getParentId())
                .eq(AttachmentDirectory::getName, directory.getName())
                .ne(directory.getId() != null, AttachmentDirectory::getId, directory.getId()));
        if (dup > 0) {
            throw new IllegalArgumentException(I18nUtils.getMessage(ATTACHMENT_DIRECTORY_NAME_EXISTS));
        }
        saveOrUpdate(directory);
        return directory;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDirectory(Long id) {
        AttachmentDirectory dir = getById(id);
        if (dir == null) {
            return;
        }
        // 子目录提升到被删目录的父级
        List<AttachmentDirectory> children = list(Wrappers.<AttachmentDirectory>lambdaQuery()
                .eq(AttachmentDirectory::getParentId, id));
        for (AttachmentDirectory child : children) {
            child.setParentId(dir.getParentId());
        }
        if (!children.isEmpty()) {
            updateBatchById(children);
        }
        // 目录下附件移回未分类
        attachmentService.update(Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getDirectoryId, id)
                .set(Attachment::getDirectoryId, AttachmentDirectory.UNCLASSIFIED_ID));
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveAttachments(List<Long> attachmentIds, Long directoryId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        if (directoryId == null) {
            directoryId = AttachmentDirectory.UNCLASSIFIED_ID;
        }
        if (directoryId != AttachmentDirectory.UNCLASSIFIED_ID) {
            AttachmentDirectory target = getById(directoryId);
            if (target == null) {
                throw new IllegalArgumentException(I18nUtils.getMessage(ATTACHMENT_DIRECTORY_NOT_EXIST));
            }
        }
        attachmentService.update(Wrappers.<Attachment>lambdaUpdate()
                .in(Attachment::getId, attachmentIds)
                .set(Attachment::getDirectoryId, directoryId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttachmentDirectory getOrCreateAiGeneratedDir() {
        AttachmentDirectory dir = getOne(Wrappers.<AttachmentDirectory>lambdaQuery()
                .eq(AttachmentDirectory::getParentId, AttachmentDirectory.ROOT_PARENT_ID)
                .eq(AttachmentDirectory::getName, AttachmentDirectory.AI_GENERATED_DIR_NAME)
                .last("limit 1"));
        if (dir != null) {
            return dir;
        }
        dir = new AttachmentDirectory();
        dir.setParentId(AttachmentDirectory.ROOT_PARENT_ID);
        dir.setName(AttachmentDirectory.AI_GENERATED_DIR_NAME);
        dir.setSortNum(9999);
        save(dir);
        return dir;
    }
}
