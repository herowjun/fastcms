# 05 · 数据库 Schema

> 权威来源：`doc/sql/fastcms.sql`（**37 张表**）。另有历史版本 `fastcms-0.0.2.sql` … `fastcms-0.2.0.sql` 供升级参考。

## 1. 表清单（按域分组）

### 认证与 RBAC
| 表 | 说明 | 实体 |
| --- | --- | --- |
| `user` | 用户 | `User` |
| `role` | 角色 | `Role` |
| `user_role` | 用户-角色关联 | — |
| `resource` | 资源（权限点） | `Resource` |
| `role_resource` | 角色-资源关联 | — |
| `permission` | 权限 | `Permission` |
| `role_permission` | 角色-权限关联 | — |
| `menu` | 菜单（前端路由） | `Menu` |
| `department` | 部门（数据权限依据） | `Department` |
| `department_user` | 部门-用户关联 | — |
| `user_openid` | 用户-微信 openid | `UserOpenid` |
| `user_server_openid` | 用户-服务端 openid（小程序） | `UserServerOpenid` |
| `user_tag` | 用户标签 | `UserTag` |
| `user_tag_relation` | 用户-标签关联 | — |

### 内容管理（CMS）
| 表 | 说明 | 实体 |
| --- | --- | --- |
| `article` | 文章 | `Article` |
| `article_category` | 文章分类 | `ArticleCategory` |
| `article_category_relation` | 文章-分类关联 | — |
| `article_tag` | 文章标签 | `ArticleTag` |
| `article_tag_relation` | 文章-标签关联 | — |
| `article_comment` | 文章评论 | `ArticleComment` |
| `article_zan` | 文章点赞 | `ArticleZan` |
| `single_page` | 单页 | `SinglePage` |
| `single_page_comment` | 单页评论 | `SinglePageComment` |
| `attachment` | 附件 | `Attachment` |

### 订单 / 支付 / 资金
| 表 | 说明 | 实体 |
| --- | --- | --- |
| `order` | 订单 | `Order` |
| `order_item` | 订单项 | `OrderItem` |
| `order_invoice` | 订单发票 | `OrderInvoice` |
| `payment_record` | 支付记录 | `PaymentRecord` |
| `user_amount` | 用户余额 | `UserAmount` |
| `user_amount_payout` | 余额提现 | `UserAmountPayout` |
| `user_amount_statement` | 余额流水 | `UserAmountStatement` |

### 系统
| 表 | 说明 | 实体 |
| --- | --- | --- |
| `config` | 系统配置（key-value） | `Config` |

### AI（2026 新增）
| 表 | 说明 | 实体 |
| --- | --- | --- |
| `ai_model_config` | AI 模型配置（动态构建 ChatModel） | `AiModelConfig` |
| `ai_template_file` | AI 模板文件 | `AiTemplateFile` |
| `ai_template_file_backup` | AI 模板备份 | `AiTemplateFileBackup` |
| `ai_template_message` | AI 模板消息 | `AiTemplateMessage` |
| `ai_template_session` | AI 模板会话 | `AiTemplateSession` |
| `ai_usage_log` | AI 用量日志 | `AiUsageLog` |
| `ai_article_op_log` | AI 文章操作日志 | `AiArticleOpLog` |

## 2. 关系图（核心）

```
user ──< user_role >── role
role ──< role_resource >── resource
role ──< role_permission >── permission
department ──< department_user >── user

user ──< user_openid            (微信公众号)
user ──< user_server_openid     (小程序)
user ──< user_tag_relation >── user_tag
user ──< user_amount ──< user_amount_payout
                     └──< user_amount_statement

article ──< article_category_relation >── article_category
article ──< article_tag_relation >── article_tag
article ──< article_comment
article ──< article_zan

order ──< order_item
order ──  order_invoice
payment_record (订单支付)
```

## 3. 数据权限要点

- `FastcmsDataPermissionInterceptor` 依据当前用户的**部门 / 角色**自动给 `SELECT` 追加 WHERE 条件（行级隔离）。
- 可被两类配置豁免：
  - `fastcms.mybatis.ignore.mappedStatementIds`（`application.yml`）
  - 方法上的 `@IgnoreDataPermission` 注解
- 典型豁免：`selectById`、`getArticleById`、`pageArticleZan`、`ArticleCategoryMapper.selectList`。

## 4. 初始化与迁移

- 首次建库：导入 `doc/sql/fastcms.sql`。
- 版本升级：按 `doc/sql/fastcms-0.x.x.sql` 增量脚本依次执行。
- 默认数据源（`application.yml`）：MySQL `localhost:3308/fastcms`，驱动 `com.mysql.cj.jdbc.Driver`。
