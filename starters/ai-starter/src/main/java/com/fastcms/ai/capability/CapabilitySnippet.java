/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.capability;

import java.util.List;

/**
 * 能力的官方参考实现片段（snippet）
 *
 * <p>snippet 是插件作者<strong>测试过的</strong> HTML+FTL+JS 完整交互流程，
 * 含 {@code {{PARAM}}} 占位符。AI 集成能力时优先引用 snippet（只填参数），
 * 质量与现网水平对齐；snippet 无法满足需求时才走 custom-html 逃生舱手写。</p>
 *
 * <p>snippet 硬性规范（与 custom-html 逃生舱一致）：</p>
 * <ul>
 *     <li>完全自包含：自带弹窗 DOM / 请求封装 / token 处理，禁止依赖站点 layout 的全局 helper</li>
 *     <li>JS 禁用模板字符串（${} 与 FreeMarker 冲突），一律字符串拼接</li>
 *     <li>IIFE 包裹；轮询 clearInterval 覆盖 成功/关闭/过期/beforeunload 四处</li>
 *     <li>需文章上下文时用 FTL 插值（如 {@code ${(article.id)!}}），渲染期求值</li>
 * </ul>
 *
 * @param id          snippet 标识（能力内唯一）
 * @param description 用途说明（AI 判断是否适用的依据）
 * @param params      占位符清单；SECTION_ID 由系统自动注入（防同页多实例 id 冲突），无需声明
 * @param file        snippet 源文件相对能力声明包的路径（如 snippets/wxpay-qr-flow.html）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record CapabilitySnippet(
        String id,
        String description,
        List<String> params,
        String file) {

    public List<String> safeParams() {
        return params == null ? List.of() : params;
    }
}
