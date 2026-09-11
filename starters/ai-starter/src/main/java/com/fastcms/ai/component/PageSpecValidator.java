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

import com.fastcms.ai.capability.CapabilityDescriptor;
import com.fastcms.ai.capability.CapabilitySnippet;
import com.fastcms.ai.capability.PluginCapabilityRegistry;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final int MAX_TOP_MENUS = 8;
    private static final int MAX_MENU_CHILDREN = 6;
    private static final int MAX_ARTICLES = 12;

    private final ComponentRegistry registry;

    /**
     * 插件能力注册中心（custom-html 能力集成校验用；测试环境无能力时可注入 null）
     */
    private final PluginCapabilityRegistry capabilityRegistry;

    public PageSpecValidator(ComponentRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public PageSpecValidator(ComponentRegistry registry, PluginCapabilityRegistry capabilityRegistry) {
        this.registry = registry;
        this.capabilityRegistry = capabilityRegistry;
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
        validateSite(spec, errors);
        validatePages(spec, errors);
        return errors;
    }

    /**
     * suffix 合法字符（与 PageSpec.suffixedPageKey 一致）
     */
    private static final String SUFFIX_PATTERN = "[a-zA-Z0-9_-]+";

    /**
     * 内容页：正文占位组件 tw:content-body 的适用页面类型
     */
    private static boolean isContentPage(String basePageKey) {
        return PageSpec.PAGE_ARTICLE_LIST.equals(basePageKey)
                || PageSpec.PAGE_ARTICLE.equals(basePageKey)
                || PageSpec.PAGE_PAGE.equals(basePageKey);
    }

    /**
     * site 信息架构校验：菜单数量、type 合法性、suffix 格式与同类条目内唯一性。
     * 菜单与分类/单页共享同一 suffix 是预期设计（菜单指向对应栏目页，见提示词契约），不算重复；
     * 报重复的是同列表内两个条目指向同一页面（两个菜单同一 suffix、两个分类同一 suffix 等）。
     */
    private void validateSite(PageSpec spec, List<String> errors) {
        SiteContentSpec site = spec.safeSite();
        if (site == null) {
            return;
        }
        List<SiteContentSpec.NavItem> menus = site.safeMenus();
        if (menus.size() > MAX_TOP_MENUS) {
            errors.add("site.menus 顶级菜单 " + menus.size() + " 个超上限 " + MAX_TOP_MENUS);
        }
        Set<String> menuPageKeys = new HashSet<>();
        for (SiteContentSpec.NavItem menu : menus) {
            validateNavItem(menu, menuPageKeys, errors);
        }
        Set<String> categorySuffixes = new HashSet<>();
        for (SiteContentSpec.CatalogItem item : site.safeCategories()) {
            validateCatalogSuffix("site.categories", item, categorySuffixes, errors);
        }
        Set<String> singlePageSuffixes = new HashSet<>();
        for (SiteContentSpec.CatalogItem item : site.safeSinglePages()) {
            validateCatalogSuffix("site.singlePages", item, singlePageSuffixes, errors);
        }
        if (site.safeArticles().size() > MAX_ARTICLES) {
            errors.add("site.articles " + site.safeArticles().size() + " 篇超上限 " + MAX_ARTICLES);
        }
    }

    private void validateNavItem(SiteContentSpec.NavItem menu, Set<String> menuPageKeys,
                                 List<String> errors) {
        if (menu.name() == null || menu.name().isBlank()) {
            errors.add("site.menus 存在空菜单名");
        }
        String type = menu.safeType();
        if (!SiteContentSpec.NavItem.TYPE_INDEX.equals(type)
                && !SiteContentSpec.NavItem.TYPE_ARTICLE_LIST.equals(type)
                && !SiteContentSpec.NavItem.TYPE_ARTICLE.equals(type)
                && !SiteContentSpec.NavItem.TYPE_PAGE.equals(type)) {
            errors.add("site.menus[" + menu.name() + "] type 非法: " + type);
        }
        if (SiteContentSpec.NavItem.TYPE_INDEX.equals(type)) {
            // 首页菜单忽略 suffix
        } else if (menu.suffix() == null || menu.suffix().isBlank()) {
            errors.add("site.menus[" + menu.name() + "] 缺少 suffix（非首页菜单必须带 suffix 才能生成对应页面）");
        } else if (!menu.suffix().matches(SUFFIX_PATTERN)) {
            errors.add("site.menus[" + menu.name() + "] suffix 非法: " + menu.suffix()
                    + "（仅允许字母数字下划线中划线）");
        } else if (!menuPageKeys.add(type + ":" + menu.suffix())) {
            errors.add("site.menus[" + menu.name() + "] 与其他菜单指向同一页面: " + type + "_"
                    + menu.suffix() + "（菜单 suffix 不得重复）");
        }
        if (menu.safeChildren().size() > MAX_MENU_CHILDREN) {
            errors.add("site.menus[" + menu.name() + "] 子菜单超上限 " + MAX_MENU_CHILDREN);
        }
        for (SiteContentSpec.NavItem child : menu.safeChildren()) {
            validateNavItem(child, menuPageKeys, errors);
        }
    }

    /**
     * 分类/单页条目 suffix 校验：格式 + 同列表内唯一（跨列表与菜单共享是预期设计）
     */
    private void validateCatalogSuffix(String location, SiteContentSpec.CatalogItem item,
                                       Set<String> usedSuffixes, List<String> errors) {
        String suffix = item.suffix();
        if (suffix == null || suffix.isBlank()) {
            return;
        }
        if (!suffix.matches(SUFFIX_PATTERN)) {
            errors.add(location + "[" + item.title() + "] suffix 非法: " + suffix
                    + "（仅允许字母数字下划线中划线）");
            return;
        }
        if (!usedSuffixes.add(suffix)) {
            errors.add(location + "[" + item.title() + "] suffix 与同列表其他条目重复: " + suffix);
        }
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
        // 页面 key 必须是基础页或合法的 suffix 变体（article_list_products 等）
        String basePageKey = PageSpec.basePageKeyOf(pageKey);
        if (basePageKey == null) {
            errors.add("页面 key 非法: " + pageKey
                    + "（应为 index/article_list/article/page 或 article_list_{suffix} 等变体）");
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
        // 正文占位：仅内容页可用，每页至多一个
        int contentBodyCount = 0;
        for (SectionSpec section : sections) {
            if (section != null && PageSpec.CONTENT_BODY_SECTION.equals(section.component())) {
                contentBodyCount++;
            }
        }
        if (contentBodyCount > 1) {
            errors.add("页面 " + pageKey + " 的 " + PageSpec.CONTENT_BODY_SECTION
                    + " 出现 " + contentBodyCount + " 次（每页至多一个）");
        }
        for (int i = 0; i < sections.size(); i++) {
            // appliesTo 适用性按基础页类型判定（suffix 页与基础页共享同一套适用性）
            validateSection(pageKey, basePageKey, i, sections.get(i), foundations, errors);
        }
    }

    private void validateSection(String pageKey, String basePageKey, int index, SectionSpec section,
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
        // 正文占位（虚拟组件）：仅内容页可用，无变体/槽位校验
        if (PageSpec.CONTENT_BODY_SECTION.equals(fullId)) {
            if (!isContentPage(basePageKey)) {
                errors.add(location + " " + fullId
                        + " 只能用于内容页（article_list/article/page 及其 suffix 变体）");
            }
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
                && basePageKey != null && !descriptor.safeAppliesTo().contains(basePageKey)) {
            errors.add(location + " 组件 " + fullId + " 不适用于 " + basePageKey
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

        // custom-html 能力集成校验（snippetId / data.html 互斥、能力与 snippet 存在性、参数完整性）
        if (section.isCustomHtml()) {
            validateCapabilitySection(location, section, errors);
        }
    }

    /**
     * custom-html section 的能力集成校验（1.3）
     *
     * <p>错误信息必须可行动（回喂 AI 自我修正）：指出位置 + 正确用法。</p>
     */
    private void validateCapabilitySection(String location, SectionSpec section, List<String> errors) {
        if (section.id() == null || section.id().isBlank()) {
            errors.add(location + " custom-html section 必须有非空 id（snippet 的 SECTION_ID 参数、"
                    + "渲染期物化文件名与预览点选定位都依赖它）");
            return;
        }
        String snippetId = section.snippetId();
        String html = section.safeData().get("html") instanceof String s ? s : null;
        boolean hasSnippet = snippetId != null && !snippetId.isBlank();
        boolean hasHtml = html != null && !html.isBlank();

        if (hasSnippet == hasHtml) {
            errors.add(location + " custom-html 必须二选一：引用官方 snippet（snippetId + snippetParams，"
                    + "推荐）或手写逃生舱（data.html），两者互斥且不能同时为空");
            return;
        }

        // capability 字段校验：引用的能力必须已注册
        for (String capabilityId : section.safeCapability()) {
            if (capabilityRegistry != null && capabilityRegistry.find(capabilityId).isEmpty()) {
                errors.add(location + " 引用的能力未注册: " + capabilityId
                        + "（对应插件未安装，或能力 id 拼写错误；可用能力见 system prompt 的能力清单）");
            }
        }

        if (!hasSnippet) {
            return;
        }
        // snippet 存在性 + 归属能力 + 参数完整性
        CapabilitySnippet snippet = findSnippet(snippetId);
        if (snippet == null) {
            errors.add(location + " snippetId 不存在: " + snippetId
                    + "（可用 snippet 见 get_capability_detail 返回的官方 snippet 清单）");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String param : snippet.safeParams()) {
            if ("SECTION_ID".equals(param)) {
                continue; // 系统自动注入
            }
            Object value = section.safeSnippetParams().get(param);
            if (value == null || (value instanceof String sv && sv.isBlank())) {
                missing.add(param);
            }
        }
        if (!missing.isEmpty()) {
            errors.add(location + " snippet " + snippetId + " 缺少参数: " + String.join(", ", missing));
        }
    }

    /**
     * 按 snippetId 全局查找（能力声明内唯一，全局遍历命中第一个）
     */
    private CapabilitySnippet findSnippet(String snippetId) {
        if (capabilityRegistry == null) {
            return null;
        }
        for (PluginCapabilityRegistry.RegisteredCapability rc : capabilityRegistry.listCapabilities()) {
            for (CapabilitySnippet snippet : rc.descriptor().safeSnippets()) {
                if (snippet.id().equals(snippetId)) {
                    return snippet;
                }
            }
        }
        return null;
    }

    private void validateSlots(String location, String fullId, ComponentDescriptor descriptor,
                               SectionSpec section, List<String> errors) {
        List<String> missing = new ArrayList<>();
        for (ComponentSlot slot : descriptor.safeSlots()) {
            Object value = section.safeData().get(slot.name());
            if (slot.isRequired()
                    && (value == null || (value instanceof String s && s.isBlank()))) {
                missing.add(slot.name());
            }
            validateMediaSlotValue(location, fullId, slot, value, errors);
        }
        if (!missing.isEmpty()) {
            errors.add(location + " 组件 " + fullId + " 缺少必填槽位: " + String.join(", ", missing));
        }
    }

    /**
     * media 槽位值协议校验（PageSpec 1.2）：search:关键词 / 图片 URL / 空，其余视为非法
     */
    private void validateMediaSlotValue(String location, String fullId, ComponentSlot slot,
                                        Object value, List<String> errors) {
        if (!"media".equals(slot.type()) || !(value instanceof String s) || s.isBlank()) {
            return;
        }
        if (ImageAssetSpec.isSearchRef(s)) {
            if (ImageAssetSpec.searchKeyword(s) == null) {
                errors.add(location + " 槽位 " + slot.name() + " 的 search: 后缺少关键词（如 \"search:产品主图\"）");
            }
            return;
        }
        if (!ImageAssetSpec.isDirectRef(s)) {
            errors.add(location + " 槽位 " + slot.name() + " 值非法: " + s
                    + "（media 槽位应填 search:关键词 或 图片URL，不要编造地址）");
        }
    }

}