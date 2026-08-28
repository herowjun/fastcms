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
package com.fastcms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.entity.AiUsageLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 调用审计日志 Service
 *
 * <p>主工程侧的 AI 治理数据面：记录每次模型调用的 token 消耗与耗时，
 * 并提供配额统计（按用户当日聚合）与管理端统计查询。
 * ai-starter 的各 AI 场景服务通过本接口落审计。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiUsageLogService extends IService<AiUsageLog> {

    /**
     * AI 场景常量
     */
    interface Scene {
        /** 模板生成（AI 新建模板） */
        String TEMPLATE_GEN = "TEMPLATE_GEN";
        /** 模板调整（编辑页 AI 调整正式模板） */
        String TEMPLATE_ADJUST = "TEMPLATE_ADJUST";
        /** 文章全文生成 */
        String ARTICLE_GEN = "ARTICLE_GEN";
        /** 文章划词改写/扩写/润色 */
        String ARTICLE_REWRITE = "ARTICLE_REWRITE";
        /** 文章单字段生成（标题/摘要/SEO） */
        String ARTICLE_FIELD = "ARTICLE_FIELD";
    }

    /**
     * 记录一次 AI 调用（审计开关关闭时静默跳过）
     */
    void record(AiUsageLog usageLog);

    /**
     * 查询某用户当日（自然日）已消耗的总 token 数
     *
     * @return 已消耗 token 数，无记录返回 0
     */
    long getTodayUsedTokens(Long userId);

    /**
     * 管理端统计：按场景聚合指定时间段的调用次数与 token 消耗
     */
    List<Map<String, Object>> statsByScene(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 管理端统计：按用户聚合指定时间段的调用次数与 token 消耗（Top N）
     */
    List<Map<String, Object>> statsByUser(LocalDateTime startTime, LocalDateTime endTime, int limit);
}
