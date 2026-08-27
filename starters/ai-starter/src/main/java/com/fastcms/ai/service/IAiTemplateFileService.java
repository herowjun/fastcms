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
import com.fastcms.entity.AiTemplateFile;

import java.util.List;

/**
 * AI 模板生成文件 Service
 *
 * <p>持久化 AI 在会话中生成的模板文件，便于跨重启恢复，
 * 同时也作为"应用模板"时的源文件。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiTemplateFileService extends IService<AiTemplateFile> {

    /**
     * 按 sessionId 查询所有文件
     */
    List<AiTemplateFile> listBySessionId(String sessionId);

    /**
     * 保存或更新文件（按 session_id + file_path 唯一）
     *
     * @param sessionId 会话 ID
     * @param filePath  相对路径，如 {@code index.html}
     * @param content   文件内容
     * @param action    操作类型: create/modify/delete
     */
    AiTemplateFile saveOrUpdateFile(String sessionId, String filePath, String content, String action);

    /**
     * 删除会话所有文件
     */
    void deleteBySessionId(String sessionId);

    /**
     * 删除会话中的指定文件记录（回滚后清理对应的展示记录）
     */
    void removeFile(String sessionId, String filePath);

}
