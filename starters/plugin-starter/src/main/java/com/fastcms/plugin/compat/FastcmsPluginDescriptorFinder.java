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
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginDescriptor;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * 插件描述符查找器：在 pf4j 标准 manifest 解析的基础上，额外读取
 * {@link FastcmsPluginDescriptor#FASTCMS_VERSION} 兼容声明。
 *
 * <p><b>为什么复用而不是重写</b>：pf4j 的 {@link ManifestPluginDescriptorFinder}
 * 已经处理了 jar / zip / 目录三种插件形态的 manifest 读取，重写会引入不必要的
 * 分叉风险。这里只覆写「创建描述符」这一个钩子，把标准字段照常填好，
 * 再补一个 fastcms 专属字段——标准行为完全不变，只是多带了一个键。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public class FastcmsPluginDescriptorFinder extends ManifestPluginDescriptorFinder {

    @Override
    protected PluginDescriptor createPluginDescriptor(Manifest manifest) {
        PluginDescriptor descriptor = super.createPluginDescriptor(manifest);
        if (descriptor instanceof FastcmsPluginDescriptor fastcmsDescriptor) {
            Attributes attributes = manifest.getMainAttributes();
            String fastcmsVersion = attributes.getValue(FastcmsPluginDescriptor.FASTCMS_VERSION);
            if (fastcmsVersion != null && !fastcmsVersion.isBlank()) {
                fastcmsDescriptor.setFastcmsVersion(fastcmsVersion.trim());
            }
        }
        return descriptor;
    }

    @Override
    protected DefaultPluginDescriptor createPluginDescriptorInstance() {
        return new FastcmsPluginDescriptor();
    }

}
