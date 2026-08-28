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
package com.fastcms.web.controller.admin;

import com.fastcms.common.auth.ActionTypes;
import com.fastcms.common.auth.Secured;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import com.fastcms.entity.AiUsageLog;
import com.fastcms.service.IAiUsageLogService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 用量审计管理
 *
 * <p>提供按场景/按用户的 token 消耗统计与调用明细查询。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@RestController
@RequestMapping(com.fastcms.common.constants.FastcmsConstants.ADMIN_MAPPING + "/ai/usage")
public class AiUsageController {

    @Autowired
    private IAiUsageLogService usageLogService;

    /**
     * 用量统计：按场景聚合 + 按用户聚合（默认最近 7 天）
     */
    @GetMapping("stats")
    @Secured(name = "fastcms.resource.name.ai.usage.list", resource = "ai:usage:list", action = ActionTypes.READ)
    public RestResult<Map<String, Object>> stats(@RequestParam(value = "days", defaultValue = "7") int days) {
        days = Math.max(1, Math.min(days, 90));
        LocalDateTime startTime = LocalDate.now().minusDays(days - 1).atStartOfDay();
        LocalDateTime endTime = startTime.plusDays(days);

        Map<String, Object> result = new HashMap<>();
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("byScene", usageLogService.statsByScene(startTime, endTime));
        result.put("byUser", usageLogService.statsByUser(startTime, endTime, 10));
        return RestResultUtils.success(result);
    }

    /**
     * 调用明细分页（可按用户/场景过滤）
     */
    @GetMapping("logs")
    @Secured(name = "fastcms.resource.name.ai.usage.list", resource = "ai:usage:list", action = ActionTypes.READ)
    public RestResult<Page<AiUsageLog>> logs(@RequestParam(value = "page", defaultValue = "1") long page,
                                             @RequestParam(value = "pageSize", defaultValue = "20") long pageSize,
                                             @RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "scene", required = false) String scene) {
        QueryWrapper<AiUsageLog> wrapper = new QueryWrapper<>();
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        if (StringUtils.hasText(scene)) {
            wrapper.eq("scene", scene);
        }
        wrapper.orderByDesc("id");
        return RestResultUtils.success(usageLogService.page(new Page<>(page, Math.min(pageSize, 100)), wrapper));
    }
}
