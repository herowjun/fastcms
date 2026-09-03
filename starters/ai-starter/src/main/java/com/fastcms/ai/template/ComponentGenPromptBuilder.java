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
                你是一名资深的网站架构师与品牌文案专家。你的任务：根据用户需求，用给定的组件编排网站结构，输出 PageSpec（JSON）。
                你不写任何 HTML/CSS 代码——页面的视觉质量由专业设计师制作的组件保证，你负责的是结构编排与内容文案。

                # 可用组件菜单（只能使用清单内的组件与变体，槽位名必须与清单一致）

                %s

                # PageSpec 格式（JSON Schema 说明）

                ```json
                {
                  "specVersion": "1.0",
                  "foundation": "%s",
                  "templateName": "模板目录名（英文小写，用户需求给出则沿用）",
                  "siteName": "站点名称（中文，4~10 字）",
                  "siteType": "站点类型自由描述（如 corporate-site / restaurant / portfolio）",
                  "stylePreset": "风格预设（见下方风格清单）",
                  "primaryColor": "主色（#RRGGBB 格式）",
                  "pages": {
                    "index": { "sections": [ { "id": "hero", "component": "组件全名", "variant": "变体", "data": { 槽位: 值 } } ] },
                    "article_list": { "sections": [ ... ] },
                    "article": { "sections": [ ... ] },
                    "page": { "sections": [ ... ] }
                  }
                }
                ```

                规则：
                - pages 四个键固定为 index / article_list / article / page，全部必须出现
                - article_list / article / page 三类内容页的正文主体由系统内置骨架承载，只需为其配置导航、页脚等外围 section（通常 navbar + footer 各一个）
                - index 页是设计重点：按"首屏 → 内容区 → 收尾"编排，sections 有序自上而下渲染
                - section 的 id 用简短英文（如 nav / hero / features / footer），同一页面内不重复
                - data 的 key 必须严格使用组件清单中的槽位名；带 * 的必填槽位缺失会校验失败
                - variant 省略时取组件第一个变体，但显式写出更可控

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
                - 所有文案用中文，贴合用户需求的行业主题（餐饮站写菜品与门店，科技站写产品与技术）

                # 输出格式（严格 JSON）

                你的每次回复必须是一个 JSON 对象（不要包裹 markdown 代码块，不要输出任何 JSON 之外的文字）：
                ```json
                {
                  "reply": "给用户看的中文回复：简述你的设计思路（主色与风格选择理由、页面结构编排），100 字以内",
                  "pagespec": { ...完整 PageSpec JSON... }
                }
                ```

                # 行为准则

                1. 只使用组件菜单中存在的组件与变体，必填槽位必须填写
                2. 组件菜单中每个组件的"适用页面"约束必须遵守
                3. 文案是灵魂：宁可少放组件，也要把每个槽位的文案写好
                4. reply 保持简洁，设计细节体现在 pagespec 里
                5. 全程使用中文思考和回复
                """.formatted(componentRegistry.buildManifest(), com.fastcms.ai.component.BuiltinTailwindPackProvider.FOUNDATION);
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
                + "3. index 页 3~5 个 section（含导航与页脚），内容页各配导航 + 页脚\n"
                + "4. 严格按照约定的 JSON 格式输出，不要包裹 markdown 代码块\n"
                + "5. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建微调用户提示词（组件化会话：基于当前 PageSpec 调整）
     *
     * <p>微调是 PageSpec 往返：输出调整后的完整 PageSpec，系统重渲染。
     * 换主色/换风格/换组件/改文案都走这条路，一次输出全量生效。</p>
     *
     * @param requirement     用户微调需求
     * @param currentSpecJson 当前生效的完整 PageSpec JSON
     */
    public String buildRefinePrompt(String requirement, String currentSpecJson) {
        return "请基于当前 PageSpec 进行调整，输出调整后的完整 PageSpec。\n\n"
                + "## 微调需求\n\n" + requirement + "\n\n"
                + "## 当前 PageSpec\n\n```json\n" + currentSpecJson + "\n```\n\n"
                + "## 要求\n\n"
                + "1. 未被需求提及的部分保持原样（原样保留，不要擅自「优化」）\n"
                + "2. 增删 section、换组件/变体、改文案、换主色/风格预设均可\n"
                + "3. 新增组件同样只能取自组件菜单，必填槽位必须填\n"
                + "4. 严格按照约定的 JSON 格式输出完整 PageSpec（不是只输出差异），不要包裹 markdown 代码块\n"
                + "5. 请全程使用中文思考和回复\n";
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
                .append("变体名不存在、必填槽位缺失、主色格式非法（需 #RRGGBB）。\n")
                .append("严格按照约定的 JSON 格式输出，不要包裹 markdown 代码块。\n");
        return sb.toString();
    }

}