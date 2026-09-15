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
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 安装流水线：测试连接 → 建库 → 执行全量SQL → 创建管理员 → 写外部配置 → 落安装锁
 * <p>
 * 安全设计：
 * <ul>
 *     <li>仅未安装模式（哑数据源启动）下可用，防止已安装系统被重放安装</li>
 *     <li>AtomicBoolean 防并发重复提交</li>
 *     <li>目标库已有安装锁记录时进入"修复配置"流程：必须校验原管理员密码，防止配置文件丢失后被劫持指向恶意库</li>
 * </ul>
 */
@Service
public class InstallService {

    private static final Logger log = LoggerFactory.getLogger(InstallService.class);

    private static final int ADMIN_PASSWORD_MIN_LENGTH = 8;

    @Autowired
    private List<DatabaseInstaller> databaseInstallers;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final AtomicBoolean installing = new AtomicBoolean(false);

    /**
     * 支持的数据库类型清单（向导动态发现）
     */
    public List<Map<String, Object>> getSupportedDatabases() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DatabaseInstaller installer : databaseInstallers) {
            result.add(Map.of(
                    "type", installer.getType(),
                    "name", installer.getDisplayName(),
                    "defaultPort", installer.getDefaultPort()
            ));
        }
        return result;
    }

    /**
     * 测试数据库连接（向导第二步实时反馈）
     */
    public void testConnection(InstallRequest request) throws InstallException {
        getInstaller(request).testConnection(request.getDb());
    }

    /**
     * 执行安装流水线
     *
     * @return 安装结果提示信息
     */
    public synchronized String execute(InstallRequest request) throws InstallException {
        if (!FastcmsInstallState.isInstallMode()) {
            throw new InstallException("系统已安装，如需重新安装请先清空数据库并删除外部配置文件");
        }
        if (!installing.compareAndSet(false, true)) {
            throw new InstallException("安装正在进行中，请勿重复提交");
        }
        try {
            return doExecute(request);
        } finally {
            installing.set(false);
        }
    }

    private String doExecute(InstallRequest request) throws InstallException {
        DatabaseInstaller installer = getInstaller(request);
        InstallRequest.DbConfig db = request.getDb();

        installer.testConnection(db);
        installer.createDatabase(db);

        if (installer.isDatabaseInstalled(db)) {
            return repairConfig(installer, request);
        }

        executeScript(installer, db);
        createAdministrator(installer, request);
        writeExternalConfig(installer, db);

        log.info("fastcms 安装完成: {}:{}/{}", db.getHost(), db.getPort(), db.getDatabase());
        return "安装完成，请重启应用后使用管理员账号登录";
    }

    /**
     * 修复配置模式：目标库已存在安装数据（外部配置文件丢失场景），
     * 仅重写外部配置文件，不触碰任何数据；必须先用原管理员密码验证身份
     */
    private String repairConfig(DatabaseInstaller installer, InstallRequest request) throws InstallException {
        InstallRequest.AdminConfig admin = request.getAdmin();
        if (StringUtils.isBlank(admin.getUsername()) || StringUtils.isBlank(admin.getPassword())) {
            throw new InstallException("检测到该数据库已安装过程序。请输入原管理员账号与密码以修复配置；如需全新安装请清空数据库");
        }
        try (Connection connection = openTargetConnection(installer, request.getDb());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT password FROM `user` WHERE id = 1 AND user_name = ?")) {
            statement.setString(1, admin.getUsername());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !passwordEncoder.matches(admin.getPassword(), rs.getString("password"))) {
                    throw new InstallException("原管理员账号或密码不正确，拒绝修复配置");
                }
            }
        } catch (SQLException e) {
            throw new InstallException("校验原管理员信息失败", e);
        }
        writeExternalConfig(installer, request.getDb());
        log.info("fastcms 外部配置修复完成: {}:{}/{}", request.getDb().getHost(), request.getDb().getPort(), request.getDb().getDatabase());
        return "配置修复完成，请重启应用";
    }

    /**
     * 执行全量初始化脚本（过滤建库/use 语句，由安装器统一控制库的选择）
     */
    private void executeScript(DatabaseInstaller installer, InstallRequest.DbConfig db) throws InstallException {
        String script = readScript(installer.getScriptLocation());
        List<String> statements = splitStatements(script);
        try (Connection connection = openTargetConnection(installer, db);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new InstallException("初始化数据表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 用向导输入的管理员替换 SQL 脚本中的默认账号（保留 id=1 的角色与关联数据），密码 BCrypt 加密
     */
    private void createAdministrator(DatabaseInstaller installer, InstallRequest request) throws InstallException {
        InstallRequest.AdminConfig admin = request.getAdmin();
        if (StringUtils.isBlank(admin.getUsername())) {
            throw new InstallException("管理员账号不能为空");
        }
        if (admin.getUsername().length() < 3 || !admin.getUsername().matches("[A-Za-z0-9_\\-]+")) {
            throw new InstallException("管理员账号仅支持字母、数字、下划线和横线，且长度不小于3");
        }
        if (StringUtils.isBlank(admin.getPassword()) || admin.getPassword().length() < ADMIN_PASSWORD_MIN_LENGTH) {
            throw new InstallException("管理员密码长度不能少于" + ADMIN_PASSWORD_MIN_LENGTH + "位");
        }
        if (!admin.getPassword().equals(admin.getConfirmPassword())) {
            throw new InstallException("两次输入的管理员密码不一致");
        }
        try (Connection connection = openTargetConnection(installer, request.getDb());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE `user` SET user_name = ?, nick_name = ?, email = ?, password = ?, must_change_pwd = 0 WHERE id = 1")) {
            statement.setString(1, admin.getUsername());
            statement.setString(2, admin.getUsername());
            statement.setString(3, StringUtils.defaultString(admin.getEmail()));
            statement.setString(4, passwordEncoder.encode(admin.getPassword()));
            int rows = statement.executeUpdate();
            if (rows <= 0) {
                throw new InstallException("初始化管理员账号失败");
            }
        } catch (SQLException e) {
            throw new InstallException("初始化管理员账号失败", e);
        }
    }

    /**
     * 写外部配置文件 ~/fastcms/config/application.yml（数据源 + 随机 JWT 密钥），重启后由 additional-location 引入生效
     */
    private void writeExternalConfig(DatabaseInstaller installer, InstallRequest.DbConfig db) throws InstallException {
        try {
            Path file = FastcmsInstallState.getExternalConfigFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, buildExternalConfigYaml(db), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallException("写入外部配置文件失败，请检查应用对 " + FastcmsInstallState.getExternalConfigDir() + " 目录的写权限", e);
        }
    }

    private String buildExternalConfigYaml(InstallRequest.DbConfig db) {
        String secretKey = HexFormat.of().formatHex(generateSecretKey());
        return "# fastcms 安装配置（由安装向导生成）\n"
                + "# 修改数据库连接信息前请先停止应用，改完重启生效\n"
                + "fastcms:\n"
                + "  db:\n"
                + "    host: '" + escapeYaml(db.getHost()) + "'\n"
                + "    port: " + db.getPort() + "\n"
                + "    database: '" + escapeYaml(db.getDatabase()) + "'\n"
                + "    username: '" + escapeYaml(db.getUsername()) + "'\n"
                + "    password: '" + escapeYaml(db.getPassword()) + "'\n"
                + "  auth:\n"
                + "    token:\n"
                + "      secret-key: '" + secretKey + "'\n";
    }

    /**
     * 引号感知的 SQL 脚本切分：忽略引号内分号、行注释与块注释；
     * 过滤 drop database / create database / use 语句（建库与选库由安装器控制）
     */
    List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false, inDoubleQuote = false, inBacktick = false;
        char[] chars = script.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\'' && !inDoubleQuote && !inBacktick) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
            } else if (c == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
            } else if (c == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick;
                current.append(c);
            } else if (c == '\\' && (inSingleQuote || inDoubleQuote) && i + 1 < chars.length) {
                current.append(c).append(chars[++i]);
            } else if (!inSingleQuote && !inDoubleQuote && !inBacktick && c == '-' && i + 1 < chars.length && chars[i + 1] == '-') {
                // 行注释：丢弃至行尾
                while (i < chars.length && chars[i] != '\n') {
                    i++;
                }
            } else if (!inSingleQuote && !inDoubleQuote && !inBacktick && c == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                // 块注释：丢弃至 */
                i += 2;
                while (i + 1 < chars.length && !(chars[i] == '*' && chars[i + 1] == '/')) {
                    i++;
                }
                i++;
            } else if (!inSingleQuote && !inDoubleQuote && !inBacktick && c == ';') {
                addStatement(statements, current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addStatement(statements, current.toString());
        return statements;
    }

    private void addStatement(List<String> statements, String sql) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("drop database") || lower.startsWith("create database") || lower.startsWith("use ")) {
            return;
        }
        statements.add(trimmed);
    }

    private String readScript(String location) throws InstallException {
        try (InputStream inputStream = new ClassPathResource(location).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallException("读取初始化脚本失败：" + location, e);
        }
    }

    private Connection openTargetConnection(DatabaseInstaller installer, InstallRequest.DbConfig db) throws SQLException {
        DriverManager.setLoginTimeout(10);
        return DriverManager.getConnection(installer.buildJdbcUrl(db), db.getUsername(), db.getPassword());
    }

    private DatabaseInstaller getInstaller(InstallRequest request) throws InstallException {
        if (StringUtils.isBlank(request.getDbType())) {
            throw new InstallException("请选择数据库类型");
        }
        return databaseInstallers.stream()
                .filter(item -> item.getType().equals(request.getDbType()))
                .findFirst()
                .orElseThrow(() -> new InstallException("不支持的数据库类型：" + request.getDbType()));
    }

    private byte[] generateSecretKey() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private String escapeYaml(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

}
