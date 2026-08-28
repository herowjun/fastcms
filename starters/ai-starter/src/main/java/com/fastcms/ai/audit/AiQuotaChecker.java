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
package com.fastcms.ai.audit;

import com.fastcms.ai.autoconfigure.FastcmsAiProperties;
import com.fastcms.service.IAiUsageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 调用配额检查器
 *
 * <p>在模型调用前检查用户当日（自然日）已消耗 token 是否达到
 * {@code fastcms.ai.daily-token-quota} 上限（0 = 不限）。
 * 配额数据基于 {@code ai_usage_log} 审计表按日聚合，无需单独的配额表。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiQuotaChecker {

    private static final Logger log = LoggerFactory.getLogger(AiQuotaChecker.class);

    private final IAiUsageLogService usageLogService;
    private final FastcmsAiProperties properties;

    public AiQuotaChecker(IAiUsageLogService usageLogService, FastcmsAiProperties properties) {
        this.usageLogService = usageLogService;
        this.properties = properties;
    }

    /**
     * 检查用户配额，超限抛 {@link AiQuotaExceededException}（由调用方转为用户可见的错误提示）
     */
    public void check(Long userId) {
        long quota = properties.getDailyTokenQuota();
        if (quota <= 0 || userId == null) {
            return;
        }
        long used = usageLogService.getTodayUsedTokens(userId);
        if (used >= quota) {
            log.warn("AI 配额超限: userId={}, used={}, quota={}", userId, used, quota);
            throw new AiQuotaExceededException(
                    String.format("今日 AI 用量已达上限（%d/%d token），请明天再试或联系管理员调整配额", used, quota));
        }
    }
}
