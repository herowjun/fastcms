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

import com.fastcms.ai.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSS/JS 分离规范的提示词层测试（2026-10-29）
 *
 * <p><b>关键语义</b>：规范是「默认分开写」，<b>不是「禁止内联」</b>——
 * 人工快速调整模板时，在 HTML 里写少量 CSS/JS 是合法写法。
 * 提示词若写成「严禁内联」，AI 会把用户的合法写法也当违规处理（甚至顺手拆掉），
 * 所以这里锁定「允许少量内联」的表述必须存在，且不得出现一刀切的禁止式措辞。</p>
 *
 * @author wjun_java@163.com
 */
class AssetSeparationPromptTest {

    private final TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));

    /** 各条链路的提示词（含 CSS/JS 分离约束） */
    private List<String> allPrompts() {
        String filesWithContent = "### index.html\n```html\n<html></html>\n```\n";
        return List.of(
                builder.buildUserPrompt("t1", "做一个企业官网"),
                builder.buildPlanPrompt("t1", "做一个企业官网"),
                builder.buildSingleFilePrompt("做一个企业官网", "index.html", "", "", true),
                builder.buildSingleFilePrompt("做一个企业官网", "static/css/base.css", "", "", true),
                builder.buildSingleFilePrompt("做一个企业官网", "static/js/main.js", "", "", true),
                builder.buildRefinePrompt("改一下导航", filesWithContent),
                builder.buildAdjustPrompt("改一下导航", filesWithContent, "index.html", null, null, null, null),
                builder.buildAnchorFixPrompt(List.of("id:hero"), filesWithContent),
                builder.buildAuditFixPrompt(
                        List.of(new com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue(
                                "index.html", "missing_class", "btn-ghost 不存在")),
                        filesWithContent, "现代商务风"),
                builder.buildRenderFixPrompt(List.of("模板语法错误"), filesWithContent, "index.html"),
                builder.buildStyleUpgradePrompt(filesWithContent, List.of(), 0, 1, false, 0, null,
                        "把按钮改成圆角", null, null));
    }

    @Test
    @DisplayName("所有链路的提示词都包含 CSS/JS 分离约束")
    void allPromptsContainSeparationRule() {
        for (String prompt : allPrompts()) {
            assertThat(prompt).contains("CSS/JS 分离");
        }
    }

    @Test
    @DisplayName("规范明确「不是禁止内联」：出现允许少量内联的表述")
    void statesThatInlineIsAllowed() {
        // 生成型主路径必须含这句（AI 靠它判断「少量内联不用拆」）
        assertThat(builder.buildUserPrompt("t1", "做一个企业官网"))
                .contains("不是禁止内联");
        assertThat(builder.buildUserPrompt("t1", "做一个企业官网"))
                .contains("少量");
        // 单文件轮（HTML 目标）同样要给出例外
        assertThat(builder.buildSingleFilePrompt("做官网", "index.html", "", "", true))
                .contains("不必强行拆分");
    }

    @Test
    @DisplayName("提示词不含一刀切的禁止式措辞（避免 AI 把合法写法当违规）")
    void noBlanketProhibitionWording() {
        for (String prompt : allPrompts()) {
            assertThat(prompt)
                    .as("提示词不应出现「严禁把大段 CSS/JS 内联」这类一刀切措辞")
                    .doesNotContain("严禁把大段 CSS/JS 内联")
                    .doesNotContain("只放结构与内容")
                    .doesNotContain("严禁在 HTML 中内联");
        }
    }

    @Test
    @DisplayName("修改型链路要求「不要顺手拆掉已有内联」（保护人工代码）")
    void modifyPromptsProtectExistingInline() {
        String filesWithContent = "### index.html\n```html\n<html></html>\n```\n";
        for (String prompt : List.of(
                builder.buildRefinePrompt("改一下", filesWithContent),
                builder.buildAdjustPrompt("改一下", filesWithContent, "index.html", null, null, null, null),
                builder.buildAnchorFixPrompt(List.of("id:hero"), filesWithContent),
                builder.buildAuditFixPrompt(
                        List.of(new com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue(
                                "index.html", "missing_class", "btn-ghost 不存在")),
                        filesWithContent, "现代商务风"),
                builder.buildRenderFixPrompt(List.of("err"), filesWithContent, "index.html"))) {
            assertThat(prompt)
                    .as("修改型链路必须声明「已有文件里的少量内联保持原样」")
                    .contains("保持原样即可，不要顺手拆出去");
        }
    }

    @Test
    @DisplayName("生成型链路给出体量判据（阈值），让 AI 知道何时才该拆")
    void statesSizeThreshold() {
        assertThat(builder.buildUserPrompt("t1", "做官网"))
                .contains("2000 字符")
                .contains("60 行");
    }

    @Test
    @DisplayName("CSS/JS 目标文件的提示词不要求把内容搬回 HTML")
    void assetTargetPromptsDoNotForceMoveBack() {
        assertThat(builder.buildSingleFilePrompt("做官网", "static/css/base.css", "", "", true))
                .contains("不必把本文件内容复制回去");
        assertThat(builder.buildSingleFilePrompt("做官网", "static/js/main.js", "", "", true))
                .contains("不必把本文件内容复制回去");
    }
}
