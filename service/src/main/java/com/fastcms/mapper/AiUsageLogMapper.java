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
package com.fastcms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastcms.entity.AiUsageLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 调用审计日志 Mapper
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface AiUsageLogMapper extends BaseMapper<AiUsageLog> {

    /**
     * 统计某用户当日（自然日）已消耗的总 token 数，用于配额检查
     */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM ai_usage_log WHERE user_id = #{userId} AND created >= #{startTime} AND created < #{endTime}")
    Long sumTodayTokens(@Param("userId") Long userId,
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

    /**
     * 管理端统计：按场景聚合指定时间段的调用次数与 token 消耗
     */
    @Select("SELECT scene, COUNT(*) AS callCount, COALESCE(SUM(total_tokens), 0) AS totalTokens, " +
            "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, COALESCE(SUM(completion_tokens), 0) AS completionTokens " +
            "FROM ai_usage_log WHERE created >= #{startTime} AND created < #{endTime} GROUP BY scene ORDER BY totalTokens DESC")
    List<Map<String, Object>> statsByScene(@Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime);

    /**
     * 管理端统计：按用户聚合指定时间段的调用次数与 token 消耗（Top N）
     */
    @Select("SELECT user_id AS userId, COUNT(*) AS callCount, COALESCE(SUM(total_tokens), 0) AS totalTokens " +
            "FROM ai_usage_log WHERE created >= #{startTime} AND created < #{endTime} " +
            "GROUP BY user_id ORDER BY totalTokens DESC LIMIT #{limit}")
    List<Map<String, Object>> statsByUser(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("limit") int limit);
}
