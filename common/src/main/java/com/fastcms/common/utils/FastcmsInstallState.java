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
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.fastcms.common.utils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 安装状态运行时标记
 * <p>
 * 由 web 模块的数据源配置（FastcmsDataSourceConfig）在启动时写入判定结果，
 * 供 plugin-starter 等无法直接感知数据源配置的模块在启动期读取，
 * 以决定是否跳过依赖数据库的初始化逻辑（如插件加载）。
 * <p>
 * 判定规则：外部安装配置（~/fastcms/config/application.yml 存在且含 fastcms.db.* 数据源配置）
 * 或传统 spring.datasource.* 配置可成功建立连接，二者满足其一即为"已配置数据源"。
 */
public final class FastcmsInstallState {

    /**
     * fastcms 工作目录（与插件目录 ~/fastcms/plugins、日志目录保持同一约定）
     */
    public static final String FASTCMS_HOME_DIR = "fastcms";

    /**
     * 外部配置文件相对路径：~/fastcms/config/application.yml
     */
    public static final String CONFIG_SUB_DIR = "config";

    public static final String CONFIG_FILE_NAME = "application.yml";

    private static volatile Boolean installMode;

    private FastcmsInstallState() {
    }

    /**
     * 由数据源配置在启动时写入：true 表示系统处于未安装模式（哑数据源）
     */
    public static void setInstallMode(boolean installMode) {
        FastcmsInstallState.installMode = installMode;
    }

    /**
     * 是否处于未安装模式。未经过数据源初始化时默认返回 false（按已配置处理，保证老部署行为不变）
     */
    public static boolean isInstallMode() {
        return Boolean.TRUE.equals(installMode);
    }

    /**
     * fastcms 工作目录：~/fastcms
     */
    public static Path getFastcmsHome() {
        return Path.of(System.getProperty("user.home"), FASTCMS_HOME_DIR);
    }

    /**
     * 外部配置目录：~/fastcms/config
     */
    public static Path getExternalConfigDir() {
        return getFastcmsHome().resolve(CONFIG_SUB_DIR);
    }

    /**
     * 外部配置文件：~/fastcms/config/application.yml
     */
    public static Path getExternalConfigFile() {
        return getExternalConfigDir().resolve(CONFIG_FILE_NAME);
    }

    /**
     * 外部安装配置文件是否存在
     */
    public static boolean isExternalConfigPresent() {
        return Files.exists(getExternalConfigFile());
    }

}
