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
import com.fastcms.entity.AiUsageLog;
import com.fastcms.service.IAiUsageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 用量记录器
 *
 * <p>各 AI 场景服务（模板生成/调整、文章生成/改写等）在模型调用结束后通过本类落审计。
 * {@code fastcms.ai.audit-enabled=false} 时静默跳过。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);

    private final IAiUsageLogService usageLogService;
    private final FastcmsAiProperties properties;

    public AiUsageRecorder(IAiUsageLogService usageLogService, FastcmsAiProperties properties) {
        this.usageLogService = usageLogService;
        this.properties = properties;
    }

    /**
     * 记录一次成功的 AI 调用
     */
    public void record(Long userId, String scene, String sessionId, String model,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens, long durationMs) {
        doRecord(userId, scene, sessionId, model, promptTokens, completionTokens, totalTokens, durationMs, true, null);
    }

    /**
     * 记录一次失败的 AI 调用（失败场景同样占用配额之外的审计价值：定位问题、统计失败率）
     */
    public void recordError(Long userId, String scene, String sessionId, String model,
                            long durationMs, String errorMsg) {
        doRecord(userId, scene, sessionId, model, 0, 0, 0, durationMs, false, truncate(errorMsg, 1000));
    }

    private void doRecord(Long userId, String scene, String sessionId, String model,
                          Integer promptTokens, Integer completionTokens, Integer totalTokens,
                          long durationMs, boolean success, String errorMsg) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            AiUsageLog usageLog = new AiUsageLog();
            usageLog.setUserId(userId);
            usageLog.setScene(scene);
            usageLog.setSessionId(sessionId);
            usageLog.setModel(model);
            usageLog.setPromptTokens(promptTokens == null ? 0 : promptTokens);
            usageLog.setCompletionTokens(completionTokens == null ? 0 : completionTokens);
            usageLog.setTotalTokens(totalTokens == null ? 0 : totalTokens);
            usageLog.setDurationMs(durationMs);
            usageLog.setSuccess(success);
            usageLog.setErrorMsg(errorMsg);
            usageLogService.record(usageLog);
        } catch (Exception e) {
            // 审计落库失败不影响业务主流程
            log.warn("AI 用量审计记录失败: userId={}, scene={}", userId, scene, e);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
