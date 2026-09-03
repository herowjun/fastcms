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

/**
 * Section 组件元数据：组件包 {@code component.json} 的解析结果
 *
 * <p>元数据是给 AI 看的"菜单"——AI 据此选择组件与变体、填充槽位数据；
 * 组件 FTL 源码永远不进入 AI 上下文（质量由预打磨组件保证，不由 AI 复述保证）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record ComponentDescriptor(
        String id,
        String name,
        String description,
        String category,
        List<String> appliesTo,
        List<ComponentVariant> variants,
        List<ComponentSlot> slots,
        List<String> cmsBindings) {

    /**
     * 组件分类：导航 / 首屏 / 内容 / 信任背书 / 转化 / 收尾
     */
    public static final String CATEGORY_STRUCTURAL = "structural";
    public static final String CATEGORY_CONTENT = "content";
    public static final String CATEGORY_SOCIAL_PROOF = "social-proof";
    public static final String CATEGORY_CONVERSION = "conversion";
    public static final String CATEGORY_FOOTER = "footer";

    public boolean hasVariant(String variantId) {
        return variants != null && variants.stream().anyMatch(v -> v.id().equals(variantId));
    }

    public ComponentVariant findVariant(String variantId) {
        if (variants == null) {
            return null;
        }
        return variants.stream().filter(v -> v.id().equals(variantId)).findFirst().orElse(null);
    }

    public List<ComponentSlot> safeSlots() {
        return slots == null ? List.of() : slots;
    }

    public List<String> safeAppliesTo() {
        return appliesTo == null ? List.of() : appliesTo;
    }

}
