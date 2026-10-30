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

import com.fastcms.common.utils.VersionUtils;
import org.pf4j.PluginDescriptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件与 fastcms 主程序的版本兼容性判定。
 *
 * <p><b>要解决的问题</b>：主程序与插件版本错配时，插件的技能正文可能比主程序
 * 期望的旧（例如插件 jar 停留在几个月前、源码已新增了规范章节）。这类错配
 * <b>不会报错</b>——插件照常加载、正文照常注入，只是内容悄悄落后，产出质量无声下降。
 * 这与「孤儿技能」是同一类问题的两面：都是「静默降级」。</p>
 *
 * <p><b>判定规则</b>：</p>
 * <ul>
 *     <li>插件未声明 {@link FastcmsPluginDescriptor#FASTCMS_VERSION} → <b>兼容</b>
 *         （第三方插件不受 fastcms 版本约束，不做拦截）</li>
 *     <li>主程序版本无法读取 → <b>兼容但告警</b>（无法判定不等于不兼容，
 *         但要留下痕迹，否则同样会退化成静默）</li>
 *     <li>声明版本 &le; 主程序版本 → <b>兼容</b></li>
 *     <li>声明版本 &gt; 主程序版本 → <b>不兼容</b>（插件要求更高版本的主程序）</li>
 * </ul>
 *
 * <p><b>版本号比较口径</b>：只比较 {@code major.minor.patch} 三段数字，
 * 忽略 {@code -SNAPSHOT} 等预发布后缀——因为 fastcms 当前版本就是
 * {@code 1.0.0-SNAPSHOT}，若严格按 SemVer 规则（预发布版本 &lt; 正式版本），
 * 会导致「插件声明 1.0.0、主程序 1.0.0-SNAPSHOT」被误判为不兼容。
 * 后缀只影响「哪个构建更新」，不影响「接口是否兼容」。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public final class PluginCompatibility {

    /**
     * 判定结果
     *
     * @param compatible  是否兼容
     * @param reason     不兼容原因（兼容时为 null）
     */
    public record Result(boolean compatible, String reason) {

        public static Result ok() {
            return new Result(true, null);
        }

        public static Result rejected(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * 版本号解析：抽取 major.minor.patch 三段数字
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    private PluginCompatibility() {
    }

    /**
     * 判定插件与当前 fastcms 主程序的兼容性
     *
     * @param descriptor        插件描述符
     * @param fastcmsVersion    当前 fastcms 主程序版本
     * @return 判定结果
     */
    public static Result check(PluginDescriptor descriptor, String fastcmsVersion) {
        if (descriptor == null) {
            return Result.ok();
        }
        String required = requiredVersion(descriptor);
        if (required == null) {
            // 未声明兼容要求：第三方插件等，不做拦截
            return Result.ok();
        }
        if (fastcmsVersion == null || fastcmsVersion.isBlank()) {
            // 主程序版本读不到：无法判定不等于不兼容，但必须留下痕迹
            return Result.ok();
        }
        int[] requiredParts = parse(required);
        int[] currentParts = parse(fastcmsVersion);
        if (requiredParts == null || currentParts == null) {
            return Result.ok();
        }
        int cmp = compare(currentParts, requiredParts);
        if (cmp >= 0) {
            return Result.ok();
        }
        return Result.rejected("插件要求 fastcms 主程序版本 >= " + required
                + "，当前主程序版本为 " + fastcmsVersion
                + "——插件可能调用了当前主程序不存在的能力，加载后会在运行期出错，"
                + "请升级主程序或换用与当前主程序匹配的插件版本");
    }

    /**
     * 提取插件声明的 fastcms 版本要求（仅当描述符是 fastcms 自有类型时）
     */
    public static String requiredVersion(PluginDescriptor descriptor) {
        if (descriptor instanceof FastcmsPluginDescriptor fastcmsDescriptor
                && fastcmsDescriptor.hasFastcmsVersion()) {
            return fastcmsDescriptor.getFastcmsVersion();
        }
        return null;
    }

    /**
     * 当前 fastcms 主程序版本
     *
     * <p>来源：{@code common} 模块的 {@code fastcms-version.txt}（构建期资源过滤写入
     * {@code ${project.version}}）。读不到时返回 null。</p>
     */
    public static String currentFastcmsVersion() {
        String version = VersionUtils.version;
        return (version == null || version.isBlank() || version.startsWith("${")) ? null : version;
    }

    /**
     * 解析版本号中的 major.minor.patch；解析失败返回 null
     */
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(version.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 比较两个版本：a &gt; b 返回正数，a &lt; b 返回负数，相等返回 0
     */
    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int diff = Integer.compare(a[i], b[i]);
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

}
