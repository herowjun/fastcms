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
package com.fastcms.ai.template;

import com.fastcms.ai.component.ComponentRegistry;
import com.fastcms.ai.component.TokenEngine;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 组件化生成提示词构建器：让 AI 输出 PageSpec（结构规划），而非直写 HTML
 *
 * <p>与 {@link TemplateGenPromptBuilder}（直写 HTML 流水线）并列，由配置项
 * {@code fastcms.ai.template.gen-mode} 决定走哪条管线（component / html）。</p>
 *
 * <p>提示词设计的核心取舍：</p>
 * <ul>
 *     <li>AI 只做擅长的事——理解需求、编排结构、写文案；视觉质量由预制的组件库保证</li>
 *     <li>组件菜单（{@link ComponentRegistry#buildManifest()}）注入 system prompt，
 *         只含元数据不含源码，规模可随组件库增长而不爆 token</li>
 *     <li>输出契约 {@code {"reply": "...", "pagespec": {...}}} 与 reply 流式提取器兼容，
 *         前端打字机效果零改造复用</li>
 *     <li>PageSpec 体量几 KB，从结构上消除 HTML 流水线"整套模板输出被截断"的顽疾</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class ComponentGenPromptBuilder {

    private final ComponentRegistry componentRegistry;

    public ComponentGenPromptBuilder(ComponentRegistry componentRegistry) {
        this.componentRegistry = componentRegistry;
    }

    /**
     * 构建系统提示词：角色 + 组件菜单 + PageSpec schema + 输出契约
     */
    public String buildSystemPrompt() {
        return """
                你是一名资深的网站信息架构师、网站架构师与品牌文案专家。你的任务：根据用户需求，先规划站点信息架构（菜单/栏目/页面），再用给定的组件编排页面结构，输出 PageSpec（JSON）。
                你不写任何 HTML/CSS 代码——页面的视觉质量由专业设计师制作的组件保证，你负责的是结构编排与内容文案。

                # 可用组件菜单（只能使用清单内的组件与变体，槽位名必须与清单一致）
                组件清单由系统装配（内置包 + 已安装的插件组件包），不同环境可用组件不同，一律以上方清单为准。

                %s

                # 内置正文占位（虚拟组件，不在上述清单，内容页编排必用）

                tw:content-body — 正文占位：渲染时替换为该页真实正文（文章列表/文章详情/单页正文），无变体无槽位。
                每个内容页（article_list / article / page 及其 suffix 变体）在 sections 序列中放一个 content-body
                标记正文位置，前后可自由叠加其他组件，实现"栏目横幅 → 正文 → 底部转化区"等完整页面结构。

                # PageSpec 格式（JSON Schema 说明）

                ```json
                {
                  "specVersion": "1.2",
                  "foundation": "%s",
                  "templateName": "模板目录名（英文小写，用户需求给出则沿用）",
                  "siteName": "站点名称（中文，4~10 字）",
                  "siteType": "站点类型自由描述（如 corporate-site / restaurant / portfolio）",
                  "stylePreset": "风格预设（见下方风格清单）",
                  "primaryColor": "主色（#RRGGBB 格式）",
                  "site": {
                    "menus": [ { "name": "首页", "type": "index", "suffix": null, "children": [] },
                               { "name": "产品展示", "type": "article_list", "suffix": "products",
                                 "children": [ { "name": "子栏目", "type": "article_list", "suffix": "sub1", "children": [] } ] },
                               { "name": "关于我们", "type": "page", "suffix": "about", "children": [] } ],
                    "categories": [ { "title": "分类名", "suffix": "products" } ],
                    "singlePages": [ { "title": "关于我们", "suffix": "about" } ],
                    "articles": [ { "title": "文章标题", "summary": "文章摘要" } ]
                  },
                  "pages": {
                    "index": { "sections": [ { "id": "hero", "component": "组件全名", "variant": "变体", "data": { "title": "槽位值", "image": "search:产品主图 生态农场" } } ] },
                    "article_list": { "sections": [ ... ] },
                    "article": { "sections": [ ... ] },
                    "page": { "sections": [ ... ] },
                    "page_landing": { "standalone": true, "sections": [ ... ] }
                  }
                }
                ```

                # 图片槽位协议（type=media 的槽位统一遵守）

                配图由系统装配，你不写任何图片地址：
                - 值填 "search:搜索关键词"（如 "search:散养土鸡 生态农场"）：系统自动从附件库搜索匹配图片，
                  附件库无合适图片时自动使用演示图。关键词用贴合站点主题的中文短语（产品/场景/氛围），2~8 个词
                - 用户需求中明确提供了图片地址时，直接填该 URL（http(s):// 或 / 开头）
                - 不需要配图时留空（组件自带占位设计）
                - 严禁编造 unsplash / picsum 等占位图站 URL 或任何虚假地址
                - spec 顶层的 imageAssets 字段是系统的图片解析记录，你不要输出它

                # 信息架构规划（site 段，你的第一职责，必须完成）

                用户需求可能只有"创新土鸡官网"一句话，你必须先推导出完整站点结构再编排页面：

                - menus：主导航 4~7 个顶级（首页 + 3~6 个栏目），每个顶级可带 0~4 个二级。
                  type ∈ index / article_list / page（内容栏目用 article_list，固定介绍页用 page）。
                  首页之外每个菜单必须带 suffix（英文小写单词，如 products / news / about / contact），
                  系统按 {type}_{suffix}.html 生成对应页面——菜单点击才有地方可去。
                  子菜单同样需要 type + suffix（type 通常继承父级）。
                - categories：文章分类（与 article_list 型栏目对应，数量 3~6 个），
                  suffix 与对应菜单一致或自定；每个分类须在某个 article_list 菜单树下可达。
                - singlePages：单页列表，与 page 型菜单一一对应（title 即菜单名，suffix 一致）。
                - articles：预览文章 8~12 篇，标题 + 摘要全部贴合用户需求主题！
                  土鸡站就写散养环境/五谷喂养/品类介绍/冷链配送，严禁出现与主题无关的内容。
                  这是预览页面的文章数据，直接决定用户对模板"专不专业"的第一印象。

                规则：
                - pages 四个基础键 index / article_list / article / page 全部必须出现；
                  需要差异化编排时也可输出带 suffix 的键（如 "article_list_products"），省略时系统自动用对应基础页骨架
                - 公共布局：导航（navbar）与页脚（footer）section 只需在 index 页编排一次，
                  系统自动抽取到全站共享的 _layout.html，其余页面自动获得相同导航与页脚，无需重复编排
                - 个别页面需要完全独立设计（不要公共导航/页脚）时，在该页加 "standalone": true，
                  并为其编排完整 sections（含自己的 navbar/footer）
                - 每个页面都是设计重点，不是只有首页！内容页的编排范式（除导航页脚外）：
                    栏目横幅（如 %s）开场 → tw:content-body 正文 → 补充组件（如 %s 常见问题 / %s 转化区）收尾；
                    同一页面 content-body 至多一个，未放置时正文追加在页面末尾
                - 各页面编排必须差异化：产品页突出产品卖点，新闻页突出资讯，关于页突出团队/历程/联系；
                  不同页面选不同组件与不同文案，严禁所有页面除正文外长得一模一样
                - index 页按"首屏 → 内容区 → 收尾"编排，通常 4~6 个 section；内容页通常 2~4 个 section（不含导航页脚）
                - section 的 id 用简短英文（如 nav / hero / features / faq / footer），同一页面内不重复
                - data 的 key 必须严格使用组件清单中的槽位名；带 * 的必填槽位缺失会校验失败
                - variant 省略时取组件第一个变体，但显式写出更可控
                - suffix 仅用小写字母数字下划线中划线；同一栏目页的菜单与对应分类/单页共用同一 suffix（菜单指向该页面的机制）；不同栏目页之间 suffix 不得重复

                # 风格预设清单（stylePreset 只能取以下值之一）

                - minimal：现代无衬线、标准圆角，通用科技/工具站（拿不准就选它）
                - corporate：商务稳重、小圆角，企业官网/金融/制造
                - warm：圆润亲和、大圆角，餐饮/母婴/生活服务
                - bold：硬朗强对比、小圆角，科技/电竞/潮流
                - elegant：衬线优雅，文化/艺术/高端品牌

                # 主色选择建议

                主色决定全站色彩体系（系统按主色自动推导十档色阶）。
                遵循行业惯例：科技蓝(#2563eb)、餐饮暖橙(#ea580c)、生态绿(#16a34a)、文创紫(#7c3aed)、高端金(#b45309)、沉稳藏青(#1e3a8a)。
                除非用户指定，避免纯黑、纯白、荧光色。

                # 文案质量要求（这是你的核心价值）

                - hero 主标题：一句有冲击力的话（≤24 字），点出站点核心价值，不要写"欢迎来到XX网站"这类废话
                - 副标题：一句话补充说明（≤60 字），具体、有信息量
                - 特性/列表项文案：标题精炼（≤8 字），描述具体可信（≤40 字），避免"专业高效服务一流"式的空洞套话
                - 所有文案用中文，贴合用户需求的行业主题（餐饮站写菜品与门店，科技站写产品与技术）：
                  site.menus/categories/singlePages/articles 与各组件槽位文案共同构成"这个网站就是为该客户做的"的观感，
                  任何一处出现通用模板味（如科技站文案出现在土鸡站）都算失败

                # 输出格式（严格 JSON）

                你的每次回复必须是一个 JSON 对象（不要包裹 markdown 代码块，不要输出任何 JSON 之外的文字）：
                ```json
                {
                  "reply": "给用户看的中文回复：简述你的设计思路（主色与风格选择理由、页面结构编排），100 字以内",
                  "pagespec": { ...完整 PageSpec JSON... },
                  "filePatches": [ { "path": "_components/组件文件名.ftl", "search": "组件源码原文精确片段", "replace": "替换后片段" } ]
                }
                ```
                filePatches 为可选字段（仅在用户提示词给出组件源码且需求属组件源码级样式时输出），无补丁时省略。

                # 行为准则

                1. 只使用组件菜单中存在的组件与变体，必填槽位必须填写
                2. 组件菜单中每个组件的"适用页面"约束必须遵守
                3. 文案是灵魂：宁可少放组件，也要把每个槽位的文案写好
                4. reply 保持简洁，设计细节体现在 pagespec 里
                5. site 信息架构必须完整输出（menus/categories/singlePages/articles 四段齐全），哪怕需求只有一句话
                6. 全程使用中文思考和回复
                """.formatted(componentRegistry.buildManifest(),
                exampleFullId("page-hero"), exampleFullId("faq"), exampleFullId("cta-banner"),
                com.fastcms.ai.component.BuiltinTailwindPackProvider.FOUNDATION);
    }

    /**
     * 组件 id → 当前注册表中的实际全名（提示词示例用）：
     * 插件组件包装载时返回 pack 前缀全名（如 twx:faq），未装载时回退裸 id（提示词示例自然降级）
     */
    private String exampleFullId(String componentId) {
        return componentRegistry.listComponents().stream()
                .map(ComponentRegistry.RegisteredComponent::fullId)
                .filter(fullId -> fullId.endsWith(":" + componentId))
                .findFirst()
                .orElse(componentId);
    }

    /**
     * 构建首次生成的用户提示词
     *
     * @param templateName 模板目录名
     * @param requirement  用户需求描述
     */
    public String buildFirstGenPrompt(String templateName, String requirement) {
        return "请为模板目录「" + templateName + "」规划网站结构，输出完整 PageSpec。\n\n"
                + "## 用户需求\n\n" + requirement + "\n\n"
                + "## 要求\n\n"
                + "1. templateName 固定为 " + templateName + "\n"
                + "2. 站点名称、主色、风格预设按需求主题自行判断（需求未指定时给出专业选择）\n"
                + "3. 先规划 site 信息架构：菜单 4~7 个顶级（首页 + 3~6 个内容栏目，各带 suffix）、\n"
                + "   分类/单页与菜单一一对应、预览文章 8~12 篇全部贴合需求主题\n"
                + "4. 再编排 pages（全部页面精心设计，不是只有首页）：\n"
                + "   index 页 4~6 个 section（含导航与页脚）；每个内容页用「栏目横幅 → tw:content-body →\n"
                + "   补充组件（常见问题/转化区等）」范式，各页面选不同组件与文案，体现页面差异化\n"
                + "5. 图片槽位（type=media）按「图片槽位协议」填 search: 搜索关键词，\n"
                + "   附件库无匹配时系统自动用演示图，不要编造图片 URL\n"
                + "6. 严格按照约定的 JSON 格式输出，不要包裹 markdown 代码块\n"
                + "7. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建微调用户提示词（组件化会话：基于当前 PageSpec 调整）
     *
     * <p>微调是 PageSpec 往返：输出调整后的完整 PageSpec，系统重渲染。
     * 换主色/换风格/换组件/改文案都走这条路，一次输出全量生效。
     * spec 表达不了的组件源码级样式（选中态/hover 色、写死的间距圆角等）走 filePatches，
     * 由 {@code componentSourcesBlock} 注入的组件源码支撑精准 search/replace。</p>
     *
     * @param requirement     用户微调需求
     * @param currentSpecJson 当前生效的完整 PageSpec JSON
     * @param componentSourcesBlock 组件源码注入块（可为空串：无组件文件时不注入）
     */
    public String buildRefinePrompt(String requirement, String currentSpecJson, String componentSourcesBlock) {
        return "请基于当前 PageSpec 进行调整，输出调整后的完整 PageSpec"
                + (StringUtils.hasText(componentSourcesBlock) ? "（spec 表达不了的可附 filePatches）" : "") + "。\n\n"
                + "## 微调需求\n\n" + requirement + "\n\n"
                + "## 当前 PageSpec\n\n```json\n" + currentSpecJson + "\n```\n\n"
                + componentSourcesBlock
                + "## 要求\n\n"
                + "1. 未被需求提及的部分保持原样（包括 site 信息架构与各页 sections，不要擅自「优化」）\n"
                + "2. 增删 section、换组件/变体、改文案、换主色/风格预设均可\n"
                + "3. 新增组件同样只能取自组件菜单，必填槽位必须填\n"
                + "4. 图片槽位：已有图片的槽位值是系统解析后的图片地址，未要求换图时保持原样；\n"
                + "   需要换图时改填 search:新关键词（按「图片槽位协议」）；spec 中的 imageAssets\n"
                + "   字段由系统维护，原样保留即可\n"
                + "5. 需求路由：结构/文案/槽位数据/主色等 spec 能表达的 → 只改 PageSpec；\n"
                + "   组件源码内写死的样式（如导航选中态颜色与 hover 不一致、选中态加粗黑色、\n"
                + "   固定圆角间距字号等 spec 无对应槽位）→ 输出 filePatches 直接改组件源码，\n"
                + "   此时 pagespec 原样带回。两类需求并存时同时输出\n"
                + "6. filePatches 格式（可选字段）：[{\"path\": \"_components/组件文件名.ftl\",\n"
                + "   \"search\": \"当前源码中的原文精确片段（须全文唯一）\", \"replace\": \"替换后片段\"}]。\n"
                + "   search 必须与上方组件源码逐字一致（含空格缩进）；不要删除源码中\n"
                + "   data-ai-section-root / data-ai-slot 标记（预览点选依赖它们）\n"
                + "   类名约束：改样式优先复用源码已有的类；可用类还有——主色系\n"
                + "   text/bg/border-primary-50~900（含 hover:/! 变体）；标准刻度数值类\n"
                + "   px/py/p/m/gap/w/h-0~64（如 py-4、mt-6、h-20）、text-xs~9xl、\n"
                + "   font-thin~black、rounded-none~full（以上全站已兜底定义）。\n"
                + "   禁用任意值语法（py-[13px]、text-[1.1rem]、bg-[#ff0000]）及未列出的\n"
                + "   自造类名（CSS 未编译，样式会静默失效）\n"
                + "7. 严格按照约定的 JSON 格式输出完整 PageSpec（不是只输出差异），不要包裹 markdown 代码块\n"
                + "8. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建选中区块的微调用户提示词（预览页点选区块后聚焦修改）
     *
     * <p>与 {@link #buildRefinePrompt} 的区别：明确告知用户点选的目标区块，
     * 注入该 section 的 spec 片段，并施加「只改该区块、其余逐字保留」的强约束——
     * 输出仍是完整 PageSpec（渲染引擎只接受全量 spec），但变更范围被限定在目标 section。
     * 组件源码级样式同样可走 filePatches（目标组件源码在注入块中）。</p>
     *
     * @param requirement      用户微调需求（针对选中区块）
     * @param currentSpecJson  当前生效的完整 PageSpec JSON
     * @param sectionId        用户点选的区块 ID
     * @param focusSectionJson 该区块的当前 spec 片段（JSON）
     * @param elementHint      用户点选区块时命中的具体元素描述（可空；元素级语义提示）
     * @param componentSourcesBlock 组件源码注入块（可为空串）
     */
    public String buildFocusRefinePrompt(String requirement, String currentSpecJson, String sectionId,
                                         String focusSectionJson, String elementHint, String componentSourcesBlock) {
        StringBuilder sb = new StringBuilder("用户在预览页面点选了区块「").append(sectionId)
                .append("」，本轮需求只针对该区块。请调整 PageSpec，输出调整后的完整 PageSpec")
                .append(StringUtils.hasText(componentSourcesBlock) ? "（spec 表达不了的可附 filePatches）" : "")
                .append("。\n\n");
        if (elementHint != null && !elementHint.isBlank()) {
            sb.append("（用户点选时聚焦的是区块内的：").append(elementHint.trim())
                    .append("，需求很可能与此元素相关）\n\n");
        }
        sb.append("## 微调需求\n\n").append(requirement).append("\n\n")
                .append("## 选中区块「").append(sectionId).append("」的当前定义\n\n```json\n")
                .append(focusSectionJson).append("\n```\n\n")
                .append("## 当前完整 PageSpec\n\n```json\n").append(currentSpecJson).append("\n```\n\n")
                .append(componentSourcesBlock)
                .append("## 要求\n\n")
                .append("1. 只允许修改选中区块「").append(sectionId).append("」（可换组件/变体、改文案、")
                .append("增删该 section 的槽位数据；若需求实属全站级调整如换主色，可顺带完成，但需在 reply 中说明）\n")
                .append("2. 除选中区块外，输出的完整 PageSpec 中其余所有内容（site 信息架构、其他页面的")
                .append("所有 sections、布局结构）必须与当前 PageSpec 逐字保持一致，严禁任何「顺手优化」\n")
                .append("3. 选中区块调整后仍须遵守组件菜单约束：只能取清单内组件与变体，必填槽位必须填\n")
                .append("4. 图片槽位：未要求换图时保持原值；需要换图时改填 search:新关键词；")
                .append("spec 中的 imageAssets 字段由系统维护，原样保留即可\n")
                .append("5. 需求路由：spec 能表达的（文案/槽位数据/换组件变体）→ 只改 PageSpec；")
                .append("组件源码内写死的样式（如选中态颜色、hover、固定圆角间距字号）→ 输出 filePatches")
                .append("改组件源码，pagespec 原样带回。filePatches 格式：[{\"path\": ")
                .append("\"_components/组件文件名.ftl\", \"search\": \"原文精确片段（须全文唯一）\", ")
                .append("\"replace\": \"替换后片段\"}]，search 与源码逐字一致（含缩进），")
                .append("不要删除 data-ai-section-root / data-ai-slot 标记。类名约束：优先复用源码")
                .append("已有的类；可用 text/bg/border-primary-50~900（含 ! 变体）、标准刻度类 ")
                .append("px/py/p/m/gap/w/h-0~64、text-xs~9xl、font-thin~black、rounded-none~full")
                .append("（全站已兜底）；禁用任意值语法（py-[13px] 等）及自造类名（会静默失效）\n")
                .append("6. 严格按照约定的 JSON 格式输出完整 PageSpec（不是只输出选中区块），不要包裹 markdown 代码块\n")
                .append("7. 请全程使用中文思考和回复\n");
        return sb.toString();
    }

    /**
     * 组件源码注入块总字节数上限：超过时只注入文件清单（避免 prompt 膨胀稀释注意力，
     * 此时模型无源码依据，不宜输出 filePatches，提示用户用选区模式定位）
     */
    private static final int COMPONENT_SOURCES_MAX_BYTES = 96 * 1024;

    /**
     * 构建组件源码注入块（refine 轮：让模型看到组件源码全文，才能输出精准 search/replace）
     *
     * <p>焦点模式只注入目标组件的源码（需求只针对该区块，其余组件无关）；
     * 非焦点模式注入全部组件源码（需求可能指向任意区块）。总量超上限时只列清单。</p>
     *
     * @param sources 组件源码列表（path + content），已按调用方策略筛选
     * @return prompt 注入块（无组件文件时返回空串）
     */
    public String buildComponentSourcesBlock(java.util.List<ComponentSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        long totalBytes = sources.stream().mapToLong(s -> s.content().length()).sum();
        StringBuilder sb = new StringBuilder("## 组件源码（filePatches 的 search 须与其逐字一致）\n\n");
        if (totalBytes > COMPONENT_SOURCES_MAX_BYTES) {
            sb.append("组件源码总量过大，仅列文件清单：\n");
            for (ComponentSource s : sources) {
                sb.append("- ").append(s.path()).append("\n");
            }
            sb.append("\n（源码未注入，无法输出可靠 filePatches；组件样式级调整请在预览中点选目标区块后重试）\n\n");
            return sb.toString();
        }
        for (ComponentSource s : sources) {
            sb.append("### ").append(s.path()).append("\n\n```ftl\n")
                    .append(s.content()).append("\n```\n\n");
        }
        return sb.toString();
    }

    /**
     * 组件源码（渲染产物落盘版，含系统注入的点选标记）
     */
    public record ComponentSource(String path, String content) {
    }

    /**
     * 构建校验失败后的自动修正提示（把校验错误回喂给模型自我修正）
     *
     * @param errors 校验错误清单（可行动：位置 + 候选）
     */
    public String buildFixPrompt(java.util.List<String> errors) {
        StringBuilder sb = new StringBuilder("你输出的 PageSpec 校验失败，错误如下：\n\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(i + 1).append(". ").append(errors.get(i)).append("\n");
        }
        sb.append("\n请输出修正后的完整 PageSpec（严格按错误提示修正，其余部分保持原样）。\n")
                .append("常见错误：组件全名写错（必须与组件菜单完全一致，含包前缀）、")
                .append("变体名不存在、必填槽位缺失、主色格式非法（需 #RRGGBB）、")
                .append("图片槽位值非法（应填 search:关键词 或 图片URL）。\n")
                .append("严格按照约定的 JSON 格式输出，不要包裹 markdown 代码块。\n");
        return sb.toString();
    }

    /**
     * 构建渲染校验失败的修复提示词（渲染产物 FTL 报错时回喂模型自动修复）
     *
     * <p>渲染校验与预览同管线，失败说明页面在预览中也是坏的。错误可能来自：
     * spec 槽位数据结构不符（如组件期望 sequence 却给了标量）、组件包自身源码缺陷
     * （FTL 报错指向 _components/ 下的文件，改 spec 修不了源码，只能规避）。
     * 提示词据此给出两类修复路径。</p>
     *
     * @param renderErrors   渲染错误列表（含文件与行号）
     * @param currentSpecJson 当前生效的完整 PageSpec JSON（已渲染落盘的版本）
     */
    public String buildRenderFixPrompt(java.util.List<String> renderErrors, String currentSpecJson) {
        StringBuilder sb = new StringBuilder("模板已渲染，但渲染校验发现 ")
                .append(renderErrors.size()).append(" 个页面渲染失败（预览同样会报错），错误如下：\n\n");
        for (int i = 0; i < renderErrors.size(); i++) {
            sb.append(i + 1).append(". ").append(renderErrors.get(i)).append("\n");
        }
        sb.append("\n## 当前 PageSpec\n\n```json\n").append(currentSpecJson).append("\n```\n\n")
                .append("请修复导致渲染失败的问题，输出修复后的完整 PageSpec：\n")
                .append("1. 若报错与槽位数据有关（如期望序列/哈希却给了其他类型），修正对应 section 的槽位数据结构\n")
                .append("2. 若 FTL 错误指向 _components/ 下的组件源码文件，那是组件包自身的缺陷，")
                .append("改 spec 修不了源码——两条路任选：a) 规避：将报错页面的该 section 换用清单内的")
                .append("其他组件或变体，或调整槽位数据让组件可用（如缺少 items 时补充合法数组）；")
                .append("b) 若报错行是简单语法/逻辑问题（如布尔运算写法），可输出 filePatches ")
                .append("精准修正该组件源码（格式与微调协议一致）\n")
                .append("3. 其余未报错的页面与 section 保持原样，不要顺手改动\n")
                .append("4. 严格按照约定的 JSON 格式输出完整 PageSpec，不要包裹 markdown 代码块\n");
        return sb.toString();
    }

}