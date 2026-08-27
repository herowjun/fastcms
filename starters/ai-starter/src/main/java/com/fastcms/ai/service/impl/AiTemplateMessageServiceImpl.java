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
import com.fastcms.ai.service.IAiTemplateMessageService;
import com.fastcms.entity.AiTemplateMessage;
import com.fastcms.mapper.AiTemplateMessageMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 模板生成对话消息 Service 实现
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiTemplateMessageServiceImpl
        extends ServiceImpl<AiTemplateMessageMapper, AiTemplateMessage>
        implements IAiTemplateMessageService {

    @Override
    public List<AiTemplateMessage> listBySessionId(String sessionId) {
        return list(Wrappers.<AiTemplateMessage>lambdaQuery()
                .eq(AiTemplateMessage::getSessionId, sessionId)
                .orderByAsc(AiTemplateMessage::getCreated)
                .orderByAsc(AiTemplateMessage::getId));
    }

    @Override
    public AiTemplateMessage saveMessage(String sessionId, String role, String content) {
        return saveMessage(sessionId, role, content, null);
    }

    @Override
    public AiTemplateMessage saveMessage(String sessionId, String role, String content, String reasoning) {
        AiTemplateMessage message = new AiTemplateMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setReasoning(reasoning);
        save(message);
        return message;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        remove(Wrappers.<AiTemplateMessage>lambdaQuery()
                .eq(AiTemplateMessage::getSessionId, sessionId));
    }

}
