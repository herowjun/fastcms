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
package com.fastcms.ai.skill;

import com.fastcms.ai.agent.BuiltinAgents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 必需技能防复发校验（2026-10-29）
 *
 * <p><b>要防的是什么</b>：曾出现真实事故——设计方法论技能 {@code design-brief} 只存在于某台
 * 开发机的 {@code ~/fastcms/skills/} 目录（由外部工具手工写入、从未进版本控制），
 * 代码侧却把它当硬依赖。换机器后技能凭空消失，而调用点静默返回 null，
 * 模板照常生成、只是少了一段方法论约束——从日志里完全看不出来。</p>
 *
 * <p><b>三道闸门</b>：</p>
 * <ol>
 *     <li><b>构建期</b>（本测试）：硬依赖清单里的每个技能都必须在插件源码树里有对应 SKILL.md。
 *         加了依赖却忘了把技能放进插件 → 编译期测试失败。</li>
 *     <li><b>启动期</b>（{@link RequiredSkillChecker}）：技能缺失时在启动日志里显式告警。</li>
 *     <li><b>运行期</b>（{@link RequiredSkills#requireContent}）：缺失时明确失败，不静默降级。</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
class RequiredSkillsTest {

    /**
     * 插件源码树根目录（相对模块目录）。ai-starter 位于 starters/ 下，故上溯两级。
     */
    private static Path pluginsRoot() {
        Path moduleDir = Paths.get("").toAbsolutePath();
        Path candidates = moduleDir.resolve("../../plugins");
        return candidates.normalize();
    }

    /**
     * 扫描插件源码树，返回所有插件源技能的完整 id 集合（{pluginId}/{slug}）
     */
    private static List<String> pluginSkillIdsInSourceTree() throws IOException {
        Path root = pluginsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (Stream<Path> pluginDirs = Files.list(root)) {
            for (Path pluginDir : pluginDirs.filter(Files::isDirectory).toList()) {
                String pluginId = pluginIdOf(pluginDir);
                if (pluginId == null) {
                    continue;
                }
                Path skillsDir = pluginDir.resolve("src/main/resources/skills");
                if (!Files.isDirectory(skillsDir)) {
                    continue;
                }
                try (Stream<Path> skillDirs = Files.list(skillsDir)) {
                    for (Path skillDir : skillDirs.filter(Files::isDirectory).toList()) {
                        if (Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                            ids.add(pluginId + "/" + skillDir.getFileName());
                        }
                    }
                }
            }
        }
        return ids;
    }

    /**
     * 从插件 pom.xml 里读 plugin.id（插件的真实标识，jar 的 Plugin-Id 也来自它）
     */
    private static String pluginIdOf(Path pluginDir) throws IOException {
        Path pom = pluginDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        int idx = xml.indexOf("<plugin.id>");
        if (idx < 0) {
            return null;
        }
        int end = xml.indexOf("</plugin.id>", idx);
        if (end < 0) {
            return null;
        }
        return xml.substring(idx + "<plugin.id>".length(), end).trim();
    }

    // ==================== 闸门 1：构建期校验 ====================

    @Test
    @DisplayName("每个硬依赖技能都在插件源码树里有对应 SKILL.md（防止加了依赖忘放技能）")
    void everyRequiredSkillExistsInPluginSourceTree() throws IOException {
        List<String> available = pluginSkillIdsInSourceTree();
        assertThat(available)
                .as("插件源码树扫描失败：目录 %s 下没找到任何插件技能，请确认测试工作目录正确", pluginsRoot())
                .isNotEmpty();

        for (Map.Entry<String, RequiredSkills.SkillRequirement> entry : RequiredSkills.dependencies().entrySet()) {
            assertThat(available)
                    .as("硬依赖技能 [%s]（用途：%s）在插件源码树里找不到对应 SKILL.md——"
                                    + "代码侧硬依赖必须由插件提供，请把技能放进对应插件的 "
                                    + "src/main/resources/skills/<slug>/SKILL.md",
                            entry.getKey(), entry.getValue().purpose())
                    .contains(entry.getKey());
        }
    }

    @Test
    @DisplayName("硬依赖技能必须是插件源 id（含斜杠），不能是裸 slug 的本机磁盘技能")
    void requiredSkillsMustBePluginSource() {
        for (String skillId : RequiredSkills.dependencies().keySet()) {
            assertThat(skillId)
                    .as("硬依赖技能 [%s] 必须是 {pluginId}/{slug} 形式的插件源 id——"
                            + "裸 slug 只能来自某台机器的 ~/fastcms/skills/ 目录，无法随代码分发", skillId)
                    .contains("/");
        }
    }

    @Test
    @DisplayName("硬依赖清单覆盖了两个实际调用点用到的技能（防止新增调用点忘记登记）")
    void coversAllHardDependencyCallSites() {
        Map<String, RequiredSkills.SkillRequirement> deps = RequiredSkills.dependencies();
        assertThat(deps).containsKey(BuiltinAgents.TEMPLATE_SPEC_SKILL_ID);
        assertThat(deps).containsKey(BuiltinAgents.DESIGN_BRIEF_SKILL_ID);
    }

    @Test
    @DisplayName("design-brief 已迁到插件源（不再是本机磁盘技能的裸 slug）")
    void designBriefMigratedToPluginSource() {
        assertThat(BuiltinAgents.DESIGN_BRIEF_SKILL_ID)
                .isEqualTo("design-skills-plugin/design-brief");
        assertThat(BuiltinAgents.DESIGN_BRIEF_SKILL_ID)
                .isNotEqualTo("design-brief");
    }

    // ==================== 闸门 1b：契约锚点与插件源码正文一致 ====================

    @Test
    @DisplayName("插件源码里的 SKILL.md 满足正文契约（防止改了契约却没改技能正文）")
    void pluginSourceContentSatisfiesContract() throws IOException {
        for (Map.Entry<String, RequiredSkills.SkillRequirement> entry : RequiredSkills.dependencies().entrySet()) {
            String skillId = entry.getKey();
            RequiredSkills.SkillRequirement requirement = entry.getValue();
            if (requirement.requiredParts().isEmpty()) {
                continue;
            }
            Path skillFile = skillFileOf(skillId);
            assertThat(skillFile)
                    .as("硬依赖技能 [%s] 在插件源码树里找不到 SKILL.md（路径 %s）", skillId, skillFile)
                    .isNotNull();

            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            for (String part : requirement.requiredParts()) {
                assertThat(content)
                        .as("插件源码的 %s 缺少正文契约要求的章节 [%s]——"
                                        + "主程序声明依赖该章节，但插件里的技能正文没有，"
                                        + "构建出的 jar 会带着残缺规范上线（运行期才被发现）",
                                skillFile, part)
                        .contains(part);
            }
        }
    }

    /**
     * 由技能 id（{pluginId}/{slug}）定位插件源码树里的 SKILL.md
     */
    private static Path skillFileOf(String skillId) {
        Path root = pluginsRoot();
        int slash = skillId.indexOf('/');
        if (slash < 0) {
            return null;
        }
        Path pluginDir = root.resolve(skillId.substring(0, slash));
        Path skillFile = pluginDir.resolve("src/main/resources/skills")
                .resolve(skillId.substring(slash + 1))
                .resolve("SKILL.md");
        return Files.isRegularFile(skillFile) ? skillFile : null;
    }

    // ==================== 闸门 3：运行期明确失败 ====================

    @Test
    @DisplayName("技能缺失时 requireContent 抛异常，不静默返回 null")
    void missingSkillFailsLoudly() {
        // 空注册表 = 所有技能都缺失
        SkillRegistry empty = new SkillRegistry(List.of());

        assertThatThrownBy(() -> RequiredSkills.requireContent(empty,
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, "测试场景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BuiltinAgents.DESIGN_BRIEF_SKILL_ID)
                .hasMessageContaining("测试场景");

        assertThatThrownBy(() -> RequiredSkills.requireContent(empty,
                BuiltinAgents.TEMPLATE_SPEC_SKILL_ID, "测试场景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BuiltinAgents.TEMPLATE_SPEC_SKILL_ID);
    }

    @Test
    @DisplayName("未登记到清单的技能直接拒绝（防止绕过登记静默降级）")
    void unregisteredSkillRejected() {
        SkillRegistry empty = new SkillRegistry(List.of());
        assertThatThrownBy(() -> RequiredSkills.requireContent(empty, "some-unregistered-skill", "测试场景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    @DisplayName("技能就绪且正文满足契约时正常返回正文")
    void returnsContentWhenSkillPresent() {
        SkillRegistry registry = new SkillRegistry(List.of(new StubSkillProvider(
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, compliantDesignBrief())));

        String content = RequiredSkills.requireContent(registry,
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, "测试场景");
        assertThat(content).contains("设计方法论");
    }

    @Test
    @DisplayName("正文为空白时同样视为缺失（不能把空内容当就绪）")
    void blankContentTreatedAsMissing() {
        SkillRegistry registry = new SkillRegistry(List.of(new StubSkillProvider(
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, "   \n  ")));

        assertThatThrownBy(() -> RequiredSkills.requireContent(registry,
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, "测试场景"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== 闸门 2：正文漂移检测 ====================

    @Test
    @DisplayName("正文缺少必需章节时明确失败（插件 jar 比主程序旧 → 不静默降级）")
    void driftedContentFailsLoudly() {
        // 模拟 Sep 24 事故：插件 jar 里的 template-spec 停留在旧版，缺 §1.0/§1.1/§1.2
        String staleSpec = """
                # fastcms 模板规范

                ## 1. 目录结构
                （旧版正文，没有 CSS/JS 分离、栏目专属页、_components 章节）
                """;

        SkillRegistry registry = new SkillRegistry(List.of(new StubSkillProvider(
                BuiltinAgents.TEMPLATE_SPEC_SKILL_ID, staleSpec)));

        assertThatThrownBy(() -> RequiredSkills.requireContent(registry,
                BuiltinAgents.TEMPLATE_SPEC_SKILL_ID, "测试场景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正文漂移")
                .hasMessageContaining("### 1.0 CSS/JS 分离")
                .hasMessageContaining("### 1.2 _components 组件目录")
                .hasMessageContaining("测试场景");
    }

    @Test
    @DisplayName("正文缺少部分必需章节时，异常信息列出全部缺失项")
    void driftedContentReportsAllMissingParts() {
        String partialSpec = """
                # fastcms 模板规范

                ### 1.0 CSS/JS 分离（默认分开写）
                已有该节

                ## 2. _template.properties 模板元信息
                （缺 §1.1 与 §1.2）
                """;

        SkillRegistry registry = new SkillRegistry(List.of(new StubSkillProvider(
                BuiltinAgents.TEMPLATE_SPEC_SKILL_ID, partialSpec)));

        assertThatThrownBy(() -> RequiredSkills.requireContent(registry,
                BuiltinAgents.TEMPLATE_SPEC_SKILL_ID, "测试场景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("### 1.1 栏目专属页")
                .hasMessageContaining("### 1.2 _components 组件目录")
                .hasMessageNotContaining("### 1.0 CSS/JS 分离\n");
    }

    @Test
    @DisplayName("设计方法论正文缺章节同样被拦（漂移检测不只针对 template-spec）")
    void driftedDesignBriefFailsLoudly() {
        SkillRegistry registry = new SkillRegistry(List.of(new StubSkillProvider(
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, "# 设计方法论\n\n## 视觉层次\n只有一节")));

        assertThatThrownBy(() -> RequiredSkills.requireContent(registry,
                BuiltinAgents.DESIGN_BRIEF_SKILL_ID, "测试场景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正文漂移")
                .hasMessageContaining("## CTA 设计");
    }

    /**
     * 构造满足 design-brief 契约的正文（含全部必需锚点）
     */
    private static String compliantDesignBrief() {
        return """
                # 设计方法论

                ## 视觉层次
                内容

                ## 留白与节奏
                内容

                ## 色彩语义
                内容

                ## CTA 设计
                内容
                """;
    }

    /**
     * 只提供单个技能的桩 Provider（用于运行期行为测试）
     */
    private record StubSkillProvider(String skillId, String content) implements SkillProvider {

        @Override
        public String getProviderId() {
            return "stub-plugin";
        }

        @Override
        public List<SkillDescriptor> listSkills() {
            return List.of(new SkillDescriptor(skillId, "桩技能", "测试用", "1.0.0",
                    SkillDescriptor.SOURCE_PLUGIN, getProviderId(), false, null));
        }

        @Override
        public String loadContent(String id) {
            return skillId.equals(id) ? content : null;
        }
    }
}
