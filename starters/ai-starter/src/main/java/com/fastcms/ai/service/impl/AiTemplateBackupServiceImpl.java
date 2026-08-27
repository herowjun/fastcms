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
package com.fastcms.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.ai.service.IAiTemplateBackupService;
import com.fastcms.entity.AiTemplateFileBackup;
import com.fastcms.mapper.AiTemplateFileBackupMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * AI 模板文件修改前备份 Service 实现
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiTemplateBackupServiceImpl
        extends ServiceImpl<AiTemplateFileBackupMapper, AiTemplateFileBackup>
        implements IAiTemplateBackupService {

    private static final Logger log = LoggerFactory.getLogger(AiTemplateBackupServiceImpl.class);

    @Override
    public void backupBeforeWrite(String sessionId, Long messageId, String filePath, Path target) {
        AiTemplateFileBackup backup = new AiTemplateFileBackup();
        backup.setSessionId(sessionId);
        backup.setMessageId(messageId);
        backup.setFilePath(filePath);

        if (Files.isRegularFile(target)) {
            backup.setExisted(true);
            try {
                backup.setContent(Files.readString(target, StandardCharsets.UTF_8));
            } catch (IOException e) {
                // 读不到旧内容时仍然记录备份（existed=true, content=null），
                // 回滚时按"文件存在但旧内容缺失"处理并告警，不静默跳过
                log.warn("读取待备份文件失败: {}", target, e);
            }
        } else {
            backup.setExisted(false);
        }

        save(backup);
    }

    @Override
    public boolean hasBackups(String sessionId) {
        return count(Wrappers.<AiTemplateFileBackup>lambdaQuery()
                .eq(AiTemplateFileBackup::getSessionId, sessionId)) > 0;
    }

    @Override
    public List<AiTemplateFileBackup> listLatestRoundBackups(String sessionId) {
        AiTemplateFileBackup latest = getOne(Wrappers.<AiTemplateFileBackup>lambdaQuery()
                .eq(AiTemplateFileBackup::getSessionId, sessionId)
                .orderByDesc(AiTemplateFileBackup::getMessageId)
                .last("limit 1"));
        if (latest == null) {
            return Collections.emptyList();
        }
        return list(Wrappers.<AiTemplateFileBackup>lambdaQuery()
                .eq(AiTemplateFileBackup::getSessionId, sessionId)
                .eq(AiTemplateFileBackup::getMessageId, latest.getMessageId())
                .orderByAsc(AiTemplateFileBackup::getId));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        remove(Wrappers.<AiTemplateFileBackup>lambdaQuery()
                .eq(AiTemplateFileBackup::getSessionId, sessionId));
    }

    @Override
    public void deleteByMessageId(Long messageId) {
        remove(Wrappers.<AiTemplateFileBackup>lambdaQuery()
                .eq(AiTemplateFileBackup::getMessageId, messageId));
    }

}
