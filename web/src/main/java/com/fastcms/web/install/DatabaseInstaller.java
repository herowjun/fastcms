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

/**
 * 数据库安装器 SPI：安装流水线中与数据库方言相关的所有环节。
 * <p>
 * 新增数据库支持（PostgreSQL、人大金仓、Oracle 等）只需实现本接口并注册为 Spring Bean，
 * 安装向导前端通过 GET /fastcms/api/install/databases 动态发现可用数据库类型，
 * 核心流水线（InstallService）无需任何改动。
 * <p>
 * 各数据库的差异集中在：
 * <ul>
 *     <li>驱动类名与 URL 模板（向导表单据此渲染）</li>
 *     <li>建库语句（MySQL: CREATE DATABASE IF NOT EXISTS；PG: 先查后建；Oracle: 用户即 schema）</li>
 *     <li>初始化脚本位置（classpath:install/sql/{dbType}/fastcms.sql）</li>
 *     <li>生成外部数据源配置（driver、url、连接池参数）</li>
 *     <li>MyBatis-Plus 分页方言枚举</li>
 * </ul>
 */
public interface DatabaseInstaller {

    /**
     * 数据库类型标识（唯一，向导提交时的 type 值）
     */
    String getType();

    /**
     * 向导展示名称
     */
    String getDisplayName();

    /**
     * 默认端口（向导表单默认值）
     */
    int getDefaultPort();

    /**
     * 初始化 SQL 脚本的 classpath 位置
     */
    String getScriptLocation();

    /**
     * 测试数据库连接（不建库）
     *
     * @param request 含 host/port/username/password 的连接信息
     * @throws InstallException 连接失败时抛出（message 面向用户，已脱敏）
     */
    void testConnection(InstallRequest.DbConfig request) throws InstallException;

    /**
     * 创建数据库（不存在则创建，幂等）
     *
     * @throws InstallException 创建失败时抛出
     */
    void createDatabase(InstallRequest.DbConfig request) throws InstallException;

    /**
     * 建库后目标库的 JDBC URL（安装执行脚本、写入外部配置均使用此 URL）
     */
    String buildJdbcUrl(InstallRequest.DbConfig request);

    /**
     * JDBC 驱动类名
     */
    String getDriverClassName();

    /**
     * 校验目标库是否已完成安装（存在安装锁记录）
     */
    boolean isDatabaseInstalled(InstallRequest.DbConfig request) throws InstallException;

}
