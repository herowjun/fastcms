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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PageSpec 渲染引擎：spec → 自包含的 fastcms 模板目录
 *
 * <p>渲染是纯确定性过程（无 AI 参与）：组件 FTL 源码从 {@link ComponentRegistry} 取出，
 * 槽位数据（转为 {@code <#assign comp = {...}>}）留在页面/布局中；
 * CMS 绑定指令（menuTag/articleListTag 等）原样保留，由真实站点/预览 mock 在渲染期执行。
 * 因此同一个 spec 在预览环境与生产环境产出一致结构。</p>
 *
 * <p><b>公共布局（1.2）</b>：页面公共部分抽取到全站共享文件，改一处全站生效——</p>
 * <ul>
 *     <li>{@code _layout.html}：HTML 骨架（head/body）+ 导航区 + 页脚区，以 {@code <#macro page>}
 *         定义，页面通过 {@code <#import "_layout.html" as layout>} + {@code <@layout.page>} 使用；
 *         导航（structural 类）/页脚（footer 类）section 取自首个编排它们的页面（通常 index）</li>
 *     <li>{@code _components/*.ftl}：组件 FTL 源码全站共享，页面/布局以
 *         {@code <#include "_components/tw__navbar__sticky.ftl">} 引用（include 为运行期解析，
 *         手改组件源码即刻全站生效，无需重新渲染）；槽位数据（assign）留在引用处，支持每页差异化</li>
 *     <li>页面级 {@code standalone: true}：不走公共布局，渲染完整 HTML（自带骨架与 navbar/footer）</li>
 * </ul>
 *
 * <p>产物布局（对齐 fastcms 模板规范）：</p>
 * <pre>
 * {targetDir}/
 * ├── _pagespec.json          ← spec 落盘（微调/重渲染的事实源）
 * ├── _template.properties    ← 模板注册信息
 * ├── _preview_data.json      ← 预览演示数据（menus/categories/singlePages/articles/seo 全量）
 * ├── _layout.html            ← 公共布局宏（骨架 + 导航区 + 页脚区）
 * ├── index.html              ← 引用布局 + 本页专属 sections
 * ├── article_list.html       ← 引用布局 + 内置列表正文
 * ├── article.html            ← 引用布局 + 内置文章正文
 * ├── page.html               ← 引用布局 + 内置单页正文
 * ├── article_list_xxx.html   ← site 信息架构中带 suffix 的栏目页（自动生成，
 * │                              spec.pages 显式编排时用编排结果）
 * ├── page_xxx.html           ← 同上（单页专属页）
 * ├── _components/
 * │   └── tw__navbar__sticky.ftl  ← 组件源码（每组件变体一份，全站共享）
 * └── static/css/
 *     ├── pack-{packId}.css   ← 组件包地基（每包一份，多包并存按序引入）
 *     ├── tokens.css          ← TokenEngine 按主色+风格预设生成
 *     └── site.css            ← 内容页正文排版（h2/p/img 等富文本元素）
 * </pre>
 *
 * <p>组件升级 → 存量 _pagespec.json 重渲染即继承新组件，这是"弹药补充"闭环的落地点。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class PageSpecRenderer {

    private static final Logger log = LoggerFactory.getLogger(PageSpecRenderer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 组件包地基资产路径（provider 约定）
     */
    private static final String ASSET_PACK_CSS = "static/pack.css";

    /**
     * 内容页正文骨架的包资产路径前缀（provider 约定：pages/{pageKey}.ftl）
     */
    private static final String PAGE_SKELETON_PREFIX = "pages/";

    /**
     * 共享组件源码目录（模板内相对路径）
     */
    private static final String COMPONENTS_DIR = "_components";

    /**
     * 组件覆盖目录：AI 微调的 filePatches 落盘处，渲染时优先于组件包原版
     */
    public static final String COMPONENT_OVERRIDES_DIR = "_component_overrides";

    /**
     * media 槽位模板内引用前缀（附件库搜图/演示图解析产物，见 {@link AttachmentImageSearcher}）
     */
    private static final String TEMPLATE_INTERNAL_PREFIX = "static/";

    private final ComponentRegistry registry;

    private final TokenEngine tokenEngine;

    public PageSpecRenderer(ComponentRegistry registry, TokenEngine tokenEngine) {
        this.registry = registry;
        this.tokenEngine = tokenEngine;
    }

    /**
     * 渲染结果：写出的模板内相对路径清单（供渲染校验 / 前端刷新使用）
     */
    public record RenderResult(List<String> writtenFiles) {
    }

    /**
     * 公共布局区：头部（导航类 section）与尾部（页脚类 section），
     * 均取自首个编排它们的非 standalone 页面（通常 index）
     */
    private record LayoutZones(List<SectionSpec> header, List<SectionSpec> footer) {
    }

    /**
     * 渲染计划：页面清单 + 布局区 + 用到的组件包（决定 CSS 落盘与引入顺序）
     *
     * <p>多包并存（1.2）：内置包与插件包可在同一模板混用（同一地基前提），
     * 每个包的地基 CSS 独立落盘为 {@code pack-{packId}.css} 并按序引入——
     * 地基包（提供正文骨架的包）始终在列，其余包按首次使用顺序追加。</p>
     */
    private record RenderPlan(LinkedHashSet<String> pageKeys, LayoutZones zones,
                              LinkedHashSet<SectionComponentProvider> usedProviders,
                              List<String> cssFiles) {
    }

    /**
     * 渲染 PageSpec 到目标模板目录（已存在的同名文件被覆盖，多余文件不清理——
     * 微调场景由上层负责目录状态管理）。等价于 {@code render(spec, targetDir, true)}。
     *
     * @throws IllegalArgumentException 组件/变体缺失（应先经 {@link PageSpecValidator}）
     * @throws IOException               写盘失败
     */
    public RenderResult render(PageSpec spec, Path targetDir) throws IOException {
        return render(spec, targetDir, true);
    }

    /**
     * 渲染 PageSpec 到目标模板目录（已存在的同名文件被覆盖，多余文件不清理——
     * 微调场景由上层负责目录状态管理）
     *
     * @param mobileAdaptive 是否适配移动端：true 时向布局/独立页注入
     *                       {@code <#assign mobileAdaptive = true>}（组件模板据此输出
     *                       移动端汉堡菜单等响应式结构）；false 时注入 false，组件输出桌面专用结构
     * @throws IllegalArgumentException 组件/变体缺失（应先经 {@link PageSpecValidator}）
     * @throws IOException               写盘失败
     */
    public RenderResult render(PageSpec spec, Path targetDir, boolean mobileAdaptive) throws IOException {
        if (spec == null) {
            throw new IllegalArgumentException("PageSpec 为空");
        }
        List<String> written = new ArrayList<>();

        LinkedHashSet<String> pageKeys = collectPageKeys(spec);
        LayoutZones zones = extractLayoutZones(spec, pageKeys);
        RenderPlan plan = buildRenderPlan(spec, pageKeys, zones);

        writeStaticAssets(spec, plan, targetDir, written);
        writeComponentSources(spec, plan, targetDir, written);
        writeLayout(spec, plan, targetDir, written, mobileAdaptive);
        writePages(spec, plan, targetDir, written, mobileAdaptive);
        writePagespec(spec, targetDir, written);
        writeTemplateProperties(spec, targetDir, written);
        writePreviewData(spec, targetDir, written);

        log.info("PageSpec 渲染完成: {} 个文件 -> {}（移动端适配: {}）", written.size(), targetDir, mobileAdaptive);
        return new RenderResult(List.copyOf(written));
    }

    /**
     * 构建渲染计划：收集 spec 用到的全部组件包（地基包置首），生成 CSS 文件名清单
     */
    private RenderPlan buildRenderPlan(PageSpec spec, LinkedHashSet<String> pageKeys,
                                        LayoutZones zones) {
        LinkedHashSet<SectionComponentProvider> used = new LinkedHashSet<>();
        // 地基包置首：foundation 与 spec.foundation 匹配的包；无匹配时取第一个注册包
        LinkedHashSet<SectionComponentProvider> distinct = new LinkedHashSet<>();
        for (ComponentRegistry.RegisteredComponent rc : registry.listComponents()) {
            distinct.add(rc.provider());
        }
        if (distinct.isEmpty()) {
            throw new IllegalStateException("没有已注册的组件包");
        }
        SectionComponentProvider foundationProvider = distinct.iterator().next();
        for (SectionComponentProvider provider : distinct) {
            if (provider.getFoundation().equals(spec.foundation())) {
                foundationProvider = provider;
                break;
            }
        }
        used.add(foundationProvider);
        // 收集 spec 实际引用的包
        LinkedHashSet<SectionSpec> allSections = new LinkedHashSet<>();
        for (String pageKey : pageKeys) {
            allSections.addAll(effectiveSections(spec, pageKey));
        }
        allSections.addAll(zones.header());
        allSections.addAll(zones.footer());
        for (SectionSpec section : allSections) {
            if (isContentBody(section)) {
                continue;
            }
            registry.find(section.component())
                    .ifPresent(rc -> used.add(rc.provider()));
        }
        // CSS 文件：每包独立（pack-{packId}.css），供 head 按序引入
        List<String> cssFiles = new ArrayList<>();
        for (SectionComponentProvider provider : used) {
            if (provider.getPackAsset(ASSET_PACK_CSS) != null) {
                cssFiles.add("static/css/pack-" + provider.getPackId() + ".css");
            }
        }
        if (cssFiles.isEmpty()) {
            throw new IllegalStateException("组件包缺少地基资产: " + ASSET_PACK_CSS);
        }
        return new RenderPlan(pageKeys, zones, used, cssFiles);
    }

    // ==================== 页面清单与布局抽取 ====================

    /**
     * 组装页面清单：4 个基础页 + spec.pages 显式编排的 suffix 页 + site 信息架构
     * 要求但未显式编排的 suffix 页（克隆对应基础页的外围 section，保证菜单链接全部可达）
     */
    private LinkedHashSet<String> collectPageKeys(PageSpec spec) {
        LinkedHashSet<String> pageKeys = new LinkedHashSet<>(List.of(
                PageSpec.PAGE_INDEX, PageSpec.PAGE_ARTICLE_LIST,
                PageSpec.PAGE_ARTICLE, PageSpec.PAGE_PAGE));
        if (spec.pages() != null) {
            spec.pages().keySet().forEach(pageKeys::add);
        }
        if (spec.safeSite() != null) {
            for (String key : spec.safeSite().allSuffixedPageKeys()) {
                pageKeys.add(key);
            }
        }
        return pageKeys;
    }

    /**
     * 页面的生效 section 编排：spec.pages 显式编排优先，未编排的 suffix 页克隆对应基础页
     */
    private List<SectionSpec> effectiveSections(PageSpec spec, String pageKey) {
        String base = PageSpec.basePageKeyOf(pageKey);
        List<SectionSpec> sections = spec.sectionsOf(pageKey);
        if (sections.isEmpty() && base != null && !spec.sectionsOf(base).isEmpty()) {
            sections = spec.sectionsOf(base);
        }
        return sections;
    }

    private boolean isStandalone(PageSpec spec, String pageKey) {
        PageSpecPage page = spec.pages() == null ? null : spec.pages().get(pageKey);
        return page != null && page.safeStandalone();
    }

    /**
     * 抽取公共布局区：按页面写入顺序扫描（index 最前），首个含导航/页脚 section 的
     * 非 standalone 页面贡献布局区；standalone 页面不参与公共布局
     */
    private LayoutZones extractLayoutZones(PageSpec spec, LinkedHashSet<String> pageKeys) {
        List<SectionSpec> header = null;
        List<SectionSpec> footer = null;
        for (String pageKey : pageKeys) {
            if (isStandalone(spec, pageKey)) {
                continue;
            }
            if (header != null && footer != null) {
                break;
            }
            for (SectionSpec section : effectiveSections(spec, pageKey)) {
                if (header == null && isHeaderSection(section)) {
                    header = new ArrayList<>();
                }
                if (footer == null && isFooterSection(section)) {
                    footer = new ArrayList<>();
                }
                if (header != null && isHeaderSection(section)) {
                    header.add(section);
                } else if (footer != null && isFooterSection(section)) {
                    footer.add(section);
                }
            }
        }
        return new LayoutZones(
                header == null ? List.of() : List.copyOf(header),
                footer == null ? List.of() : List.copyOf(footer));
    }

    // ==================== 共享组件源码 ====================

    /**
     * 组件源码落盘到 _components/：每个（组件, 变体）一份，页面与布局通过 include 引用。
     * 同一组件变体被多个 section 引用时只写一次（源码全站共享的基础）；
     * content-body 为虚拟占位组件（渲染时替换为正文骨架），不落盘
     */
    private void writeComponentSources(PageSpec spec, RenderPlan plan, Path targetDir,
                                        List<String> written) throws IOException {
        Path dir = targetDir.resolve(COMPONENTS_DIR);
        Files.createDirectories(dir);
        LinkedHashSet<String> writtenRels = new LinkedHashSet<>();
        for (String pageKey : plan.pageKeys()) {
            for (SectionSpec section : effectiveSections(spec, pageKey)) {
                if (!isContentBody(section)) {
                    writeComponentFile(section, dir, writtenRels, written);
                }
            }
        }
        for (SectionSpec section : plan.zones().header()) {
            writeComponentFile(section, dir, writtenRels, written);
        }
        for (SectionSpec section : plan.zones().footer()) {
            writeComponentFile(section, dir, writtenRels, written);
        }
    }

    private void writeComponentFile(SectionSpec section, Path dir,
                                     LinkedHashSet<String> writtenRels, List<String> written) throws IOException {
        if (isContentBody(section)) {
            return;
        }
        String fileName = componentFileName(section);
        String rel = COMPONENTS_DIR + "/" + fileName;
        if (!writtenRels.add(rel)) {
            return;
        }
        // 组件覆盖优先：AI 微调输出的 filePatches 落在 _component_overrides/ 下，
        // 存在则直接使用（覆盖版基于已注入标记的工作目录版本修改，标记天然保留，
        // 不再重复注入）；不存在走组件包原版 + 标记注入。重渲染因此不会冲掉补丁。
        Path override = dir.getParent().resolve(COMPONENT_OVERRIDES_DIR).resolve(fileName);
        String source;
        if (Files.isRegularFile(override)) {
            source = Files.readString(override, StandardCharsets.UTF_8);
        } else {
            source = injectMediaSlotMarkers(componentSource(section), mediaSlotNames(section));
            source = injectSectionRootMarker(source);
        }
        Files.writeString(dir.resolve(fileName), source, StandardCharsets.UTF_8);
        written.add(rel);
    }

    /**
     * 首个 HTML 元素开标签匹配（注入区块根标记用）：
     * 标签名以字母开头，天然跳过 FreeMarker 指令（{@code <#...>}/{@code <@...>}）与闭标签
     */
    private static final java.util.regex.Pattern ROOT_TAG_PATTERN =
            java.util.regex.Pattern.compile("<([a-zA-Z][a-zA-Z0-9-]*)");

    /**
     * FreeMarker 注释与 HTML 注释区间（标记注入时跳过：注释内的标签不会渲染）
     */
    private static final java.util.regex.Pattern COMMENT_PATTERN =
            java.util.regex.Pattern.compile("<#--[\\s\\S]*?-->|<!--[\\s\\S]*?-->", java.util.regex.Pattern.DOTALL);

    /**
     * 组件根元素注入 data-ai-section-root 标记：选区修改模式据此定位区块边界
     *
     * <p>只在首个 HTML 元素开标签的标签名后追加属性，不改变 DOM 结构与布局
     * （不用 wrapper div，避免影响组件 CSS 的直接子元素选择器）。
     * 组件含多个根元素时仅首根带标记；媒体槽位 img 另有 data-ai-section/data-ai-slot
     * 标记，选区点击兜底同样可用。section id 来自 include 处的 {@code _aiSection} assign。</p>
     */
    private String injectSectionRootMarker(String source) {
        // 注释区间内的标签不渲染，注入前先圈出来
        List<int[]> commentSpans = new ArrayList<>();
        java.util.regex.Matcher commentMatcher = COMMENT_PATTERN.matcher(source);
        while (commentMatcher.find()) {
            commentSpans.add(new int[]{commentMatcher.start(), commentMatcher.end()});
        }
        java.util.regex.Matcher matcher = ROOT_TAG_PATTERN.matcher(source);
        while (matcher.find()) {
            int start = matcher.start();
            boolean inComment = commentSpans.stream().anyMatch(span -> start >= span[0] && start < span[1]);
            if (inComment) {
                continue;
            }
            String tag = matcher.group();
            String replacement = tag + " data-ai-section-root=\"${(_aiSection)!''}\"";
            return source.substring(0, start) + replacement + source.substring(matcher.end());
        }
        return source;
    }

    /**
     * img 标签匹配（注入 data-ai-slot 标记用；{@code [^>]*} 覆盖跨行属性）
     */
    private static final java.util.regex.Pattern IMG_TAG_PATTERN =
            java.util.regex.Pattern.compile("<img\\b[^>]*>");

    /**
     * 组件源码中引用 media 槽位的 img 标签注入 data-ai-slot/data-ai-section 标记：
     * {@code <img src="${comp.image}">} → 追加 {@code data-ai-slot="image" data-ai-section="${(_aiSection)!''}"}。
     * AI 调整页点选图片（S4-2）据此定位槽位（section id 来自 include 处的 {@code _aiSection} assign），
     * 对内置与插件组件统一生效；引用 CMS 动态数据（item.thumbnail 等）的 img 不受影响
     */
    private String injectMediaSlotMarkers(String source, Set<String> mediaSlots) {
        if (mediaSlots.isEmpty()) {
            return source;
        }
        java.util.regex.Matcher matcher = IMG_TAG_PATTERN.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String tag = matcher.group();
            String slot = mediaSlotReferenced(tag, mediaSlots);
            if (slot != null && !tag.contains("data-ai-slot")) {
                String closing = tag.endsWith("/>") ? "/>" : ">";
                String marker = " data-ai-slot=\"" + slot + "\" data-ai-section=\"${(_aiSection)!''}\"";
                tag = tag.substring(0, tag.length() - closing.length()) + marker + closing;
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * img 标签引用的 media 槽位名（src/样式取自 {@code comp.<slot>}），无匹配返回 null
     */
    private String mediaSlotReferenced(String imgTag, Set<String> mediaSlots) {
        for (String slot : mediaSlots) {
            if (imgTag.contains("comp." + slot)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 共享组件文件名：组件全名中的 ':' 换 '__' + 变体 id，如 tw:navbar/sticky → tw__navbar__sticky.ftl
     */
    private String componentFileName(SectionSpec section) {
        return section.component().replace(":", "__") + "__" + resolveVariantId(section) + ".ftl";
    }

    /**
     * section 引用（布局与页面统一）：槽位数据 assign（留在引用处，支持每页差异化）+ include 共享源码
     *
     * <p>点选支持（S4/S6）：无条件注入 {@code <#assign _aiSection>}，
     * 配合组件源码中 {@link #injectMediaSlotMarkers} 注入的 data-ai-slot/data-ai-section 标记
     * （换图定位槽位）与 {@link #injectSectionRootMarker} 注入的 data-ai-section-root 标记
     * （选区修改定位区块），预览页点选即可定位 section</p>
     */
    private String renderSection(SectionSpec section) {
        StringBuilder sb = new StringBuilder();
        sb.append("<#assign comp = ").append(toFtlLiteral(adaptMediaRefs(section))).append(">\n");
        sb.append("<#assign _aiSection = ").append(toFtlLiteral(section.id())).append(">\n");
        sb.append("<#include \"").append(COMPONENTS_DIR).append("/")
                .append(componentFileName(section)).append("\">\n");
        return sb.toString();
    }

    /**
     * media 槽位的模板内图片引用（{@code static/} 前缀，附件库搜图/演示图解析产物）→ ctx() 表达式：
     * {@code static/images/demo-nature.svg} → {@code ctx() + "/images/demo-nature.svg"}。
     * 预览与生产环境的 ctx() 分别指向会话静态分支与模板静态目录，同一模板文件两环境通用；
     * 站内绝对路径（/attachment/...）与完整 URL 原样保留
     */
    private Map<String, Object> adaptMediaRefs(SectionSpec section) {
        Set<String> mediaSlots = mediaSlotNames(section);
        if (mediaSlots.isEmpty()) {
            return section.safeData();
        }
        Map<String, Object> copy = new LinkedHashMap<>(section.safeData());
        boolean adapted = false;
        for (String slot : mediaSlots) {
            Object value = copy.get(slot);
            if (value instanceof String s && s.startsWith(TEMPLATE_INTERNAL_PREFIX)) {
                copy.put(slot, new FtlExpression("ctx() + \"/"
                        + s.substring(TEMPLATE_INTERNAL_PREFIX.length()) + "\""));
                adapted = true;
            }
        }
        return adapted ? copy : section.safeData();
    }

    /**
     * media 类型槽位名集合（组件不在注册表时返回空集，校验器已拦截）
     */
    private Set<String> mediaSlotNames(SectionSpec section) {
        return registry.find(section.component())
                .map(rc -> {
                    Set<String> names = new LinkedHashSet<>();
                    for (ComponentSlot slot : rc.descriptor().safeSlots()) {
                        if ("media".equals(slot.type())) {
                            names.add(slot.name());
                        }
                    }
                    return names;
                })
                .orElse(Set.of());
    }

    /**
     * 组件源码（缺省变体取第一个），缺失即抛异常（调用前应已通过校验器）
     */
    private String componentSource(SectionSpec section) {
        return registry.getTemplateSource(section.component(), resolveVariantId(section));
    }

    /**
     * 解析 section 的具体变体 id：显式指定优先，缺省取组件第一个变体
     */
    private String resolveVariantId(SectionSpec section) {
        ComponentRegistry.RegisteredComponent rc = registry.find(section.component())
                .orElseThrow(() -> new IllegalArgumentException(
                        "组件不存在: " + section.component() + "（渲染前应先通过 PageSpecValidator）"));
        String variantId = section.variant();
        if (variantId == null || variantId.isBlank()) {
            List<ComponentVariant> variants = rc.descriptor().variants();
            variantId = (variants == null || variants.isEmpty()) ? "default" : variants.get(0).id();
        }
        return variantId;
    }

    // ==================== 公共布局 ====================

    /**
     * 公共布局 _layout.html：HTML 骨架 + 导航区 + <#nested> 页面内容 + 页脚区
     *
     * <p>CSS 按包引入（pack-{packId}.css 每包一份，地基包在前），tokens/site 全局唯一</p>
     */
    private void writeLayout(PageSpec spec, RenderPlan plan, Path targetDir,
                             List<String> written, boolean mobileAdaptive) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<#-- 公共布局：HTML 骨架 + 导航区 + 页脚区，全站共享，修改本文件即刻全站生效 -->\n");
        sb.append("<#-- 页面用法：<#import \"_layout.html\" as layout> + <@layout.page>...页面内容...</@layout.page> -->\n");
        sb.append("<#-- 页面标题由页面在 import 后 <#assign pageTitle> 提供；standalone 页面不经过本文件 -->\n");
        sb.append("<#macro page>\n");
        sb.append("<#-- 移动端适配开关：组件模板以 (mobileAdaptive!true) 读取，控制响应式结构输出 -->\n");
        sb.append("<#assign mobileAdaptive = ").append(mobileAdaptive).append(">\n");
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"zh-CN\" class=\"bg-white text-slate-900 antialiased\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<title>${pageTitle!''}</title>\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<meta name=\"keywords\" content=\"").append(escapeAttr(spec.safeSiteName())).append("\">\n");
        sb.append("<meta name=\"description\" content=\"${seoTag(\"website_sub_title\")!\"\"}\">\n");
        for (String css : plan.cssFiles()) {
            sb.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/").append(css.substring(css.lastIndexOf('/') + 1)).append("\">\n");
        }
        sb.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/tokens.css\">\n");
        sb.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/site.css\">\n");
        sb.append("</head>\n");
        sb.append("<body class=\"font-sans\">\n");
        sb.append("<#-- 头部区（structural 类组件，取自首个编排页面） -->\n");
        for (SectionSpec section : plan.zones().header()) {
            sb.append(renderSection(section));
        }
        sb.append("<#-- 页面专属内容 -->\n");
        sb.append("<#nested>\n");
        sb.append("<#-- 尾部区（页脚类组件，取自首个编排页面） -->\n");
        for (SectionSpec section : plan.zones().footer()) {
            sb.append(renderSection(section));
        }
        sb.append("</body>\n");
        sb.append("</html>\n");
        sb.append("</#macro>\n");
        Files.writeString(targetDir.resolve("_layout.html"), sb.toString(), StandardCharsets.UTF_8);
        written.add("_layout.html");
    }

    // ==================== 页面组装 ====================

    private void writePages(PageSpec spec, RenderPlan plan, Path targetDir,
                             List<String> written, boolean mobileAdaptive) throws IOException {
        for (String pageKey : plan.pageKeys()) {
            writePage(spec, targetDir, pageKey, plan, written, mobileAdaptive);
        }
    }

    private void writePage(PageSpec spec, Path targetDir, String pageKey,
                           RenderPlan plan, List<String> written, boolean mobileAdaptive) throws IOException {
        String base = PageSpec.basePageKeyOf(pageKey);
        if (base == null) {
            throw new IllegalArgumentException("未知页面 key: " + pageKey + "（应为 index/article_list/article/page 或带 suffix 的变体）");
        }
        List<SectionSpec> sections = effectiveSections(spec, pageKey);
        String html = isStandalone(spec, pageKey)
                ? buildStandaloneHtml(spec, base, sections, plan.cssFiles(), mobileAdaptive)
                : buildLayoutPageHtml(spec, base, sections);
        Path file = targetDir.resolve(pageKey + ".html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, html, StandardCharsets.UTF_8);
        written.add(pageKey + ".html");
    }

    /**
     * 布局页面：引用 _layout.html，正文只含本页专属 sections（布局区 section 由布局提供）
     *
     * <p>内容页的正文骨架：AI 用 tw:content-body 占位时替换在序列位置（横幅→正文→转化区
     * 等自定义结构）；未占位（旧协议 spec / 空 sections）追加在末尾，行为不变</p>
     *
     * @param base 基础页类型（决定正文骨架与标题表达式）
     */
    private String buildLayoutPageHtml(PageSpec spec, String base, List<SectionSpec> sections) {
        StringBuilder body = new StringBuilder();
        boolean contentInserted = false;
        for (SectionSpec section : sections) {
            if (isLayoutSection(section)) {
                continue; // 导航/页脚由 _layout.html 统一提供
            }
            if (isContentBody(section)) {
                if (isContentPage(base) && !contentInserted) {
                    body.append(contentSkeleton(base));
                    contentInserted = true;
                }
                continue;
            }
            body.append(renderSection(section));
        }
        // 旧协议兼容：内容页未显式占位时正文追加在末尾（位于布局页脚区之前）
        if (isContentPage(base) && !contentInserted) {
            body.append(contentSkeleton(base));
        }

        StringBuilder html = new StringBuilder();
        html.append("<#import \"_layout.html\" as layout>\n");
        html.append("<#-- 页面标题（布局 head 中引用） -->\n");
        html.append("<#assign pageTitle>").append(pageTitle(spec, base)).append("</#assign>\n");
        html.append("<@layout.page>\n");
        html.append(body);
        html.append("</@layout.page>\n");
        return html.toString();
    }

    /**
     * 独立页面（standalone）：完整 HTML，自带骨架与全部 sections（含 navbar/footer）
     *
     * @param base     基础页类型（决定正文骨架与标题表达式）
     * @param cssFiles 多包 CSS 清单（pack-{packId}.css 每包一份）
     * @param mobileAdaptive 是否适配移动端（注入 assign 变量供组件模板读取）
     */
    private String buildStandaloneHtml(PageSpec spec, String base, List<SectionSpec> sections,
                                       List<String> cssFiles, boolean mobileAdaptive) {
        StringBuilder body = new StringBuilder();

        boolean contentInserted = false;
        for (SectionSpec section : sections) {
            if (isContentBody(section)) {
                if (isContentPage(base) && !contentInserted) {
                    body.append(contentSkeleton(base));
                    contentInserted = true;
                }
                continue;
            }
            if (!contentInserted && isContentPage(base) && isFooterSection(section)) {
                body.append(contentSkeleton(base));
                contentInserted = true;
            }
            body.append(renderSection(section));
        }
        // 内容页无 footer section 且未显式占位时正文追加在末尾
        if (isContentPage(base) && !contentInserted) {
            body.append(contentSkeleton(base));
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<#-- 移动端适配开关：组件模板以 (mobileAdaptive!true) 读取 -->\n");
        html.append("<#assign mobileAdaptive = ").append(mobileAdaptive).append(">\n");
        html.append("<html lang=\"zh-CN\" class=\"bg-white text-slate-900 antialiased\">\n");
        html.append("<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<title>").append(pageTitle(spec, base)).append("</title>\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<meta name=\"keywords\" content=\"").append(escapeAttr(spec.safeSiteName())).append("\">\n");
        html.append("<meta name=\"description\" content=\"${seoTag(\"website_sub_title\")!\"\"}\">\n");
        for (String css : cssFiles) {
            html.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/").append(css.substring(css.lastIndexOf('/') + 1)).append("\">\n");
        }
        html.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/tokens.css\">\n");
        html.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/site.css\">\n");
        html.append("</head>\n");
        html.append("<body class=\"font-sans\">\n");
        html.append(body);
        html.append("</body>\n");
        html.append("</html>\n");
        return html.toString();
    }

    /**
     * 页面 title 的 FTL 表达式（运行期取 CMS 数据，静态兜底用 spec 站点名）
     */
    private String pageTitle(PageSpec spec, String base) {
        return switch (base) {
            case PageSpec.PAGE_INDEX -> "${seoTag(\"website_title\")!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
            case PageSpec.PAGE_ARTICLE_LIST -> "${(category.title)!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
            case PageSpec.PAGE_ARTICLE -> "${(article.title)!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
            default -> "${(singlePage.title)!\"" + escapeAttr(spec.safeSiteName()) + "\"}";
        };
    }

    /**
     * 内容页正文骨架（组件包 pages/ 资产），缺失时抛异常（包不完整）
     */
    private String contentSkeleton(String pageKey) {
        for (ComponentRegistry.RegisteredComponent rc : registry.listComponents()) {
            byte[] asset = rc.provider().getPackAsset(PAGE_SKELETON_PREFIX + pageKey + ".ftl");
            if (asset != null) {
                return new String(asset, StandardCharsets.UTF_8).strip() + "\n";
            }
        }
        throw new IllegalStateException("组件包缺少内容页骨架: " + PAGE_SKELETON_PREFIX + pageKey + ".ftl");
    }

    // ==================== 静态资产 ====================

    /**
     * 静态资产落盘：每个用到的组件包一份 pack-{packId}.css（多包并存，1.2），
     * tokens/site 全局唯一
     */
    private void writeStaticAssets(PageSpec spec, RenderPlan plan, Path targetDir,
                                    List<String> written) throws IOException {
        Path cssDir = targetDir.resolve("static/css");
        Files.createDirectories(cssDir);

        for (SectionComponentProvider provider : plan.usedProviders()) {
            byte[] packCss = provider.getPackAsset(ASSET_PACK_CSS);
            if (packCss == null) {
                continue;
            }
            String fileName = "pack-" + provider.getPackId() + ".css";
            write(cssDir.resolve(fileName), packCss, "static/css/" + fileName, written);
        }

        write(cssDir.resolve("tokens.css"),
                tokenEngine.generateTokens(spec.primaryColor(), spec.stylePreset())
                        .getBytes(StandardCharsets.UTF_8),
                "static/css/tokens.css", written);

        write(cssDir.resolve("site.css"), SITE_CSS.getBytes(StandardCharsets.UTF_8),
                "static/css/site.css", written);
    }

    private void write(Path file, byte[] content, String relPath, List<String> written) throws IOException {
        Files.write(file, content);
        written.add(relPath);
    }

    // ==================== 元数据文件 ====================

    private void writePagespec(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        // pretty 输出便于人工检查与 AI patch
        StringWriter sw = new StringWriter();
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(sw, spec);
        Files.writeString(targetDir.resolve("_pagespec.json"), sw.toString(), StandardCharsets.UTF_8);
        written.add("_pagespec.json");
    }

    private void writeTemplateProperties(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        // 手写 UTF-8（历史教训：Properties 默认 ISO-8859-1 读写中文乱码，此处写端保证 UTF-8）
        String name = spec.safeTemplateName();
        StringBuilder sb = new StringBuilder();
        sb.append("template.id=").append(name).append("\n");
        sb.append("template.name=").append(name).append("\n");
        sb.append("template.path=/").append(name).append("/\n");
        sb.append("template.version=0.0.1\n");
        sb.append("template.i18n=").append(name).append("\n");
        sb.append("template.provider=ai-component\n");
        sb.append("template.description=AI 组件化生成模板\n");
        Files.writeString(targetDir.resolve("_template.properties"), sb.toString(), StandardCharsets.UTF_8);
        written.add("_template.properties");
    }

    private void writePreviewData(PageSpec spec, Path targetDir, List<String> written) throws IOException {
        // 预览演示数据：site 信息架构全量落盘（menus/categories/singlePages/articles/seo），
        // 预览渲染不再回退内置默认（SpringBoot 演示数据）——菜单/文章与用户主题一致
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, String> seo = new LinkedHashMap<>();
        seo.put("website_title", spec.safeSiteName());
        root.put("seo", seo);

        SiteContentSpec site = spec.safeSite();
        if (site != null) {
            List<Map<String, Object>> menus = new ArrayList<>();
            for (SiteContentSpec.NavItem item : site.safeMenus()) {
                menus.add(navItemJson(item));
            }
            if (!menus.isEmpty()) {
                root.put("menus", menus);
            }
            List<Map<String, String>> categories = new ArrayList<>();
            for (SiteContentSpec.CatalogItem item : site.safeCategories()) {
                categories.add(itemJson(item.title(), item.suffix()));
            }
            if (!categories.isEmpty()) {
                root.put("categories", categories);
            }
            List<Map<String, String>> singlePages = new ArrayList<>();
            for (SiteContentSpec.CatalogItem item : site.safeSinglePages()) {
                singlePages.add(itemJson(item.title(), item.suffix()));
            }
            if (!singlePages.isEmpty()) {
                root.put("singlePages", singlePages);
            }
            if (!site.safeArticles().isEmpty()) {
                Map<String, Object> articles = new LinkedHashMap<>();
                articles.put("titles", site.safeArticles().stream()
                        .map(SiteContentSpec.PreviewArticle::title).toList());
                articles.put("summaries", site.safeArticles().stream()
                        .map(SiteContentSpec.PreviewArticle::summary).toList());
                root.put("articles", articles);
            }
        }

        // 用户在预览页点选换图产生的替换映射（imageOverrides）不属于 spec 派生数据，
        // 重渲染时从既有文件原样保留（否则每次 AI 调整都会丢失用户换过的演示图）
        carryOverImageOverrides(root, targetDir);

        StringWriter sw = new StringWriter();
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(sw, root);
        Files.writeString(targetDir.resolve("_preview_data.json"), sw.toString() + "\n", StandardCharsets.UTF_8);
        written.add("_preview_data.json");
    }

    /**
     * 从目录中既有的 _preview_data.json 提取 imageOverrides 并并入待写入数据
     *
     * <p>key/value 均须为非空字符串；既有文件缺失或非法时静默跳过（等同无保留项）。</p>
     */
    private void carryOverImageOverrides(Map<String, Object> root, Path targetDir) {
        Path existing = targetDir.resolve("_preview_data.json");
        if (!Files.isRegularFile(existing)) {
            return;
        }
        try {
            tools.jackson.databind.JsonNode node = MAPPER.readTree(Files.readString(existing, StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                return;
            }
            tools.jackson.databind.JsonNode overrides = node.get("imageOverrides");
            if (overrides == null || !overrides.isObject() || overrides.isEmpty()) {
                return;
            }
            Map<String, String> carry = new LinkedHashMap<>();
            overrides.properties().forEach(entry -> {
                tools.jackson.databind.JsonNode value = entry.getValue();
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                if (value != null && value.isTextual() && !key.isEmpty() && !value.asString().trim().isEmpty()) {
                    carry.put(key, value.asString().trim());
                }
            });
            if (!carry.isEmpty()) {
                root.put("imageOverrides", carry);
            }
        } catch (Exception e) {
            // 保留失败不阻断渲染（既有文件非法时以本轮渲染产物为准）
            log.debug("保留 imageOverrides 失败，忽略: {}", existing, e);
        }
    }

    private Map<String, Object> navItemJson(SiteContentSpec.NavItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", item.name());
        m.put("type", item.safeType());
        if (item.suffix() != null && !item.suffix().isBlank()) {
            m.put("suffix", item.suffix());
        }
        if (item.safeChildren().isEmpty()) {
            m.put("children", List.of());
        } else {
            List<Map<String, Object>> children = new ArrayList<>();
            for (SiteContentSpec.NavItem child : item.safeChildren()) {
                children.add(navItemJson(child));
            }
            m.put("children", children);
        }
        return m;
    }

    private Map<String, String> itemJson(String title, String suffix) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", title);
        if (suffix != null && !suffix.isBlank()) {
            m.put("suffix", suffix);
        }
        return m;
    }

    // ==================== 工具 ====================

    private boolean isContentPage(String pageKey) {
        return PageSpec.PAGE_ARTICLE_LIST.equals(pageKey)
                || PageSpec.PAGE_ARTICLE.equals(pageKey)
                || PageSpec.PAGE_PAGE.equals(pageKey);
    }

    /**
     * 正文占位 section（tw:content-body，虚拟组件）：渲染时替换为该页真实正文骨架
     */
    private boolean isContentBody(SectionSpec section) {
        return section != null && PageSpec.CONTENT_BODY_SECTION.equals(section.component());
    }

    /**
     * 布局区 section：组件声明 layoutScope（header/footer 站点级 chrome），
     * 由 _layout.html 统一提供；页面级组件（hero/feature-grid 等）不属于布局区
     */
    private boolean isLayoutSection(SectionSpec section) {
        return isHeaderSection(section) || isFooterSection(section);
    }

    private boolean isHeaderSection(SectionSpec section) {
        ComponentRegistry.RegisteredComponent rc = registry.find(section.component()).orElse(null);
        return rc != null && ComponentDescriptor.LAYOUT_SCOPE_HEADER.equals(rc.descriptor().layoutScope());
    }

    private boolean isFooterSection(SectionSpec section) {
        ComponentRegistry.RegisteredComponent rc = registry.find(section.component()).orElse(null);
        return rc != null && ComponentDescriptor.LAYOUT_SCOPE_FOOTER.equals(rc.descriptor().layoutScope());
    }

    /**
     * FTL 原生表达式包装（media 槽位模板内图片引用 → ctx() 表达式，见 adaptMediaRefs）：
     * 字面量序列化时原样输出表达式而非字符串
     */
    record FtlExpression(String expression) {
    }

    /**
     * 槽位数据（Jackson 解析出的 POJO）→ FTL 字面量
     *
     * <p>AI 产出的 data 经 _pagespec.json 往返（Map/List/String/Number/Boolean），
     * 本方法将其转为 FreeMarker 表达式字面量供 {@code <#assign comp = ...>} 使用。</p>
     */
    static String toFtlLiteral(Object value) {
        if (value == null) {
            return "''";
        }
        if (value instanceof FtlExpression expr) {
            return expr.expression();
        }
        if (value instanceof String s) {
            return "\"" + escapeFtl(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object item : list) {
                sb.append(toFtlLiteral(item)).append(", ");
            }
            if (!list.isEmpty()) {
                sb.setLength(sb.length() - 2);
            }
            return sb.append("]").toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append("\"").append(escapeFtl(String.valueOf(entry.getKey()))).append("\": ")
                        .append(toFtlLiteral(entry.getValue())).append(", ");
            }
            if (!map.isEmpty()) {
                sb.setLength(sb.length() - 2);
            }
            return sb.append("}").toString();
        }
        // 未知类型退化为字符串（防 AI 塞入奇怪结构导致渲染失败）
        return "\"" + escapeFtl(String.valueOf(value)) + "\"";
    }

    /**
     * FTL 字符串转义（反斜杠、引号、换行）
     */
    private static String escapeFtl(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    /**
     * HTML 属性转义（title 静态兜底值）
     */
    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }


    /**
     * 内容页正文排版样式（富文本 contentHtml 无 Tailwind 类可用，手写基础排版）
     */
    private static final String SITE_CSS = """
            /* 内容页正文排版（renderHtml 富文本元素，非组件输出） */
            .article-content { color: #334155; font-size: 1rem; line-height: 1.9; }
            .article-content h1, .article-content h2, .article-content h3,
            .article-content h4 { color: #0f172a; font-weight: 600; line-height: 1.4; margin: 2em 0 .8em; }
            .article-content h2 { font-size: 1.5rem; }
            .article-content h3 { font-size: 1.25rem; }
            .article-content p { margin: 1em 0; }
            .article-content a { color: var(--color-primary-600, #2563eb); text-decoration: underline; }
            .article-content a:hover { color: var(--color-primary-700, #1d4ed8); }
            .article-content img { border-radius: .75rem; margin: 1.5em 0; max-width: 100%; }
            .article-content ul, .article-content ol { margin: 1em 0; padding-left: 1.5em; }
            .article-content ul { list-style: disc; }
            .article-content ol { list-style: decimal; }
            .article-content li { margin: .4em 0; }
            .article-content blockquote {
              border-left: 4px solid var(--color-primary-300, #93c5fd);
              background: #f8fafc; border-radius: 0 .5rem .5rem 0;
              margin: 1.5em 0; padding: .8em 1.2em; color: #475569;
            }
            .article-content pre {
              background: #0f172a; border-radius: .75rem; color: #e2e8f0;
              margin: 1.5em 0; overflow-x: auto; padding: 1em 1.25em;
              font-family: var(--font-mono, monospace); font-size: .875rem; line-height: 1.7;
            }
            .article-content code {
              background: #f1f5f9; border-radius: .25rem;
              font-family: var(--font-mono, monospace); font-size: .875em; padding: .15em .4em;
            }
            .article-content pre code { background: none; padding: 0; }
            .article-content table { border-collapse: collapse; margin: 1.5em 0; width: 100%; }
            .article-content th, .article-content td {
              border: 1px solid #e2e8f0; padding: .5em .75em; text-align: left;
            }
            .article-content th { background: #f8fafc; font-weight: 600; }

            /* 内容页骨架补充：面包屑 / 文章封面 / 上下篇 / 相关推荐（正文骨架的布局类，
               与组件包工具类解耦，随 site.css 全站可用） */
            .page-breadcrumb { color: #64748b; font-size: .875rem; }
            .page-breadcrumb__link { color: #64748b; text-decoration: none; }
            .page-breadcrumb__link:hover { color: var(--color-primary-600, #2563eb); }
            .page-breadcrumb__sep { margin: 0 .5em; color: #cbd5e1; }
            .page-breadcrumb__current { color: #334155; }
            .article-cover { border-radius: .75rem; overflow: hidden; }
            .article-cover__img { display: block; width: 100%; max-height: 26rem; object-fit: cover; }
            .article-pager { display: grid; gap: .75rem; grid-template-columns: 1fr; }
            @media (min-width: 48rem) {
                .article-pager { grid-template-columns: repeat(2, minmax(0, 1fr)); }
            }
            .article-pager__link {
                border: 1px solid #e2e8f0; border-radius: .5rem; display: flex; flex-direction: column;
                gap: .25rem; padding: .875rem 1.25rem; text-decoration: none;
                transition: border-color .15s ease;
            }
            .article-pager__link:hover { border-color: var(--color-primary-600, #2563eb); }
            .article-pager__link--empty { background: #f8fafc; }
            .article-pager__link--end { text-align: right; }
            .article-pager__label { color: #94a3b8; font-size: .75rem; }
            .article-pager__title { color: #0f172a; font-size: .875rem; font-weight: 600; line-height: 1.5; }
            .article-related__title { border-left: 4px solid var(--color-primary-600, #2563eb); font-size: 1.125rem; font-weight: 600; padding-left: .75rem; }
            .article-related__grid { display: grid; gap: 1rem; grid-template-columns: 1fr; margin-top: 1rem; }
            @media (min-width: 48rem) {
                .article-related__grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
            }
            .article-related__card {
                border: 1px solid #e2e8f0; border-radius: .5rem; display: flex; flex-direction: column;
                gap: .375rem; padding: .875rem; text-decoration: none;
                transition: border-color .15s ease, box-shadow .15s ease;
            }
            .article-related__card:hover { border-color: var(--color-primary-300, #93c5fd); box-shadow: 0 10px 15px -3px rgb(0 0 0 / .1); }
            .article-related__thumb { border-radius: .375rem; overflow: hidden; }
            .article-related__thumb img { display: block; aspect-ratio: 16/9; object-fit: cover; width: 100%; }
            .article-related__name { color: #0f172a; font-size: .875rem; font-weight: 600; line-height: 1.5; }
            .article-related__time { color: #94a3b8; font-size: .75rem; }
            """;

}
