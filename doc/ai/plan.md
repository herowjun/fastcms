# FastCMS AI 集成方案与功能拆分

> 基线：Spring Boot 4.1.0 + JDK 21 + Spring AI 2.0.1 + PF4J 3.13.0

## 一、整体架构：三层分层

FastCMS 已有清晰的「starter 基础设施 + plugin 业务扩展」分层，AI 能力沿用同一思路：

```
┌─────────────────────────────────────────────────────────────┐
│  场景化插件层（plugins/ai-xxx-plugin）                        │
│  文章智能润色 / 站点 AI 客服 / 智能搜索 / 营销文案生成 ...    │
└─────────────────────────────────────────────────────────────┘
                          ↑ 基于
┌─────────────────────────────────────────────────────────────┐
│  AI 扩展层（plugin-starter 中增加 AI 注册器）                 │
│  AiToolRegister / AiDirectiveRegister / AiControllerRegister  │
└─────────────────────────────────────────────────────────────┘
                          ↑ 基于
┌─────────────────────────────────────────────────────────────┐
│  AI 基础设施层（starters/ai-starter）                        │
│  ChatModel 工厂 / AiToolRegistry / 配置管理 / 多租户上下文    │
└─────────────────────────────────────────────────────────────┘
```

- **基础设施层**：所有 AI 能力的"底座"，与业务无关，提供通用能力（模型管理、工具注册、记忆、配额）。
- **扩展层**：让插件可以注册 AI 工具、指令、Controller，复用 fastcms 现有的 `ControllerRegister`/`MyBatisMapperRegister`/`FreeMarkerViewRegister` 模式。
- **场景化插件层**：具体的业务场景，每个插件解决一类问题。

## 二、AI 功能应用场景清单

基于 FastCMS 作为 CMS 的业务特点，AI 能落地的场景：

| 场景 | 描述 | 价值 |
|---|---|---|
| 文章智能润色 | 编辑器中选中文本，AI 帮助润色、扩写、缩写、纠错 | 提升内容生产效率 |
| 智能摘要生成 | 文章保存时自动生成 summary、seo_keywords、seo_description | SEO 优化 |
| 站点 AI 客服 | 前端浮动窗，结合 RAG 检索站点文章知识库回答访客 | 用户留存 |
| 智能搜索 | 用向量检索替代/补充现有的 Lucene 关键词搜索 | 提升搜索体验 |
| 营销文案生成 | 输入产品关键词，AI 生成多套营销文案供选择 | 营销提效 |
| 内容审核 | 文章/评论提交时 AI 自动检测违规、敏感内容 | 合规 |
| 多语言翻译 | 文章一键翻译为多语言版本，写入 `language` 字段 | 国际化 |
| 图片描述/ALT 生成 | 上传图片时 AI 生成 alt 文本、文件名 | 无障碍/SEO |
| 模板代码生成 | 输入需求，AI 生成 FreeMarker 模板片段 | 降低模板开发门槛 |
| 智能标签推荐 | 文章输入标题和正文，AI 推荐合适的标签 | 内容组织 |
| 后台 AI 助手 | 后台侧边栏对话，可调用站点管理工具（查文章、改配置） | 管理提效 |

## 三、功能拆分表：主工程 vs 插件

### 判断原则

| 维度 | 适合主工程（ai-starter） | 适合插件 |
|---|---|---|
| 通用性 | 所有站点/插件都会用到 | 仅特定场景需要 |
| 复用性 | 被多个插件依赖 | 独立闭环 |
| 变更频率 | 稳定，少改动 | 经常迭代 |
| 配置依赖 | 复用 fastcms 全局配置 | 插件独立配置 |
| 数据模型 | 复用 fastcms 核心表 | 独立表 |

### 拆分明细

