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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fastcms.ai.image.ImageGenRequest;
import com.fastcms.ai.service.IAiImageTaskService;
import com.fastcms.common.auth.ActionTypes;
import com.fastcms.common.auth.Secured;
import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import com.fastcms.core.mybatis.PageModel;
import com.fastcms.core.auth.AuthUtils;
import com.fastcms.entity.AiImageTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.fastcms.service.IResourceService.ResourceI18n.*;

/**
 * AI 生图任务管理（文生图/修图）
 *
 * <p>生图上游耗时 10~60 秒，接口全部为"提交即返回 + 前端轮询"模式：
 * generate/retry 返回 pending 任务，前端轮询 task/{id} 直到 success/failed。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
@RestController
@RequestMapping(FastcmsConstants.ADMIN_MAPPING + "/ai/image")
public class AiImageController {

    @Autowired
    private IAiImageTaskService aiImageTaskService;

    /**
     * 提交生图任务（taskType=t2i 文生图 / edit 修图）
     */
    @PostMapping("generate")
    @Secured(name = RESOURCE_NAME_AI_IMAGE_GENERATE, resource = "ai:image:generate", action = ActionTypes.WRITE)
    public RestResult<AiImageTask> generate(@RequestBody ImageGenRequest request) {
        try {
            AiImageTask task = aiImageTaskService.submit(request, AuthUtils.getUserId());
            return RestResultUtils.success(task);
        } catch (IllegalArgumentException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 查询任务状态（前端轮询）
     */
    @GetMapping("task/{id}")
    @Secured(name = RESOURCE_NAME_AI_IMAGE_TASK, resource = "ai:image:task", action = ActionTypes.READ)
    public RestResult<AiImageTask> task(@PathVariable("id") Long id) {
        AiImageTask task = aiImageTaskService.getTaskDetail(id);
        if (!Boolean.TRUE.equals(AuthUtils.isAdmin())
                && !AuthUtils.getUserId().equals(task.getUserId())) {
            return RestResultUtils.failed("只能查看自己的生图任务");
        }
        return RestResultUtils.success(task);
    }

    /**
     * 重试失败的任务
     */
    @PostMapping("retry/{id}")
    @Secured(name = RESOURCE_NAME_AI_IMAGE_RETRY, resource = "ai:image:retry", action = ActionTypes.WRITE)
    public RestResult<AiImageTask> retry(@PathVariable("id") Long id) {
        AiImageTask task = aiImageTaskService.getById(id);
        if (task == null) {
            return RestResultUtils.failed("任务不存在");
        }
        if (!Boolean.TRUE.equals(AuthUtils.isAdmin())
                && !AuthUtils.getUserId().equals(task.getUserId())) {
            return RestResultUtils.failed("只能重试自己的生图任务");
        }
        try {
            return RestResultUtils.success(aiImageTaskService.retry(id));
        } catch (IllegalArgumentException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 应用模板图片修图结果（用户对比原图/生成图确认后调用：
     * 原图备份为 .bak，结果图覆盖模板原路径）
     */
    @PostMapping("apply/{id}")
    @Secured(name = RESOURCE_NAME_AI_IMAGE_APPLY, resource = "ai:image:apply", action = ActionTypes.WRITE)
    public RestResult<AiImageTask> apply(@PathVariable("id") Long id) {
        AiImageTask task = aiImageTaskService.getById(id);
        if (task == null) {
            return RestResultUtils.failed("任务不存在");
        }
        if (!Boolean.TRUE.equals(AuthUtils.isAdmin())
                && !AuthUtils.getUserId().equals(task.getUserId())) {
            return RestResultUtils.failed("只能应用自己的生图任务");
        }
        try {
            return RestResultUtils.success(aiImageTaskService.applyToTemplate(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 任务列表（分页，非管理员只看自己的）
     */
    @GetMapping("list")
    @Secured(name = RESOURCE_NAME_AI_IMAGE_LIST, resource = "ai:image:list", action = ActionTypes.READ)
    public RestResult<Page<AiImageTask>> list(PageModel page,
                                              @RequestParam(value = "taskType", required = false) String taskType,
                                              @RequestParam(value = "status", required = false) String status) {
        boolean admin = Boolean.TRUE.equals(AuthUtils.isAdmin());
        return RestResultUtils.success(
                aiImageTaskService.pageTasks((Page<AiImageTask>) page.toPage(), AuthUtils.getUserId(), admin, taskType, status));
    }

}
