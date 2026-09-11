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

import com.fastcms.common.utils.FastcmsInstallState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

/**
 * 数据源引导配置：替换 Spring Boot 自动配置的数据源，支持"未安装模式"启动。
 * <p>
 * 数据源判定顺序：
 * <ol>
 *     <li>外部安装配置 fastcms.db.*（~/fastcms/config/application.yml，安装向导生成）→ 真实数据源</li>
 *     <li>传统 spring.datasource.*（jar 内 application.yml，兼容手工配置的老部署）→ 测试连接，可用则真实数据源</li>
 *     <li>以上皆无或连接失败 → 哑数据源（进入安装模式，由 InstallGuardFilter 引导至安装向导）</li>
 * </ol>
 */
@Configuration
public class FastcmsDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(FastcmsDataSourceConfig.class);

    private static final int LEGACY_PROBE_TIMEOUT_SECONDS = 5;

    /**
     * 未安装模式下的哑数据源：任何获取连接的调用都抛出统一异常。
     * MyBatis 启动期（SqlSessionFactory 构建）不会获取连接，因此未安装状态应用可正常启动。
     */
    static class UninstalledDataSource extends AbstractDataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("系统尚未安装，请先完成安装向导（/install）");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

    }

    @Bean
    public DataSource dataSource(Environment environment) {
        Map<String, String> dbConfig = Binder.get(environment)
                .bind("fastcms.db", Bindable.mapOf(String.class, String.class))
                .orElse(null);

        // 1. 外部安装配置优先（安装向导写入）
        if (dbConfig != null && StringUtils.isNotBlank(dbConfig.get("host")) && StringUtils.isNotBlank(dbConfig.get("database"))) {
            String url = buildExternalJdbcUrl(dbConfig);
            FastcmsInstallState.setInstallMode(false);
            log.info("使用外部安装配置数据源: {}", dbConfig.get("host") + ":" + dbConfig.get("port") + "/" + dbConfig.get("database"));
            return buildHikari(url, dbConfig.get("username"), dbConfig.get("password"));
        }

        // 2. 兼容传统 spring.datasource.* 配置（jar 内 application.yml）
        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        if (StringUtils.isNotBlank(url) && legacyDataSourceReachable(url, username, password)) {
            FastcmsInstallState.setInstallMode(false);
            String driver = environment.getProperty("spring.datasource.driver-class-name");
            return buildHikari(url, username, password, driver);
        }

        // 3. 未安装模式
        FastcmsInstallState.setInstallMode(true);
        log.info("未检测到可用的数据库配置，进入安装模式，请访问 /install 完成安装");
        return new UninstalledDataSource();
    }

    private boolean legacyDataSourceReachable(String url, String username, String password) {
        try {
            DriverManager.setLoginTimeout(LEGACY_PROBE_TIMEOUT_SECONDS);
            try (Connection ignored = DriverManager.getConnection(url, username, password)) {
                log.info("检测到传统数据源配置（spring.datasource.*），连接测试通过");
                return true;
            }
        } catch (Exception e) {
            log.warn("检测到传统数据源配置（spring.datasource.*）但连接失败，将进入安装模式: {}", e.getMessage());
            return false;
        }
    }

    private String buildExternalJdbcUrl(Map<String, String> dbConfig) {
        return "jdbc:mysql://" + dbConfig.get("host") + ":" + dbConfig.get("port") + "/" + dbConfig.get("database")
                + "?autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai";
    }

    private DataSource buildHikari(String url, String username, String password) {
        return buildHikari(url, username, password, null);
    }

    private DataSource buildHikari(String url, String username, String password, String driverClassName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        if (StringUtils.isNotBlank(driverClassName)) {
            config.setDriverClassName(driverClassName);
        }
        config.setPoolName("fastcms-pool");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        return new HikariDataSource(config);
    }

}