| 功能 | 实现位置 | 理由 |
|---|---|---|
| **ChatModel 动态工厂** | ai-starter | 所有 AI 调用的入口，根据 `ai_model_config` 表激活记录构造 OpenAiChatModel，统一管理连接、重试、超时 |
| **模型配置管理（CRUD + 激活 + 测试）** | ai-starter + web | 后台「AI → 模型管理」菜单，`AiModelConfigController` + `IAiModelConfigService`，多模型切换 |
| **AiTool 注解 + 注册中心** | ai-starter | `@AiTool` 标注方法，`AiToolRegistry` 维护工具元数据，`AiToolRegister` 扫描容器内 bean 自动注册。所有插件都依赖此机制 |
| **AiToolRegister（插件扩展点）** | plugin-starter | 让插件能把自己的 `@AiTool` 方法注册到主工程的 `AiToolRegistry`，遵循现有 `ControllerRegister` 模式 |
| **ChatClient 基础 Bean** | ai-starter | 封装 `ChatClient.builder().defaultSystem(...).defaultTools(...)`，插件直接注入使用 |
| **对话记忆（ChatMemory）** | ai-starter | 提供 `MessageChatMemoryAdvisor`，支持按 sessionId/userId 隔离，窗口大小可配 |
| **多租户/多站点上下文** | ai-starter | Advisor 链中注入当前 siteId/userId，保证 AI 调用的数据隔离 |
| **配额与审计** | ai-starter | `dailyTokenQuota`、`auditEnabled` 配置项，记录每次调用的 token 消耗，防止滥用 |
| **MCP Server 集成** | ai-starter | 启用 `spring-ai-starter-mcp-server-webmvc`，让外部 AI 客户端能调用 fastcms 暴露的工具 |
| **配置属性类（FastcmsAiProperties）** | ai-starter | `fastcms.ai.*` 系列配置：enabled、defaultSystemPrompt、chatMemoryWindow、dailyTokenQuota、auditEnabled |
| **文章智能润色** | plugins/ai-article-polish-plugin | 业务场景：编辑器集成按钮，调用 ChatClient 改写文本。依赖 ai-starter，独立前端入口 |
| **智能摘要生成** | plugins/ai-article-summary-plugin | 监听文章保存事件，自动生成 summary/seo 字段。也可做成 ai-starter 的可选 Advisor，但做成插件更灵活 |
| **站点 AI 客服** | plugins/ai-chatbot-plugin | 前端浮动窗组件 + 后端 SSE 流式接口 + RAG 知识库（向量库可选）。独立完整业务 |
| **智能搜索（向量检索）** | plugins/ai-vector-search-plugin | 替代/补充 Lucene，需要向量库（Milvus/PgVector），插件自带 Schema 和索引任务 |
| **营销文案生成** | plugins/ai-marketing-plugin | 纯业务，输入关键词生成文案，独立页面 |
| **内容审核** | plugins/ai-moderation-plugin | 监听文章/评论提交事件，调用 AI 检测，违规则拦截或标记 |
| **多语言翻译** | plugins/ai-translate-plugin | 调用 AI 翻译文章，写入 `language` 字段，独立操作按钮 |
| **图片 ALT 生成** | plugins/ai-image-alt-plugin | 监听附件上传事件，调用多模态模型生成描述 |
| **模板代码生成** | plugins/ai-template-gen-plugin | 后台独立页面，AI 生成 FreeMarker 片段 |
| **智能标签推荐** | plugins/ai-tag-recommend-plugin | 文章编辑页按钮，AI 分析正文推荐标签 |
| **后台 AI 助手** | plugins/ai-admin-assistant-plugin | 后台侧边栏对话组件，注册一组管理工具（查文章数、改配置、生成报表） |

## 四、ai-starter 已完成清单

> 第一阶段已完成的基础设施，对应本次提交的代码。

| 模块 | 文件 | 说明 |
|---|---|---|
| 依赖管理 | `pom.xml`（根）| 引入 `spring-ai-bom 2.0.1` |
| Starter 骨架 | `starters/ai-starter/pom.xml` | 依赖 `spring-ai-starter-model-openai`、`spring-ai-starter-mcp-server-webmvc`、`fastcms-core`、`fastcms-service` |
| 配置属性 | `FastcmsAiProperties.java` | `fastcms.ai.*` 配置：enabled、defaultSystemPrompt、chatMemoryWindow、dailyTokenQuota、auditEnabled |
| 自动配置 | `FastcmsAiAutoConfiguration.java` | 条件装配，打印启动日志 |
| 工具注解 | `AiTool.java` | 标注可被 AI 调用的方法，含 name、description、returnDirect |
| 工具注册中心 | `AiToolRegistry.java` | 维护 `Map<String, ToolDescriptor>`，支持 register/unregister |
| 工具扫描器 | `AiToolRegister.java` | `SmartInitializingSingleton` 钩子，容器就绪后扫描所有 bean 的 `@AiTool` 方法 |
| 模型配置实体 | `AiModelConfig.java`（service） | 多模型配置实体，含 provider/baseUrl/apiKey/model/temperature/maxTokens/isActive |
| 模型配置 Mapper | `AiModelConfigMapper.java`（service） | MyBatis-Plus BaseMapper |
| 模型配置 Service | `IAiModelConfigService.java` + `AiModelConfigServiceImpl.java`（ai-starter） | CRUD + 激活 + 测试连接 + `buildChatModel()` 动态构造 OpenAiChatModel |
| 后台 Controller | `AiModelConfigController.java`（web） | `/admin/ai/model/*`：list/get/save/delete/activate/test，含 `@Secured` 权限 |
| SQL 初始化 | `doc/sql/fastcms.sql` 末尾 0.2.0 块 | `ai_model_config` 建表 + `permission` 菜单（AI → 模型管理） |
| 前端 API | `ui/src/api/ai/index.ts` | 7 个接口封装 |
| 前端页面 | `ui/src/views/ai/model/index.vue` | 列表/新增/编辑/测试/激活/删除，供应商预设端点 |
| 前端 i18n | `ui/src/i18n/lang/{zh-cn,en,zh-tw}.ts` | 新增 `ai`、`aiModel` 路由标题 |
| 权限资源 | `IResourceService.java`（service） | 新增 5 个 `RESOURCE_NAME_AI_MODEL_*` 常量 |

