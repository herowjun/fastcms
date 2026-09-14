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
package com.fastcms.ai.template.design;

import com.fastcms.ai.component.DesignDirectionLibrary;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 设计契约提示词拼装（设计稿先行模式，见 doc/wiki/ai-template-two-mode-design.md §4.3）
 *
 * <p>纯静态工具。两层结构：</p>
 * <ul>
 *     <li>{@link #DESIGN_SYSTEM_PROMPT}：设计契约（可转化性/审美/输出契约）——
 *     注册为 builtin.template-designer 的系统提示词基底（AgentChatExecutor.prepare 自动携带）</li>
 *     <li>{@link #build(...)}：场景段（站点需求 + 方向资产 + 页面任务 + 修正指令）——
 *     由 {@code MockupDesignService} 按页/按轮拼装为用户消息</li>
 * </ul>
 *
 * <p><b>方向资产注入方式</b>（与管线模式的差异点）：管线模式方向资产由代码轮换注入提示词；
 * 设计模式走确定性注入——本类把方向资产的 do/dont/参考块直接拼进场景段，保证方向可约束。
 * design-brief 设计方法论同理经 {@link #buildInjectedSystemPrompt} 全文直注系统提示词
 * （不走 load_skill 两段式，理由见该方法注释；S5 方向技能包若落地亦应沿用直注）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class DesignContractPrompt {

    private DesignContractPrompt() {
    }

    /**
     * 设计契约（系统提示词，§4.3 原文）
     *
     * <p>可转化性契约 1~5 与 {@code DesignHtmlValidator} V1~V6、{@code MockupAuditor} A1~A7
     * 一一对应——契约文本变更时需同步校验/审计规则。</p>
     */
    public static final String DESIGN_SYSTEM_PROMPT = """
            你是资深网站设计师。按用户描述设计一个完整网站的静态设计稿（纯 HTML + Tailwind CDN + 内联样式变量）。
            【可转化性契约】（违反将导致无法转成 CMS 模板）：
            1. 每个页面 ≥3 个顶层 <section data-block="语义名"> 区块（hero/features/gallery/footer 等）
            2. 色彩统一走 :root CSS 变量（--c-primary/--c-accent/--c-bg/--c-text/--c-muted），禁止区块内硬编码色值
            3. 导航与页脚在所有页面保持相同结构与 id（#nav-toggle / #footer）
            4. 图片用占位：design/assets/placeholder-<语义>.svg（简单几何 SVG），并在 img 上加 data-src-hint="真实图描述"
            5. 交互 JS 只允许：汉堡菜单切换 + 锚点平滑滚动 + 简单滚动渐显（其余交互会被丢弃）
            【审美契约】：留白优先、字号三级、每个区块一个视觉重点；若下方附有《设计方法论》全文则严格遵循（契约优先，方法论补充细化）。
            【输出契约】：文件块格式（===FILE: path===），一次输出该页全部文件。""";

    /**
     * 直注模式系统提示词 = 设计契约基底 + 设计方法论（design-brief 技能 L2 正文）全文直拼
     *
     * <p>设计轮技能必用，无需 L1 清单 + load_skill 两段式按需加载——工具往返会重启思考流
     * （重启链缓冲爆炸的根因之一）且徒增时延，故 template-designer 经 prepare(false) 跳过
     * 技能注入，由调用方把技能正文经本方法直接拼入系统提示词。</p>
     *
     * <p>技能正文首段自带"与设计契约配合使用（契约优先，本方法论补充细化）"的定位说明，
     * 此处只加标题不再重复。</p>
     *
     * @param baseSystemPrompt   设计契约基底（prepare 产出的 getBaseSystemPrompt）
     * @param designBriefContent design-brief 技能 L2 正文（SkillRegistry.loadContent 产出）；
     *                           空/null（技能库未安装）时原样返回基底——契约措辞已条件化，
     *                           不做"已注入"的虚假声明
     */
    public static String buildInjectedSystemPrompt(String baseSystemPrompt, String designBriefContent) {
        if (!StringUtils.hasText(designBriefContent)) {
            return baseSystemPrompt;
        }
        return baseSystemPrompt + "\n\n# 设计方法论（完整指令，已直接注入系统提示词）\n\n"
                + designBriefContent.trim();
    }

    /**
     * 拼装单页设计任务的用户消息（场景段）
     *
     * @param requirement      站点需求描述（会话 requirement）
     * @param direction        设计方向资产（可空 = AI 自由发挥；来自 DesignDirectionLibrary）
     * @param page             本次要设计的页面规划（DesignPagePlanner 产出）
     * @param sitePageContext  全站页面清单摘要（跨页 nav/footer 一致性提示用，如 "index(首页)/about(关于我们)/..."）
     * @param mobileAdaptive   是否适配移动端（true=响应式：viewport + ≥2 个断点 + 汉堡菜单）
     * @param prevAuditIssues  上轮机器审计问题（可空；非空 = 审计修正轮，逐条拼入）
     * @param prevFormatError  上轮格式校验错误（可空；非空 = 格式修正轮，拼入修正指令原文）
     * @param userComment      用户否决意见（可空；REJECT 确认携带的修改意见，拼入"用户具体不满"段，
     *                         与升级管线 feedback 同口径）
     */
    public static String build(String requirement,
                               DesignDirectionLibrary.DesignDirectionAsset direction,
                               DesignPagePlanner.PagePlan page,
                               String sitePageContext,
                               boolean mobileAdaptive,
                               List<String> prevAuditIssues,
                               String prevFormatError,
                               String userComment) {
        StringBuilder sb = new StringBuilder();

        sb.append("【站点需求】\n").append(requirement == null ? "" : requirement.trim()).append('\n');

        if (direction != null) {
            sb.append("\n【设计方向：").append(direction.name()).append("】\n");
            sb.append(direction.summary() == null ? "" : direction.summary().trim()).append('\n');
            if (StringUtils.hasText(direction.primaryColor())) {
                sb.append("建议主色：").append(direction.primaryColor().trim()).append('\n');
            }
            if (direction.doRules() != null && !direction.doRules().isEmpty()) {
                sb.append("方向要求（必须遵守）：\n");
                for (String rule : direction.doRules()) {
                    sb.append("- ").append(rule).append('\n');
                }
            }
            if (direction.dontRules() != null && !direction.dontRules().isEmpty()) {
                sb.append("方向禁止（不许出现）：\n");
                for (String rule : direction.dontRules()) {
                    sb.append("- ").append(rule).append('\n');
                }
            }
            // 参考块 few-shot：取前 2 块控制 token（参考块是方向资产的核心 few-shot 素材）
            if (direction.referenceBlocks() != null && !direction.referenceBlocks().isEmpty()) {
                sb.append("\n【方向参考块】（风格参照，不要照抄文案）\n");
                direction.referenceBlocks().stream().limit(2).forEach(block ->
                        sb.append(block).append("\n\n"));
            }
        }

        sb.append("\n【全站页面清单】\n").append(sitePageContext).append('\n');
        sb.append("导航与页脚必须在所有页面保持相同结构与 id（#nav-toggle / #footer）。\n");

        sb.append("\n【本次任务】设计页面：").append(page.name()).append(".html（").append(page.title()).append("）\n");
        if (StringUtils.hasText(page.description())) {
            sb.append("页面定位：").append(page.description().trim()).append('\n');
        }
        sb.append("输出文件：design/").append(page.name()).append(".html");
        if (mobileAdaptive) {
            sb.append("（响应式：<meta viewport> + 至少 768px/1024px 两个断点 + 移动端汉堡菜单）");
        } else {
            sb.append("（专注桌面端设计，宽度按 1280px 基准）");
        }
        sb.append("，图片占位 SVG 一并输出（design/assets/placeholder-*.svg）。\n");

        if (prevAuditIssues != null && !prevAuditIssues.isEmpty()) {
            sb.append("\n【上轮机器审计问题】（本轮必须逐条修复）\n");
            for (int i = 0; i < prevAuditIssues.size(); i++) {
                sb.append(i + 1).append(". ").append(prevAuditIssues.get(i)).append('\n');
            }
        }
        if (StringUtils.hasText(prevFormatError)) {
            sb.append("\n【上轮输出格式错误】（按以下指令修正后重新输出完整文件）\n")
              .append(prevFormatError.trim()).append('\n');
        }
        if (StringUtils.hasText(userComment)) {
            sb.append("\n【用户具体不满】（本轮设计必须针对性改进）\n").append(userComment.trim()).append('\n');
        }

        sb.append("\n输出该页全部文件（===FILE: design/").append(page.name()).append(".html=== 起头，");
        sb.append("占位图 SVG 各自独立文件块），不要输出解释性文字。");
        return sb.toString();
    }
}
