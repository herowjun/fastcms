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
import com.fastcms.ai.service.IAiTemplateFileService;
import com.fastcms.entity.AiTemplateFile;
import com.fastcms.mapper.AiTemplateFileMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 模板生成文件 Service 实现
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiTemplateFileServiceImpl
        extends ServiceImpl<AiTemplateFileMapper, AiTemplateFile>
        implements IAiTemplateFileService {

    @Override
    public List<AiTemplateFile> listBySessionId(String sessionId) {
        return list(Wrappers.<AiTemplateFile>lambdaQuery()
                .eq(AiTemplateFile::getSessionId, sessionId)
                .orderByAsc(AiTemplateFile::getId));
    }

    @Override
    public AiTemplateFile saveOrUpdateFile(String sessionId, String filePath, String content, String action) {
        AiTemplateFile existing = getOne(Wrappers.<AiTemplateFile>lambdaQuery()
                .eq(AiTemplateFile::getSessionId, sessionId)
                .eq(AiTemplateFile::getFilePath, filePath)
                .last("limit 1"));
        if (existing == null) {
            existing = new AiTemplateFile();
            existing.setSessionId(sessionId);
            existing.setFilePath(filePath);
        }
        existing.setContent(content);
        existing.setAction(action);
        saveOrUpdate(existing);
        return existing;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        remove(Wrappers.<AiTemplateFile>lambdaQuery()
                .eq(AiTemplateFile::getSessionId, sessionId));
    }

    @Override
    public void removeFile(String sessionId, String filePath) {
        remove(Wrappers.<AiTemplateFile>lambdaQuery()
                .eq(AiTemplateFile::getSessionId, sessionId)
                .eq(AiTemplateFile::getFilePath, filePath));
    }

}
