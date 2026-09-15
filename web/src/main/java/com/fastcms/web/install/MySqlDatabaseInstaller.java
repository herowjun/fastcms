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
package com.fastcms.web.install;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MySQL 安装器实现
 */
@Component
public class MySqlDatabaseInstaller implements DatabaseInstaller {

    public static final String TYPE = "mysql";

    private static final String DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";

    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDisplayName() {
        return "MySQL";
    }

    @Override
    public int getDefaultPort() {
        return 3306;
    }

    @Override
    public String getScriptLocation() {
        return "/install/sql/mysql/fastcms.sql";
    }

    @Override
    public void testConnection(InstallRequest.DbConfig request) throws InstallException {
        validateDbConfig(request);
        try {
            DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
            try (Connection ignored = openServerConnection(request)) {
                // 能建立连接即视为账号可用
            }
        } catch (SQLException e) {
            throw new InstallException("数据库连接失败，请检查地址、端口、账号或密码", e);
        }
    }

    @Override
    public void createDatabase(InstallRequest.DbConfig request) throws InstallException {
        validateDbConfig(request);
        try {
            DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
            try (Connection connection = openServerConnection(request);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + request.getDatabase()
                        + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
        } catch (SQLException e) {
            throw new InstallException("创建数据库失败，请确认该账号具有建库权限", e);
        }
    }

    @Override
    public String buildJdbcUrl(InstallRequest.DbConfig request) {
        return "jdbc:mysql://" + request.getHost() + ":" + request.getPort() + "/" + request.getDatabase()
                + "?autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai";
    }

    @Override
    public String getDriverClassName() {
        return DRIVER_CLASS_NAME;
    }

    @Override
    public boolean isDatabaseInstalled(InstallRequest.DbConfig request) throws InstallException {
        String url = buildJdbcUrl(request);
        try {
            DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
            try (Connection connection = DriverManager.getConnection(url, request.getUsername(), request.getPassword());
                 Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM fastcms_install")) {
                    rs.next();
                    return rs.getInt(1) > 0;
                } catch (SQLException e) {
                    // 表不存在说明未完成安装
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new InstallException("无法访问目标数据库", e);
        }
    }

    /**
     * 连接 MySQL 服务器实例（不指定具体库），用于建库与权限探测
     */
    private Connection openServerConnection(InstallRequest.DbConfig request) throws SQLException {
        String url = "jdbc:mysql://" + request.getHost() + ":" + request.getPort()
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=" + (CONNECT_TIMEOUT_SECONDS * 1000)
                + "&socketTimeout=" + (CONNECT_TIMEOUT_SECONDS * 1000);
        return DriverManager.getConnection(url, request.getUsername(), request.getPassword());
    }

    private void validateDbConfig(InstallRequest.DbConfig request) throws InstallException {
        if (request == null || StringUtils.isBlank(request.getHost())) {
            throw new InstallException("数据库地址不能为空");
        }
        if (request.getPort() == null || request.getPort() <= 0) {
            throw new InstallException("数据库端口无效");
        }
        if (StringUtils.isBlank(request.getUsername())) {
            throw new InstallException("数据库账号不能为空");
        }
        if (StringUtils.isBlank(request.getDatabase())) {
            throw new InstallException("数据库名不能为空");
        }
        // 防止库名被注入建库语句
        if (!request.getDatabase().matches("[A-Za-z0-9_\\-]+")) {
            throw new InstallException("数据库名仅支持字母、数字、下划线和横线");
        }
    }

}
