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
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.fastcms.plugin.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.PluginDescriptor;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件版本兼容声明与判定测试。
 *
 * <p><b>为什么要有这层闸门</b>：主程序与插件版本错配时，插件照常加载、
 * 技能正文照常注入，只是内容可能比主程序期望的旧——产出质量无声下降，
 * 从日志里完全看不出来。这与「孤儿技能」是同一类问题（静默降级），
 * 必须由代码显式拦截。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
class PluginCompatibilityTest {

    private static PluginDescriptor descriptor(String fastcmsVersion) {
        FastcmsPluginDescriptor descriptor = new FastcmsPluginDescriptor(
                "test-plugin", "测试插件", "org.pf4j.Plugin", "1.0.0", "*", "test", null);
        descriptor.setFastcmsVersion(fastcmsVersion);
        return descriptor;
    }

    @Test
    @DisplayName("未声明 fastcms 版本 → 兼容（第三方插件不受约束，不做拦截）")
    void undeclaredIsCompatible() {
        PluginCompatibility.Result result = PluginCompatibility.check(descriptor(null), "1.0.0");
        assertThat(result.compatible()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("声明版本 == 主程序版本 → 兼容")
    void equalVersionIsCompatible() {
        assertThat(PluginCompatibility.check(descriptor("1.0.0"), "1.0.0").compatible()).isTrue();
    }

    @Test
    @DisplayName("声明版本 < 主程序版本 → 兼容（老插件跑在新主程序上属正常）")
    void olderRequiredVersionIsCompatible() {
        assertThat(PluginCompatibility.check(descriptor("1.0.0"), "2.0.0").compatible()).isTrue();
    }

    @Test
    @DisplayName("声明版本 > 主程序版本 → 不兼容，且给出可操作的修复指引")
    void newerRequiredVersionIsRejected() {
        PluginCompatibility.Result result = PluginCompatibility.check(descriptor("2.0.0"), "1.0.0");
        assertThat(result.compatible()).isFalse();
        assertThat(result.reason())
                .contains("2.0.0")
                .contains("1.0.0")
                .contains("升级主程序");
    }

    @Test
    @DisplayName("主程序版本带 -SNAPSHOT 后缀 → 仍判定为兼容（预发布版本不被误判）")
    void snapshotSuffixDoesNotBreakCompatibility() {
        // fastcms 当前版本就是 1.0.0-SNAPSHOT；若严格按 SemVer（预发布 < 正式），
        // 插件声明 1.0.0 会被误判为不兼容 —— 这正是需要规避的坑
        assertThat(PluginCompatibility.check(descriptor("1.0.0"), "1.0.0-SNAPSHOT").compatible()).isTrue();
        assertThat(PluginCompatibility.check(descriptor("1.0.0-SNAPSHOT"), "1.0.0-SNAPSHOT").compatible()).isTrue();
    }

    @Test
    @DisplayName("主程序版本读不到 → 兼容但不静默（调用方负责打告警）")
    void blankFastcmsVersionIsCompatible() {
        assertThat(PluginCompatibility.check(descriptor("2.0.0"), null).compatible()).isTrue();
        assertThat(PluginCompatibility.check(descriptor("2.0.0"), "").compatible()).isTrue();
    }

    @Test
    @DisplayName("版本号格式非法 → 兼容（无法判定不等于不兼容）")
    void malformedVersionIsCompatible() {
        assertThat(PluginCompatibility.check(descriptor("not-a-version"), "1.0.0").compatible()).isTrue();
        assertThat(PluginCompatibility.check(descriptor("1.0.0"), "unknown").compatible()).isTrue();
    }

    @Test
    @DisplayName("patch 版本参与比较（1.0.1 > 1.0.0）")
    void patchVersionParticipatesInComparison() {
        assertThat(PluginCompatibility.check(descriptor("1.0.1"), "1.0.0").compatible()).isFalse();
        assertThat(PluginCompatibility.check(descriptor("1.0.0"), "1.0.1").compatible()).isTrue();
    }

    @Test
    @DisplayName("描述符查找器能从 manifest 读出兼容声明，且不影响标准字段")
    void descriptorFinderReadsFastcmsVersionFromManifest() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Plugin-Id", "template-skills-plugin");
        attributes.putValue("Plugin-Class", "com.fastcms.template.skill.TemplateSkillsPlugin");
        attributes.putValue("Plugin-Version", "1.0.0-SNAPSHOT");
        attributes.putValue("Plugin-Fastcms-Version", "1.0.0");

        FastcmsPluginDescriptorFinder finder = new FastcmsPluginDescriptorFinder();
        PluginDescriptor descriptor = finder.createPluginDescriptor(manifest);

        assertThat(descriptor).isInstanceOf(FastcmsPluginDescriptor.class);
        // 标准字段照常解析
        assertThat(descriptor.getPluginId()).isEqualTo("template-skills-plugin");
        assertThat(descriptor.getPluginClass()).isEqualTo("com.fastcms.template.skill.TemplateSkillsPlugin");
        assertThat(descriptor.getVersion()).isEqualTo("1.0.0-SNAPSHOT");
        // 兼容声明被读出
        assertThat(((FastcmsPluginDescriptor) descriptor).getFastcmsVersion()).isEqualTo("1.0.0");
        assertThat(((FastcmsPluginDescriptor) descriptor).hasFastcmsVersion()).isTrue();
    }

    @Test
    @DisplayName("manifest 无兼容声明键 → 描述符不声明版本")
    void descriptorFinderHandlesMissingFastcmsVersion() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Plugin-Id", "third-party-plugin");

        FastcmsPluginDescriptorFinder finder = new FastcmsPluginDescriptorFinder();
        PluginDescriptor descriptor = finder.createPluginDescriptor(manifest);

        assertThat(descriptor).isInstanceOf(FastcmsPluginDescriptor.class);
        assertThat(((FastcmsPluginDescriptor) descriptor).hasFastcmsVersion()).isFalse();
        assertThat(PluginCompatibility.requiredVersion(descriptor)).isNull();
    }

    @Test
    @DisplayName("非 fastcms 描述符（如第三方实现）不声明版本 → 不拦截")
    void foreignDescriptorIsNotIntercepted() {
        DefaultPluginDescriptor foreign = new DefaultPluginDescriptor(
                "foreign-plugin", "第三方插件", "org.pf4j.Plugin", "1.0.0", "*", "third", null);
        assertThat(PluginCompatibility.requiredVersion(foreign)).isNull();
        assertThat(PluginCompatibility.check(foreign, "1.0.0").compatible()).isTrue();
    }

}
