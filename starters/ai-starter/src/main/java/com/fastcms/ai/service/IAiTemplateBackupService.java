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
package com.fastcms.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.entity.AiTemplateFileBackup;

import java.nio.file.Path;
import java.util.List;

/**
 * AI 模板文件修改前备份 Service
 *
 * <p>调整型会话中 AI 修改正式模板文件前留存旧版本，
 * 备份粒度为一轮对话（messageId），支持"恢复到该轮修改前"。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiTemplateBackupService extends IService<AiTemplateFileBackup> {

    /**
     * 写入前备份：读取磁盘上的旧内容并插入备份记录
     *
     * <p>必须在文件被覆写/删除之前调用，顺序不可颠倒——
     * 崩溃在中途时最坏情况是"有备份但文件没改成"，无害；反之则不可恢复。</p>
     *
     * @param sessionId 会话ID
     * @param messageId 触发本次变更的 AI 消息ID
     * @param filePath  相对路径
     * @param target    即将被写入/删除的目标文件（绝对路径）
     */
    void backupBeforeWrite(String sessionId, Long messageId, String filePath, Path target);

    /**
     * 会话是否存在备份
     */
    boolean hasBackups(String sessionId);

    /**
     * 最新一轮（messageId 最大）的备份记录，按 id 升序
     *
     * @return 空列表表示无可回滚的修改
     */
    List<AiTemplateFileBackup> listLatestRoundBackups(String sessionId);

    /**
     * 删除会话的所有备份
     */
    void deleteBySessionId(String sessionId);

    /**
     * 删除某轮对话的所有备份（回滚完成后调用，使下一次回滚作用于更早一轮）
     */
    void deleteByMessageId(Long messageId);

}
