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
package com.fastcms.core.template;

/**
 * 模板「彻底删除」时的数据库残余清理扩展点。
 *
 * <p>为什么要有这个接口：模板数据的归属横跨多个模块 —— {@code menu.template_id} 在 cms 模块，
 * 而清理时机在 core 的 {@code DefaultTemplateService} 里，core <b>看不到</b> cms（依赖方向 cms → core）。
 * 由 core 在彻底删除时遍历容器里所有 {@link TemplateDataCleaner} 实现，谁的数据谁清。</p>
 *
 * <h3>清理口径（重要，别扩大）</h3>
 * <p>只清「<b>模板自身的绑定/配置</b>」——即模板不存在后这些数据就再也没有意义、也无法再被展示的：
 * 例如 {@code menu.template_id} 指向该模板的专属菜单行。</p>
 *
 * <p><b>AI 侧数据一律不动</b>：{@code ai_template_session} 及其 {@code ai_template_message} /
 * {@code ai_template_file} / {@code ai_template_file_backup}、{@code ai_image_task}、
 * {@code ai_usage_log} 全部保留。原因：{@code ai_usage_log} 是 token 用量/计费审计数据必须留着，
 * 而它的 {@code session_id} 要靠会话表才能追溯"这笔 token 花在哪个模板、哪轮对话"——
 * 删掉会话等于把计费的溯源链砍断。既然要留 usage，就必须留会话，两者口径必须一致。</p>
 *
 * <p>副作用（已知且接受）：保留下来的会话里 {@code template_id} / {@code applied_template_id}
 * 会指向一个已不存在的模板，前端「去正式模板」入口会拿到「模板不存在」提示。这是可接受的——
 * 它本来就是事实，且比丢失计费溯源数据好。</p>
 *
 * <p><b>触发时机</b>：只有「彻底删除」才会触发。移动到备份目录的模式下模板可能被拷回恢复，
 * 这些绑定必须原样保留。实现方无需自己判断模式。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public interface TemplateDataCleaner {

    /**
     * 清理指定模板在数据库里的绑定/配置残余
     *
     * @param templateId 模板id
     * @return 本次影响的记录条数（用于日志，无记录返回 0）
     */
    int cleanTemplateData(String templateId);

}
