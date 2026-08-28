/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.entity.AiArticleOpLog;

import java.util.List;

/**
 * AI 文章划词操作记录 Service
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiArticleOpLogService extends IService<AiArticleOpLog> {

    /**
     * 记录一次划词操作
     *
     * @param opLog 操作记录（userId/operation 必填）
     * @return 落库后的记录ID
     */
    Long record(AiArticleOpLog opLog);

    /**
     * 查询文章的 AI 操作历史（按时间倒序）
     *
     * @param articleId 文章ID
     * @param userId 当前用户（权限过滤）
     */
    List<AiArticleOpLog> listByArticle(Long articleId, Long userId);

    /**
     * 将本次页面会话期间产生的记录绑定到文章（新建文章保存成功后调用）
     *
     * @param articleId 文章ID
     * @param opIds 操作记录ID列表
     * @param userId 当前用户（只能绑定自己的记录）
     * @return 绑定条数
     */
    int bindToArticle(Long articleId, List<Long> opIds, Long userId);
}
