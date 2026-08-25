# AGENTS.md

面向 AI 编码代理的项目指南。修改代码前请先阅读本文件。

## 项目概述

FastCMS 是基于 Spring Boot 的 Java CMS（内容管理系统），支持 PF4J 插件化扩展、FreeMarker 模板渲染、静态化页面生成、OAuth2 授权服务器、微信/支付集成。前端管理界面为 Vue 3（`ui/` 目录，构建产物已预编译到 `web/src/main/resources/static/`）。

## 技术栈基线（勿降级）

- Java 21 / Spring Boot 4.1.0（已从 3.2.5 完成全量升级）
- Spring Security 7.1.0（OAuth2 Authorization Server 已并入主项目，用 `http.oauth2AuthorizationServer()` DSL，不再使用 `applyDefaultSecurity()`）
- MyBatis-Plus 3.5.16（`mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser-4.9`）
- PF4J 3.13.0 插件系统
- Jackson 3（包名 `tools.jackson.*`，非 `com.fasterxml.*`）
- JSqlParser 4.9（`SubSelect`→`ParenthesedSelect`、`SubJoin`→`ParenthesedFromItem` 等 API 已变更）
- 编译要求 `JAVA_HOME` 指向 JDK 21

## 构建与运行

```bash
# 全量打包（测试已在 surefire 配置中默认 skip）
mvn clean install -Dmaven.test.skip=true

# 单模块编译（含依赖模块）
mvn -pl starters/plugin-starter -am compile -DskipTests

# 本地运行 web 模块（连接本地 MySQL:3308/fastcms）
cd web && mvn spring-boot:run

# 验证插件加载必须用 deploytest profile（dev 模式 PF4J 只扫描目录不加载 jar）
cd web && mvn spring-boot:run -Dspring-boot.run.profiles=deploytest
```

- Windows 打包脚本：`build.bat`（产物在 `.dist/`，可执行 jar 为 `fastcms-web-*-exec.jar`）
- 运行日志：`~/fastcms/logs/fastcms.log`（logback 控制台输出仅对 dev/prod profile 配置）
- 数据库初始化脚本：`doc/sql/fastcms.sql`

## 模块结构

| 模块 | 职责 |
|---|---|
| `common` | 基础工具、通用模型（RestResultUtils、TreeNode 等） |
| `core` | 核心抽象（Site、Template、静态化服务 `FakeStaticHtmlService`）、logback 配置 |
| `service` | 用户/角色/权限/订单/配置等实体与 Service，ehcache 缓存配置 |
| `cms` | 文章、分类、标签、单页、菜单等 CMS 领域模型与 Mapper |
| `starters/*` | 8 个 starter：plugin（PF4J）、mybatis（数据权限）、oauth2、payment、wechat、lucene、email |
| `web` | 启动入口（`com.fastcms.web.Fastcms`）、Security 配置、Controller、i18n、预编译前端资源 |
| `templates` | FreeMarker 站点模板（cms 模板资源） |
| `plugins/` | 插件工程（hello-world-plugin 为参考实现） |
| `ui/` | Vue 3 管理后台源码（修改后需自行构建并同步到 web/static） |
| `codegen` | 代码生成器 |

## 核心架构要点

### 插件系统（plugin-starter）
- 插件通过 `ControllerRegister`/`MyBatisMapperRegister`/`FreeMarkerViewRegister` 等注册器动态注册到主应用
- 带有 `@PassFastcms` 注解的插件 Controller 方法会获得免认证放行：`ControllerRegister.addPermitAllSecurityFilterChain()` 通过反射向 FilterChainProxy 顶部插入空过滤器链
- **Spring Security 7 关键点**：`springSecurityFilterChain` bean 是 `WebSecurityConfiguration$CompositeFilterChainProxy`，真实过滤器链在其私有字段 `springSecurityFilterChain` 指向的子代理中，必须先解包再修改（详见该类中 `unwrapCompositeFilterChainProxy()`）
- `FilterChainProxy.getFilterChains()` 返回不可变集合，禁止直接 `add()`

### 安全配置（web/security）
- `FastcmsAuthServerConfig`：OAuth2 授权服务器，`@Order` 最高优先级，用 `securityMatcher` 限定只处理 `/oauth2/**`、`/.well-known/**`、`/userinfo`
- `FastcmsAuthConfig`：主过滤链，必须以 `.anyRequest().permitAll()` 收尾，否则未配置路径会被意外要求认证
- 所有 `SecurityFilterChain` bean 必须显式声明 `@Order`
- 认证使用 JWT（jjwt），过滤器为 `JwtAuthTokenFilter`，Token 管理委托链为 `DelegatingTokenManager`

### URL 与静态化
- URL 使用语义化长路径（`/article/category/2`、`/page/pay`），禁止短路径（`/a/c/2`）
- 路径常量集中在 `FakeStaticHtmlService.java`
- URL 生成链路：`StaticPathHelper` → `FastcmsStaticHtmlManager` → `FakeStaticHtmlService`

## 硬性约束

1. 升级依赖时保持 Spring Boot 4.1.x + JDK 21 基线，不回退到 Boot 3.x API
2. `spring-boot-starter-aop` 在 Boot 4 中已更名为 `spring-boot-starter-aspectj`，禁止使用旧名
3. 自动配置注册必须使用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（`spring.factories` 已废弃，历史遗留文件仅部分模块清理中）
4. 修改 `starters/*` 后必须 `mvn install` 到本地仓库，`web` 模块运行才会生效（IDE 直接运行除外）
5. MyBatis-Plus 保持 3.5.16+（Boot 4 兼容版本），拦截器依赖需单独引 `mybatis-plus-jsqlparser-4.9`
6. 插件工程（`plugins/`）编译 JDK 版本必须为 21（`plugins/pom.xml` 中 `java.version`）

## 已知遗留问题

- `wechat-starter` 的 flatten 插件报 `Non-readable POM .flattened-pom.xml`（不影响主流程构建）
- `service/pom.xml` 存在 `commons-lang` 重复声明警告
- logback 配置中 `<level>` 元素写法已废弃（应改用 `level` 属性），日志启动时有警告
- 根 pom 的 `spring-boot-configuration-processor` 依赖未显式声明版本，依赖 BOM 解析

## 提交规范

- Git 仓库主分支托管于 gitee（xjd2020/fastcms），许可证 LGPL-3.0
- 提交前至少执行：`mvn -pl <改动模块> -am compile -DskipTests` 验证编译
- 涉及插件系统、Security 配置的改动，必须用 `deploytest` profile 启动并实际验证插件端点（如 `/fastcms/plugin/hello/say` 返回 200 且无需认证）
