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
package com.fastcms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.entity.AiUsageLog;
import com.fastcms.mapper.AiUsageLogMapper;
import com.fastcms.service.IAiUsageLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 调用审计日志 Service 实现
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Service
public class AiUsageLogServiceImpl extends ServiceImpl<AiUsageLogMapper, AiUsageLog> implements IAiUsageLogService {

    @Override
    public void record(AiUsageLog usageLog) {
        if (usageLog == null || usageLog.getUserId() == null || usageLog.getScene() == null) {
            return;
        }
        save(usageLog);
    }

    @Override
    public long getTodayUsedTokens(Long userId) {
        if (userId == null) {
            return 0L;
        }
        LocalDateTime startTime = LocalDate.now().atStartOfDay();
        LocalDateTime endTime = startTime.plusDays(1);
        Long tokens = baseMapper.sumTodayTokens(userId, startTime, endTime);
        return tokens == null ? 0L : tokens;
    }

    @Override
    public List<Map<String, Object>> statsByScene(LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.statsByScene(startTime, endTime);
    }

    @Override
    public List<Map<String, Object>> statsByUser(LocalDateTime startTime, LocalDateTime endTime, int limit) {
        return baseMapper.statsByUser(startTime, endTime, Math.max(1, Math.min(limit, 100)));
    }
}