## 五、下一步规划

### 5.1 ai-starter 待补齐

- [ ] **ChatClient 基础 Bean**：基于激活的模型配置构造 ChatClient，注入默认 system prompt 和 tools
- [ ] **ChatMemory 接入**：`MessageChatMemoryAdvisor`，支持 sessionId/userId 隔离
- [ ] **多租户 Advisor**：注入 siteId/userId 上下文
- [ ] **配额与审计**：每次调用记录 token 消耗，超限拒绝
- [ ] **配置变更监听**：模型激活切换后，失效缓存的 ChatModel 实例

### 5.2 plugin-starter 扩展

- [ ] **AiToolRegister**：让插件 `@AiTool` 方法自动注册到主工程
- [ ] **AiDirectiveRegister**：让插件注册 FreeMarker AI 指令（如 `<@aiChat />`）
- [ ] **AiControllerRegister**：让插件注册 AI 相关 Controller

### 5.3 参考插件实现

- [ ] **plugins/ai-hello-plugin**：最小参考实现，注册一个 `@AiTool` 工具，演示插件如何扩展 AI 能力
- [ ] **plugins/ai-article-polish-plugin**：文章润色插件，演示与编辑器集成
- [ ] **plugins/ai-chatbot-plugin**：站点客服插件，演示 SSE 流式 + RAG

## 六、模块依赖关系

```
starters/ai-starter  ──依赖──>  fastcms-service, fastcms-core
        │
        │ 提供 IAiModelConfigService, AiToolRegistry, @AiTool
        ↓
starters/plugin-starter  ──依赖──>  starters/ai-starter (待加 AiToolRegister)
        │
        │ 提供 AiToolRegister, AiDirectiveRegister
        ↓
plugins/ai-xxx-plugin  ──依赖──>  starters/plugin-starter, starters/ai-starter
        │
        │ 注册 @AiTool 方法 / Controller / 指令
        ↓
web (运行时加载插件)
```

**关键约束**：
- `fastcms-service` 不能反向依赖 `ai-starter`（会形成循环依赖，已踩过坑）
- 实体/Mapper 放 service 模块，Service 实现/接口放 ai-starter 模块
- `web` 模块依赖 `ai-starter`，Controller 可直接注入 `IAiModelConfigService`

## 七、配置示例

### 7.1 application.yml（可选，默认配置即可启动）

```yaml
fastcms:
  ai:
    enabled: true                          # 总开关
    register-directive: true              # 是否注册 FreeMarker AI 指令
    default-system-prompt: "你是 fastcms 站点的 AI 助手，请用中文回答用户问题。"
    chat-memory-window: 10                # 对话记忆窗口
    daily-token-quota: 0                  # 0=不限
    audit-enabled: true                   # 是否审计 AI 调用
```

### 7.2 模型配置（后台动态配置）

不写在 yml 中，通过后台「AI → 模型管理」页面配置，支持多模型切换：

| 供应商 | base-url | model | 备注 |
|---|---|---|---|
| DeepSeek | https://api.deepseek.com | deepseek-chat | 性价比高 |
| 通义千问 | https://dashscope.aliyuncs.com/compatible-mode/v1 | qwen-plus | 阿里云 |
| 智谱 GLM | https://open.bigmodel.cn/api/paas/v4 | glm-4 | 清华系 |
| Moonshot | https://api.moonshot.cn/v1 | moonshot-v1-8k | Kimi |
| OpenAI | https://api.openai.com | gpt-4o-mini | 官方 |
| Ollama | http://localhost:11434 | llama3.1 | 本地部署 |

所有模型均通过 OpenAI 兼容协议接入，运行时通过 `IAiModelConfigService.getActiveConfig()` 获取当前激活配置，调用 `AiModelConfigServiceImpl.buildChatModel(config)` 构造 `OpenAiChatModel` 实例。
