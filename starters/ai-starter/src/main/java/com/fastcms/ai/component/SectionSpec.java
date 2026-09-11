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
package com.fastcms.ai.component;

import java.util.List;
import java.util.Map;

/**
 * PageSpec 中单个 section 的描述：用哪个组件、哪个变体、槽位数据是什么
 *
 * <p>这是 AI 规划的最小输出单元——AI 只需回答"这里放什么组件（+变体）+ 填什么内容"，
 * 视觉质量由组件本身保证。{@code data} 的 key 与组件 {@link ComponentSlot#name()} 对应，
 * 组件 FTL 中以 {@code comp.xxx} 读取。</p>
 *
 * <p><b>插件能力集成（1.3）</b>：custom-html section 支持两种形态——</p>
 * <ul>
 *     <li>snippet 引用（优先）：{@code snippetId} + {@code snippetParams}，渲染期展开官方
 *         参考实现，AI 只填参数，质量与现网水平对齐</li>
 *     <li>逃生舱手写（兜底）：{@code data.html} 完整 HTML+JS，严格按能力契约（get_capability_detail）编写</li>
 * </ul>
 * <p>{@code capability} 记录该 section 依赖的能力 id 清单：渲染期据此做 hasPlugin 门控，
 * 微调时据此识别"已集成能力"防止重复添加。snippetId 与 data.html 互斥（校验器强制）。</p>
 *
 * @param id            section 稳定 id（可选，微调 patch 按此定位；缺省时按页面内序号生成）
 * @param component     组件全名（{@code packId:componentId}，如 "tw:hero"）
 * @param variant       变体 id（如 "split"），缺省时取组件第一个变体
 * @param data          槽位数据（标题/副标题/列表项等，结构由组件 slots 定义）
 * @param capability    依赖的插件能力 id 清单（如 ["payment:wxpay","article:paid-download"]），可空
 * @param snippetId     官方 snippet 引用（能力声明内唯一 id），与 data.html 互斥，可空
 * @param snippetParams snippet 占位符参数（key=占位符名，value=填充值；SECTION_ID 系统自动注入）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record SectionSpec(
        String id,
        String component,
        String variant,
        Map<String, Object> data,
        List<String> capability,
        String snippetId,
        Map<String, Object> snippetParams) {

    /**
     * 1.2 兼容构造器（无能力字段）：存量代码与测试沿用
     */
    public SectionSpec(String id, String component, String variant, Map<String, Object> data) {
        this(id, component, variant, data, null, null, null);
    }

    public Map<String, Object> safeData() {
        return data == null ? Map.of() : data;
    }

    public List<String> safeCapability() {
        return capability == null ? List.of() : capability;
    }

    public Map<String, Object> safeSnippetParams() {
        return snippetParams == null ? Map.of() : snippetParams;
    }

    /**
     * 是否为 custom-html 逃生舱/能力集成 section（渲染器走专属物化分支）
     */
    public boolean isCustomHtml() {
        return component != null && component.endsWith(":custom-html");
    }

    /**
     * 供 FTL 渲染的统一数据模型（组件模板以 {@code comp.title} 等读取）
     */
    public Map<String, Object> renderModel() {
        return safeData();
    }

}