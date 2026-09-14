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
package com.fastcms.ai.agent;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置智能体定义（代码注册，不落库、只读、可复制为自定义副本）
 *
 * <p>内置智能体与业务域一一对应，克制拆分粒度：只有执行模式或输出契约
 * 不同的业务才配独立内置（chat 的文章写作、pipeline 的模板生成）。
 * 差异化变体（SEO 写手等）通过"复制内置 + 改提示词"派生，不新增内置。</p>
 *
 * <p>内置升级策略：版本化演进时不动用户副本（副本记 baseAgentId），
 * 列表上仅提示"内置已更新，可合并/忽略"。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Component
public class BuiltinAgents {

    /** 文章续写智能体（chat：模型自主循环） */
    public static final String ARTICLE_WRITER_ID = "builtin.article-writer";

    /** 模板生成智能体（pipeline：规划→渲染→修复管线由代码驱动） */
    public static final String TEMPLATE_GENERATOR_ID = "builtin.template-generator";

    /**
     * 模板设计智能体（chat：设计稿先行模式的设计环节，见 doc/wiki/ai-template-two-mode-design.md §3）
     *
     * <p>与 {@link #TEMPLATE_GENERATOR_ID} 的分界：</p>
     * <table border="1">
     *     <tr><th></th><th>template-generator（管线）</th><th>template-designer（设计）</th></tr>
     *     <tr><td>executionMode</td><td>PIPELINE</td><td>CHAT（自主型）</td></tr>
     *     <tr><td>systemPrompt</td><td>null（提示词由管线代码拼）</td><td>设计契约（场景段由 DesignContractPrompt 追加）</td></tr>
     *     <tr><td>skills</td><td>空（管线不用技能——控制权在代码）</td><td>非空（design-brief 绑定，但经全文直注系统提示词消费而非 load_skill 两段式——设计轮技能必用，工具往返徒增思考流重启与时延）</td></tr>
     *     <tr><td>谁决定输入输出</td><td>管线代码</td><td>AI 自主 + 格式契约兜底</td></tr>
     * </table>
     */
    public static final String TEMPLATE_DESIGNER_ID = "builtin.template-designer";

    /**
     * 文章技能插件 id（技能随 article-skills-plugin 插件分发，
     * skillId 命名空间为 {pluginId}/{slug}）
     */
    public static final String ARTICLE_SKILLS_PLUGIN_ID = "article-skills-plugin";

    /**
     * 设计方法论技能 id（file 源磁盘技能 ~/fastcms/skills/design-brief）
     *
     * <p>消费方式：<b>全文直注</b>——template-designer 经 prepare(false) 跳过 L1 清单，
     * 由 MockupDesignService 把本技能 L2 正文直接拼入系统提示词（见
     * DesignContractPrompt#buildInjectedSystemPrompt）。设计轮技能必用，走 load_skill
     * 两段式只会徒增工具往返（思考流重启/时延），故不走自主加载。</p>
     */
    public static final String DESIGN_BRIEF_SKILL_ID = "design-brief";

    private final Map<String, AgentProfile> builtins = new LinkedHashMap<>();

    public BuiltinAgents() {
        builtins.put(ARTICLE_WRITER_ID, AgentProfile.builder()
                .agentId(ARTICLE_WRITER_ID)
                .name("文章续写智能体")
                .description("面向文章编辑场景的写作智能体：选题策划、长文续写、SEO 优化与文案润色")
                .executionMode(AgentProfile.MODE_CHAT)
                .source(AgentProfile.SOURCE_BUILTIN)
                .systemPrompt(
                        "你是本站点的资深内容编辑，负责文章写作辅助。写作要求：\n" +
                        "- 标题简洁有信息量，正文结构清晰、段落简短；\n" +
                        "- 术语与人称保持一致，标点风格统一；\n" +
                        "- 根据用户需要切换技能：选题策划、长文续写、SEO 优化、文案润色。")
                .skills(List.of(
                        ARTICLE_SKILLS_PLUGIN_ID + "/article-topic",
                        ARTICLE_SKILLS_PLUGIN_ID + "/article-continue",
                        ARTICLE_SKILLS_PLUGIN_ID + "/article-seo",
                        ARTICLE_SKILLS_PLUGIN_ID + "/article-polish"))
                .tools(Collections.emptyList())
                .dailyTokenQuota(0L)
                .sortNum(0)
                .status(1)
                .build());

        builtins.put(TEMPLATE_GENERATOR_ID, AgentProfile.builder()
                .agentId(TEMPLATE_GENERATOR_ID)
                .name("模板生成智能体")
                .description("站点模板生成管线：规划（PageSpec）→ 渲染 → 校验修复。由系统代码驱动，AI 是管线中被调用的生成环节")
                .executionMode(AgentProfile.MODE_PIPELINE)
                .source(AgentProfile.SOURCE_BUILTIN)
                .systemPrompt(null)
                .skills(Collections.emptyList())
                .tools(Collections.emptyList())
                .dailyTokenQuota(0L)
                .sortNum(1)
                .status(1)
                .build());

        builtins.put(TEMPLATE_DESIGNER_ID, AgentProfile.builder()
                .agentId(TEMPLATE_DESIGNER_ID)
                .name("模板设计智能体")
                .description("设计稿先行模式：自主设计站点 HTML 设计稿。自主型——AI 自主决定加载哪个设计技能")
                .executionMode(AgentProfile.MODE_CHAT)
                .source(AgentProfile.SOURCE_BUILTIN)
                // 设计契约（可转化性/审美/输出契约）见 DesignContractPrompt；场景段由其拼装追加
                .systemPrompt(com.fastcms.ai.template.design.DesignContractPrompt.DESIGN_SYSTEM_PROMPT)
                // design-brief=设计方法论技能（全文直注系统提示词，见 DESIGN_BRIEF_SKILL_ID 注释）；
                // 方向资产的确定性注入由 DesignContractPrompt 直接拼入提示词（§4.3）
                .skills(List.of(DESIGN_BRIEF_SKILL_ID))
                .tools(Collections.emptyList())
                .dailyTokenQuota(0L)
                .sortNum(2)
                .status(1)
                .build());
    }

    /**
     * 全部内置智能体（保持注册顺序）
     */
    public List<AgentProfile> list() {
        return List.copyOf(builtins.values());
    }

    /**
     * 按 agentId 查找内置智能体
     */
    public AgentProfile get(String agentId) {
        return agentId == null ? null : builtins.get(agentId);
    }

    public boolean isBuiltin(String agentId) {
        return agentId != null && builtins.containsKey(agentId);
    }

}
