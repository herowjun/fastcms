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

import java.io.Serializable;

/**
 * 安装向导提交的完整安装请求：数据库连接信息 + 管理员账号
 */
public class InstallRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 数据库类型（DatabaseInstaller#getType）
     */
    private String dbType;

    private DbConfig db = new DbConfig();

    private AdminConfig admin = new AdminConfig();

    public static class DbConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        private String host;

        private Integer port;

        private String database;

        private String username;

        private String password;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

    }

    public static class AdminConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 管理员登录账号（替换 SQL 脚本中的默认 admin）
         */
        private String username;

        /**
         * 管理员密码（BCrypt 加密入库）
         */
        private String password;

        /**
         * 确认密码
         */
        private String confirmPassword;

        private String email;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConfirmPassword() {
            return confirmPassword;
        }

        public void setConfirmPassword(String confirmPassword) {
            this.confirmPassword = confirmPassword;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public DbConfig getDb() {
        return db;
    }

    public void setDb(DbConfig db) {
        this.db = db;
    }

    public AdminConfig getAdmin() {
        return admin;
    }

    public void setAdmin(AdminConfig admin) {
        this.admin = admin;
    }

}
