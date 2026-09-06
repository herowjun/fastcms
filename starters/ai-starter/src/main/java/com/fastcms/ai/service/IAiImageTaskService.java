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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.ai.image.ImageGenRequest;
import com.fastcms.entity.AiImageTask;

/**
 * AI 生图任务服务（文生图/修图）
 *
 * <p>任务提交后异步执行（生图上游耗时 10~60 秒），
 * 结果图片统一下载转存附件库（归档到"AI 生成"目录），
 * 前端轮询任务状态接口获取进度与结果。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
public interface IAiImageTaskService extends IService<AiImageTask> {

    /**
     * 提交生图任务（文生图/修图）
     *
     * <p>校验请求 → 落库 pending → 提交异步执行 → 立即返回任务记录（前端轮询状态）。</p>
     *
     * @param request 生图请求
     * @param userId  发起用户ID
     * @return 已创建的任务（status=pending）
     */
    AiImageTask submit(ImageGenRequest request, Long userId);

    /**
     * 获取任务详情（含 resultPaths 解析后的 results 视图）
     * <p>归属校验由 Controller 层完成（管理员可查全部，普通用户只能查自己的）</p>
     *
     * @param id 任务ID
     * @return 任务详情
     */
    AiImageTask getTaskDetail(Long id);

    /**
     * 重试失败的任务（复用原参数重新执行）
     * <p>归属校验由 Controller 层完成；仅 failed 状态可重试</p>
     *
     * @param id 任务ID
     * @return 重置后的任务（status=pending）
     */
    AiImageTask retry(Long id);

    /**
     * 应用模板图片修图结果（用户对比确认后调用）
     * <p>修图任务成功后结果仅存附件库，不回写模板；用户确认满意后调用本方法
     * 将结果第一张回写模板文件（原图先备份为 .bak）。归属校验由 Controller 层完成。</p>
     *
     * @param id 任务ID
     * @return 已应用的任务
     */
    AiImageTask applyToTemplate(Long id);

    /**
     * 分页查询任务列表
     *
     * @param page     分页参数
     * @param userId   当前用户ID
     * @param isAdmin  是否管理员（true 查全部，false 只查自己）
     * @param taskType 任务类型过滤（t2i/edit，空查全部）
     * @param status   状态过滤（pending/running/success/failed，空查全部）
     * @return 分页结果
     */
    Page<AiImageTask> pageTasks(Page<AiImageTask> page, Long userId, boolean isAdmin, String taskType, String status);

}
