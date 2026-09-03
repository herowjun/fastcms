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

import java.util.Map;

/**
 * PageSpec 中单个 section 的描述：用哪个组件、哪个变体、槽位数据是什么
 *
 * <p>这是 AI 规划的最小输出单元——AI 只需回答"这里放什么组件（+变体）+ 填什么内容"，
 * 视觉质量由组件本身保证。{@code data} 的 key 与组件 {@link ComponentSlot#name()} 对应，
 * 组件 FTL 中以 {@code comp.xxx} 读取。</p>
 *
 * @param id        section 稳定 id（可选，微调 patch 按此定位；缺省时按页面内序号生成）
 * @param component 组件全名（{@code packId:componentId}，如 "tw:hero"）
 * @param variant   变体 id（如 "split"），缺省时取组件第一个变体
 * @param data      槽位数据（标题/副标题/列表项等，结构由组件 slots 定义）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record SectionSpec(
        String id,
        String component,
        String variant,
        Map<String, Object> data) {

    public Map<String, Object> safeData() {
        return data == null ? Map.of() : data;
    }

    /**
     * 供 FTL 渲染的统一数据模型（组件模板以 {@code comp.title} 等读取）
     */
    public Map<String, Object> renderModel() {
        return safeData();
    }

}