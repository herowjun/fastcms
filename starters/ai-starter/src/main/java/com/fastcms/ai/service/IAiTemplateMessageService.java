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
import com.fastcms.entity.AiTemplateMessage;

import java.util.List;

/**
 * AI 模板生成对话消息 Service
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiTemplateMessageService extends IService<AiTemplateMessage> {

    /**
     * 按 sessionId 查询消息，按创建时间正序
     */
    List<AiTemplateMessage> listBySessionId(String sessionId);

    /**
     * 保存一条消息
     */
    AiTemplateMessage saveMessage(String sessionId, String role, String content);

    /**
     * 保存一条消息（含推理模型思考过程，仅 assistant 消息有值）
     */
    AiTemplateMessage saveMessage(String sessionId, String role, String content, String reasoning);

    /**
     * 删除会话所有消息
     */
    void deleteBySessionId(String sessionId);

}
