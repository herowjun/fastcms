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
package com.fastcms.plugin.autoconfigure;

import com.fastcms.common.utils.DirUtils;
import com.fastcms.plugin.FastcmsPluginManager;
import com.fastcms.plugin.PluginPermitAllManager;
import org.pf4j.AbstractPluginManager;
import org.pf4j.DefaultPluginManager;
import org.pf4j.RuntimeMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * @author： wjun_java@163.com
 * @date： 2021/9/14
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
@Configuration
@ConditionalOnClass({DefaultPluginManager.class})
@EnableConfigurationProperties(PluginProperties.class)
public class PluginAutoConfiguration {

    /**
     * 插件目录与运行模式：
     * - 默认（不配置）：DEPLOYMENT 模式 + ~/fastcms/plugins（FASTCMS_HOME 可覆盖），
     *   dev/prod 一致以 jar 形式加载，界面安装/卸载均可用
     * - 测试/调试：配置 fastcms.plugin.mode=DEVELOPMENT + fastcms.plugin.path 指向插件工程目录，
     *   直接从源码目录加载插件（免打包），见 web/src/test/resources/application.yml
     */
    @Bean
    @ConditionalOnMissingBean(FastcmsPluginManager.class)
    public FastcmsPluginManager fastcmsPluginManager(PluginProperties properties) {

        if (properties.getMode() != null && !properties.getMode().isBlank()) {
            System.setProperty(AbstractPluginManager.MODE_PROPERTY_NAME, properties.getMode());
        }

        String path = (properties.getPath() != null && !properties.getPath().isBlank())
                ? properties.getPath()
                : DirUtils.getPluginDir();

        return new FastcmsPluginManager(Paths.get(path));
    }

    @Bean
    @ConditionalOnMissingBean(PluginPermitAllManager.class)
    public PluginPermitAllManager pluginPermitAllManager() {
        return new PluginPermitAllManager();
    }

}
