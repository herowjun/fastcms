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
import com.fastcms.entity.AiTemplateSession;

import java.util.List;

/**
 * AI 模板生成会话 Service
 *
 * <p>接口放在 ai-starter 模块（与 {@link IAiModelConfigService} 同包同模块），
 * 避免业务代码直接依赖 Mapper。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiTemplateSessionService extends IService<AiTemplateSession> {

    /**
     * 通过 sessionId 查询会话
     */
    AiTemplateSession getBySessionId(String sessionId);

    /**
     * 列出用户的所有会话，按创建时间倒序（最近的在前）
     */
    List<AiTemplateSession> listByUserId(Long userId);

    /**
     * 更新会话状态
     */
    void updateStatus(String sessionId, String status);

}
