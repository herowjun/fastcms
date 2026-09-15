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
import java.util.Map;

/**
 * 插件能力描述符：{@code plugin-capabilities.json} 的解析结果，AI 集成插件能力的唯一契约源
 *
 * <p><b>能力建模原则</b>：一个能力 = 一个完整业务流程（不是一接口一能力）。
 * 能力分三类角色：</p>
 * <ul>
 *     <li>{@code core}：主应用内置能力（core:order / core:scan-pay 等），恒可用，不门控</li>
 *     <li>{@code channel}：渠道能力（payment:wxpay / payment:alipay），声明组合 core 能力的完整流程</li>
 *     <li>{@code business}：业务能力（article:paid-download 等），声明 {@code requiresChannel} 依赖</li>
 * </ul>
 *
 * @param capabilityId     能力唯一标识，命名空间格式（core:order / payment:wxpay / article:paid-download）
 * @param name             能力名（中文，L1 摘要展示）
 * @param description      一句话描述（L1 摘要展示，AI 据此判断能力是否匹配用户需求）
 * @param category         能力分类（payment-channel / content-monetize / auth / share ...）
 * @param capabilityRole   core / channel / business
 * @param appliesTo        适用页面类型（article / page / index / *）
 * @param contractVersion  契约版本（升级漂移检测用）
 * @param requiresChannel  业务能力依赖的渠道能力模式（如 "payment:*"），null=无依赖
 * @param authFlow         认证流说明（mechanism / loginUrl / tokenStorage / anonymousBehavior 等自由 key）
 * @param endpoints        HTTP 端点契约清单（AI 写 JS 的唯一依据）
 * @param frontendFlow     前端交互流程（编号步骤）
 * @param compositionEvents 组合事件协议（key=事件名，value=派发/监听约定；渠道与业务能力跨插件联动用）
 * @param initialState     页面初始状态探测（已购用户直接显示下载等）
 * @param snippets         官方参考实现片段（优先于手写）
 * @param previewMock      预览模式模拟数据（key=端点 id，value=模拟行为说明）
 * @param errorHandling    异常分支处理（key=错误码/场景，value=处理方式）
 * @param detectionPatterns 已集成识别特征（正则，扫描旧模板 HTML 判断能力是否已集成）
 * @param suggestedPosition 咨询插入位置建议（after-content / sidebar / floating）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record CapabilityDescriptor(
        String capabilityId,
        String name,
        String description,
        String category,
        String capabilityRole,
        List<String> appliesTo,
        String contractVersion,
        String requiresChannel,
        Map<String, String> authFlow,
        List<CapabilityEndpoint> endpoints,
        List<String> frontendFlow,
        Map<String, String> compositionEvents,
        Map<String, String> initialState,
        List<CapabilitySnippet> snippets,
        Map<String, String> previewMock,
        Map<String, String> errorHandling,
        List<String> detectionPatterns,
        String suggestedPosition) {

    public static final String ROLE_CORE = "core";
    public static final String ROLE_CHANNEL = "channel";
    public static final String ROLE_BUSINESS = "business";

    public List<CapabilityEndpoint> safeEndpoints() {
        return endpoints == null ? List.of() : endpoints;
    }

    public List<CapabilitySnippet> safeSnippets() {
        return snippets == null ? List.of() : snippets;
    }

    public List<String> safeAppliesTo() {
        return appliesTo == null ? List.of() : appliesTo;
    }

    public List<String> safeFrontendFlow() {
        return frontendFlow == null ? List.of() : frontendFlow;
    }

    public List<String> safeDetectionPatterns() {
        return detectionPatterns == null ? List.of() : detectionPatterns;
    }

    public Map<String, String> safeAuthFlow() {
        return authFlow == null ? Map.of() : authFlow;
    }

    public Map<String, String> safeCompositionEvents() {
        return compositionEvents == null ? Map.of() : compositionEvents;
    }

    public Map<String, String> safeErrorHandling() {
        return errorHandling == null ? Map.of() : errorHandling;
    }

    public CapabilitySnippet findSnippet(String snippetId) {
        return safeSnippets().stream()
                .filter(s -> s.id().equals(snippetId))
                .findFirst().orElse(null);
    }

    public boolean isCore() {
        return ROLE_CORE.equalsIgnoreCase(capabilityRole)
                || (capabilityId != null && capabilityId.startsWith("core:"));
    }
}
