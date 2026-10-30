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

import org.pf4j.DefaultPluginDescriptor;

/**
 * 带 fastcms 版本兼容声明的插件描述符。
 *
 * <p><b>为什么需要它</b>：pf4j 的 {@link org.pf4j.PluginDescriptor} 是固定接口，
 * 只承载 {@code Plugin-Id / Plugin-Class / Plugin-Version / Plugin-Provider /
 * Plugin-Dependencies / Plugin-Requires / Plugin-License} 这几个硬编码字段，
 * <b>无法携带任意的 manifest 键</b>。而 pf4j 自带的 {@code Plugin-Requires}
 * 语义是「插件要求的 Java/Spring 运行环境版本」，且 pf4j 的
 * {@code systemVersion} 默认为 {@code "0.0.0"} → {@code isPluginValid()} 整个校验
 * 被短路绕过（fastcms 也从未调用 {@code setSystemVersion}）。
 * 结果就是：<b>主程序与插件版本错配在运行时完全无法被检测</b>。</p>
 *
 * <p>因此这里自建描述符，额外承载 {@link #FASTCMS_VERSION} 声明的
 * 「本插件要求�� fastcms 主程序版本」。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public class FastcmsPluginDescriptor extends DefaultPluginDescriptor {

    /**
     * manifest 键：插件要求的 fastcms 主程序版本（最低版本）。
     *
     * <p>取值形如 {@code 1.0.0}，表示本插件要求 fastcms 主程序版本 &gt;= 1.0.0。
     * 留空表示不声明兼容要求（第三方插件可留空，不做拦截）。</p>
     */
    public static final String FASTCMS_VERSION = "Plugin-Fastcms-Version";

    /**
     * 插件要求的 fastcms 最低版本；null 表示未声明
     */
    private String fastcmsVersion;

    public FastcmsPluginDescriptor() {
        super();
    }

    /**
     * 与 pf4j {@link DefaultPluginDescriptor} 全参构造器对齐
     */
    public FastcmsPluginDescriptor(String pluginId, String pluginDescription, String pluginClass,
                                   String version, String requires, String provider, String license) {
        super(pluginId, pluginDescription, pluginClass, version, requires, provider, license);
    }

    public String getFastcmsVersion() {
        return fastcmsVersion;
    }

    public void setFastcmsVersion(String fastcmsVersion) {
        this.fastcmsVersion = fastcmsVersion;
    }

    /**
     * 是否声明了 fastcms 版本兼容要求
     */
    public boolean hasFastcmsVersion() {
        return fastcmsVersion != null && !fastcmsVersion.isBlank();
    }

}
