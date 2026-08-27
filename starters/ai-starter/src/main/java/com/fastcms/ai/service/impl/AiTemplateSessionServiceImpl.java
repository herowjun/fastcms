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
import com.fastcms.ai.service.IAiTemplateSessionService;
import com.fastcms.entity.AiTemplateSession;
import com.fastcms.mapper.AiTemplateSessionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 模板生成会话 Service 实现
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiTemplateSessionServiceImpl
        extends ServiceImpl<AiTemplateSessionMapper, AiTemplateSession>
        implements IAiTemplateSessionService {

    @Override
    public AiTemplateSession getBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        return getOne(Wrappers.<AiTemplateSession>lambdaQuery()
                .eq(AiTemplateSession::getSessionId, sessionId)
                .last("limit 1"));
    }

    @Override
    public List<AiTemplateSession> listByUserId(Long userId) {
        return list(Wrappers.<AiTemplateSession>lambdaQuery()
                .eq(AiTemplateSession::getUserId, userId)
                .orderByDesc(AiTemplateSession::getCreated)
                // 同秒创建时按 id 兜底，保证排序稳定
                .orderByDesc(AiTemplateSession::getId));
    }

    @Override
    public void updateStatus(String sessionId, String status) {
        AiTemplateSession session = getBySessionId(sessionId);
        if (session == null) {
            return;
        }
        session.setStatus(status);
        updateById(session);
    }

}
