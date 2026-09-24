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
 * Unless required by applicable law or in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template;

import com.fastcms.ai.agent.BuiltinAgents;
import com.fastcms.ai.component.DesignDirectionLibrary;
import com.fastcms.ai.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模板生成系统提示词构建器
 *
 * <p>封装 fastcms 模板的完整规范：
 * <ol>
 *     <li>目录结构与 _template.properties 配置</li>
 *     <li>_layout.html 宏定义（header / body / script）</li>
 *     <li>FreeMarker 指令清单（articleListTag、menuTag、articlePageTag 等）</li>
 *     <li>上下文变量（article、category、articleVoPage 等）</li>
 *     <li>必备页面（index.html / article.html / article_list.html / page.html）</li>
 *     <li>响应格式（JSON 数组：[{path, content, action}]）</li>
 * </ol>
 *
 * <p>该提示词注入到 ChatClient 系统消息中，确保 AI 生成的文件可直接被 fastcms 识别。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class TemplateGenPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(TemplateGenPromptBuilder.class);

    /**
     * 技能注册中心：模板制作规范段（## 1~## 11）优先从插件技能
     * {@code template-skills-plugin/template-spec} 加载（规范随插件可插拔/可升级），
     * 技能缺失时回退内置副本 {@link #SPEC_PROMPT_BUILTIN}（两条路径提示词逐字节一致）
     */
    private final SkillRegistry skillRegistry;

    public TemplateGenPromptBuilder(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    // ==================== 深度焕新设计方向（单一来源） ====================
    // designDirectionName（短名，收尾摘要用）、buildStyleUpgradePrompt（方向段）、
    // buildRefreshScopePrompt（范围评估）三处的方向定义全部收敛到本区块，
    // 防止三份硬编码漂移不一致。

    /**
     * 焕新轮换方向（<strong>仅资产库加载失败时的兜底文案</strong>，内容与
     * {@code resources/ai/design-directions/*.json} 保持同步；正常路径从资产取 name+summary）
     */
    private static final String[] DESIGN_DIRECTIONS = {
            "现代商务风：更强的视觉层次与对比——深色或渐变 hero 区、大号粗标题、粗分区留白、"
                    + "明显的主色按钮与徽章，整体大气稳重",
            "轻盈优雅风：更多留白与呼吸感——浅色背景、柔和阴影、细边框、大圆角卡片、"
                    + "克制的主色点缀与细腻的 hover 微交互，整体精致轻快",
            "杂志编辑风：内容优先的排版——大图视觉、超大标题、编辑式不对称网格、"
                    + "去卡片化的开放分区、强烈的排版节奏与编号/线条装饰"
    };

    /**
     * 轮换方向短名（兜底用，同上；正常路径从资产取 name）
     */
    private static final String[] DESIGN_DIRECTION_NAMES = {"现代商务风", "轻盈优雅风", "杂志编辑风"};

    /**
     * 反馈关键词命中表：用户具体不满 → 定向修正方向（P0-2）。
     *
     * <p><strong>关键词整编（问题3修复）</strong>：全部为多字词且逐词过反语义校验——
     * 单字词（暗/花/密…）会被「再暗一点」「别这么花」等反向/否定表达误命中，
     * 给出与诉求完全相反的方向；命中点紧邻前文有否定前缀（不/别/莫/勿/没那么/不要/不太）
     * 时该命中作废（见 {@link #indexOfNotNegated}）。「太丑/不好看/土」等整体评价
     * 刻意不收录——整体不满不可靠映射到具体修正方向，落回原文直传（优先级最高段）+ 轮换。</p>
     *
     * <p>未命中走轮换表兜底（不比现状差）。每行：{方向描述, 短名, 资产key, 关键词...}
     * （资产key 为问题5修复：反馈命中也注入方向资产 few-shot/do/dont + tokens 覆写）。</p>
     */
    private static final String[][] FEEDBACK_DIRECTION_TABLE = {
            {"提亮留白方向：整体提亮配色——浅色背景为主、减少深色大面积区块与重渐变、"
                    + "提高明度对比与留白面积，让页面明亮通透", "提亮留白", "feedback-brighten",
                    "太暗", "太黑", "发暗", "发黑", "昏暗", "太深了", "压抑", "沉闷"},
            {"疏朗留白方向：降低信息密度——减少同屏卡片数量、增大区块间距与内边距、"
                    + "每个区块只保留核心元素，让页面有呼吸感", "疏朗留白", "feedback-spacious",
                    "太密", "太挤", "太满", "拥挤", "紧凑", "密密麻麻", "塞满"},
            {"强对比层次方向：强化视觉层次——加大标题与正文的字号/字重对比、"
                    + "主色强调关键信息、区块之间拉开节奏差异，让页面有重点有起伏", "强对比层次", "feedback-contrast",
                    "太素", "太平", "单调", "平淡", "没特色", "没亮点", "没层次"},
            {"统一语言方向：统一全站版式语言——一致的卡片/栅格/圆角/间距规范、"
                    + "消除同站混搭的版式差异，让页面整体协调", "统一语言", "feedback-unify",
                    "混乱", "花哨", "不统一", "风格不一", "太乱", "杂乱"}
    };

    /**
     * 否定前缀清单（命中点紧邻前文出现即视为反向表达，该命中作废）
     */
    private static final String[] NEGATION_PREFIXES = {"没那么", "不要", "不太", "不", "别", "莫", "勿"};

    /**
     * 焕新轮次对应的 设计方向短名（收尾摘要用）。
     * 用户反馈命中关键词时返回命中方向短名，否则从「未被否决的方向池」顺序取
     * （问题4：焕新触发=否决上一轮方向，rejected 集合持久化于计划文件）；
     * 池耗尽时退回兜底文案轮换（调用方应同时给出池耗尽提示）。
     */
    public static String designDirectionName(int refreshRound, String userFeedback,
                                             java.util.Set<String> rejectedKeys) {
        if (refreshRound <= 0) {
            return "默认现代风";
        }
        String hit = matchFeedbackDirection(userFeedback);
        if (hit != null) {
            return hit;
        }
        DesignDirectionLibrary.DesignDirectionAsset asset =
                DesignDirectionLibrary.firstUnrejected(rejectedKeys);
        return asset != null ? asset.name()
                : DESIGN_DIRECTION_NAMES[(refreshRound - 1) % DESIGN_DIRECTION_NAMES.length];
    }

    /**
     * 焕新设计方向描述（提示词用）：用户反馈关键词命中优先（定向修正），
     * 未命中从「未被否决的方向池」顺序取（问题4）；池耗尽退回兜底文案轮换。
     * refreshRound <= 0 返回 null（首次升级无方向段）。
     */
    static String resolveDesignDirection(int refreshRound, String userFeedback,
                                         java.util.Set<String> rejectedKeys) {
        if (refreshRound <= 0) {
            return null;
        }
        int hit = matchFeedbackDirectionIndex(userFeedback);
        if (hit >= 0) {
            return FEEDBACK_DIRECTION_TABLE[hit][0];
        }
        DesignDirectionLibrary.DesignDirectionAsset asset =
                DesignDirectionLibrary.firstUnrejected(rejectedKeys);
        return asset != null ? asset.name() + "：" + asset.summary()
                : DESIGN_DIRECTIONS[(refreshRound - 1) % DESIGN_DIRECTIONS.length];
    }

    /**
     * 本轮焕新对应的方向资产键（P0-3 + 问题5）：供升级管线在 restartPlan 时按
     * {@code DesignDirectionLibrary.get(key).tokensOverride} 覆写 tokens.css（确定性换肤）。
     *
     * <p>反馈命中 → 返回反馈方向的资产键（问题5：定向修正同样有 tokens 覆写 +
     * few-shot/do/dont 加持，不再是最贫瘠输入）；未命中 → 从未被否决的轮换池顺序取
     * （问题4）；池耗尽返回 null（默认 tokens + 兜底文案轮换）。</p>
     */
    public static String resolveDirectionAssetKey(int refreshRound, String userFeedback,
                                                  java.util.Set<String> rejectedKeys) {
        if (refreshRound <= 0) {
            return null;
        }
        int hit = matchFeedbackDirectionIndex(userFeedback);
        if (hit >= 0) {
            return FEEDBACK_DIRECTION_TABLE[hit][2];
        }
        return DesignDirectionLibrary.firstUnrejectedKey(rejectedKeys);
    }

    /**
     * 本轮方向的语言描述（P2-1 混血审计修复用）：方向名 + do/dont 规则摘要。
     * layout_language_mix 修复以该语言为统一基准；refreshRound &lt;= 0（首次升级）
     * 返回 null（修复提示词回退为「以站内多数页面语言为准」）。
     */
    public static String directionLanguage(int refreshRound, String userFeedback,
                                           java.util.Set<String> rejectedKeys) {
        if (refreshRound <= 0) {
            return null;
        }
        String key = resolveDirectionAssetKey(refreshRound, userFeedback, rejectedKeys);
        DesignDirectionLibrary.DesignDirectionAsset asset =
                key == null ? null : DesignDirectionLibrary.get(key);
        if (asset != null) {
            StringBuilder sb = new StringBuilder(asset.name()).append("：").append(asset.summary());
            if (!asset.doRules().isEmpty()) {
                sb.append("；必须做到：").append(String.join("；", asset.doRules()));
            }
            if (!asset.dontRules().isEmpty()) {
                sb.append("；禁止：").append(String.join("；", asset.dontRules()));
            }
            return sb.toString();
        }
        return resolveDesignDirection(refreshRound, userFeedback, rejectedKeys);
    }

    /**
     * 轮换方向池（3 内置 + 4 反馈修正方向，问题②扩池后共 7 个）是否已全部被用户
     * 否决（问题4池耗尽判定：全部焕新过一遍仍未满意）。rejected 为空不视为耗尽。
     */
    public static boolean rotationExhausted(java.util.Set<String> rejectedKeys) {
        return DesignDirectionLibrary.allDirectionsRejected(rejectedKeys);
    }

    /**
     * 反馈是否命中关键词（供范围评估提示词判断定向修正场景）
     */
    static boolean isFeedbackDirected(String userFeedback) {
        return matchFeedbackDirectionIndex(userFeedback) >= 0;
    }

    /**
     * 反馈命中的方向短名（问题3c 命中透出）：服务层在焕新前置消息中播报
     * 「已识别反馈方向：X」，让用户看到系统如何理解了自己的话，发现反语义
     * 误解可立即中断重填。未命中返回 null。
     */
    public static String matchedFeedbackDirectionName(String userFeedback) {
        return matchFeedbackDirection(userFeedback);
    }

    private static int matchFeedbackDirectionIndex(String userFeedback) {
        if (userFeedback == null || userFeedback.isBlank()) {
            return -1;
        }
        for (int i = 0; i < FEEDBACK_DIRECTION_TABLE.length; i++) {
            for (int k = 3; k < FEEDBACK_DIRECTION_TABLE[i].length; k++) {
                if (indexOfNotNegated(userFeedback, FEEDBACK_DIRECTION_TABLE[i][k]) >= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 子串命中 + 否定前缀剥离（问题3）：命中点向前紧邻否定词（如「不要太密」「别这么…」
     * 的反向表达）时该命中作废，继续找下一个出现位置；全部被否定返回 -1
     */
    private static int indexOfNotNegated(String text, String keyword) {
        int idx = text.indexOf(keyword);
        while (idx >= 0) {
            if (!precededByNegation(text, idx)) {
                return idx;
            }
            idx = text.indexOf(keyword, idx + 1);
        }
        return -1;
    }

    private static boolean precededByNegation(String text, int hitIdx) {
        for (String neg : NEGATION_PREFIXES) {
            int start = hitIdx - neg.length();
            if (start >= 0 && text.startsWith(neg, start)) {
                return true;
            }
        }
        return false;
    }

    private static String matchFeedbackDirection(String userFeedback) {
        int idx = matchFeedbackDirectionIndex(userFeedback);
        return idx < 0 ? null : FEEDBACK_DIRECTION_TABLE[idx][1];
    }

    /**
     * 方向资产 few-shot 段（P0-3 + 问题5）：注入参考区块与 do/dont 清单——
     * 结构可按页面调整，但视觉语言（配色/圆角/密度/节奏）必须与参考一致。
     *
     * <p>反馈定向修正同样注入（问题5：用户意图最强的场景不再拿最贫瘠的输入，
     * 按命中方向取反馈资产）；资产缺失（库加载失败/资产未配置）不注入，保持现状文案。</p>
     */
    private static void appendDirectionAssetSection(StringBuilder sb, int refreshRound,
                                                    String userFeedback,
                                                    java.util.Set<String> rejectedKeys) {
        int hit = matchFeedbackDirectionIndex(userFeedback);
        DesignDirectionLibrary.DesignDirectionAsset asset = hit >= 0
                ? DesignDirectionLibrary.get(FEEDBACK_DIRECTION_TABLE[hit][2])
                : DesignDirectionLibrary.firstUnrejected(rejectedKeys);
        if (asset == null) {
            return;
        }
        List<String> blocks = asset.referenceBlocks();
        if (!blocks.isEmpty()) {
            sb.append("### 方向参考区块（few-shot：结构可变，视觉语言必须一致）\n\n");
            for (int i = 0; i < blocks.size(); i++) {
                sb.append("参考 ").append(i + 1).append("：\n```html\n")
                        .append(blocks.get(i)).append("\n```\n\n");
            }
        }
        if (!asset.doRules().isEmpty()) {
            sb.append("### 本方向必须做到（do）\n\n");
            asset.doRules().forEach(rule -> sb.append("- ").append(rule).append('\n'));
            sb.append('\n');
        }
        if (!asset.dontRules().isEmpty()) {
            sb.append("### 本方向禁止（dont）\n\n");
            asset.dontRules().forEach(rule -> sb.append("- ").append(rule).append('\n'));
            sb.append('\n');
        }
    }

    /**
     * 构建系统提示词
     *
     * <p>系统提示词是固定的，不随用户需求变化。
     * 用户需求通过 user 消息注入，由 {@link #buildUserPrompt(String, String)} 生成。</p>
     *
     * @param templateName   模板目录名（英文，作为 pathName）
     * @param mobileAdaptive 是否适配移动端（控制第 10 节移动端适配与行为准则第 3 条的强弱）
     */
    public String buildSystemPrompt(String templateName, boolean mobileAdaptive) {
        // 规范段（## 1~## 11）：插件技能优先，缺失回退内置副本；协议段（## 12 + 行为准则）恒内置。
        // 拼装顺序与原 BASE_SYSTEM_PROMPT 单常量逐字节一致（规范段 + 空行 + 协议段）。
        String base = loadSpecPrompt() + "\n\n" + SYSTEM_PROMPT_TAIL;
        return base
                .replace("${templateName}", templateName)
                .replace("${mobileAdaptiveSection}", mobileAdaptive ? MOBILE_SECTION_REQUIRED : MOBILE_SECTION_DISABLED)
                .replace("${mobileRule}", mobileAdaptive ? MOBILE_RULE_REQUIRED : MOBILE_RULE_DISABLED);
    }

    /**
     * 加载模板制作规范段（系统提示词 ## 1~## 11）
     *
     * <p>优先读插件技能 {@code template-skills-plugin/template-spec}（SKILL.md 正文，
     * 与 {@link #SPEC_PROMPT_BUILTIN} 内容保持同步）；插件未安装或技能解析失败返回 null，
     * 回退内置副本——保证规范段永远可用，且两条路径提示词一致。</p>
     */
    private String loadSpecPrompt() {
        String skillContent = skillRegistry.loadContent(BuiltinAgents.TEMPLATE_SPEC_SKILL_ID);
        if (StringUtils.hasText(skillContent)) {
            return skillContent;
        }
        log.warn("模板制作规范技能 [{}] 未加载，系统提示词回退内置规范段", BuiltinAgents.TEMPLATE_SPEC_SKILL_ID);
        return SPEC_PROMPT_BUILTIN;
    }

    /**
     * 构建用户首条消息（初始需求描述，默认开启移动端适配）
     *
     * @param templateName 模板目录名
     * @param requirement  用户对模板的需求描述（风格、配色、布局、栏目等）
     */
    public String buildUserPrompt(String templateName, String requirement) {
        return buildUserPrompt(templateName, requirement, true);
    }

    /**
     * 构建用户首条消息（初始需求描述）
     *
     * @param templateName   模板目录名
     * @param requirement    用户对模板的需求描述（风格、配色、布局、栏目等）
     * @param mobileAdaptive 是否适配移动端
     */
    public String buildUserPrompt(String templateName, String requirement, boolean mobileAdaptive) {
        String mobileRequire = mobileAdaptive
                ? "7. 移动端适配（强制）：\n"
                + "   7.1 base.css 必须包含至少两档 media query 断点：@media (max-width: 768px) 与 @media (max-width: 480px)，\n"
                + "       分别对应平板/小屏手机；桌面优先，小屏下容器宽度改为 100%、栅格列数折叠、字号与间距缩小\n"
                + "   7.2 header 导航在宽度 <= 768px 时隐藏菜单 ul，显示汉堡按钮（三横线 / svg 图标），\n"
                + "       点击后通过纯 CSS + checkbox 或少量 JS 展开为竖向侧边菜单，不要依赖 Bootstrap 等外部框架\n"
                + "   7.3 文章列表在手机端改为单列（移动端自动堆叠），图片使用 max-width:100% 自适应\n"
                : "7. 移动端适配：本会话用户选择桌面专用模板，无需 media query 断点与汉堡菜单，按 ≥1200px 固定布局设计\n";
        return "请为模板目录「" + templateName + "」生成一套完整的网站模板。\n\n"
                + "## 用户需求\n\n" + requirement + "\n\n"
                + "## 输出要求\n\n"
                + "1. 必须包含必备文件：_template.properties、_layout.html、_articlePage.html、index.html、article.html、article_list.html、page.html、_preview_data.json\n"
                + "   其中 _articlePage.html 为文章分页宏文件：_layout.html 顶层用 <#include \"_articlePage.html\"> 引入，"
                + "文章列表页用 <@layout._articlePage/> 输出分页条，宏定义参考如下（样式类名可按模板自身风格调整）：\n"
                + AiTemplateConstants.ARTICLE_PAGE_HTML + "\n"
                + "2. _preview_data.json 预览演示数据：菜单/分类/单页/文章标题贴合用户需求主题（如餐饮模板用\"菜品展示/门店故事\"）\n"
                + "3. 必须包含基础样式文件 static/css/base.css（若主样式命名为 style.css 等，base.css 可作为基础重置与变量定义，样式文件总数控制在 2 个以内）\n"
                + "4. 静态资源路径使用 ${ctx()} 前缀，例如 <link href=\"${ctx()}/css/base.css\">\n"
                + "5. 页面通过 <#import \"_layout.html\" as layout> 引入布局宏\n"
                + "6. 使用 fastcms 指令渲染动态内容，不要硬编码文章列表\n"
                + mobileRequire
                + "8. 菜单选中高亮（强制）：\n"
                + "   8.1 在 body 宏开头用 <#assign currentUri=request.contextPath! + request.requestURI!> 获取当前请求路径（request 变量由框架注入）\n"
                + "   8.2 渲染首页 <li> 时判断：currentUri == (request.contextPath + '/') → 添加 class=\"active\"\n"
                + "   8.3 menuTag 遍历的每一项对比 (item.url!): currentUri?starts_with(item.url!) → 当前 li 加 class=\"active\"，\n"
                + "       且其所有祖先（父菜单）也应加 active 类（递归 children 时同步判断）\n"
                + "   8.4 _layout.html 中必须包含一个递归宏（如 menuChildren）处理二级及以下菜单，子菜单 active 同样按前缀匹配\n"
                + "   8.5 CSS 中必须定义 nav li.active > a { 颜色/下划线/背景 高亮 } 样式\n"
                + "9. 严格按照约定的 JSON 对象格式输出（reply 字段总结生成结果，files 字段为文件数组），不要输出额外解释\n"
                + "10. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建规划轮提示词（分批流水线第一轮：只输出文件清单，不生成内容）
     *
     * <p>生成型会话首次对话不再一次性输出整套模板（单轮输出易超 max_tokens 上限被截断），
     * 而是先让模型规划文件清单（输出量极小、结构上不可能截断），再逐文件生成。</p>
     *
     * @param templateName 模板目录名
     * @param requirement  用户需求描述
     */
    public String buildPlanPrompt(String templateName, String requirement) {
        return "请为模板目录「" + templateName + "」规划一套完整的网站模板。\n\n"
                + "## 用户需求\n\n" + requirement + "\n\n"
                + "## 输出要求\n\n"
                + "1. 本轮只做规划，不生成任何文件内容：files 数组中每一项只包含 path 和 action 两个字段，禁止输出 content 字段\n"
                + "2. 必须涵盖必备文件：_template.properties、_layout.html、_articlePage.html（文章分页宏，被 _layout.html include）、"
                + "index.html、article.html、article_list.html、page.html、_preview_data.json（菜单/文章标题贴合需求主题），"
                + "以及基础样式 static/css/base.css\n"
                + "3. static/js 为可选目录：仅当确有交互脚本需求时才规划，脚本统一放 static/js/main.js，没有交互脚本则不要规划任何 js 文件；"
                + "其他文件可按需补充，但文件总数控制在 10 个以内\n"
                + "4. reply 字段简要说明整体设计思路（配色、布局、栏目结构，100 字以内）\n"
                + "5. 严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n"
                + "6. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建单文件生成轮提示词（分批流水线：一次只生成一个文件的完整内容）
     *
     * <p>单文件输出量级天然在几 K token 以内，远低于 max_tokens 上限，
     * 从结构上避免整套模板一次性输出导致的截断问题。</p>
     *
     * @param requirement      用户需求描述
     * @param targetPath       本次要生成的文件相对路径
     * @param existingContext  已生成文件的上下文（文件清单 + _layout.html 完整内容），可为空
     * @param retryHint        重试提示（上次输出截断/格式非法时非空，附加压缩篇幅要求）
     * @param mobileAdaptive   是否适配移动端（false 时关键文件的强约束降级为桌面专用）
     */
    public String buildSingleFilePrompt(String requirement, String targetPath,
                                        String existingContext, String retryHint, boolean mobileAdaptive) {
        StringBuilder sb = new StringBuilder();
        sb.append("请生成模板中的一个文件。\n\n## 用户需求\n\n").append(requirement).append("\n\n");
        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("## 已生成的文件（保持风格一致，复用其中的路径与宏）\n\n")
                    .append(existingContext).append("\n\n");
        }
        sb.append("## 本次任务\n\n")
                .append("只生成文件 `").append(targetPath).append("` 的完整内容。\n\n")
                .append("## 输出要求\n\n")
                .append("1. files 数组只包含一个元素：path 为 `").append(targetPath)
                .append("`，content 为完整文件内容，action 为 create\n")
                .append("2. content 必须是可直接使用的完整内容，禁止省略或输出占位符（如 ... 省略 ...）\n")
                .append("3. 控制篇幅：").append(buildSizeHint(targetPath, mobileAdaptive)).append('\n')
                .append("4. 静态资源路径使用 ${ctx()} 前缀\n");

        // 针对关键文件追加差异化强约束（覆盖生成型与分块单文件两条路径共用此提示词）
        String extra = buildTargetFileConstraint(targetPath, mobileAdaptive);
        if (extra != null && !extra.isBlank()) {
            sb.append("5. ").append(extra).append('\n');
            sb.append("6. 严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n")
              .append("7. 请全程使用中文思考和回复\n");
        } else {
            sb.append("5. 严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n")
              .append("6. 请全程使用中文思考和回复\n");
        }

        if (retryHint != null && !retryHint.isBlank()) {
            sb.append("\n## 注意\n\n").append(retryHint).append("\n");
        }
        return sb.toString();
    }

    /**
     * 按目标文件给出差异化的强约束提示，仅对关键文件（_layout.html / base.css / 主 JS）返回非空
     *
     * @param targetPath     目标文件相对路径
     * @param mobileAdaptive 是否适配移动端（false 时 _layout/CSS 的移动端约束降级为桌面专用）
     */
    private String buildTargetFileConstraint(String targetPath, boolean mobileAdaptive) {
        String name = targetPath.contains("/")
                ? targetPath.substring(targetPath.lastIndexOf('/') + 1)
                : targetPath;
        if ("_layout.html".equals(name)) {
            return "本文件必须包含：(a) header/body/script 三个宏 + 一个递归子菜单宏 menuChildren；"
                    + "(b) body 宏开头 `<#assign cp = request.contextPath!> <#assign currentUri = cp + (request.requestURI)!>`；"
                    + "(c) 首页 li 与 menuTag 每一项都按 `currentUri?starts_with(item.url!)` 输出 class=\"active\"；"
                    + (mobileAdaptive
                        ? "(d) 移动端汉堡按钮 `<input type=\"checkbox\" id=\"nav-toggle\">` + label + 三横线 span；"
                        : "(d) 本会话为桌面专用模板，无需移动端汉堡按钮与响应式结构；")
                    + "(e) 不引入 Bootstrap/jQuery，不使用 data-toggle。菜单选中判断必须在 FreeMarker 层，不要只在 JS 里切";
        }
        if (name.endsWith(".css")) {
            return (mobileAdaptive
                        ? "本文件必须包含三档响应式断点：@media (max-width:991px)、@media (max-width:768px)、@media (max-width:480px)；"
                          + "≤768px 时隐藏 .site-nav ul、显示 .nav-toggle-label 汉堡按钮，#nav-toggle:checked 控制 .site-nav 展开；"
                        : "本会话为桌面专用模板，按 ≥1200px 固定布局设计，无需 media query 断点与汉堡菜单样式；")
                    + "必须定义 .site-nav li.active > a { 颜色+下划线 } 高亮样式；img{max-width:100%;height:auto}；"
                    + "主题色用 :root CSS 变量。若文件名为 style.css 且已存在 base.css，则 base.css 可只放变量+重置，主样式放本文件";
        }
        if (name.endsWith(".js")) {
            return "JS 只负责导航展开的降级（若纯 CSS 方案不可用）与回到顶部等轻交互，禁止接管菜单 active 状态；"
                    + "active 必须由 FreeMarker 模板输出，JS 里不要写 nav 切换 active 的逻辑";
        }
        return null;
    }

    /**
     * 按文件类型给出差异化的篇幅约束（端到端测试中 CSS 最易超限截断，要求最严格）
     *
     * @param targetPath     目标文件相对路径
     * @param mobileAdaptive 是否适配移动端（false 时 CSS 不要求响应式断点）
     */
    private String buildSizeHint(String targetPath, boolean mobileAdaptive) {
        String ext = targetPath.contains(".")
                ? targetPath.substring(targetPath.lastIndexOf('.') + 1).toLowerCase()
                : "";
        return switch (ext) {
            // CSS 在 JSON 中转义开销最大，最易被 max_tokens 截断：紧凑写法 + 变量复用 + 硬性行数上限
            case "css" -> "采用紧凑写法（每条规则一行），总行数不超过 200 行；"
                    + "主题色/字体/间距用 CSS 变量（:root）统一定义后复用；删除全部注释；"
                    + (mobileAdaptive ? "响应式只需桌面 + 移动两档断点" : "按桌面固定布局设计，无需 media query 断点");
            case "js" -> "总行数不超过 150 行，只实现必要交互（导航切换、回到顶部等），删除全部注释";
            case "properties" -> "只输出配置键值对，不超过 10 行";
            case "json" -> "只输出预览演示数据 JSON，总行数不超过 60 行，字段名与系统提示中的 schema 一致";
            default -> "HTML 文件不超过 250 行，注释精简";
        };
    }

    /**
     * 构建分块规划轮提示词（单文件直出失败后的分块生成路径第一步）
     *
     * <p>针对超出 max_tokens 上限的大文件：先让模型按功能划分块（输出量极小，
     * 结构上不可能截断），再逐块生成（见 {@link #buildChunkPartPrompt}），
     * 从结构上保证任意大小的文件都能生成。</p>
     *
     * @param requirement     用户需求描述
     * @param targetPath      目标文件相对路径
     * @param existingContext 已生成文件上下文（保持风格一致），可为空
     */
    public String buildChunkPlanPrompt(String requirement, String targetPath, String existingContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件 `").append(targetPath).append("` 内容较大，需要分块生成，本轮先做分块规划。\n\n")
                .append("## 用户需求\n\n").append(requirement).append("\n\n");
        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("## 已生成的文件（保持风格一致）\n\n").append(existingContext).append("\n\n");
        }
        sb.append("## 输出要求\n\n")
                .append("只输出一个 JSON 对象（不要包裹 markdown 代码块，不要输出其他字段）：\n")
                .append("{\"total\": 块数, \"outline\": [\"第1块摘要\", \"第2块摘要\", ...]}\n\n")
                .append("1. 块数尽可能少：每块尽量写满（接近块行数上限），通常 2~5 块，绝对不超过 6 块；")
                .append("禁止按单个功能/组件切小块（不要出现\"头部一块、页脚一块\"这种碎片划分）\n")
                .append("2. 按文件结构顺序大块划分，块间内容不重叠、合起来是完整文件\n")
                .append("3. 每块摘要不超过 12 个字（如：变量与基础重置、布局与组件、响应式适配）\n")
                .append("4. 本轮禁止输出任何文件内容\n")
                .append("5. 请全程使用中文思考和回复\n");
        return sb.toString();
    }

    /**
     * 构建单块生成轮提示词（分块生成路径：一次只生成目标文件的一块）
     *
     * <p>每块输出量级远低于 max_tokens 上限，从结构上保证不截断；
     * 单块解析失败时通过 retryHint 压缩篇幅重试该块，错误不传播到其他块。</p>
     *
     * @param requirement     用户需求描述
     * @param targetPath      目标文件相对路径
     * @param partIndex      当前块序号（从 1 开始）
     * @param totalParts     总块数
     * @param partOutline    分块规划轮得到的全部块摘要（让模型明确自己负责哪块）
     * @param existingContext 已生成文件上下文，可为空
     * @param maxLines       本块行数上限（按 max_tokens 动态计算）
     * @param retryHint      重试提示（单块输出截断/格式非法重试时非空）
     */
    public String buildChunkPartPrompt(String requirement, String targetPath, int partIndex, int totalParts,
                                       List<String> partOutline, String existingContext,
                                       int maxLines, String retryHint) {
        StringBuilder outline = new StringBuilder();
        for (int i = 0; i < partOutline.size(); i++) {
            outline.append("第 ").append(i + 1).append(" 块：").append(partOutline.get(i)).append('\n');
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本轮是分块生成模式，只生成文件 `").append(targetPath).append("` 的其中一块。\n\n")
                .append("## 用户需求\n\n").append(requirement).append("\n\n")
                .append("## 分块方案（共 ").append(totalParts).append(" 块）\n\n")
                .append(outline).append('\n');
        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("## 已生成的文件（保持风格一致，复用其中的路径与宏）\n\n")
                    .append(existingContext).append("\n\n");
        }
        sb.append("## 本次任务\n\n")
                .append("只生成第 ").append(partIndex).append('/').append(totalParts)
                .append(" 块（").append(partOutline.get(partIndex - 1)).append("），忽略其他块。\n\n")
                .append("## 输出要求\n\n")
                .append("1. 本轮为分块生成：content 只包含该块的内容（不是完整文件），忽略系统提示中\"content 必须是完整文件\"的要求\n")
                .append("2. files 数组只包含一个元素：path 为 `").append(targetPath)
                .append("`，content 为本块完整内容，action 为 create\n")
                .append("3. 本块不超过 ").append(maxLines).append(" 行，采用紧凑写法，禁止省略或输出占位符\n")
                .append("4. 块首尾保持语法完整（CSS 到完整规则、HTML/JS 到完整标签/语句），不要重复其他块的内容\n")
                .append("5. 静态资源路径使用 ${ctx()} 前缀；严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n")
                .append("6. 请全程使用中文思考和回复\n");
        if (retryHint != null && !retryHint.isBlank()) {
            sb.append("\n## 注意\n\n").append(retryHint).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建微调提示词（用户在已有会话中提出修改要求）
     *
     * @param requirement 用户的微调需求
     * @param currentFiles 当前会话已生成的文件清单（供 AI 参考上下文）
     */
    public String buildRefinePrompt(String requirement, String currentFiles) {
        return "请基于当前已有的模板文件进行微调。\n\n"
                + "## 微调需求\n\n" + requirement + "\n\n"
                + "## 当前已有文件（相对路径）\n\n" + currentFiles + "\n\n"
                + "## 数据与展示的边界（重要）\n\n"
                + "菜单、分类、标签、单页、文章标题等演示内容由 `_preview_data.json` 驱动。"
                + "凡属于内容增删改的需求（如\"去掉XX菜单\"\"增加一个栏目\"\"更换文章标题\"），"
                + "必须通过修改 _preview_data.json 实现（没有则新建，action=create，menus 等字段填入调整后的完整内容）；"
                + "严禁在模板 HTML 中加入菜单名过滤、内容判断等写死逻辑，模板必须保持数据驱动。\n\n"
                + "## 输出要求\n\n"
                + "1. files 数组中仅输出需要修改或新增的文件，未提及的文件保持不变\n"
                + "2. action 字段：新增文件用 create，修改文件用 modify\n"
                + "3. 严格按照约定的 JSON 对象格式输出（reply 字段说明本次微调内容，files 字段为变更文件数组）\n"
                + "4. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建调整型会话提示词（绑定正式模板的会话，每一轮都携带当前模板文件内容）
     *
     * <p>调整型会话的文件源是正式模板目录，用户可能在两轮对话之间通过编辑器手工修改过文件，
     * 因此每轮都从磁盘读取最新内容注入，保证 AI 始终基于最新状态调整。</p>
     *
     * <p>预览页点选区块时注入选区上下文：定位到的组件源码文件 + 元素语义提示，
     * 在提示词层面约束 AI 只修改该区块相关文件（不走 PageSpec 往返，输出仍是文件补丁）。</p>
     *
     * @param requirement            用户的调整需求
     * @param currentFilesWithContent 当前模板文件及完整内容（从磁盘实时读取）
     * @param currentFile            用户当前聚焦的页面（可空）
     * @param focusSectionId         预览页点选的区块 ID（可空：未选区时无选区约束）
     * @param focusElementHint       点选命中的元素语义提示（可空，如 标题「xx」）
     * @param focusComponentFile     选区对应的组件源码文件名（_components/ 下；focusSectionId 非空时非空）
     */
    public String buildAdjustPrompt(String requirement, String currentFilesWithContent, String currentFile,
                                    String focusSectionId, String focusElementHint, String focusComponentFile,
                                    String siteManifest) {
        boolean focused = siteManifest != null && !siteManifest.isBlank();
        String focusSectionBlock = (focusSectionId == null || focusSectionId.isBlank()) ? ""
                : "## 用户选中的区块（本轮修改目标）\n\n"
                + "用户在预览页点选了区块 `" + focusSectionId + "`"
                + (focusElementHint == null || focusElementHint.isBlank() ? "" : "（命中元素：" + focusElementHint + "）")
                + "。该区块的组件源码文件为 `_components/" + focusComponentFile + "`，"
                + "由页面/布局文件通过 `<#include>` 引用，区块的文案/图片等槽位数据在引用处的 `<#assign comp = ...>` 中。\n\n"
                + "本轮约束：\n"
                + "1. 只修改该区块相关的文件：优先修改 `_components/" + focusComponentFile + "`；"
                + "确需调整文案/图片数据时，可一并修改引用它的页面文件中的 comp assign\n"
                + "2. 严禁修改其他区块的组件文件与无关页面\n"
                + "3. 保留 data-ai-slot / data-ai-section / data-ai-section-root 标记（预览点选依赖，删除会导致点选功能失效）\n\n";
        String currentFileSection = (currentFile == null || currentFile.isBlank()) ? ""
                : "## 用户当前正在查看的页面\n\n"
                + "用户当前正在编辑/预览 `" + currentFile + "`，未明确指定其他页面时请优先调整该页面。\n\n"
                + "注意：页面渲染依赖公共布局文件（如 _layout.html），若调整需求涉及公共部分（导航、页脚等），应修改布局文件而非每个页面。\n\n";
        // 聚焦注入模式：L1 全站文件清单 + L2 工具使用指引（全量注入模式无此两段——
        // 全部文件已注入，无按需检索的必要）
        String manifestSection = !focused ? "" :
                "## 全站文件清单（按需查看，未全部注入）\n\n"
                + "上方注入的仅是当前页面的直接依赖。全站文本文件清单如下"
                + "（括号内为粗估 tokens，未注入完整内容的文件可按需查看）：\n\n"
                + siteManifest + "\n\n";
        String toolGuideSection = !focused ? "" :
                "## 文件查看与搜索工具（按需调用）\n\n"
                + "- read_template_file(path)：读取清单中任意文本文件的完整内容。"
                + "修改未注入或被截断的文件前，必须先调用它查看现有内容，禁止凭空臆造\n"
                + "- search_template_files(keyword)：全站搜索关键词（返回 文件:行号: 内容）。"
                + "实现新功能前先搜索站内是否已有同类实现（如其他页面的支付/下载/轮播），"
                + "有则参考其写法保持一致\n"
                + "- 集成支付、下载等插件能力时，接口契约必须用 get_capability_detail 工具获取，"
                + "禁止凭记忆编造接口路径与字段\n"
                + "- 修改范围判断：公共部分（导航/页脚/全局样式）通常在 _layout.html 中，改一处即全站生效，"
                + "不要逐页重复修改；跨页面需求先查看目标页面再改\n\n";
        return "请基于当前正式模板的文件内容进行调整，调整结果将直接写入正式模板。\n\n"
                + "## 调整需求\n\n" + requirement + "\n\n"
                + focusSectionBlock
                + currentFileSection
                + "## 数据与展示的边界（重要）\n\n"
                + "模板中菜单、分类、标签、单页、文章标题等演示内容由 `_preview_data.json` 驱动（预览数据源）。"
                + "凡属于内容增删改的需求（如\"去掉XX菜单\"\"把某菜单改名为XX\"\"增加一个栏目\"\"更换文章标题\"），"
                + "必须通过修改 _preview_data.json 实现：\n"
                + "- 目录中已有该文件：按其现有结构输出修改后的完整 JSON（action=modify）\n"
                + "- 目录中没有该文件：按系统规范新建，menus 等字段填入调整后的完整内容（action=create）\n"
                + "- 严禁在模板 HTML 中加入菜单名过滤、内容判断等写死逻辑"
                + "（如 <#if item.menuName?contains('XX')>），模板必须保持数据驱动\n\n"
                + "## " + (focused ? "当前页面及其依赖文件" : "当前模板文件") + "（相对路径 + 完整内容）\n\n"
                + currentFilesWithContent + "\n\n"
                + manifestSection
                + toolGuideSection
                + "## 输出要求\n\n"
                + "1. files 数组中仅输出需要修改或新增的文件，未提及的文件保持不变\n"
                + "2. action 字段：新增文件用 create，修改文件用 modify，删除文件用 delete\n"
                + "3. 修改文件时必须基于上述文件内容输出修改后的完整内容，不要凭空臆造原有内容"
                + (focused ? "（未注入的文件先用 read_template_file 查看）" : "") + "\n"
                + "4. 严格按照约定的 JSON 对象格式输出（reply 字段说明本次调整内容，files 字段为变更文件数组）\n"
                + "5. 若用户要求切换/查看某个页面（如「切换到首页」「看下文章详情页」）而无需修改文件，"
                + "在 JSON 顶层额外输出 \"switchTo\": \"目标页面文件路径\"（取自上方文件清单中的可路由 HTML，"
                + "如 index.html、article.html、page_about.html），系统会把预览切到该页面；修改文件的轮次无需该字段\n"
                + "6. 请全程使用中文思考和回复\n"
                + "7. 控制思考时间在最短必要范围：调整方案明确后直接输出，不要反复推演\n";
    }

    /**
     * 构建「样式组件化升级」改造轮提示（旧模板升级，保留功能、焕新视觉）
     *
     * <p>硬性契约：JS 功能锚点（元素 id + JS 选择器依赖的 class）与内联脚本一个不能丢；
     * FreeMarker 指令原样保留；结构可语义化重组并追加 utility class。</p>
     *
     * <p>v2：附<strong>真实可用</strong>的 CSS 变量清单与 utility 类族说明（防止 AI 引用不存在的
     * 变量/类导致样式静默失效——升级后页面"丑/乱"的头号原因）、禁用语法黑名单
     * （方括号任意值等 upgrade.css 不支持的形态）、设计规范（防止"能用但丑"），
     * 以及 _layout.html 的专属改造契约（在批时）。</p>
     *
     * @param filesWithContent 本批待改造文件（相对路径 + 完整内容）
     * @param anchors          全站 JS 依赖锚点清单（"id:xxx" / "class:xxx"）
     * @param doneCount        已完成改造的页面数（进度提示用）
     * @param totalFiles       页面总数
     * @param layoutInBatch    本批是否含 _layout.html（站点门面，专属契约见提示词）
     * @param refreshRound     深度焕新轮次（0 = 首次升级；>0 时注入设计方向与上一轮回顾）
     * @param lastRoundDigest  上一轮（当前磁盘版本）的结构指纹摘要（可空；服务层在恢复备份
     *                         底稿前对重做范围实时构建，含上一轮方向名头）。空时跳过回顾段
     * @param userFeedback     本轮焕新用户填写的具体不满（可空；空时提示按方向整体优化）
     * @param rejectedKeys     已被用户否决的方向资产键集合（问题4；可空——null/空集合
     *                         = 无否决记录，按完整轮换池取）
     * @param fixPatchesDigest 历史功能修复补丁摘要（P1；可空——本批涉及文件的上一轮功能修复
     *                         清单：宏默认值/锚点补全/脚本修正，恢复备份底稿后须重新落实）。
     *                         空时跳过该段（无补丁 = 行为与现状一致）
     */
    public String buildStyleUpgradePrompt(String filesWithContent, List<String> anchors,
                                          int doneCount, int totalFiles, boolean layoutInBatch,
                                          int refreshRound, String lastRoundDigest,
                                          String userFeedback, java.util.Set<String> rejectedKeys,
                                          String fixPatchesDigest) {
        String anchorList = anchors == null || anchors.isEmpty() ? "（无）"
                : String.join("、", anchors);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("请对本批旧模板页面执行「样式组件化升级」：视觉焕新为组件库风格，网站功能必须原样保留。\n\n")
                .append("当前进度：已完成 ").append(doneCount).append(" / ").append(totalFiles).append(" 个文件。\n\n");
        if (refreshRound > 0) {
            sb.append("## 本次设计方向（第 ").append(refreshRound).append(" 次深度焕新）\n\n")
                    .append("用户对上一版效果不满意，本版必须采用**")
                    .append(resolveDesignDirection(refreshRound, userFeedback, rejectedKeys))
                    .append("**。\n\n");
            appendDirectionAssetSection(sb, refreshRound, userFeedback, rejectedKeys);
            if (lastRoundDigest != null && !lastRoundDigest.isBlank()) {
                sb.append("## 上一轮焕新回顾（当前版本的实际结构，本轮改造的真实参照物）\n\n")
                        .append(lastRoundDigest).append("\n\n");
            }
            sb.append("## 用户具体不满（本轮修正目标，优先级最高）\n\n")
                    .append(userFeedback != null && !userFeedback.isBlank() ? userFeedback
                            : "用户未给出具体意见，按本轮设计方向整体优化")
                    .append("\n\n");
            sb.append("## 本轮修正策略\n\n")
                    .append("1. 针对「用户具体不满」逐条修正，这是本轮首要目标\n")
                    .append("2. 用户未点名的部分，在上一轮结构基础上做**定向改进**，")
                    .append("不要全盘推翻重排（避免每次焕新版式都剧烈变化）\n")
                    .append("3. 与上一版的差异必须体现在用户不满意的具体维度上，")
                    .append("而非盲目换 hero 形态/换栅格列数\n")
                    .append("4. 用户意见为整体评价（如不好看/土/没设计感）时：先对照上一轮结构摘要")
                    .append("自我诊断具体短板（层次/密度/一致性/明度），把诊断结论写进 reply，")
                    .append("再针对诊断结果做定向改进\n\n");
        }
        if (fixPatchesDigest != null && !fixPatchesDigest.isBlank()) {
            sb.append("## 历史功能修复（上一轮已验证，本轮必须保留，禁止删除/回退）\n\n")
                    .append("以下功能修复在上一轮升级中通过了校验（本轮底稿恢复自原始备份，")
                    .append("不含这些修复），改造对应文件时必须重新落实：\n\n")
                    .append(fixPatchesDigest).append("\n\n");
        }
        sb.append("## 可用样式资产（升级前已注入，禁止再写 <link>）\n\n")
                .append("- tokens.css：主题变量 + 语义别名（变量清单见下）\n")
                .append("- pack-*.css：组件库（卡片/按钮/导航等组件类）\n")
                .append("- upgrade.css：Tailwind 兼容 utility 全集 + preflight 重置 + .prose 正文排版\n\n")
                .append("## CSS 变量清单（只允许用这些，其余一律未定义）\n\n")
                .append("- 主色阶：--color-primary-50 ~ --color-primary-900（10 档），别名 --color-primary / ")
                .append("--color-primary-dark / --color-primary-light\n")
                .append("- 语义色：--color-text-primary / --color-text-secondary / --color-text-muted / ")
                .append("--color-bg / --color-bg-secondary / --color-border / --color-danger / ")
                .append("--color-danger-light / --color-success / --color-warning\n")
                .append("- 圆角：--radius-sm / md / lg / xl / 2xl / 3xl；字体：--font-sans / --font-mono\n\n")
                .append("## utility 类族（upgrade.css 预生成，按 Tailwind 刻度）\n\n")
                .append("- 间距：m|mt|mr|mb|ml|mx|my / p|pt|pr|pb|pl|px|py / gap - 0~96（含 0.5 步进），负 margin -mt-* 可用\n")
                .append("- 布局：flex / grid / grid-cols-1~12 / col-span-* / items-* / justify-* / self-* / ")
                .append("flex-1 / grow / shrink-0 / hidden / block / inline-block\n")
                .append("- 尺寸：w-* h-* 0~64、w-full/w-screen/w-auto/w-fit、max-w-xs~max-w-7xl/max-w-prose、min-w-0、h-full/h-screen\n")
                .append("    （内容主体容器标准写法：`max-w-7xl mx-auto px-4`）\n")
                .append("- 配色：text-*/bg-*/border-* × slate-50~900、gray-50~900、primary-50~900、")
                .append("white、black；text-danger、text-success；渐变 bg-gradient-to-r + from-*/via-*/to-*\n")
                .append("    （primary 系联动主题主色，优先使用）\n")
                .append("    （文字层次标准：标题 text-slate-900、正文 text-slate-600、辅助 text-slate-400）\n")
                .append("- 字体：text-xs~text-8xl、font-normal~font-black、leading-*、tracking-*、")
                .append("truncate、line-clamp-1~6、whitespace-*\n")
                .append("- 边框圆角阴影：border/border-0/2/4、border-t/r/b/l、rounded-sm~3xl/full、")
                .append("shadow-sm~2xl、divide-y、space-x/y-*\n")
                .append("- 效果：transition/colors/opacity/shadow、duration-*、opacity-0~100、")
                .append("hover: 常用子集（bg/text/shadow/opacity/scale/underline/border）\n")
                .append("- 响应式前缀：仅 sm:（≥640px）/ md:（≥768px）/ lg:（≥1024px）\n")
                .append("- 正文排版：富文本容器加 `.prose`（自动恢复标题/列表/引用/表格/代码样式）\n\n")
                .append("## 禁用语法（upgrade.css 是预生成类，不是运行时编译，以下形态一律无效）\n\n")
                .append("- 方括号任意值：`w-[300px]`、`bg-[#f5f5f5]`、`text-[13px]`、`grid-cols-[1fr_2fr]` → 用最近刻度类替代\n")
                .append("- 其他断点/变体：`xl:`、`2xl:`、`focus:`、`active:`、`group-hover:`、`dark:` → 只用 sm:/md:/lg:/hover:\n")
                .append("- 未生成的类族：`animate-*`、`container`、`scroll-*`、`filter`、`blur-*`（backdrop-blur 除外）、")
                .append("`ring-*`、`outline-*`、`indent-*`、`writing-*`、`columns-*`\n")
                .append("- 未定义 CSS 变量（只用上方清单内的变量）\n\n")
                .append("## 设计规范（目标：现代、精致、有呼吸感，避免「能用但丑」）\n\n")
                .append("1. 版心：页面主体 `max-w-7xl mx-auto px-4`，区块间距 py-12~py-20，杜绝内容贴边\n")
                .append("2. 卡片化：内容块用 `bg-white rounded-xl border border-slate-200 shadow-sm` 组合，")
                .append("hover 加 `hover:shadow-lg transition`\n")
                .append("3. 层次分明：区块标题 `text-2xl md:text-3xl font-bold text-slate-900`，")
                .append("可配 `text-primary-600` 强调词与 `text-slate-500` 副标题\n")
                .append("4. 主色克制：按钮/链接/高亮用 primary 系（`bg-primary-600 text-white rounded-lg px-5 py-2.5`），")
                .append("大面积底色用 slate-50/白\n")
                .append("5. 首屏 hero：大标题 + 副标题 + 主按钮，可用 `bg-gradient-to-b from-primary-50 to-white`\n")
                .append("6. 列表/产品：`grid grid-cols-1 md:grid-cols-3 gap-6`，图片 `rounded-lg object-cover`\n")
                .append("7. 交互暗示：可点击元素统一 `transition` + hover 变化（色/影/位移）\n")
                .append("8. **顶层区块语义化（必须遵守）**：页面的顶层内容区块必须用 `<section>` 标签包裹")
                .append("（一个语义区块一个 `<section>`，区块内部结构自由），且顶层 `<section>` 数量")
                .append("与本文件旧稿保持一致——样式升级只改视觉不改内容结构；确需合并/拆分区块时")
                .append("必须在 reply 中说明新旧区块的对应关系\n\n")
                .append("## 铁律（违反任何一条即失败）\n\n")
                .append("1. **严禁删除或修改任何 id 属性**：旧文件中出现的所有 id=\"xxx\" 必须在新文件中原样存在\n")
                .append("2. **严禁丢失 JS 依赖锚点 class**：以下 class 被 JS 脚本引用（选择器），对应元素上必须原样保留——\n")
                .append("   ").append(anchorList).append('\n')
                .append("   （旧文件中存在的锚点，新文件中必须仍在对应元素上；清单中旧文件本来就没有的无需处理）\n")
                .append("3. **严禁删除或修改任何 <script> 块**：页面内联脚本（Swiper/SuperSlide/fancybox 初始化等）")
                .append("必须原样保留在页面中，一条不能少\n")
                .append("4. **严禁修改 FreeMarker 指令**：<#...>、${...}、<@...> 及变量名原样保留")
                .append("（这是数据渲染与网站功能的核心）\n")
                .append("   - **严禁用字符串拼接伪装指令**：${''}${'#'}{if x}...${'#'}{/if} 这类写法是字面量输出")
                .append("而非条件判断（渲染后把 #{if...} 文本垃圾打进 class 属性，高亮/条件样式全部失效），")
                .append("条件必须写真正的 <#if x>...</#if>；在 class 属性内写条件时可用 <#if> 标签穿插于文本之间\n");
        if (layoutInBatch) {
            sb.append("5. **本批含 _layout.html，按以下专属契约改造**（其余铁律同样适用）：\n")
                    .append("   - <#macro>/<#function> 定义原样保留（宏名、参数、递归调用一字不动），只改宏体内的 HTML 结构与 class\n")
                    .append("   - **宏安全铁律**：递归调用传数据字段（如 <@menuChildren children=item.children/>）时，")
                    .append("宏参数必须带默认值（<#macro menuChildren children=[] currentUri=\"\">）")
                    .append("或调用处判空（<#if item.children?? && item.children?size gt 0> 包裹调用）——")
                    .append("叶子数据（如无子菜单的菜单项）该字段为 null，无兜底时整站渲染 500\n")
                    .append("   - 组件库 CSS 引入（tokens.css / pack-*.css / upgrade.css）原样保留，顺序不动\n")
                    .append("   - **删除旧站皮肤 CSS 的 <link>**（如 base.css / m.css 等本站旧样式）；")
                    .append("功能性库 CSS 保留（swiper.min.css / animate / 字体图标 css）\n")
                    .append("   - head 区 meta/viewport/seoTag 指令、favicon、统计脚本原样保留；")
                    .append("header 导航/搜索/登录区、footer 的 DOM 语义化重组后追加 utility class\n")
                    .append("   - <#macro script> 中的 JS 引入与内联脚本原样保留（可追加新 <script>，不可删改已有的）\n");
        } else {
            sb.append("5. **严禁动 _layout.html 的引入**：公共布局已由系统处理，本批只改下列页面文件\n");
        }
        sb.append("6. 旧的纯样式 class（不在锚点清单中的）应移除，视觉完全由 utility class 接管")
                .append("（残留旧 class 会与新样式冲突）\n\n")
                .append("## 本批待改造文件（相对路径 + 完整内容）\n\n")
                .append(filesWithContent).append("\n\n")
                .append("## 输出要求\n\n")
                .append("1. files 数组输出本批全部文件的 modify（完整改造后内容），一个文件都不能漏\n")
                .append("2. **即使核对后认为某文件已完全符合规范、无需任何改动，也必须把该文件原样完整输出")
                .append("（内容一字不改）**——不输出该文件会导致升级进度卡死\n")
                .append("3. 严格按照约定的 JSON 对象格式输出（reply 字段简述本批改造要点，files 字段为文件数组）\n")
                .append("4. 只使用上方 utility 类族与变量清单内的类/变量，不确定就换成熟悉的基础类\n")
                .append("5. 请全程使用中文思考和回复\n")
                .append("6. 控制思考时间在最短必要范围：按铁律逐条核对后直接输出\n");
        return sb.toString();
    }

    /**
     * 构建「智能焕新范围评估」提示（焕新前的规划调用）
     *
     * <p>目的：焕新不必重做全部页面——视觉身份 80% 由 _layout.html（头尾/导航/全局色）
     * 与 index.html（hero/分区节奏）承载，内容页多为方向无关的通用卡片布局。
     * 本调用让 AI 根据各页面当前的方向耦合度指纹，判定最小重做集合，
     * 其余页面保留当前版本（自动继承 tokens.css 变量换肤效果）。</p>
     *
     * @param fileFingerprints 每个计划文件一行指纹（路径 + 方向耦合特征统计）
     * @param refreshRound     焕新轮次（>0，决定设计方向，与改造轮共用单一来源方向表）
     * @param userFeedback     用户焕新意见（可空；命中关键词时范围评估侧重反馈涉及的页面）
     * @param rejectedKeys     已被用户否决的方向键集合（问题4：评估方向与实际焕新方向同口径，
     *                         避免按方向 A 评估耦合度、实际却换了方向 B）
     */
    public String buildRefreshScopePrompt(String fileFingerprints, int refreshRound,
                                          String userFeedback,
                                          java.util.Set<String> rejectedKeys) {
        String direction = resolveDesignDirection(refreshRound, userFeedback, rejectedKeys);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是模板焕新范围评估器。站点已完成一轮组件化升级，用户对效果不满意，")
                .append("即将执行第 ").append(refreshRound).append(" 次深度焕新，本轮设计方向：**")
                .append(direction).append("**。\n");
        if (userFeedback != null && !userFeedback.isBlank()) {
            sb.append("用户具体不满（修正目标）：").append(userFeedback).append('\n');
            if (isFeedbackDirected(userFeedback)) {
                sb.append("本轮为用户反馈驱动的定向修正：反馈涉及的方向耦合特征（如深色区/卡片密度）")
                        .append("所在页面应优先纳入重做范围。\n");
            }
        }
        sb.append("\n## 各页面当前状态指纹（文件级 + 顶层区块级）\n\n").append(fileFingerprints).append("\n\n")
                .append("## 评估任务\n\n")
                .append("判断哪些页面/区块**必须重新改造**才能落实新设计方向，哪些可以保留当前版本：\n")
                .append("- `_layout.html` 必须整文件重做（站点门面：header/footer/导航/全局色，任何焕新都包含）\n")
                .append("- 含方向耦合元素的页面需重做：深色/渐变 hero、强主色区块、大图视觉等")
                .append("（指纹中 深色/渐变/主色/超大标题 计数高的区块；尤其 index.html 首页）\n")
                .append("- **区块级重做**（优先考虑）：页面中仅少数区块方向耦合强、其余区块（白底卡片列表、")
                .append("正文排版等通用内容）与新方向不冲突时，只选出耦合区块——保留区块经 tokens.css ")
                .append("变量自动换肤，token 成本与风险都更小（区块序号即页面顶层 `<section>` 的序号，")
                .append("改造契约要求页面以 `<section>` 组织顶层区块）\n")
                .append("- 方向无关的通用内容页整体保留\n")
                .append("- 同构页面（如 article_list 系列多个下载/视频列表）只需重做有方向耦合的，其余保留\n\n")
                .append("## 输出格式（严格遵守）\n\n")
                .append("先用一两句话说明判断依据，然后输出 JSON：\n")
                .append("```json\n{\"redesign\": [\n")
                .append("  {\"file\": \"_layout.html\"},\n")
                .append("  {\"file\": \"index.html\", \"sections\": [1, 2]},\n")
                .append("  {\"file\": \"article_list.html\", \"sections\": [1]}\n")
                .append("]}\n```\n")
                .append("redesign 数组 = 必须重做的文件（只列指纹中存在的路径，宁少勿多）：整文件重做")
                .append("省略 sections；区块级重做用 sections 数组列出 1-based 顶层区块序号")
                .append("（对应指纹中的 区块N，只列方向耦合强的区块）；未列出的文件与区块将原样保留。")
                .append("拿不准区块边界时整文件重做。请全程使用中文。");
        return sb.toString();
    }

    /**
     * 构建视觉审计修复提示（升级收尾轮）：把审计器的结构化问题清单翻译给 AI 修复
     *
     * <p>审计来源见 {@link com.fastcms.ai.component.LegacyStyleUpgrader#auditUpgrade}：
     * missing_class / undefined_var / legacy_css_residue / page_without_utilities /
     * layout_language_mix（P2-1 混血）。修复铁律与改造轮一致（锚点/id/脚本/FreeMarker 不动）。</p>
     *
     * @param issues            审计问题清单
     * @param filesWithContent  涉事文件当前内容（相对路径 + 完整内容）
     * @param directionLanguage 本轮设计方向的语言描述（可空——layout_language_mix 修复的
     *                          统一基准；空时以站内多数页面语言为准）
     */
    public String buildAuditFixPrompt(
            List<com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue> issues,
            String filesWithContent, String directionLanguage) {
        // 按文件聚合，提示词更紧凑
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        for (com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue issue : issues) {
            byFile.computeIfAbsent(issue.file(), k -> new ArrayList<>())
                    .add("[" + issue.type() + "] " + issue.detail());
        }
        StringBuilder sb = new StringBuilder(2048);
        sb.append("系统对样式组件化升级后的模板做了视觉审计，发现以下样式失效问题")
                .append("（这些是页面看起来丑、布局乱的直接原因），请逐条修复：\n\n");
        int i = 1;
        for (Map.Entry<String, List<String>> e : byFile.entrySet()) {
            sb.append("### ").append(e.getKey()).append('\n');
            for (String detail : e.getValue()) {
                sb.append(i++).append(". ").append(detail).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## 修复方式（按问题类型）\n\n")
                .append("- [missing_class] 该 class 在所有 CSS 中都不存在：删除它，改用清单内等价 utility class；")
                .append("无法等价表达时用内联 style（仅可用已定义的 CSS 变量）\n")
                .append("- [undefined_var] 该 CSS 变量未定义：改用已定义变量")
                .append("（--color-primary-50~900、--color-text-primary/secondary/muted、--color-bg、")
                .append("--color-bg-secondary、--color-border、--color-danger、--color-success、")
                .append("--color-warning、--radius-sm~3xl、--font-sans/mono）或字面量\n")
                .append("- [legacy_css_residue] _layout.html 仍引入旧站皮肤 CSS：删除对应 <link> 标签")
                .append("（swiper/animate/字体图标等功能性库 CSS 保留）\n")
                .append("- [page_without_utilities] 页面没有 utility class，未被真正改造：按改造契约重新设计该页面\n")
                .append("- [escaped_directive] 字符串拼接伪装指令（${''}${'#'}{if ...} 形态）：是字面量输出而非条件判断，")
                .append("全部改写为真正的 <#if x>...</#if>（<#else> 分支同理）\n")
                .append("- [macro_arg_null_risk] 宏参数无默认值且调用传入可能为 null 的数据（如 xxx.children）：")
                .append("给宏定义参数补默认值（如 children=[]）或调用处用 <#if ?? && ?size gt 0> 判空\n");
        if (issues.stream().anyMatch(iss -> "section_count_drift".equals(iss.type()))) {
            sb.append("- [section_count_drift] 顶层 <section> 区块数与基线不一致：以基线的内容区块为准")
                    .append("重新组织区块结构（合并/拆分对齐基线数量，内容与 FreeMarker 指令不丢失），")
                    .append("区块视觉按本轮设计方向重新设计\n");
        }
        if (issues.stream().anyMatch(iss -> "layout_language_mix".equals(iss.type()))) {
            sb.append("- [layout_language_mix] 全站版式语言混血（深浅 hero 并存、卡片化页与去卡片化页并存）：");
            if (directionLanguage != null && !directionLanguage.isBlank()) {
                sb.append("以本轮设计方向的版式语言为准（").append(directionLanguage)
                        .append("），统一冲突页面的 hero 明暗与区块风格");
            } else {
                sb.append("以站内多数页面的版式语言为准，统一少数派页面的冲突区块");
            }
            sb.append("——只调整冲突区块的 utility class（背景/圆角/阴影/分隔线/标题字号层级），")
                    .append("不整页重做，严禁删除 id/锚点/脚本/FreeMarker 指令\n");
        }
        sb.append("\n## 铁律（与改造轮一致，修复时同样不可违反）\n\n")
                .append("1. 严禁删除/修改 id 属性、JS 锚点 class、<script> 块、FreeMarker 指令\n")
                .append("2. 修复必须针对审计问题本身，不要大幅重构已改造好的部分\n\n")
                .append("## 待修复文件（相对路径 + 完整内容）\n\n")
                .append(filesWithContent).append("\n\n")
                .append("## 输出要求\n\n")
                .append("1. files 数组输出全部涉事文件修复后的完整内容（action=modify）\n")
                .append("2. 严格按照约定的 JSON 对象格式输出（reply 简述修复内容，files 为文件数组）\n")
                .append("3. 请全程使用中文思考和回复\n");
        return sb.toString();
    }

    /**
     * 构建锚点存活校验失败的修复提示（升级管线专用）
     *
     * @param missingAnchors 本次改造丢失的锚点清单
     * @param filesWithContent 改造后的文件内容（含丢失锚点的最新版本）
     */
    public String buildAnchorFixPrompt(List<String> missingAnchors, String filesWithContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统校验发现你的改造丢失了 JS 依赖锚点，以下锚点被 JS 脚本引用，必须找回：\n\n");
        for (int i = 0; i < missingAnchors.size(); i++) {
            sb.append(i + 1).append(". ").append(missingAnchors.get(i)).append('\n');
        }
        sb.append("\n规则：id:xxx 表示需要 id=\"xxx\" 的元素存在；class:xxx 表示需要有元素携带 class \"xxx\"；")
                .append("macro:签名 表示对应的 <#macro> 定义必须存在且带签名中的参数默认值（如 children=[]）。\n")
                .append("请在对应功能元素（轮播容器、导航、表单等）上补回这些 id/class，宏定义补回默认值，其余改造结果保持不变。\n\n")
                .append("## 改造后的文件（需要修正的最新版本）\n\n")
                .append(filesWithContent).append("\n\n")
                .append("## 输出要求\n\n")
                .append("1. files 数组输出修正后的完整文件（action=modify）\n")
                .append("2. 严格按照约定的 JSON 对象格式输出\n");
        return sb.toString();
    }

    /**
     * 构建渲染校验失败后的自动修复提示（调整型会话专用）
     *
     * @param renderErrors           渲染失败文件及错误摘要（非空）
     * @param currentFilesWithContent 当前模板文件内容（已写盘的最新版本）
     * @param currentFile            用户当前聚焦的页面（可空）
     */
    public String buildRenderFixPrompt(List<String> renderErrors, String currentFilesWithContent, String currentFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统对刚写入的模板文件做了渲染校验，以下文件渲染失败（FreeMarker 错误，含文件名与行号）：\n\n");
        for (int i = 0; i < renderErrors.size(); i++) {
            sb.append(i + 1).append(". ").append(renderErrors.get(i)).append('\n');
        }
        sb.append("\n请立即修复以上错误。常见修复方式：\n")
                .append("- 变量空引用（InvalidReferenceException）：改为 `${item.url!''}` 或用 `<#if item?? && item.url??>` 包裹\n")
                .append("- 宏参数不匹配：核对宏定义（如 _layout.html 中 <#macro> 的参数名）与调用处\n")
                .append("- 指令未闭合/语法错误（ParseException）：按行号定位修复\n\n")
                .append((currentFile == null || currentFile.isBlank()) ? ""
                        : "用户当前聚焦页面：" + currentFile + "，优先修复与该页面相关的错误。\n\n")
                .append("## 当前模板文件（渲染失败的最新版本，相对路径 + 完整内容）\n\n")
                .append(currentFilesWithContent).append("\n\n")
                .append("## 输出要求\n\n")
                .append("1. 只输出需要修改的文件（action=modify），基于上述内容给出修复后的完整文件\n")
                .append("2. 修复必须消除报错本身，不要用 try/catch 或判断语句绕过/吞掉错误\n")
                .append("3. 严格按照约定的 JSON 对象格式输出（reply 简述修复了什么，files 为修复后的文件）\n");
        return sb.toString();
    }

    // ==================== 系统提示词常量 ====================

    /**
     * 模板制作规范段（内置兜底副本）
     *
     * <p>与插件技能 {@code template-skills-plugin/template-spec} 的正文保持同步：
     * 系统提示词的规范段（## 1~## 11）优先加载该技能，插件未安装或技能缺失时回退本常量，
     * 两条路径拼出的提示词逐字节一致。</p>
     */
    private static final String SPEC_PROMPT_BUILTIN = """
            你是一名资深的前端工程师和 fastcms 模板开发专家，精通 FreeMarker 模板引擎与响应式网页设计。
            你的任务是：根据用户需求，生成符合 fastcms 规范的完整网站模板文件。

            # fastcms 模板规范

            ## 1. 目录结构

            模板目录名为 `${templateName}`，整体结构如下：
            ```
            ${templateName}/
            ├── _template.properties      # 模板元信息（必备）
            ├── _layout.html              # 公共布局宏（必备）
            ├── _articlePage.html         # 文章分页宏（必备，_layout.html 顶层 include，页面以 <@layout._articlePage/> 调用）
            ├── index.html                # 首页（必备）
            ├── article.html              # 文章详情页（必备）
            ├── article_list.html         # 文章列表页（必备）
            ├── page.html                 # 单页面（必备）
            ├── _preview_data.json        # 预览演示数据（必备，内容贴合需求主题）
            └── static/                   # 静态资源目录
                ├── css/
                │   └── base.css          # 基础样式
                ├── js/                   # 可选——有交互脚本时才创建，无则不建此目录
                │   └── main.js           # 交互脚本（可选——有交互脚本时才生成）
                └── images/               # 图片资源
            ```

            ## 2. _template.properties 模板元信息

            必须包含以下字段，格式为 key=value：
            ```properties
            template.id=www.${templateName}.com
            template.name=${templateName}
            template.path=/${templateName}/
            template.version=0.0.1
            template.i18n=${templateName}
            template.provider=ai-generated
            template.description=AI generated template
            ```
            注意：template.path 必须以 `/` 开头和结尾，pathName 会自动去除前后斜杠得到 `${templateName}`。

            ## 3. _layout.html 公共布局宏

            使用 FreeMarker macro 定义三个核心宏（header / body / script）+ 一个递归子菜单宏 menuChildren，
            所有页面通过 `<#import "_layout.html" as layout>` 引入。
            **必须同时满足响应式（汉堡菜单）与菜单选中高亮两个要求**，示例如下：

            ```html
            <#-- 辅助宏：判断某个菜单 URL 是否应高亮（前缀匹配，子菜单命中时祖先也高亮） -->
            <#function isMenuActive url>
              <#local cp = request.contextPath!>
              <#local uri = (request.requestURI)!>
              <#if url?? && uri?? && (uri?starts_with(url!) || (uri == url!))>
                <#return true>
              </#if>
              <#return false>
            </#function>

            <#-- 递归宏：渲染二级及以下子菜单，active 同样按前缀匹配 -->
            <#macro menuChildren children currentUri>
              <#if children?? && children?size gt 0>
                <ul class="submenu">
                  <#list children as child>
                    <#local childActive = (child.url?? && currentUri?starts_with(child.url!))>
                    <li class="${'$'}{''}${'#'}{if childActive}active${'#'}{/if}">
                      <a href="${'$'}{child.url!'#'}" target="${'$'}{child.target!"_self"}">${'$'}{child.menuName!}</a>
                      <@menuChildren children=child.children currentUri=currentUri/>
                    </li>
                  </#list>
                </ul>
              </#if>
            </#macro>

            <#macro header title>
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <title>${'$'}{title!""}</title>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
              <meta name="keywords" content="${'$'}{seoTag("website_title")!""}">
              <meta name="description" content="${'$'}{seoTag("website_sub_title")!""}">
              <link href="${'$'}{ctx()}/css/base.css" rel="stylesheet">
              <#nested/>
            </head>
            </#macro>

            <#macro body>
            <body>
              <#-- body 宏开头获取当前请求路径，供菜单 active 判断使用 -->
              <#assign cp = (request.contextPath)!>
              <#assign currentUri = cp + (request.requestURI)!>
              <header class="site-header">
                <div class="container header-inner">
                  <div class="logo"><a href="${'$'}{cp}/"><img src="${'$'}{ctx()}/images/logo.png" alt="Logo"></a></div>
                  <#-- 移动端汉堡按钮（≤768px 显示）：用纯 CSS + label/checkbox 切换，不依赖外部框架 -->
                  <input type="checkbox" id="nav-toggle" class="nav-toggle" aria-label="菜单">
                  <label for="nav-toggle" class="nav-toggle-label"><span></span><span></span><span></span></label>
                  <nav class="site-nav">
                    <ul>
                      <#-- 首页 -->
                      <#local homeActive = (currentUri == cp + '/') || (currentUri == cp)>
                      <li class="${'$'}{''}${'#'}{if homeActive}active${'#'}{/if}"><a href="${'$'}{cp}/">首页</a></li>
                      <#-- 后台管理的菜单（menuTag 数据） -->
                      <@menuTag>
                        <#if data?? && (data?size > 0)>
                          <#list data as item>
                            <#local mi_active = (item.url?? && currentUri?starts_with(item.url!))>
                            <li class="${'$'}{''}${'#'}{if mi_active}active${'#'}{/if}">
                              <a href="${'$'}{item.url!}" target="${'$'}{item.target!"_self"}">${'$'}{item.menuName!}</a>
                              <@menuChildren children=item.children currentUri=currentUri/>
                            </li>
                          </#list>
                        </#if>
                      </@menuTag>
                    </ul>
                  </nav>
                </div>
              </header>
              <main class="container main-content">
                <#nested/>
              </main>
              <footer class="site-footer">
                <div class="container">
                  <p>Copyright © ${'$'}{.now?string("yyyy")} - Powered by Fastcms</p>
                </div>
              </footer>
            </body>
            </#macro>

            <#macro script>
              <script src="${'$'}{ctx()}/js/main.js"></script>
              <#nested/>
            </body>
            </html>
            </#macro>
            ```
            关键点说明：
            - `request.contextPath/requestURI` 变量由 fastcms 框架自动注入到 FreeMarker 视图，模板可直接引用（要加 ! 默认值防 null）
            - 顶部首页 `<li>` 和 menuTag 每一项都必须按上述 `currentUri?starts_with(item.url!)` 前缀匹配输出 `active` class，不要仅靠 JS 切换
            - 子菜单通过递归宏 `menuChildren` 渲染，子菜单命中时父级也应是 active（前缀匹配天然保证了这一点）
            - 移动端通过 `#nav-toggle:checked ~ .site-nav { display:block }` 之类的纯 CSS 选择器控制展开，不依赖 Bootstrap/jQuery 的 data-toggle
            - `<#nested/>` 是宏的占位符，调用方传入的页面内容会渲染到这里（header 宏中 <head> 内、body 宏中 <main> 内、script 宏中 </body> 前）

            ## 4. FreeMarker 指令清单（fastcms 自定义指令）

            指令使用 `<@指令名 参数=值></@指令名>` 调用，数据通过 `${'$'}{data}` 访问：

            | 指令名 | 作用 | 常用参数 | 返回数据 |
            |---|---|---|---|
            | articleListTag | 文章列表 | categoryId、tagId、includeTagIds、excludeTagIds、orderBy、count | data（List<Article>），每项含 id、title、summary、thumbnail、url、created、viewCount |
            | article | 单篇文章详情 | （由 URL 路由注入） | article 对象，含 id、title、contentHtml、created、viewCount |
            | articlePageTag | 文章分页 | （由 URL 路由注入） | data 对象：total、current、list（页码项）、prev、next、last |
            | menuTag | 站点菜单 | （无） | data（List<Menu>），每项含 menuName、url、target、children |
            | categoryList | 分类列表 | （无） | data（List<Category>），每项含 id、title、url |
            | tagList | 标签列表 | （无） | data（List<Tag>），每项含 id、name、url |
            | singlePageList | 单页列表 | （无） | data（List<SinglePage>），每项含 id、title、url |
            | prevArticleTag | 上一篇 | articleId | data：Article |
            | nextArticleTag | 下一篇 | articleId | data：Article |
            | relatedArticleList | 相关文章 | articleId、count | data：List<Article> |
            | seoTag | SEO 配置项 | key（如 "website_title"） | 直接返回字符串 |
            | ctx | 模板路径前缀 | （无） | 返回当前模板的静态资源根路径，如 /xjd2022/ |
            | i18n | 国际化 | key | 返回对应语言的字符串 |
            | formatTime | 时间格式化 | value、format（如 "yyyy-MM-dd"） | <@formatTime value=(item.created)! format="yyyy-MM-dd"/> |
            | fieldValue | 扩展字段 | （插件扩展） | 扩展字段值 |

            指令使用示例：
            ```html
            <@articleListTag categoryId=3 orderBy="created" count=10>
              <#if data??>
                <#list data as item>
                  <article>
                    <h2><a href="${'$'}{(item.url)!}">${'$'}{(item.title)!}</a></h2>
                    <p>${'$'}{(item.summary)!}</p>
                    <span><@formatTime value=(item.created)! format="yyyy-MM-dd"/></span>
                  </article>
                </#list>
              </#if>
            </@articleListTag>
            ```

            ## 5. 上下文变量（页面级变量，由路由自动注入）

            不同页面会自动注入不同的上下文变量：

            - **index.html**: 无特殊上下文变量（用 articleListTag 主动拉取）
            - **article.html**: 注入 `article` 对象（含 title、contentHtml、created、viewCount、thumbnail、summary）
            - **article_list.html**: 注入 `category` 对象（含 id、title、url）和 `articleVoPage`（分页对象）
              - articleVoPage.records: 当前页文章列表
              - articleVoPage.size、current、total、pages
              - 分页渲染用 `<@articlePageTag>` 指令
            - **page.html**: 注入 `singlePage` 对象（含 id、title、contentHtml）

            ## 6. 文章列表分页示例（article_list.html 关键片段）

            ```html
            <#import "_layout.html" as layout>
            <@layout.header "${'$'}{(category.title)!}列表"></@layout.header>
            <@layout.body>
              <div class="page-title">${'$'}{(category.title)!}列表</div>
              <#if articleVoPage??>
                <#list articleVoPage.records as item>
                  <article>
                    <h2><a href="${'$'}{item.url!}">${'$'}{item.title!}</a></h2>
                    <p>${'$'}{item.summary!}</p>
                  </article>
                </#list>
              </#if>
              <@layout._articlePage/>
            </@layout.body>
            ```
            其中 `<@layout._articlePage/>` 会渲染分页条，分页宏定义在 `_articlePage.html` 中：
            ```html
            <#macro _articlePage>
              <@articlePageTag>
                <div class="pagelist">
                  <a href="${'$'}{data.prev.url!}">${'$'}{data.prev.text!}</a>
                  <#list data.list as item>
                    <a href="${'$'}{item.url!}">${'$'}{item.text!}</a>
                  </#list>
                  <a href="${'$'}{data.next.url!}">${'$'}{data.next.text!}</a>
                </div>
              </@articlePageTag>
            </#macro>
            ```

            ## 7. URL 路由约定（语义化长路径）

            - 首页: /
            - 文章详情: /article/{articleId}
            - 文章分类列表: /article/category/{categoryId}
            - 文章标签列表: /article/tag/{tagId}
            - 单页面: /page/{pageName}

            模板文件名与路由的映射关系（由 fastcms 自动处理，无需在模板中配置）：
            - index.html → /
            - article.html → /article/{id}
            - article_list.html → /article/category/{id}、/article/tag/{id}
            - page.html → /page/{name}

            ## 8. 静态资源引用约定

            所有静态资源（CSS、JS、图片）必须通过 `${'$'}{ctx()}` 前缀引用，它会自动解析为模板根路径：
            ```html
            <link href="${'$'}{ctx()}/css/base.css" rel="stylesheet">
            <script src="${'$'}{ctx()}/js/main.js"></script>
            <img src="${'$'}{ctx()}/images/logo.png" alt="Logo">
            ```
            不要硬编码路径如 `/static/css/...` 或 `/xjd2022/css/...`。

            ## 9. 预览演示数据 _preview_data.json

            模板目录下必须包含 `_preview_data.json`，定义模板预览时使用的演示数据（菜单、分类、标签、单页、文章标题、SEO）。
            内容必须贴合用户需求主题：如餐饮模板用"菜品展示/门店故事/在线订座"，科技模板用"新闻动态/产品中心"。
            格式（所有字段可选，未配置的字段使用系统默认演示数据）：
            ```json
            {
              "menus": [
                { "name": "新闻动态", "type": "article_list", "children": [
                  { "name": "公司新闻", "type": "article_list" }
                ]},
                { "name": "关于我们", "type": "page", "suffix": "about" }
              ],
              "categories": ["科技前沿", "产品动态"],
              "tags": ["Java", "Spring Boot"],
              "singlePages": [{ "title": "关于我们", "suffix": "about" }, "服务条款"],
              "articles": {
                "titles": ["文章标题1", "文章标题2"],
                "summaries": ["摘要1", "摘要2"],
                "suffixes": ["news", ""]
              },
              "seo": { "website_title": "站点标题" }
            }
            ```

            字段规则：
            - menus：最多 8 项，最多两级（children 每层最多 6 项），每项含 name、type、可选 suffix、可选 children
            - type 只能取：index、article_list、article、page；省略时默认 article_list
            - suffix 对应模板文件 {type}_{suffix}.html（如 "about" 对应 page_about.html，"about_h5" 对应 page_about_h5.html）；
              配置了 suffix 时必须同时生成对应的模板文件
            - 禁止在 JSON 中写任何 url 字段，预览链接由系统按 type + suffix 自动解析
            - categories/tags/singlePages 数组元素可以是字符串（无 suffix）或 { "title": ..., "suffix": ... } 对象
            - articles 的 titles/summaries/suffixes 是平行数组，最多 12 项；summaries/suffixes 可省略
            - seo 的 key 与 seoTag 指令一致（website_title、website_sub_title、website_seo、public_website_domain）

            ## 10. 移动端响应式适配

            ${mobileAdaptiveSection}

            ## 11. 菜单选中高亮（强制，方案 A：模板层通过 request 对比）

            菜单选中状态必须由 **FreeMarker 模板渲染时静态输出 active class**，不能只依靠前端 JS 切换
            （否则新打开页面时 JS 还没执行，菜单项看起来就没选中）。
            具体实现：

            1. **当前请求路径来源**：fastcms 框架已通过 `FastcmsTemplateViewResolver` 向 FreeMarker 视图注入
               `request`（类型 `HttpServletRequest`），模板里可用 `${request.requestURI}` 与 `${request.contextPath}` 获取路径，
               **均要加 ! 默认值**：`<#assign cp = request.contextPath!>`、`<#assign currentUri = cp + (request.requestURI)!>`。
               预览模式下（AI 模板预览路由）同样注入了 request，因此 active 判断在预览/正式环境都生效。
            2. **首页高亮规则**：当 `currentUri == cp + '/' || currentUri == cp` 时首页 `<li>` 加 `class="active"`
            3. **菜单高亮规则（前缀匹配）**：对 menuTag 遍历的每一项 `item`，当
               `item.url?? && currentUri?starts_with(item.url!)` 时该 `<li>` 加 `class="active"`；
               前缀匹配的好处是：进入 `/article/123` 时父菜单 `/article/category/3`（如果指向同前缀）也会高亮
            4. **子菜单递归**：必须在 _layout.html 中定义递归宏 `<#macro menuChildren children currentUri>`，
               二级及以下菜单同样按前缀匹配输出 active；子菜单命中时其父级因前缀包含关系天然也是 active
            5. **高亮样式**：CSS 中必须定义：
               ```css
               .site-nav li.active > a { color: [主色]; border-bottom: 2px solid [主色]; font-weight: 600; }
               .site-nav li.active > .submenu { display:block; } /* 桌面端下拉菜单 */
               ```
            6. **注意**：`item.url` 可能包含 contextPath（由 menuTag 数据源决定），对比时不要重复拼接；
               若出现路径多次加前缀的情况，可在 body 宏开头先把 item.url 去掉重复前缀（如 `<#local itemUrl = (item.url?starts_with(cp+cp))?then(item.url?substring(cp?length), item.url)>`），
               推荐保持默认：`currentUri = cp + requestURI`、`item.url` 直接用，两者口径一致。
            """;

    /**
     * 系统提示词协议段：## 12 响应格式（严格 JSON）+ 行为准则
     *
     * <p>管线机器解析契约，恒内置拼接，不随技能覆盖。</p>
     */
    private static final String SYSTEM_PROMPT_TAIL = """
            ## 12. 响应格式（严格 JSON）

            你的每次回复必须是一个 JSON 对象（不能是数组），包含两个字段：
            ```json
            {
              "reply": "这里是给用户看的自然语言回复：总结本次生成了什么/修改了什么，或直接回答用户的问题",
              "files": [
                {
                  "path": "_template.properties",
                  "content": "template.id=www.${templateName}.com\\ntemplate.name=${templateName}\\n...",
                  "action": "create"
                },
                {
                  "path": "_layout.html",
                  "content": "<#macro header title>\\n...",
                  "action": "create"
                },
                {
                  "path": "index.html",
                  "content": "<#import \\"_layout.html\\" as layout>\\n...",
                  "action": "create"
                },
                {
                  "path": "static/css/base.css",
                  "content": "body { margin: 0; }\\n...",
                  "action": "create"
                }
              ]
            }
            ```

            ### 字段说明
            - **reply**: 给用户的自然语言回复（必填）
              - 生成模板时：简要说明本次生成的模板风格、包含的文件和设计要点
              - 微调时：说明本次修改了哪些文件、做了什么调整
              - 用户提出与模板无关的问题（如咨询、闲聊、询问你的身份）时：直接在 reply 中回答，此时 files 为空数组
            - **files**: 文件数组（必填，可为空数组）
              - **path**: 文件相对路径，相对于模板目录根（如 `index.html`、`static/css/base.css`）
              - **content**: 文件完整内容（字符串，JSON 字符串中的换行用 `\\n`，引号用 `\\"`）
              - **action**: 操作类型
                - `create`: 新建文件
                - `modify`: 修改已有文件
                - `delete`: 删除文件（content 可为空）

            ### 重要约束
            1. **只输出 JSON 对象本身**，不要包裹在 markdown 代码块中，不要添加任何前后文字解释
            2. JSON 必须严格合法，可被 `JSON.parse` 直接解析
            3. content 中的特殊字符必须正确转义：换行符用 `\\n`、双引号用 `\\"`、反斜杠用 `\\\\`
            4. 微调场景下，files 中只输出需要变动的文件，未提及的文件不要重复输出
            5. 不要输出占位符内容（如 `... 省略 ...`），每个文件的 content 必须是可直接使用的完整内容
            6. reply 保持简洁（一般不超过 200 字），详细内容放在文件里

            # 你的行为准则

            1. **遵循规范**：严格遵循上述 fastcms 模板规范，使用正确的指令和宏
            2. **完整可用**：生成的模板必须能被 fastcms 直接识别和应用，不缺文件
            3. **响应式**：${mobileRule}
            4. **菜单选中（硬约束）**：严格按照"第 11 节 菜单选中高亮（方案 A）"实现，在 _layout.html 的 FreeMarker 层
               用 request.contextPath + request.requestURI 对比 item.url 输出 active class，不能只在 JS 里切换；子菜单递归宏必须定义
            5. **可访问性**：HTML 语义化，alt 属性完整，aria 属性适当使用；汉堡按钮要加 aria-label
            6. **性能优先**：CSS 放头部、JS 放尾部，避免内联样式；汉堡菜单尽量用纯 CSS（checkbox/label + :checked），不引入额外 JS 依赖
            7. **不硬编码内容**：动态内容（菜单、文章列表、文章详情）必须用指令渲染，不要写死文章标题
            8. **风格统一**：配色、字体、间距遵循视觉一致性，参考现代化网站设计
            9. **语言要求**：全程使用中文。思考推理过程（reasoning）必须使用中文，reply 回复也必须是中文
            """;

    /**
     * 系统提示词第 10 节正文：开启移动端适配时的三档断点 + 汉堡菜单硬约束
     */
    private static final String MOBILE_SECTION_REQUIRED = """
            （强制）生成的模板必须同时满足桌面端（≥1200px）、平板（769–991px）、手机（≤768px，含 ≤480px 小屏）三档自适应。
            具体要求：

            1. **基础 viewport**：`<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">`
            2. **CSS 断点（必须写全）**：
               - `@media (max-width: 991px)`：平板过渡档，`.container` 最大宽改为 960px 或 100%，侧边栏与主体布局开始折叠
               - `@media (max-width: 768px)`：手机主档，`.container` 最大宽 100% + 左右各 15–20px padding；
                 栅格 3/4 列折叠为 1–2 列；文章卡片改为单列堆叠；
                 隐藏 desktop 水平菜单 `.site-nav ul`，显示 `.nav-toggle-label` 汉堡按钮
               - `@media (max-width: 480px)`：小屏手机档，标题字号 h1 缩至 22–24px、h2 至 18–20px；
                 按钮 padding 缩小；首页 banner 高度由 400–500px 降到 240–280px
            3. **汉堡菜单（纯 CSS 实现，不依赖 Bootstrap/jQuery）**：
               - header 中必须有 `<input type="checkbox" id="nav-toggle" class="nav-toggle">`
                 与 `<label for="nav-toggle" class="nav-toggle-label"><span></span>×3</label>`
               - `#nav-toggle` 默认隐藏（display:none），`.nav-toggle-label` 只在 ≤768px 显示（三条横线用 label 的三个 span + border-bottom 或背景绘制）
               - 通过 `#nav-toggle:checked + .nav-toggle-label + .site-nav` 或兄弟选择器控制
                 `.site-nav` 从 display:none → display:block，菜单展开为竖向全宽列表；子菜单在移动端默认展开或点击父项展开（不用 hover）
            4. **图片自适应**：`img { max-width:100%; height:auto; }` 写在 base.css 顶部；首页 banner 背景用 `background-size: cover; background-position: center;`
            5. **字体**：body 基础字号 15–16px（桌面）、14px（手机）；使用系统字体栈 `-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`""";

    /**
     * 系统提示词第 10 节正文：关闭移动端适配时的桌面专用约束
     */
    private static final String MOBILE_SECTION_DISABLED = """
            （本会话用户已选择桌面专用模板）无需适配移动端：不要求 media query 断点、汉堡菜单与移动端结构，
            按 ≥1200px 固定布局设计，专注桌面端视觉与交互质量；viewport meta 仍保留标准写法。""";

    /**
     * 行为准则第 3 条：开启移动端适配
     */
    private static final String MOBILE_RULE_REQUIRED = """
            （硬约束）严格按照"第 10 节 移动端响应式适配"实现三档断点 + 汉堡菜单 + 图片自适应，
               不能只写 viewport 而无 @media；不能靠 Bootstrap/外部框架兜底；文章列表/栅格/容器宽度/导航在 ≤768px 必须可验证地切换布局""";

    /**
     * 行为准则第 3 条：关闭移动端适配
     */
    private static final String MOBILE_RULE_DISABLED = """
            本会话用户选择桌面专用模板，第 10 节移动端适配要求不适用；按 ≥1200px 桌面布局设计即可""";
}
