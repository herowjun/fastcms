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

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PageSpec 校验器：AI 规划输出 → 渲染前的守门员
 *
 * <p>校验失败信息直接回喂给 AI 自我修正（错误信息必须"可行动"：
 * 指出位置 + 给出候选），而不是静默兜底——这是 AI 管线可靠性的关键一环。</p>
 *
 * <p>校验项：</p>
 * <ul>
 *     <li>结构：pages 非空，index 页必须存在且至少一个 section</li>
 *     <li>组件：存在性（附近似候选提示）、变体存在性、appliesTo 页面适用性</li>
 *     <li>数据：必填槽位缺失即报错</li>
 *     <li>主题：主色 #RRGGBB 合法、风格预设合法、foundation 与所用组件包一致</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class PageSpecValidator {

    private final ComponentRegistry registry;

    public PageSpecValidator(ComponentRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return 错误列表，空列表 = 校验通过
     */
    public List<String> validate(PageSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            errors.add("PageSpec 为空");
            return errors;
        }

        validateTheme(spec, errors);
        validatePages(spec, errors);
        return errors;
    }

    private void validateTheme(PageSpec spec, List<String> errors) {
        if (spec.primaryColor() != null && !spec.primaryColor().isBlank()
                && !TokenEngine.isValidColor(spec.primaryColor())) {
            errors.add("primaryColor 非法: \"" + spec.primaryColor() + "\"，需 #RRGGBB 格式，如 #2563eb");
        }
        if (spec.stylePreset() != null && !spec.stylePreset().isBlank()
                && !TokenEngine.presetNames().contains(spec.stylePreset().toLowerCase())) {
            errors.add("stylePreset 非法: \"" + spec.stylePreset() + "\"，可选值: "
                    + String.join("/", TokenEngine.presetNames()));
        }
    }

    private void validatePages(PageSpec spec, List<String> errors) {
        Map<String, PageSpecPage> pages = spec.pages();
        if (pages == null || pages.isEmpty()) {
            errors.add("pages 为空，至少需要 index 页");
            return;
        }
        if (pages.get(PageSpec.PAGE_INDEX) == null) {
            errors.add("缺少 index 页（pages 中必须有 key 为 \"index\" 的页面）");
        }
        Set<String> foundations = new HashSet<>();
        pages.forEach((pageKey, page) -> validatePage(pageKey, page, foundations, errors));
        if (foundations.size() > 1) {
            errors.add("混用不同地基的组件包: " + foundations + "，一个模板只能基于一种地基");
        }
        if (spec.foundation() != null && !spec.foundation().isBlank()
                && foundations.size() == 1 && !spec.foundation().equals(foundations.iterator().next())) {
            errors.add("foundation 与所用组件包不一致: spec 声明 " + spec.foundation()
                    + "，组件包实际为 " + foundations.iterator().next());
        }
    }

    private void validatePage(String pageKey, PageSpecPage page,
                              Set<String> foundations, List<String> errors) {
        if (page == null) {
            errors.add("页面 " + pageKey + " 内容为空");
            return;
        }
        List<SectionSpec> sections = page.safeSections();
        if (sections.isEmpty()) {
            // index 页必须有序列内容；内容页允许只有外围 section，但完全为空也视为可疑
            if (PageSpec.PAGE_INDEX.equals(pageKey)) {
                errors.add("index 页 sections 为空，至少需要一个 section（如 tw:hero）");
            }
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            validateSection(pageKey, i, sections.get(i), foundations, errors);
        }
    }

    private void validateSection(String pageKey, int index, SectionSpec section,
                                 Set<String> foundations, List<String> errors) {
        String location = "pages." + pageKey + ".sections[" + index + "]";
        if (section == null) {
            errors.add(location + " 为 null");
            return;
        }
        String fullId = section.component();
        if (fullId == null || fullId.isBlank()) {
            errors.add(location + " 缺少 component 字段");
            return;
        }
        ComponentRegistry.RegisteredComponent rc = registry.find(fullId).orElse(null);
        if (rc == null) {
            errors.add(location + " 组件不存在: " + fullId + "，候选: "
                    + String.join(", ", registry.suggestSimilar(fullId)));
            return;
        }
        ComponentDescriptor descriptor = rc.descriptor();
        foundations.add(rc.provider().getFoundation());

        if (descriptor.safeAppliesTo() != null && !descriptor.safeAppliesTo().isEmpty()
                && !descriptor.safeAppliesTo().contains(pageKey)) {
            errors.add(location + " 组件 " + fullId + " 不适用于 " + pageKey
                    + " 页（适用: " + String.join("/", descriptor.safeAppliesTo()) + "）");
        }

        if (section.variant() != null && !section.variant().isBlank()
                && !descriptor.hasVariant(section.variant())) {
            List<String> candidates = descriptor.variants() == null ? List.of()
                    : descriptor.variants().stream().map(ComponentVariant::id).toList();
            errors.add(location + " 变体不存在: " + fullId + "/" + section.variant()
                    + "，可选: " + String.join("/", candidates));
            return;
        }

        validateSlots(location, fullId, descriptor, section, errors);
    }

    private void validateSlots(String location, String fullId, ComponentDescriptor descriptor,
                               SectionSpec section, List<String> errors) {
        List<String> missing = new ArrayList<>();
        for (ComponentSlot slot : descriptor.safeSlots()) {
            if (slot.isRequired()) {
                Object value = section.safeData().get(slot.name());
                if (value == null || (value instanceof String s && s.isBlank())) {
                    missing.add(slot.name());
                }
            }
        }
        if (!missing.isEmpty()) {
            errors.add(location + " 组件 " + fullId + " 缺少必填槽位: " + String.join(", ", missing));
        }
    }

}