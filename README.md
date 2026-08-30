# FastCMS

![JDK](https://img.shields.io/badge/JDK-21+-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1+-green)
![Vue](https://img.shields.io/badge/Vue-3-42b883)
![License](https://img.shields.io/badge/License-LGPL--3.0-blue)
![Gitee Stars](https://gitee.com/xjd2020/fastcms/badge/star.svg)

> 插件化、AI 原生的 Java 内容管理系统 —— 对话即建站，装插件即扩展

![FastCMS](./doc/images/fastcms.png)

## ✨ 特性

- 🤖 **AI 原生**：对话式生成整套站点模板（FreeMarker）、AI 润色与改写文章、标题/摘要/SEO 智能生成，全程可预览、可回退
- 🧩 **插件化**：基于 PF4J 的热插拔，插件在线安装、卸载，无需重启服务器，装完即用
- 🚀 **全新技术栈**：JDK 21 + Spring Boot 4.1 + Spring Security 7（OAuth2 授权服务器内置）+ MyBatis-Plus，同类 Java CMS 中的最新基线
- 🖥️ **完整 CMS**：文章 / 分类 / 标签 / 单页 / 菜单 / 模板在线编辑，FreeMarker 模板 + 页面静态化
- 🌐 **多站点 / 多语言**：一套系统托管多个站点，国际化开箱即用
- 💰 **商业能力**：支付、微信生态（公众号 / 小程序）多账号管理

## 🚀 快速开始

### 方式一：源码运行

```bash
# 1. 初始化数据库（MySQL 5.7+）
mysql -u root -p fastcms < doc/sql/fastcms.sql

# 2. 修改数据库连接（默认连接本地 MySQL:3308/fastcms）
#    web/src/main/resources/application-dev.yml

# 3. 启动
cd web && mvn spring-boot:run
```

启动后：

| 入口 | 地址 |
|---|---|
| 网站前台 | http://localhost:8080 |
| 管理后台 | http://localhost:8080/fastcms |
| 初始账号 | admin / 1 |

### 方式二：打包运行

```bash
mvn clean install -Dmaven.test.skip=true
java -jar .dist/fastcms-web-*-exec.jar
```

> 环境要求：JDK 21+、Maven 3.6+、MySQL 5.7+

### 体验插件热插拔

```bash
# 使用 deploytest profile 启动后，插件 jar 会被真实加载（dev 模式只扫描目录）
cd web && mvn spring-boot:run -Dspring-boot.run.profiles=deploytest
```

## 🏗️ 架构

```
fastcms
├── common      基础工具、通用模型
├── core        核心抽象（Site / Template / 静态化服务）
├── service     用户、角色、权限、订单、配置
├── cms         文章、分类、标签、单页、菜单领域模型
├── starters    plugin / mybatis / oauth2 / payment / wechat / lucene / email / ai
├── web         启动入口、Security 配置、管理后台 API
├── templates   FreeMarker 站点模板
├── plugins     插件工程（hello-world-plugin 为参考实现）
└── ui          Vue 3 管理后台源码
```

**核心机制**：插件通过 `ControllerRegister` / `MyBatisMapperRegister` / `FreeMarkerViewRegister` 等注册器动态注册到主应用——新增一个插件 jar，即可为系统注入 Controller、Mapper、模板指令和前端页面，主工程零改动。

## 🧩 插件市场

十余个官方插件持续上架中，覆盖：

- 内容增强（评论、搜索、表单）
- 微信生态（公众号、小程序）
- 商业化（支付、会员）
- AI 能力（智能问答、内容生成）

插件市场随官网一并开放，敬请期待。

## 🛠️ 开发指南

### 二次开发

```bash
# 单模块编译（含依赖模块）
mvn -pl starters/plugin-starter -am compile -DskipTests

# 修改 starters/* 后需 install 到本地仓库，web 模块运行才生效
mvn -pl starters/plugin-starter install -DskipTests
```

### 编写插件

参考 [plugins/hello-world-plugin](./plugins/hello-world-plugin)，一个最小的插件只需：

1. 继承 `Plugin` 启动类
2. 用注册器声明要注入的 Controller / Mapper / 模板
3. `@PassFastcms` 注解可让指定接口免认证放行

### 后端技术

| 技术 | 说明 |
|---|---|
| Spring Boot 4.1 | 底层框架 |
| Spring Security 7 + OAuth2 | JWT 登录验证、权限控制、授权服务器 |
| MyBatis-Plus 3.5 | 数据访问，责任链 + 访问者模式实现数据权限 |
| PF4J 3.13 | 插件框架，jar / zip 动态热插拔 |
| FreeMarker | 模板渲染 + 页面静态化 |

### 前端技术

| 技术 | 版本要求 |
|---|---|
| Node | >= 18.16.0 |
| Vue 3 + TypeScript | TS >= 5.0.4 |
| Element Plus | >= 2.3.3 |
| Vite | >= 3.2.47 |

## 📚 文档与社区

- 📖 [文档中心](http://doc.xiaojudeng.net.cn)
- 🌐 [官方网站](http://fastcms.xiaojudeng.net.cn)
- 📺 [视频教程（B 站）](https://www.bilibili.com/video/BV12G4y167vi/)

### FastCMS 小程序

![FastCMS 小程序](./doc/images/fastcms.jpg)

## 🙏 鸣谢

- [vue-next-admin](https://gitee.com/lyt-top/vue-next-admin)
- [mybatis-plus](https://gitee.com/baomidou/mybatis-plus)

## ⭐ 支持作者

如果觉得框架不错，或者已经在使用了，希望你可以去
[Github](https://github.com/my-fastcms/fastcms) 或者 [Gitee](https://gitee.com/xjd2020/fastcms)
帮我点个 ⭐ Star，这将是对我极大的鼓励与支持。

## 💬 沟通交流

加微信请备注 **fastcms**

![微信](./doc/images/wechat.jpg)

---

License: LGPL-3.0
