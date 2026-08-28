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
package com.fastcms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.entity.AiArticleOpLog;
import com.fastcms.mapper.AiArticleOpLogMapper;
import com.fastcms.service.IAiArticleOpLogService;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * AI 文章划词操作记录 Service 实现
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiArticleOpLogServiceImpl extends ServiceImpl<AiArticleOpLogMapper, AiArticleOpLog> implements IAiArticleOpLogService {

    @Override
    public Long record(AiArticleOpLog opLog) {
        if (opLog == null || opLog.getUserId() == null || opLog.getOperation() == null) {
            return null;
        }
        save(opLog);
        return opLog.getId();
    }

    @Override
    public List<AiArticleOpLog> listByArticle(Long articleId, Long userId) {
        return list(new LambdaQueryWrapper<AiArticleOpLog>()
                .eq(AiArticleOpLog::getArticleId, articleId)
                .eq(AiArticleOpLog::getUserId, userId)
                .orderByDesc(AiArticleOpLog::getCreated)
                .orderByDesc(AiArticleOpLog::getId));
    }

    @Override
    public int bindToArticle(Long articleId, List<Long> opIds, Long userId) {
        if (articleId == null || CollectionUtils.isEmpty(opIds) || userId == null) {
            return 0;
        }
        return baseMapper.update(null, new LambdaUpdateWrapper<AiArticleOpLog>()
                .in(AiArticleOpLog::getId, opIds)
                .eq(AiArticleOpLog::getUserId, userId)
                .set(AiArticleOpLog::getArticleId, articleId));
    }
}
