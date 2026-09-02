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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 插件目录与运行模式配置（默认无需配置）：
 * - path：插件根目录，默认 ~/fastcms/plugins（FASTCMS_HOME 可覆盖）
 * - mode：PF4J 运行模式，默认 DEPLOYMENT（jar 形式加载）；
 *         测试/调试场景可配 DEVELOPMENT，直接从插件工程源码目录加载（免打包），
 *         典型配置见 web/src/test/resources/application.yml：
 *           fastcms.plugin.path: ../plugins
 *           fastcms.plugin.mode: DEVELOPMENT
 * @author： wjun_java@163.com
 * @date： 2021/9/14
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
@ConfigurationProperties(prefix = "fastcms.plugin")
public class PluginProperties {

    /**
     * 插件根目录；不配置时使用 ~/fastcms/plugins
     */
    private String path;

    /**
     * PF4J 运行模式：DEVELOPMENT（目录形式，调试用）/ DEPLOYMENT（jar 形式，默认）
     */
    private String mode;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

}
