/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017.
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
package com.fastcms.ai.capability;

import com.fastcms.ai.tool.AiTool;
import org.springframework.stereotype.Component;

/**
 * 插件能力查询 AI 工具（L2 详情按需拉取）
 *
 * <p>两级 prompt 注入的 L2 侧：</p>
 * <ul>
 *     <li>L1 摘要（每能力 2 行）常驻 system prompt，让 AI 知道"有哪些能力"</li>
 *     <li>本工具按需拉取单个能力的完整契约（接口路径/参数/响应/认证流/前端流程/
 *         官方 snippet 清单），让 AI 知道"怎么用"——仅在 AI 决定集成该能力时才消耗 token</li>
 * </ul>
 *
 * <p>AI 写支付/下载等交互 JS 前<strong>必须</strong>先调用本工具获取接口契约，
 * 禁止凭记忆编造接口路径与字段（这是防止死代码的关键闸门）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class CapabilityTool {

    private final PluginCapabilityRegistry registry;

    public CapabilityTool(PluginCapabilityRegistry registry) {
        this.registry = registry;
    }

    /**
     * 获取插件能力的详细契约（接口端点/参数/响应结构/认证流/前端交互流程/官方 snippet 清单）
     *
     * <p>何时调用：需要在页面中集成某能力（如支付、下载、登录）时，<b>写任何 HTML/JS 之前</b>先调用本工具。
     * 返回的接口契约是写前端调用代码的唯一依据，禁止编造路径与字段。</p>
     *
     * @param capabilityId 能力 id（如 "payment:wxpay-scan"、见 system prompt 能力清单中的 [xxx] 标识）
     * @return 能力详细契约文本；能力不存在时返回可用能力列表提示
     */
    @AiTool(name = "get_capability_detail",
            description = "获取插件能力的详细契约（接口路径/参数/响应/认证流/前端交互流程/官方snippet清单）。"
                    + "在页面集成支付、下载等能力前必须先调用本工具，禁止凭记忆编造接口。"
                    + "参数capabilityId见system prompt能力清单的[xxx]标识")
    public String getCapabilityDetail(String capabilityId) {
        if (capabilityId == null || capabilityId.isBlank()) {
            return "capabilityId 不能为空。可用能力:\n" + registry.buildManifest();
        }
        return registry.buildCapabilityDetail(capabilityId.trim());
    }

}
