# fastcms AI 思考场景全景与提效设计

| 项    | 内容                                                                              |
| ---- | ------------------------------------------------------------------------------- |
| 文档版本 | v1.0                                                                            |
| 日期   | 2026-10-26                                                                      |
| 关联文档 | `ai-design-reasoning-runaway-optimization.md`（推理失控优化设计，2026-09-13，未落地 M1 端点适配层） |
| 关联代码 | `starters/ai-starter/.../ai/`（template/design、template、article、agent、component） |
| 状态   | 设计评审                                                                            |
| 关联结论 | 2026-10-22 实测：端点默认思考档位为 `xhigh`（最高），fastcms 多数链路未传思考参数                          |



---

## 1. 结论速览

**Q：上下文膨胀（3 万字符规范 + 模板文件全文）能不能优化？**

能，但**优先级排第三**。实测成本模型显示：fastcms 的 AI 延迟 **90%+ 来自 decode（思考 + 输出 token）**，prefill（上下文）只占很小一块。压上下文的直接收益 ≈ 10 秒量级；压思考的收益 ≈ 分钟量级。

**真正该动的是三件事**：

1. **思考档位**——最大杠杆（10 倍级）。端点默认 `xhigh`，fastcms 9 个生产调用点里有 4 个没传任何思考参数。
2. **输出协议**——第二大杠杆。fastcms 现在的协议是"回写整个文件"，一个 20KB 的模板改一行也要重发 20KB。
3. **上下文**——第三。`focus` 聚焦注入已把 prefill 从 ~60k 降到 ~15k，边际收益有限；但上下文会**放大思考扇出**（实测 754 vs 237 思考字符），有间接价值。

---

## 2. 实测成本模型（端点 `203.176.93.143:30120`，Qwen3.6-27B）

### 2.1 思考档位（端点 400 报错原文）

```
Unexpected reasoning effort high. Supported types are xhigh (default), medium, and low.
```

→ 端点**默认档位 `xhigh`（最高思考）**；`reasoning_effort` 只认 `xhigh/medium/low`。

### 2.2 档位效果（同输入：7.7K 规范提示词 + 40KB 文件全文 + 一个简单问题）

| 发送参数                                           | 耗时       | 思考量      | 判定               |
| ---------------------------------------------- | -------- | -------- | ---------------- |
| 不传（端点默认 xhigh）                                 | 11.65s   | 754 字符   | 慢 3 倍            |
| `reasoning_effort=low`                         | 4.14s    | 237 字符   | fastcms 设计/管线轮现行 |
| `chat_template_kwargs:{enable_thinking:false}` | 4.65s    | **0 字符** | **唯一能真正关思考的方式**  |
| 顶层 `enable_thinking=false`                     | —        | 照产       | 端点静默忽略           |
| `/no_think` 软开关                                | 7.58s    | 542 字符   | 新版 Qwen 已失效      |
| `reasoning_effort=high`                        | HTTP 400 | —        | 端点不支持该枚举值        |

### 2.3 吞吐参数

| 指标         | 实测值                                    | 推导                        |
| ---------- | -------------------------------------- | ------------------------- |
| decode 吞吐  | **48 tok/s**（8000 token / 166.7s，思考关闭） | 思考 token 与正文 token **同价** |
| prefill 吞吐 | 3417 prompt token / 2.23s（含首字）         | 15K 上下文 ≈ 10s             |
| 前缀缓存       | 端点**不返回** `cached_tokens`，两次调用无差异      | **不可依赖**，别把方案押在这          |

**成本公式**：

```
T_total ≈ 0.8s + prompt_tokens / 1500 + (thinking_tokens + output_tokens) / 48
```

**验算**：代码注释记录的极端案例（16384 输出 / 6.4 分钟 / 正文零输出）→ 16384 ÷ 42 ≈ 390s，与实测 decode 吞吐同量级。**该案例的耗时 100% 是思考 token 造成的，与上下文大小无关。**

### 2.4 上下文对思考量的二阶影响

同输入对比（§2.2 首两行）：上下文相同，仅档位不同 → 思考量 754 → 237 字符（3.2 倍）。  
反向推论：**上下文越大，模型需要权衡的约束越多，思考扇出越大**。所以压上下文不是没价值，而是价值体现在"顺带压思考"。

---

## 3. fastcms AI 场景全景（生产调用点 9 处）

| #   | 场景                     | 调用点                                            | 思考档位现状         | 输出协议                    | 上下文                         |
| --- | ---------------------- | ---------------------------------------------- | -------------- | ----------------------- | --------------------------- |
| S1  | 站点结构分析（归纳 IA）          | `SiteAnalyzer:165`                             | **未设 → xhigh** | JSON                    | 导入页全文                       |
| S2  | 页面设计稿生成                | `MockupDesignService:498`                      | low            | 整页 HTML 设计稿             | 契约 + 方向资产 + 全站页清单           |
| S3  | 设计稿 → 组件映射             | `MockupConverter:957`                          | **未设 → xhigh** | JSON 数组                 | 设计稿区块摘要 + 组件库 manifest      |
| S4  | 组件化生成（PageSpec → 渲染）   | `ComponentGenPromptBuilder` → `callModelRound` | low            | **PageSpec JSON（几 KB）** | 组件菜单 + schema               |
| S5  | HTML 管线分批生成            | `AiTemplateGenServiceImpl:3713`                | low            | 规划/文件/分块多轮**全文回写**      | 分批注入                        |
| S6  | 模板调整 / 微调（单轮）          | 同上（单轮路径）                                       | low            | **整文件回写**               | focus 15k 或全量 + 历史 8k token |
| S7  | 渲染失败 / 规范违规修复          | 同上                                             | low            | 整文件回写                   | 错误 + 当前文件                   |
| S8  | 旧模板风格组件化升级             | `LegacyStyleUpgrader`                          | low            | 分批改造                    | 分批预算内                       |
| S9  | 文章生成 / 续写 / 字段候选       | `AiArticleGenServiceImpl:179/358/500`          | **未设 → xhigh** | 自由文本                    | 文章上下文                       |
| S10 | 通用对话（`@aiChat` / 能力问答） | `AgentChatExecutor:388`                        | **未设 → xhigh** | 自由文本                    | 会话历史                        |

> 另：`AiModelConfigServiceImpl:129` 为"测试连接"（30s 超时的一次性探活），不计。

**三个结构性判断**：

1. **S4 已经是范式**——"AI 只出结构 JSON，代码确定性渲染 HTML/CSS"，PageSpec 几 KB 对比整页 HTML 几万 token，这是全仓最省的形态。S5/S6/S7 还在用"AI 直写 HTML + 全文回写"。
2. **思考档位覆盖只有 3 处**（S2/S4/S5-8 走 `buildDesignOptions` / `buildPipelineOptions`），S1/S3/S9/S10 四个场景完全裸奔在 `xhigh`。
3. **输出量是第二瓶颈**：S5/S6/S7 要求模型回写整文件，改一行也要重发全站。

---

## 4. 分场景提效设计

### S1 站点结构分析（IA 归纳）

- **现状**：喂导入页全文 → 模型归纳菜单/栏目/页面 → JSON。只设了 temperature=0.2，思考档位没设（xhigh）。
- **问题**：归纳是**收敛型任务**（答案空间有限：菜单/页面清单），用 xhigh 全力思考纯属浪费；喂全文也超量（导航/链接在首屏之外价值极低）。
- **设计**：
  1. 思考档位 → `enable_thinking=false`（归纳不需要 CoT）或 `low`。
  2. 上下文裁剪：只喂 `<head>` + `<nav>` + 主内容区前 N 个链接 + 标题层级，剔除正文段落/图片/脚本（实测可砍 70%+ 上下文）。
  3. 输出收紧：JSON schema 强约束 + 枚举（页面后缀从白名单选），让模型只做"选择"不做"创作"。
- **预期**：11.65s → 3-4s，且输出可解析率上升。

### S2 页面设计稿生成

- **现状**：每页一次调用产出整页设计稿 HTML；并行度 3。
- **问题**：整页一次性决策 = 决策空间无边界（2026-09-13 文档 §2.1 的失控根因）；`/no_think` 已失效，`low` 只能压不能关。
- **设计**：
  1. **骨架先行**（文档 §7 的 P2，尚未做）：第 1 次调用只出 section 清单 + 每个 section 职责（输出 1-2K token），第 2 次起逐 section 出 HTML。决策空间缩小 1 个量级。
  2. 思考档位分档：骨架轮 `enable_thinking=false`（纯结构化），section 轮 `low`。
  3. 上下文：把设计契约从"全文注入"改为"契约摘要 + 按需校验清单"；V1~V6 校验已由代码做（`DesignHtmlValidator`），无需模型自查。
  4. 保留现有并行度 3 + 失败占位页降级。
- **预期**：单页设计 2-5 分钟 → 30-60 秒；失控概率下降一个量级。

### S3 设计稿 → 组件映射

- **现状**：每批 ≤5 个区块摘要 → 模型输出组件映射 JSON。**思考档位未设（xhigh）**。
- **问题**：这是**分类任务**（区块 → 组件库候选），本该几乎零思考；却跑在最高档。
- **设计**：
  1. 思考档位 → `enable_thinking=false`（分类任务，最典型的浪费）。
  2. **确定性前置**：先用 DOM 特征（标签结构、class 语义、data 属性、文本模式）做规则匹配，只把规则无法判定的区块送 AI；当前是全部送 AI。
  3. 输出收紧：候选组件 id 从枚举里选，置信度低于阈值（`section-confidence-threshold` 0.7）直接落 `custom_macro`。
  4. 已有并行度 3 保留。
- **预期**：映射阶段耗时降 60%+，映射准确率反而上升（规则兜底 + AI 只处理疑难）。

### S4 组件化生成（PageSpec → 渲染）—— 已是范式，扩散

- **现状**：AI 出 PageSpec JSON（几 KB）→ `PageSpecRenderer` 确定性渲染 HTML/CSS → `PageSpecValidator` 校验。
- **设计**：
  1. **把这个范式作为 S5/S6/S7 的改造目标**（见 §5 统一改造）。
  2. 思考档位 `low` 保留（信息架构确实需要权衡，但不该 xhigh）。
  3. 增量：微调场景已支持"基于当前 PageSpec 调整"（`buildRefinePrompt`）——保持"改 spec 不改 HTML"。
- **预期**：作为其他场景的改造模板，输出量降 1~2 个数量级。

### S5 HTML 管线分批生成

- **现状**：规划轮 → 逐文件轮 → 分块轮 → 修复轮，**每轮都回写完整文件**。
- **问题**：输出量主导耗时；2026-09-13 记录 base.css 单文件 50 分钟（16 块 × 2 分钟长思考）。
- **设计**：
  1. **改增量协议**：分块轮只输出本块内容（已是分块，但每块仍带全量上下文与回写语义）→ 进一步做到"只输出本块 + 锚点"，禁止回写整文件。
  2. 思考档位：分块轮 `enable_thinking=false`（照着锚点补代码是确定性任务）；规划轮 `low`（需要决策）。
  3. 输出上限：分块轮按块大小动态给 max_tokens（当前固定 `design-max-tokens` 24000 偏大）。
  4. 中期：整模式迁到 S4 的 PageSpec 形态。
- **预期**：分块轮耗时降 3-5 倍；50 分钟级案例消失。

### S6 模板调整 / 微调（用户最常问"为什么这么慢"的场景）

- **现状**：注入 focus 模式上下文（~15k token）+ 历史 8k token 预算 → 要求模型回写整文件；思考档位 `low`；调整轮 maxTokens 收紧到 8192。
- **问题**：
  - 思考吃满 8192 = 170s 纯思考零产出（代码注释记录的 6.4 分钟案例同源）。
  - 整文件回写：20KB 模板改一行 → 输出 20KB → decode 400s+。
- **设计**：
  1. **补丁协议**：模型只输出变更片段（`@@ 区块id @@` 包裹的替换段），由代码在 `AiTemplateFileService` 层做文本替换并落盘。改一行只输出几百字节。
  2. 思考档位：**按问题类型分流**——定位类问题（"为什么不居中"）`enable_thinking=false`（代码已有确定性渲染 + 截图可用时不需要 CoT）；改动类（"改成蓝色"）`low`。
  3. 上下文：focus 模式已降到 15k；再进一步——命中选中区块时只注入该区块（+ 其 CSS 变量与依赖），不注入整文件。
  4. 保留 `ADJUST_MAX_TOKENS_CAP` 兜底与"思考吃满则翻倍重试"机制（它现在是最后一道保险，不该是常态）。
- **预期**：典型调整轮 1-6 分钟 → 10-30 秒。

### S7 渲染失败 / 规范违规修复

- **现状**：把 FreeMarker 报错 / 校验问题回灌给模型，模型重出整文件。
- **设计**：
  1. 复用 S6 的补丁协议（修复天然是局部的）。
  2. 思考档位 `enable_thinking=false`——错误信息已经精确指出问题位置，模型只需改。
  3. 保留现有"错误 → 定向指令"构造逻辑（`TemplateComplianceChecker` / 渲染校验）。
- **预期**：修复轮从"重出整文件"降为"改一行"。

### S8 旧模板风格组件化升级

- **现状**：分批 + 预算控制（`UPGRADE_BATCH_TOKEN_BUDGET`），已较成熟。
- **设计**：思考档位 `enable_thinking=false`（机械改造任务）；保持分批预算。
- **预期**：小幅提速，主要收益是成本下降。

### S9 文章生成 / 续写 / 字段候选

- **现状**：3 个调用点全部用 `prepared.createPrompt` → 默认 runtimeOptions → **思考档位未设（xhigh）**。
- **问题**：写作任务对"深度思考"的收益远低于成本；实测 xhigh 下思考量是 low 的 3 倍。
- **设计**：
  1. 思考档位 → `low`（写作需要一点结构化思考，但不需要 xhigh）。
  2. 字段候选生成（`generateField`）是**枚举式**任务 → `enable_thinking=false`。
  3. 划词改写/扩写/翻译已走 `injectSkills=false`（不挂工具、不注技能），保持并叠加关思考。
- **预期**：文章类响应时间降 2-3 倍。

### S10 通用对话（`@aiChat` 标签、能力问答）

- **现状**：`AgentChatExecutor.createPrompt` → 默认 runtimeOptions → **xhigh**。
- **设计**：
  1. 默认档位 `low`；提供"深度思考"开关（用户显式选择时才升档）。
  2. 与 §5 的"思考档位下沉到模型配置"合并实现。

---

## 5. 统一改造（跨场景的架构级动作）

### 5.1 思考档位下沉到模型配置（前置项）

现状"按模型名含 `qwen3` 就设 low"的硬编码猜测有两个问题：① 端点默认 xhigh，未设即最慢；② 换模型/换端点时行为不可控。

**改造**：

1. `ai_model_config` 增列 `reasoning_effort varchar(16)`（枚举：`off/low/medium/xhigh`，默认 `low`），后台可选。
2. 新增 `AiModelConfigServiceImpl.applyReasoning(options, config)`：
   - `off` → 透传 `chat_template_kwargs:{enable_thinking:false}`（端点实测唯一有效的关闭方式）
   - `low/medium/xhigh` → 映射 `reasoning_effort`（注意：端点只认 xhigh/medium/low，**不发 `high`**，会 400）
3. **端点能力适配层**（2026-09-13 文档 §8 的 `DesignModelAdapter`）：按 provider/model 路由思考参数，OpenAI 兼容但非 Qwen 的端点用 `reasoning_effort`，Qwen 端点用 `chat_template_kwargs`。
4. 场景默认值（配置未显式指定时）：S1/S3/S7/S9-字段/S10 → `off`；S2/S4/S5-规划/S6-改动 → `low`；审计/复杂设计 → `medium`。

> 注意 Spring AI 2.0.1 的 `OpenAiChatOptions` 有 `extraBody(Map)`，可承载 `chat_template_kwargs`；`reasoningEffort` 走 SDK 的 `ReasoningEffort.of()`（已 javap 确认）。

### 5.2 输出协议：补丁取代全文回写

统一为两种输出形态：

| 形态           | 用途        | 模型输出                | 代码职责                                  |
| ------------ | --------- | ------------------- | ------------------------------------- |
| **Spec 形态**  | 新建页面、整体规划 | PageSpec JSON（几 KB） | `PageSpecRenderer` 渲染 HTML/CSS（已有，S4） |
| **Patch 形态** | 改已有文件     | 变更片段（几百字节~几 KB）     | `AiTemplateFileService` 定位替换落盘（新增）    |

全文回写仅保留在"整文件重建"场景（如导入转模板），且必须走分批。

### 5.3 早停与预算（补 2026-09-13 文档 §6 的 P1）

现有 256K 思考保险丝是"事后刹车"，应补前置信号：

1. **思考预算**（按场景配置，如调整轮 2048 token、映射轮 512 token）——超限即降档重试或直接收敛。
2. **静止计时**（如 90s 无思考增量）判假死。
3. **重复窗口**判循环。
4. 思考落盘（`workDir/design-log/*.reasoning.txt`）留证据。

---

## 6. 落地顺序（按 ROI）

| 批次     | 内容                                                  | 改动面                                                      | 预期收益                                     | 风险             | 状态     |
| ------ | --------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------- | -------------- | ------ |
| **B1** | 端点适配层 + 档位下沉配置（§5.1），S1/S3/S7/S9/S10 默认 `off`/`low` | `AiModelConfigServiceImpl` + 各 buildOptions + DB 加列 + 后台 | **最大**：简单问答 11.65s → 4.65s；映射/文章类降 2-3 倍 | 低（参数级，可灰度）     | ✅ 已完成 |
| **B2** | 调整轮补丁协议（S6/S7）                                      | `AiTemplateGenServiceImpl` + `AiTemplateFileService`     | 调整轮 1-6 分钟 → 10-30 秒                     | 中（协议变更，需兼容旧格式） | ✅ 已完成 |
| **B3** | 上下文分层（§S1 裁剪 + 选中区块级注入）                             | 提示词构造层                                                   | prefill -50%，间接压思考                       | 低              | ✅ 已完成 |
| **B4** | 设计段骨架先行（S2）+ 早停三件套（§5.3）                            | `MockupDesignService` + `FastcmsAiProperties.Design`     | 设计段提速 2-4 倍，失控率降一个量级                     | 中（改动面大，需灰度）    | ✅ 已完成 |
| **B5** | HTML 管线迁到 Spec 形态（S5 → S4）                          | 管线层                                                      | 输出量降 1-2 个数量级                            | 高（动主链路）        | ✅ 已完成 |

### 6.1 B1 实施记录（2026-10-28）

**新增文件**

- `starters/ai-starter/.../ai/support/AiReasoningSupport.java` —— 档位适配层。把「档位」翻译成各家端点认的请求参数：
  - Qwen 系 + `off` → 顶层 `chat_template_kwargs: {"enable_thinking": false}`（**唯一真能关思考的参数**）；
  - Qwen 系 + `low/medium/xhigh` → `reasoning_effort`；
  - Qwen 系发 `high` → 钳制为 `xhigh`（否则端点 HTTP 400）；
  - 非 Qwen 系 + `off` → `reasoning_effort=minimal`。
- `starters/ai-starter/src/test/.../AiReasoningSupportTest.java`（8 例）—— 档位解析与钳制单测。
- `starters/ai-starter/src/test/.../AiReasoningWireFormatProbeTest.java`（4 例）—— 起本地 `HttpServer` 抓**真实请求体**，钉死线上格式：off 档必须出现顶层 `chat_template_kwargs` 且**不含** `reasoning_effort`。

**改动文件**

| 文件 | 改动 |
| --- | --- |
| `service/.../entity/AiModelConfig.java` | 新增 `reasoning_effort` 字段（`@TableField("reasoning_effort")`） |
| `ai/.../AiModelConfigServiceImpl.java` | 新增 `optionsBuilder(config, sceneDefault)`（base + 档位）；`saveConfig` 增加 `normalizeReasoningEffort()`（非法值降级为 auto，不抛异常）；`testConnection` 走 `SCENE_DETERMINISTIC`（探活关思考） |
| `ai/.../AgentChatExecutor.java` | `prepare()` 改用 `optionsBuilder(config, SCENE_REASONING)`；新增 `createPrompt(sys, user, options)` 重载（**request 级 options 整体替换默认 options**，故必须由 `optionsBuilder` 构建） |
| `ai/.../SiteAnalyzer.java`（S1） | `buildOptions()` → `SCENE_DETERMINISTIC`（原裸奔 xhigh） |
| `ai/.../MockupConverter.java`（S3） | 映射轮 → `SCENE_DETERMINISTIC` |
| `ai/.../MockupDesignService.java`（S2） | → `SCENE_REASONING`；**删除**原 `model.contains("qwen3") → low` 硬编码 |
| `ai/.../AiTemplateGenServiceImpl.java`（S5/S6/S7） | `buildPipelineOptions()` → `SCENE_REASONING`；删除 qwen3 硬编码块 |
| `ai/.../AiArticleGenServiceImpl.java`（S9） | `generateField`（枚举式任务）→ `SCENE_DETERMINISTIC` + 新 `createPrompt` 重载 |
| `doc/sql/fastcms.sql`、`web/.../install/sql/mysql/fastcms.sql` | `ai_model_config` 建表补 `scene` 与 `reasoning_effort` 两列（**顺带修既有缺陷**：原 `scene` 的 ALTER 被注释，而安装器只跑这一个脚本 → 全新安装缺列启动即报错） |
| `doc/sql/fastcms-1.0.0.sql` | 增量 `ALTER TABLE ai_model_config ADD COLUMN reasoning_effort ...` |
| `ui/src/views/ai/model/index.vue` | 表格新增「思考档位」列（关闭/低/中/高/自动）+ 表单新增「思考档位」下拉 + 档位说明提示 |

**实测收益（走生产同源代码路径打真实端点）**

| 场景档位 | 耗时 | 思考量 |
| --- | --- | --- |
| 推理档（`low`） | 4312 ms | 141 字符 |
| 确定性档（`off`） | **974 ms** | **0 字符** |

即耗时降至 **1/4.4**，思考完全归零。

**遗留待办**

1. 前端需手动 `cd ui && npm run build` 并同步到 `web/src/main/resources/static/`（构建需删除 `ui/dist/assets`，沙箱拒绝该操作）。
2. prod 库需执行 `doc/sql/fastcms-1.0.0.sql` 的 `ALTER TABLE ... reasoning_effort`（dev 3308 已执行）。
3. 后台「设置 → 模型管理」的「思考档位」可按需覆盖场景默认（留空 = 自动：生成类 `low`，分类/映射/字段类关思考）。

---

### 6.2 B2 实施记录：调整轮补丁协议（2026-10-28）

**核心思路**：调整轮/修复轮不再要求 AI 回写整文件，改为输出 **search/replace 补丁片段**；
补丁在内存里先还原成**完整文件内容**，再走与全文回写完全相同的落盘链路
（DB 持久化 / 写前备份 / SSE 文件事件 / 渲染校验，一行都不用改）。

**新增文件**

| 文件 | 作用 |
| --- | --- |
| `template/AiTemplatePatchDto.java` | 补丁元素：`path` / `search` / `replace` |
| `support/FilePatchApplier.java` | 补丁应用器：search 唯一匹配校验、路径白名单、穿越防护、同文件多补丁串行 |
| `template/AdjustIntent.java` | 调整轮意图分类（诊断类 vs 改动类），驱动思考档位分流 |
| `test/.../FilePatchApplierTest.java`（11 例） | 补丁应用规则 |
| `test/.../AiTemplateResponseParserPatchTest.java`（8 例） | `patches` / `filePatches` 协议解析 |
| `test/.../AdjustIntentTest.java`（7 例） | 意图分类（含否定词感知） |

**改动**

- `AiTemplateResponseParser`：`ParseResult` 增 `patches`；同时认 `patches` 与 `filePatches` 两个键名；
  单条补丁不合法只丢该条（不整体失败）。
- `TemplateGenPromptBuilder`：系统提示词 §12 增补「两种输出通道」说明；
  `buildAdjustPrompt` / `buildRenderFixPrompt` 明确「本轮使用 patches 补丁通道」。
- `AiTemplateGenServiceImpl`：`applyPatchesToFiles` 做「补丁优先、files 兜底」的合并；
  `buildPipelineOptions` 增场景档位重载。

**思考档位按意图分流**（`AdjustIntent`）

- 诊断类（「为什么…」「不生效」「不显示」）→ 走**确定性档**（关思考）；
- 改动类（「改成…」「加一个…」）→ 走**推理档**（low）；
- 渲染修复轮（round ≥ 1）→ 走**确定性档**（错误信息已精确指出位置，模型只需改）。

**意图分类的三层匹配**（否定词感知，避免「不显示」被「显示」误判为改动）：

1. 强改动词（改成/修改/增加/删除…）→ 改动
2. 歧义动词（显示/居中/对齐…）+ 否定前缀感知 → 改动
3. 强诊断词（为什么/原因/不生效…）→ 诊断
4. 弱改动词（优化/配色…）→ 改动
5. 兜底 → 改动（关思考的答错风险高于多花几秒）

#### 6.2.1 真实端点活体验证（2026-10-28）——发现并修复了一个致命 bug

单元测试全绿 ≠ 功能可用。B2 写完后用**生产同款代码路径**（真实 `TemplateGenPromptBuilder` →
真实 `AiTemplateResponseParser` → 真实 `FilePatchApplier`，只把 HTTP 层换成裸调用）
打真实端点 `203.176.93.143:30120`（Qwen3.6-27B），第一次就暴露了**补丁 100% 失效**。

**实测数据**（样本 `doc/wiki/fastcms-site/index.html`，22681 字符，**CRLF 行尾**）：

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| 模型输出通道 | ✅ `patches`（files = 0） | ✅ `patches`（files = 0） |
| 模型输出总量 | 461 字符（**降 49 倍**） | 532 字符（**降 42 倍**） |
| `index.html`（CRLF）补丁 | ❌ **失败** | ✅ 成功 |
| `_preview_data.json`（LF）补丁 | ✅ 成功 | ✅ 成功 |
| 失败数 | 1 / 2 | **0 / 2** |

**根因**：模型输出的 `search` 片段**几乎总是 LF**，而磁盘上的模板文件（Windows 项目、
从网页导入的 HTML）**很可能是 CRLF**。逐字 `content.indexOf(search)` → 必然不命中 →
补丁被拒 → 回退整文件回写 → **B2 的收益（输出量降 1~2 个数量级）完全归零**。

同一个响应里 LF 文件的补丁成功、CRLF 文件的补丁失败，完全对上这条根因。

**修复**：新增 `support/PatchTextMatcher.java`（13 例单测），两处补丁应用器统一改用它：

- `FilePatchApplier.apply`（B2 调整轮/修复轮）
- `AiTemplateGenServiceImpl.applyComponentPatches`（B5 组件管线）—— **同一缺陷，两处都中招**

匹配口径：

1. 先**逐字**匹配（零成本，覆盖绝大多数情况）；
2. 未命中再把双方换行统一为 LF 后匹配（**两个方向都覆盖**：模型 LF + 磁盘 CRLF、
   模型 CRLF + 磁盘 LF）；
3. 两种口径都要求**唯一命中**，多处命中一律判歧义拒绝（避免改错地方）。

替换内容的行尾风格会**对齐文件**（模型给 LF、文件是 CRLF 时统一转 CRLF），避免混合行尾。

**为什么只归一化换行、不归一化空白**：缩进差异属于「模型重排了格式」，归一化空白后
匹配到的位置可能与模型意图不符（想改 A 处、实际匹配到 B 处）——属于**静默改错内容**，
比直接失败更危险。缩进不一致应由模型自己保证 search 与注入内容一致，
而注入内容与磁盘内容同源（同一份 CRLF），**只有换行符**会在「模型重打一遍」时丢失。

> **教训**：这个 bug 靠读代码和写单测都发现不了——单测里所有人写的都是 LF 文件。
> 只有拿真实模型 + 真实 CRLF 文件跑一遍才会暴露。**Windows 部署的项目必须做一次
> 「真实端点 + 真实磁盘文件」的端到端验证。**

---

### 6.3 B3 实施记录：上下文分层（2026-10-28）

**S1 站点分析输入裁剪**（收益最直接）

- 新增 `design/SiteStructureExtractor.java`：确定性抽出「文档元信息 + 导航菜单项 + 标题层级 +
  站内链接清单 + 正文文字节选」五段结构摘要；剔除脚本/样式/SVG/iframe/注释，外链不进导航项。
- `SiteAnalyzer.buildUserPrompt` 改为喂结构摘要（通常 1~3KB，对比原 30KB 截断 → **上下文降约 90%**）；
  摘要过短（< 200 字符，页面极简或 HTML 畸形）时**自动回退**到截断后的全文——保信息优先。
- 系统提示词增「输入形态」说明（全文 or 结构摘要两种）。

**调整轮上下文预算属性化**

- 新增 `fastcms.ai.template.adjust.*`：`focused-closure-tokens`（默认 24000）、
  `full-section-tokens`（60000）、`asset-full-tokens`（4000）。
- 原先写死在 `AiTemplateGenServiceImpl` 的三个常量改为读配置——「上下文太大导致慢」可直接调参缓解。

**选中区块 → 区块级注入**

- 新增 `buildFocusedSectionFileSection`：命中预览页选区时，只注入
  **组件源码 + 引用页面里该区块的片段（±12 行）+ 组件依赖闭包 + `_preview_data.json`**，
  不再注入整个当前页。上下文从 20KB 降到 1~3KB。
- 提示词里的「当前页面及其依赖文件」标题同步改为按实际注入内容描述。

#### 6.3.1 真实端点活体验证（2026-10-28）——「漏栏目」风险未出现

用真实端点 + 真实 `SiteStructureExtractor` + 真实 `SiteAnalyzer` 提示词路径，
对样本 `doc/wiki/fastcms-site/index.html` 实测：

| 项 | 实测值 |
| --- | --- |
| 原文长度 | 22681 字符 |
| 结构摘要长度 | 2922 字符（**压缩到 12.9%**） |
| 摘要提取的导航项 | 8 项（F FastCMS / 核心能力 / AI 建站 / 插件市场 / 定价 / 技术架构 / 免费开始使用 / 全部插件） |
| 摘要提取的标题层级 | h1×1 + h2×5 + h3×3（完整层级关系保留） |
| 摘要提取的站内链接 | 9 条（锚点 + 相对路径 + 链接文字） |
| 基于摘要的 IA 产出 | 分类与原页面结构**完全吻合**，未漏栏目 |

**结论**：五段摘要没有丢信息——栏目划分所需的「导航 + 标题层级 + 链接」三要素都完整保留，
被剔除的只有样式/脚本/图片（对归纳栏目零贡献）。上下文降 87%，且**未观察到漏栏目风险**。

---

### 6.4 B4 实施记录：骨架先行 + 早停三件套（2026-10-28）

**骨架先行（S2）**

- `DesignContractPrompt.buildSkeletonPrompt`：骨架轮只产出 section 清单 JSON
  （`layout` + `sections[{id,title,purpose,content}]`），1~2K token。
- `DesignContractPrompt.buildSkeletonConstraint`：把骨架作为**硬约束**注入实现轮提示词。
- `MockupDesignService.designPage`：第 1 轮前先跑骨架轮，骨架失败（超时/不可解析）**自动回退**
  到单阶段整页生成，不影响出稿。
- 骨架轮走**确定性档**（关思考）——纯结构化任务，不需要 CoT。

> **为什么是「骨架 + 整页实现」而不是「逐 section 产出再拼装」**：逐段产出需要代码拼装 HTML，
> 拼装结果极易过不了 V1~V6 格式校验（缺 meta/断点/导航 id 等），反而要多跑修正轮、得不偿失。
> 骨架约束 + 一次性整页产出拿到了「决策空间收缩」的主要收益，却不引入拼装风险。

**早停三件套**

- 新增 `support/DesignGuard.java`（纯逻辑、可单测）：
  - **思考预算**：累计思考字符数超阈值即中止（默认 40000 字符 ≈ 提前 3 分钟刹车）；
  - **静止计时**：超过阈值无任何增量即判假死（默认 90 秒）；
  - **重复窗口**：思考文本尾部片段反复出现即判循环（窗口 400 字符 / 重复 3 次）。
- `MockupDesignService.callDesignModel`：`blockLast()` 改为**订阅 + 闩锁**，
  看门狗线程（每 5 秒轮询）检测到静止超时就 `dispose()` 掉订阅——
  这是必须的：流「卡住不吐字」时 `doOnNext` 根本不会被调用，只能在独立线程上判定。
- **思考落盘**：`workDir/design-log/<page>.reasoning.txt`，便于事后复盘「这轮为什么想这么久」。
- 新增配置：`fastcms.ai.template.design.skeleton-first` / `reasoning-budget-chars` /
  `stall-timeout-millis` / `loop-window-chars` / `loop-repeat-threshold` / `log-reasoning`。

#### 6.4.1 真实端点活体验证（2026-10-28）——骨架 JSON 输出率 100%

用真实端点 + 真实 `DesignContractPrompt.buildSkeletonPrompt` 提示词实跑首页骨架规划：

| 项 | 实测值 |
| --- | --- |
| 耗时 | 12.9 秒 |
| usage | in=238 / out=546 / total=784 |
| 思考档位 | 确定性档（`chat_template_kwargs.enable_thinking=false`） |
| JSON 可解析 | ✅ true |
| `layout` 字段 | ✅ 存在 |
| `sections` 字段 | ✅ 存在 |
| section 数量 | 6（header / hero / features / featured-plugins / stats / footer） |
| `id` 命名 | 全部小写英文短横线，符合契约 |
| markdown 围栏 | 无（纯 JSON） |

**结论**：骨架轮的输出契约稳定可解析，`id` 命名规范、段数落在 3~6 区间内，
且骨架本身只有 546 token（对比整页 HTML 的上万 token）——决策空间收缩的收益成立。

---

### 6.5 B5 实施记录：调整型会话接 Spec 形态（2026-10-28）

**关键发现**：组件管线（Spec 形态）**早就实现了 refine（微调）分支**，
但入口条件 `!StringUtils.hasText(session.getTemplateId())` 把**调整型会话全部挡在门外**——
即使正式模板已经组件化（有 `_pagespec.json`），调整轮仍然走「AI 回写整页 HTML」的老路。

**改动**

- 抽出可测的路由判定 `AiTemplateGenServiceImpl.shouldUseComponentPipeline(genMode, isFirstChat, hasSpec, isAdjustSession)`：
  - 生成型首次对话 / 生成型微调 → 组件管线（不变）
  - **调整型会话 + 正式模板已组件化 → 组件管线（B5 新增）**：改 spec 再确定性重渲染
  - 调整型会话 + 未组件化的老模板 → **退回 HTML 调整链路**（老路径完全不受影响）
  - `gen-mode=html` → 一律不走组件管线
- **保住备份/回滚链路**：调整型会话的渲染**先落到暂存目录**，再经 `writeToFile` 写进正式模板
  —— `writeToFile` 自带「写前备份」（回滚粒度 = 一轮对话），而渲染器直接写目录会绕过它。
- 组件覆盖件（`_component_overrides/`）在每次渲染前从正式目录同步到暂存目录——
  否则重渲染会把上一轮 `filePatches` 的补丁冲掉；覆盖件本身的写入也纳入备份。
- 调整型会话的文件事件 action 从 `create` 改为 `modify`；助手消息改为「先落 → 收尾更新」，
  避免同一轮出现两条 assistant 消息。
- 新增测试 `test/.../ComponentPipelineRoutingTest.java`（7 例）覆盖路由矩阵。

#### 6.5.1 验证状态（2026-10-28）：**尚未做真实端到端验证**

B5 是本轮唯一**还没有跑过真实模型 + 真实模板目录**的批次（路由判定有 7 例单测覆盖，
但「暂存目录 → `writeToFile` → 备份 → `updateById`」整条链路未经活体验证）。
风险点按优先级：

1. **暂存目录渲染**：`PageSpecRenderer` 从「目标目录/`_component_overrides/`」读覆盖件，
   渲染前已同步到暂存目录——需验证同步是否覆盖所有情况（漏同步 → 重渲染冲掉上一轮补丁）。
2. **`writeToFile` 写前备份**：调整型渲染产物必须经它落盘才能保住回滚粒度——
   需验证备份文件是否真的生成、能否回滚。
3. **助手消息「先落 → 收尾更新」**：需验证一轮对话后 `ai_template_message` 表里
   **只有一条** assistant 消息（而不是两条）。
4. **`_component_overrides/` 覆盖件写入也纳入备份**：需验证备份路径拼接正确。

**建议**：B5 的验证需要一个「已组件化的正式模板」（含 `_pagespec.json`）+
一个绑定该模板的调整型会话，在真实环境跑一轮「点选区块 → 改 → 看渲染结果」。

### 6.6 B6 实施记录：补丁协议推广到全部 4 条修改型链路（2026-10-29）

**起因**：用户指出一个现实问题——「改其中一行 HTML，AI 却要返回整个 HTML 文件」。
澄清后确认：**这不是后端落盘/备份的开销（毫秒级），而是模型输出侧的 token 成本**
（22KB 页面改一行要重吐 22KB ≈ 9K token）。

**先分清场景**（这是本轮最重要的判断）：

| 链路 | 性质 | 输出形态 |
|---|---|---|
| 生成型（建站/分批生成） | 从无到有 | **必须全文**（补丁无从谈起） |
| **修改型（微调/锚点修复/审计修复/深度焕新）** | 改已有文件 | **补丁**（只吐变更片段） |

4 条修改型链路此前全部要求 AI 回写整文件。本轮全部切到补丁通道。

#### 6.6.1 改造清单

- **`AiTemplateResponseParser`**
  - `ParseResult` 新增 `unchanged`（模型显式声明「这些文件本轮无需改动」）。
  - 新增 `unchanged` 解析 + `sanitizePaths` 清洗。
  - `sanitize()` / `sanitizePatches()` 补**反斜杠路径归一**（此前只处理 `./` 与 `/` 前缀）。
- **`FilePatchApplier`**
  - 新增 `applyToContents(Map<String,String>, List<patch>)`：对**内存内容**应用补丁。
    - **为什么必须有**：锚点/渲染修复轮的提示词注入的是「上一轮 AI 刚输出、尚未写盘」的内容；
      若按 `apply(Path,...)` 从磁盘读，拿到的是**改造前的旧内容**，search 片段必然匹配不上（语义错误）。
- **`TemplateGenPromptBuilder`**
  - `buildRefinePrompt` / `buildAnchorFixPrompt` / `buildAuditFixPrompt` → 补丁通道。
  - `buildStyleUpgradePrompt` → **按文件类型三选一**（整页重做=files / 区块级重做=patches / 无需改动=unchanged）。
- **`AiTemplateGenServiceImpl`**
  - 微调轮：注入的文件段从「文件清单」改为「文件**完整内容**」——
    原来模型只看到路径列表，**根本写不出 search 片段**，只能整文件重写。
  - 深度焕新轮：接入 patches + `unchanged` 计入 `donePaths`（不计入 → 进度卡死 → 空转）。
  - 锚点/渲染修复轮：`patchBase` 追踪（锚点轮=内存基准；渲染轮=磁盘基准，因为已写盘）。
  - 新增 `applyPatchesToContents` / `toContentMap`。
- **测试**：`PatchProtocolExpansionTest`（13 例）+ `AiTemplateResponseParserTruncatedTest`（8 例）。

#### 6.6.2 真实端点活体验证（2026-10-29）——发现并修复了 3 个真实缺陷

探针 `doc/wiki/tools/PatchChannelsProbe.java` 复用**生产同款**
`TemplateGenPromptBuilder` + `AiTemplateResponseParser` + `FilePatchApplier` 打真实端点：

| 链路 | 提示词 | 模型输出 | 补丁 | 失败 |
|---|---|---|---|---|
| 微调轮 | 23621 字符 | **469 字符**（vs 全文 22681，**降 48 倍**） | 2/2 成功 | 0 |
| 锚点修复轮 | 23305 字符 | **184 字符** | 1/1 成功 | 0 |
| 审计修复轮 | 24020 字符 | **521 字符** | 2/2 成功 | 0 |
| 深度焕新轮 | 26840 字符 | 16564 字符（**撞 8192 上限被截断**） | 0（整轮白跑） | — |
| 深度焕新轮（32768 上限） | 26840 字符 | 44329 字符 / 19212 token / 466 秒 | 整文件完整 | 0 |
| 多文件截断抢救 | — | 24465 字符（撞 8192 被截断） | **抢救回 1 个完整文件** | 0 |

**微调轮验证了 CSS 单行补丁**（用户点名的场景）：模型只输出
`{"path":"static/css/base.css","search":"color: #333;","replace":"color: #185FA5;"}`，
12 字符 search 唯一命中，产物正确 —— 证明「CSS 改一行」不需要回写整个 CSS 文件。

#### 6.6.3 缺陷 1：JSON 截断 → 整轮白跑（最贵）

**现象**：深度焕新轮整页重做需输出 19212 token，撞上 `ADJUST_MAX_TOKENS_CAP=8192`
→ JSON 在字符串中途断裂 → 整体解析失败 → **该轮产出全部丢失**（已消耗 191 秒算力）。

**修复 1a：解析器截断抢救**（`AiTemplateResponseParser.salvageTruncated`）
- 不做整体解析，改为**逐元素扫描**数组：跟踪「字符串内/转义/花括号深度」，
  每凑齐一个完整元素就单独解析收下；遇残缺元素立即停止并丢弃。
- 抢救到的内容标记 `ParseResult.truncated=true`，服务层据此提示用户「本轮被截断」，
  **不再提额重试**（重试要把整轮请求重跑一遍，而抢救出来的内容本来就能用）。
- 8 例单测覆盖：多元素、字符串数组、content 内含花括号/引号、路径归一、不误报。

**修复 1b：按批次体积动态定输出上限**（`resolveUpgradeRoundTokens`）
- 整页重做的全文输出 ≈ 输入量 × 2（实测系数 **2.08**：22KB 页面 → 44KB 输出 / 19212 token）。
- 固定 8192 对整页重做**必然截断**。改为按批次文件体积估算所需上限
  （`est × 2.2 + 12288`，上限 65536，其中 12288 = 4096 基础余量 + 8192 思考余量），
  并与用户配置的 `max_tokens` 取较大值。
- **为什么余量里必须留 8192 思考空间**：实测 `low` 档的思考量波动极大——
  同一提示词连续三次跑 8192 上限：一次输出 17397 字符、两次**正文 0 字符**
  （思考把预算吃光）。上限只够正文，遇到思考多的那一次照样截断。
- 实测：8192 上限 → 191 秒白跑；32768 上限 → 466 秒成功产出 44329 字符完整文件。

**修复 1c：截断抢救的活体验证（决定性证据）**
- 探针场景：多文件批次 + 必然截断的上限。
- 修复前：JSON 尾部断裂 → `files=0` → **整轮白跑**。
- 修复后日志：`已从截断响应中抢救出内容: files=1 patches=0 unchanged=0`
  → `truncated=true`、`files=1` → **救回完整文件，没白跑**。

#### 6.6.4 附带发现：焕新轮思考档位的影响（建议，未擅自改）

`low` 档下思考量波动导致「正文 0 字符」高频出现；`off` 档（关思考）实测稳定：
out=8192 全部用于正文（24465 字符），且**正是这个场景验证了截断抢救生效**。

**但本轮没有擅自把焕新轮改成 `off`**：视觉焕新需要设计判断，关思考可能拉低改造质量，
这个取舍应由业务侧决定。已用动态上限（预留 8192 思考空间）从工程上规避了「思考吃光预算」
的后果 —— 无论档位如何，都不会再因思考挤占而截断。

#### 6.6.4 缺陷 2：焕新轮提示词第 1 条与第 7 条自相矛盾

原第 1 条「只输出变更片段，不要重发整个文件」与第 7 条「改动超 70% 用 files」冲突，
而深度焕新轮**本质就是整页重做**（输出全文是内在需求，不是浪费）。
**修复**：改为**按文件类型三选一**——
- A. 整页重做文件（无区块标记）→ files，输出完整内容（输出全文是这类任务的固有成本）
- B. 区块级重做文件（带「注：本文件仅区块 N…」标记）→ patches，只输出需重做区块
- C. 完全无需改动的文件 → unchanged（不再原样回吐全文占位）

同时把 `buildBatchFileSection` 的区块标注措辞从「保持原样输出」改为
「必须原样保留——本文件请用 patches 通道只输出需重做区块的变更片段，不要重发整个文件」。

#### 6.6.5 缺陷 3：微调轮注入的是文件清单而非内容

模型要写 `search` 就必须能看到原文。原实现只注入「路径 + action」列表
→ 模型无从得知原文 → **只能整文件重写**（补丁通道形同虚设）。
**修复**：改注入文件完整内容（复用现成的 `buildTemplateFileSection`，带预算控制）。

#### 6.6.6 残留风险

1. **深度焕新轮仍是「整页重做」时，输出量无法压缩**——这是任务性质决定的，
   不是协议缺陷。真正压缩要靠「区块级重做」（P2-2 已有基础，靠 `sectionRedo` 驱动）。
2. **模型可能误选通道**：整页重做选了 patches 会导致改造不完整（有渲染校验兜底）。
   已有兜底：渲染校验失败 → 自动修复轮。
3. **`unchanged` 计入进度**后，若模型谎报「无需改动」→ 该文件被跳过但未真正改造
   → 视觉审计轮（阶段三）会复查并修复。

---

### 6.7 B7 实施记录：CSS/JS 分离规范（2026-10-29）

#### 6.7.1 需求与边界（**不是禁止内联**）

用户要求：AI 生产模板时 HTML / CSS / JS 分开写，不要把大段 CSS/JS 塞进 HTML。
用户随后澄清（关键）：**不是强制禁止在 HTML 里写 CSS/JS** —— 人工快速调整模板时，
在 HTML 里写少量 CSS/JS 是合法且必要的，硬拆反而增加维护成本。

因此本项落地为**「默认分开写 + 按体量划线 + 按产物来源分级」**三层，而非一刀切禁止。

| 层 | 机制 | 作用 |
| --- | --- | --- |
| 1 提示词 | `ASSET_SEPARATION_RULE`（单一来源，8 处接入） | 让 AI 主动分开；同时明确「不是禁止内联」 |
| 2 合规校验 | `TemplateComplianceChecker` 第 10 项 | 检测 + 分级处置 |
| 3 确定性兜底 | `InlineAssetExtractor` | AI 不听话时把大段内联抽成独立文件 |

#### 6.7.2 分级规则（核心）

| 产物 | 内联体量 | 处置 |
| --- | --- | --- |
| AI 生成产物（`readOnly=false`） | 超过阈值（2000 字符 / 60 行）且无 FTL | **确定性抽取**：写 `static/css/inline-*.css` / `static/js/inline-*.js`，HTML 就地改写为引用 |
| AI 生成产物 | 超过阈值且含 FreeMarker 指令 | 报 ERROR（`fixable=false`）→ 交回 AI 改写（静态资源不经 FTL 渲染，抽出去会失效） |
| **人工产物 / 正式模板目录**（`readOnly=true`） | 超过阈值 | **只提示（WARNING），绝不改内容** —— 判 ERROR 会触发 AI 重写，把人工代码抹掉 |
| 任何来源 | 阈值内 | **完全放行**，不检查不提示 |

> **为什么人工产物必须降为 WARNING**：`AiTemplateGenServiceImpl` 的合规修复轮筛选条件是
> `severity == ERROR && !fixable() && file.endsWith(".html")`。人工写的内联 CSS 若被判 ERROR，
> 会触发 AI 重写整个 HTML —— 用户的手工代码被抹掉。降为 WARNING 后只提示不阻断。

#### 6.7.3 阈值取值的实测依据

扫全部存量模板（`templates/src/main/resources` + `doc/wiki/fastcms-site`）的内联块分布：

```
1333 字符 44 行  xjd2022/article_pics.html <script>   ← 最大（Swiper 第三方集成）
 505 字符  5 行  xjd2022/_layout.html <script>
 330 字符 11 行  xjd2022/index.html <script>
 313 字符  7 行  cms/_layout.html <script>
内联块总数 = 7
```

- **字符阈值 2000**：对存量**零误伤**。
- **行数阈值**：初版取 20 → 会误判那 44 行的 Swiper 块 → 调整为 **60**（存量零误伤，
  且能盖住 AI 生成的大段内联，实测 AI 内联 CSS 普遍 300~800 行）。

#### 6.7.4 实施中发现并修复的 2 个真实缺陷

1. **`design/` 中间态目录未排除**：`checkInlineAssets` 用 `Files.walk` 扫全目录，
   会把设计稿（本身就是「HTML + 内联 `<style>`」形态，V1~V6 校验与 `MockupConverter`
   都依赖它）的样式抽走 → **破坏 A-pipeline 链路**。
   修复：扫描时排除 `design/` 前缀（与既有 `scanAllProductFiles` 口径一致）。
2. **行数阈值误伤存量模板**（见 6.7.3）。

#### 6.7.5 真实端点活体验证（2026-10-29）

探针：`doc/wiki/tools/AssetSeparationProbe.java`（复用生产同款
`TemplateGenPromptBuilder` / `AiTemplateResponseParser` / `TemplateComplianceChecker`）。

| 场景 | 验证内容 | 实测结果 |
| --- | --- | --- |
| `generate` | AI 是否主动分离 | **0 内联违规**，HTML 含 `<link rel="stylesheet">` + `<script src=`（两次独立跑一致） |
| `induce` | 诱导需求（「样式都写在这个 HTML 里」）下 AI 是否仍守规矩 | **仍主动分离**，0 内联违规 |
| `manual` | 人工写的内联 CSS 是否被 AI 顺手拆掉 / 被校验器判违规 | AI 用**单行补丁**精确改色（`color: #fff` → `#F59E0B`），**未拆块未重写整页**；校验器 `已自动修复=[]`、`inline-assets 问题=[]`、人工 CSS 原样保留 |
| `extract` | 兜底层能否在真实产物上确定性抽取 | **HTML 9794 → 275 字符（降 96%）**，抽出 `inline-index.css` / `inline-index.js`，HTML 就地改写为引用 |

修改型 4 链路回归（补丁协议未被本项破坏）：`refine` 输出 382 字符 / 2 补丁全成功、
`anchor` 195 字符 / 0 失败、`audit` 523 字符 / 0 失败、`trunc` 截断抢救仍生效。

#### 6.7.6 遗留风险

1. **抽出的静态资源不经 FTL 渲染** → 含 `<#...>` / `${...}` 的内联块无法抽取，
   只能交回 AI 改写（已按 ERROR 处置）。
2. **抽取时机在落盘之后**：若 AI 同时产出了 `static/css/base.css` 又在 HTML 里内联样式，
   会多出一个 `inline-*.css` 文件（不冲突，但文件数略增）。
3. **阈值是硬编码**：未来若模板规模变化（超大单页），2000 字符可能偏松/偏紧，
   目前以「存量零误伤 + AI 生成必被捕获」为基准。

---

### 6.8 B8 实施记录：思考预算与「思考吃满 → 关思考重试」（2026-10-29）

用户要求：**「跑一次吧，而且要有最佳的方案处理好长思考，思考的文字占满整个输出的问题，
导致实际输出 0 token 的问题，这个一定要有最佳方案处理好」**。

#### 6.8.1 先把病因查清楚（端点实测，2026-10-29）

三条实测结论决定了后面的方案形态：

| 结论 | 证据 | 对方案的影响 |
| --- | --- | --- |
| **`max_tokens` 是思考 + 正文共享预算** | 512 预算 + low 档 + 硬任务 → `finish_reason=length`、正文 **0 字符**、思考 2067 字符 | 「给正文单独留预算」在端点层做不到，只能在应用层管理 |
| **端点不支持任何单独的思考预算参数** | `thinking_budget` / `reasoning_budget` / `max_reasoning_tokens` 实测全部无效（思考长度只是随机波动） | 不能指望端点提供「思考配额」开关 |
| **预算越大，模型思考越长** | 512 预算 → 思考 2067 字符；**1024 预算 → 思考 4075 字符**（翻倍、正文仍是 0） | ⚠️ **「思考吃满 → 翻倍 maxTokens 重试」是错的解法**，只会给思考更多空间 |

> **最关键的一条**：旧代码里 3 处兜底重试都是「输出顶满 → 翻倍上限重试」。
> 实测证明这条路径在「思考吃满」场景下**必然白跑**（第二轮思考更长，正文照样 0）。
> 必须改成**关思考重试**。

#### 6.8.2 端点档位层的真 bug（连带发现）

端点前面挂了一层网关（FastAPI + Pydantic），**实际只放行 `low` / `medium`**：

```
reasoning_effort=xhigh → 400  Input should be 'none', 'low', 'medium' or 'high'   ← 网关层
reasoning_effort=high  → 400  Unexpected reasoning effort high. Supported types are
                                xhigh (default), medium, and low.                ← vLLM 层
reasoning_effort=none / minimal → 400
不传参数 = 端点默认最高档（思考量等同 xhigh）
```

而 `AiReasoningSupport.normalize()` 的 Qwen 分支原本 `EFFORT_XHIGH -> EFFORT_XHIGH`（原样发出）
→ **用户配置 xhigh 会直接 400**。已修正为归一到最高可用档 `medium`，
并新增 `EFFORT_MAX_SUPPORTED = medium` 常量。

#### 6.8.3 三层方案

| 层 | 手段 | 位置 | 效果 |
| --- | --- | --- | --- |
| **预防** | 档位归一（xhigh→medium）+ 关思考即 `chat_template_kwargs.enable_thinking=false` | `AiReasoningSupport` / `AiThinkingBudget.forceOff` | 避免 400；关思考后思考恒为 0 |
| **降本** | **提示词思考预算约束**：「请在 500 字以内完成思考…」 | `AiThinkingBudget.thinkingBudgetHint` → 统一注入到 `callModelRound` 最内层 | 硬任务下思考 2120 → 1585 字符（**-25%**），**正文从 0 → 361 字符** |
| **兜底** | **思考吃满 → 关思考重试**（保留原预算，不翻倍） | `AiTemplateGenServiceImpl` 3 处链路 | 同 512 预算：low 档正文 0 → 关思考后正文 **2497 字符** |

配套还加了**安全垫**（`AiThinkingBudget.applyReserve`）：
正文估算之外留 1024/2048 token。**垫子刻意很小** —— 因为「预算越大思考越长」，
垫子给大了反而诱发思考膨胀、挤掉正文。

#### 6.8.4 「思考吃满」的判定（三信号同时成立）

```java
AiThinkingBudget.isThinkingFlooded(finishReason, contentLength, reasoningLength, reasoningTokens)
// 1. finish_reason == "length"   （撞上 max_tokens 上限）
// 2. 正文长度 == 0              （确实一个 token 都没出）
// 3. 思考长度 > 0 或 reasoning_tokens > 0  （确实产生了思考）
```

三个信号缺一不可：
- 缺 1 → 正常结束，不是故障；
- 缺 2 → 是「正文被截断」（该提额，不是该关思考）；
- 缺 3 → 模型压根没思考，判定无意义。

**端点返回的 `finish_reason` 是大写 `LENGTH`**，判定用 `equalsIgnoreCase`（单测已锁）。

#### 6.8.5 改造清单（4 条链路 + 1 个统一注入点）

| 链路 | 位置 | 改造 |
| --- | --- | --- |
| 调整轮 | `AiTemplateGenServiceImpl` L1231 起 | `parseFailed && usage 顶满` → **先判思考吃满 → 关思考重试**；否则才走原「翻倍上限」 |
| 深度焕新轮 | 同上 L1799 起 | 新增 `thinkingOffRetryUsed` 标志；`thinkingOffThisRound` 决定本轮 options |
| 生成型管线 | 同上 L2913 / L2948 起 | 空响应与 `spec == null` 两处兜底都先判思考吃满 |
| **生成型单文件轮** | 同上 `generateSingleFile` | ⚠️ **本轮补上的遗漏**（见 6.8.5.1） |
| **统一注入点** | `callModelRound` 最内层 | 所有走该方法的链路自动获得「思考预算约束」提示词（幂等，不重复追加） |

##### 6.8.5.1 补上的遗漏：生成型单文件轮

排查「还有哪些地方在用『翻倍上限重试』」时发现，单文件轮的兜底链是
「直出失败 → 分块 → 压缩篇幅 + **翻倍上限**重试」，**全程没有思考吃满判定**：

```
直出失败（思考吃满，正文 0）
  → 分块生成（每块提示词一样长 → 思考照样吃满）
    → 压缩篇幅 + 翻倍上限重试（预算更大 → 思考更长 → 正文仍是 0）
      → 失败
```

即**必然白跑三次**。改造：

1. `generateSingleFile` 收集 `RoundDiagnostics`，解析失败时先判 `thinkingFlooded()`，
   命中则**直接关思考重试一次**（保留原上限）；
2. 兜底重试按失败原因分流：`resolveSingleFileRetryMaxTokens(modelConfig, thinkingFlooded)`
   —— 思考吃满 → **不翻倍**（走关思考）；确系内容截断 → 翻倍（至少 32768）。

#### 6.8.6 真实端点活体验证（2026-10-29）

探针：`doc/wiki/tools/ThinkingBudgetProbe.java`（复用生产同款
`AiModelConfigServiceImpl.baseOptionsBuilder` + `AiReasoningSupport` + `AiThinkingBudget`）。

| 场景 | 验证内容 | 实测结果 |
| --- | --- | --- |
| `flood` | 故障可复现 + 判定可靠 | 512 预算 + low 档 → `finish_reason=LENGTH`、正文 **0 字符**、思考 2067 字符 → 判定 **true** ✅ |
| `floodOff` | **关思考重试**（保留原预算） | 同 512 预算 + `enable_thinking=false` → 思考 **0**、正文 **2497 字符** ✅ |
| `floodOff` 对照组 | **翻倍上限**（旧解法，已废弃） | 1024 预算 + 保持思考 → 思考 **4075 字符**（比 512 时更长！）、正文仍 **0** → **翻倍治不了思考吃满** ✅ 反证成立 |
| `hint` | 提示词约束的效果 | 思考 2120 → 1585 字符（-25%），**正文 0 → 361 字符** ✅ |
| `easy` | 判定**不误杀**正常响应 | 简单翻译任务 → `finish_reason=STOP`、正文 70 字符 → 判定 **false** ✅ |
| `stream` | **流式**下 `finish_reason` 能否采集到（生产走流式） | 流式下 `finish_reason=LENGTH` 采集成功、判定 **true** → **生产兜底确实能触发** ✅ |

##### 6.8.6.2 深度焕新轮的非流式跑法不可用（探针口径修正）

用非流式（`.call()`）跑深度焕新轮（32768 预算、22KB 页面）时，**跑了 55 分钟仍未返回**，
而 32768 token @ 48 tok/s 的理论上限只有约 11 分钟。原因：

- 该链路要求整页重做，正文本身就有 16000+ 字符；
- 非流式调用要等模型**全部生成完**才返回，中途没有任何反馈；
- 改用流式后立刻看到真相：**正文已正常写出 16173 字符**，只是尚未收尾
  （`finish_reason` 未到）—— **不是思考吃满**，而是长输出时端点吞吐显著下降
  （300 秒约 54 字符/秒，远低于 48 tok/s 对应的字符速率）。

结论：**深度焕新轮必须用流式跑**（生产本来就是流式），非流式探针在该链路上没有参考价值。

##### 6.8.6.3 大页面会被 HTTP 读超时打断（端点吞吐问题，非思考吃满）

流式跑 22KB 页面（16384 预算）时，**10 分钟后被 OkHttp 读超时打断**
（`InterruptedIOException: timeout` → `Socket closed`）——生产侧的
`baseOptionsBuilder` 把 timeout 设为 10 分钟。

用**小页面**（4861 字符提示词、8192 预算）复跑，链路完全正常：

| 指标 | 值 |
| --- | --- |
| finish_reason | **STOP**（正常收尾） |
| 思考长度 | 2233 字符 |
| 正文长度 | 1869 字符 |
| 耗时 | **54.6 秒** |
| isThinkingFlooded | **false** |

→ **深度焕新轮链路本身没问题**，大页面超时是「端点长输出吞吐下降 + 10 分钟 HTTP 超时」
共同作用的结果，**与思考吃满是两回事**。这是本轮顺带发现的既有风险（不是本轮改动引入），
处理方向是「放大 timeout」或「按批次拆小」（后者已在 `resolveUpgradeRoundTokens` 之外
有分批机制，但单页 22KB 仍偏大）。

> **流式观测的坑**：`reasoningContent` 在流式下是**累积快照**（每帧带全部历史思考），
> 直接累加会平方级膨胀（实测原始累计 529250 字符，真实思考量只有 2126 字符，**差 249 倍**）。
> 生产代码用 `ReasoningStreamAccumulator` 做差分，探针已改为同款（否则观测数据失真）。

##### 6.8.6.1 顺带核实的「思考失控保险丝」是否误杀

生产代码对累计思考量设了 **256KB 硬上限**（`REASONING_BUF_MAX_CHARS`），超限即中断流
（防止模型陷入思考循环时前端无界累积崩溃）。既然原始快照是平方级的，就必须确认
**差分累加后的真实思考量不会触碰这条线**。

用生产同款累加器实测（`ReasoningShapeProbe`，512 预算 + 需推理任务）：

| 指标 | 值 |
| --- | --- |
| 带 reasoning 的 chunk 数 | 513 |
| 原始 `reasoningContent` 累计（直接累加） | **529250 字符**（平方级假象） |
| 累加器差分后的真实思考量 | **2126 字符** |
| 是否越 256KB 保险丝线 | **否**（差 123 倍） |

结论：**保险丝不会误杀正常流**。原始快照的平方级膨胀只存在于「不做差分」的用法里
（第一版探针就踩了这个坑，报出 529764 的假思考量）。

#### 6.8.7 修改型 4 链路回归（提示词措辞改动后复跑）

| 链路 | 输出量 | 补丁 | 失败 | 耗时 |
| --- | --- | --- | --- | --- |
| `refine` 微调轮 | 382 字符 | 2（含 CSS 单行补丁 `color: #333` → `color: #185FA5`） | 0 | 28.7 s |
| `anchor` 锚点修复轮 | 184 字符 | 1 | 0 | 13.5 s |
| `audit` 审计修复轮 | 524 字符 | 2 | 0 | 16.8 s |

与 B7 改造前同口径（382 / 195 / 523 字符，0 失败）一致 —— **CSS/JS 分离措辞调整未破坏补丁协议**。

#### 6.8.8 遗留风险

1. **关思考重试只有一次机会**：再失败就落「AI 返回空响应」。因为关思考后思考恒为 0，
   仍失败说明是别的原因（上下文超限 / 端点异常），继续重试无意义。
2. **提示词约束是「软约束」**：模型可能不听。实测硬任务下思考仍从 2067 → 1585 字符（只降 25%），
   说明**兜底层才是确定解**，提示词约束只是降本。
3. **~~未接入的链路~~**：见 §6.9 —— `MockupDesignService`（设计稿生成）与
   `AiArticleGenServiceImpl`（文章生成/续写）已于 §6.9 补齐；
   `AgentChatExecutor`（通用对话）仍只有档位、无独立执行器（它只是装配器，
   被 S1/S2/S3/S9 复用，实际调用发生在各自链路里 → 已随 S9 一并覆盖）。
4. **非 Qwen 端点的关思考形态**：修正前 `forceOff` 一律写 `chat_template_kwargs`，
   对 OpenAI o 系列无效（它们认 `reasoning_effort=minimal`）。
   已按端点族分流（`AiThinkingBudget.forceOff(builder, config)`）。
5. **大页面深度焕新轮会被 10 分钟 HTTP 读超时打断**（见 6.8.6.3）：端点长输出吞吐下降所致，
   **不是思考吃满**。既有风险，本轮只做定位未改（改 timeout 会影响所有调用方，需单独评估）。

---

### 6.9 B9 实施记录：非 `callModelRound` 链路的补齐（2026-10-29）

#### 6.9.1 为什么要有这一轮

§6.8 的三层方案（提示词约束 / 三信号判定 / 关思考兜底）统一挂在
`AiTemplateGenServiceImpl.callModelRound` 这一个流式执行器上。但**不是所有链路都走它**：

| 链路 | 执行器 | §6.8 后是否覆盖 |
| --- | --- | --- |
| S4~S8 模板生成 / 调整 / 修复 / 焕新 | `AiTemplateGenServiceImpl.callModelRound` | ✅ 已覆盖 |
| **S2 设计稿生成（设计段轮）** | `MockupDesignService.callDesignModel` | ❌ **缺约束 + 缺兜底** |
| **S9 文章生成 / 续写** | `AiArticleGenServiceImpl.callArticleModel` | ❌ **缺约束 + 缺兜底** |
| S1 / S3 站点分析 / 组件映射 | 走 off 档（确定性场景） | ✅ 天然无风险 |
| S10 通用对话 | 无独立执行器（`AgentChatExecutor` 只是装配器） | ✅ 随 S9 覆盖 |

两条漏掉的链路恰好是**低档 + 高输出量**的形态（设计稿整页 HTML、文章正文），
正是最容易「思考吃满 → 正文 0」的组合。

#### 6.9.2 S2 改造（`MockupDesignService`）

原 `callDesignModel` 是一个方法里塞了「构造 Prompt → 流式订阅 → 看门狗 → 空响应抛错」
全部逻辑，无法插入兜底轮。拆成两层：

```
callDesignModel（编排层）
  ├─ AiThinkingBudget.applyBudgetHint(messages, designOptions)   ← 注入思考预算约束
  ├─ runDesignStream(...)  → DesignStreamOutcome(text, reasoning, diagnostics)
  ├─ text 为空 && diagnostics.thinkingFlooded()？
  │    └─ 是 → buildThinkingOffOptions → runDesignStream 再跑一次（保留原上限，不翻倍）
  └─ 仍为空 → 抛「设计模型返回空响应（思考过程可能耗尽了输出上限）」
```

`runDesignStream` 相对原方法体的新增采集：**`finish_reason`**（流式最后一个 chunk 携带）
与**真实思考量**（用生产同款 `ReasoningStreamAccumulator` 差分，不累加累积快照）。
`DesignGuard`（思考预算 / 静止计时 / 重复窗口）语义保持不变。

**骨架轮天然安全**：骨架走 `buildSkeletonOptions`（确定性档 = 关思考），
`applyBudgetHint` 检测到已关思考会跳过约束注入 → 不会给「纯结构化任务」塞思考约束文本。

#### 6.9.3 S9 改造（`AiArticleGenServiceImpl`）

新增 `callArticleModel(...)` 统一文章生成/续写两条调用点：

1. **显式传 `prepared.getRuntimeOptions()`** —— 顺带修掉一个真实缺陷：
   过去用 `createPrompt(system, user)` 两参重载 → 请求级 options 缺失 →
   智能体 profile 配的 `temperature`/`maxTokens` **不生效**，思考档位也落到 ChatModel 默认值。
2. **注入思考预算约束**（`applyBudgetHint`）。
3. **采集 `finish_reason` 与思考量**。
4. **思考吃满 → 关思考重试一次**（保留原上限，不翻倍）。

#### 6.9.4 顺带修掉的一个真实缺陷：`RoundDiagnostics` 隐含前置条件

`RoundDiagnostics.thinkingFlooded()` 原实现把正文长度**硬编码为 0**：

```java
public boolean thinkingFlooded() {
    return AiThinkingBudget.isThinkingFlooded(finishReason, 0, reasoningChars, reasoningTokens);
}
```

这等于把「调用方已确认正文为空」这个前置条件藏进了方法签名里 —— 方法名看不出来，
一旦有调用点忘了先判正文为空，**「正文被截断但已产出内容」就会被误判成「思考吃满」**，
从而错误地触发关思考重试（把已经写出来的正文丢掉重跑）。

改为显式字段 `contentLength`，由各流式执行器在收尾时回填：

- `AiTemplateGenServiceImpl.callModelRound` → `responseBuffer.length()`
- `AiArticleGenServiceImpl.runArticleStream` → `contentBuf.length()`
- `MockupDesignService.runDesignStream` → `textBuf.length()`

副作用是**判定更准了**：管线里「JSON 中途被截断、reply/files 均解析失败但正文非空」的场景，
现在会正确地走「提额重试」而不是「关思考重试」——前者保住已写出的内容，后者会把内容丢掉。

#### 6.9.5 公共化：`thinkingOffOptions` 与 `extractReasoningTokens`

两条链路各自实现了一遍「关思考 options」构造，口径迟早漂移。提升为公共方法：

- `AiModelConfigServiceImpl.thinkingOffOptions(config, origin)` —— 从 `baseOptionsBuilder` 出发
  （请求级 options 整体替换默认 options，漏设 apiKey/baseUrl/timeout 会 404 / 断流），
  保留原 `maxTokens`/`temperature`，再按端点族分流关思考。
- `AiThinkingBudget.extractReasoningTokens(nativeUsage)` —— 从
  `completion_tokens_details.reasoning_tokens` 提取思考 token 数，
  三条链路共用一份实现（原先 `AiTemplateGenServiceImpl` 有一份私有副本）。

#### 6.9.6 真实端点活体验证（2026-10-29）

探针：`doc/wiki/tools/ThinkingOffFallbackProbe.java`（复刻 S2/S9 的完整兜底链路，
含流式差分累加器与 `finish_reason` 采集）。

**S2 设计段**（512 预算 / low 档 / 设计稿任务形态）：

| 轮次 | finish_reason | 正文 | 思考 | 耗时 |
| --- | --- | --- | --- | --- |
| 首轮（注入思考预算约束） | `LENGTH` | 1213 字符 | **86 字符** | 24.4 s |
| 兜底轮（关思考，保留原上限 512） | `LENGTH` | **1775 字符** | **0 字符** | 22.8 s |

**S9 文章段**（512 预算 / low 档 / 文章任务形态）：

| 轮次 | finish_reason | 正文 | 思考 | 耗时 |
| --- | --- | --- | --- | --- |
| 首轮（注入思考预算约束） | `LENGTH` | 700 字符 | **230 字符** | 22.2 s |
| 兜底轮（关思考，保留原上限 512） | `LENGTH` | **932 字符** | **0 字符** | 20.4 s |

**提示词约束的量化效果**（2048 预算 / 设计稿任务）：

| | 思考 | 正文 | 耗时 |
| --- | --- | --- | --- |
| 不加约束 | 380 字符 | 5027 字符 | 89.1 s |
| 加约束 | **215 字符（-43%）** | 5241 字符 | 90.2 s |

结论三条：

1. **提示词约束在 S2/S9 形态下确实压住了思考**（-43%），首轮没到「正文 0」；
2. **`thinkingOffOptions` 构造出的 options 在真实端点生效**：
   `reasoningEffort=null` + `extraBody={chat_template_kwargs={enable_thinking=false}}`
   → 思考恒为 0、正文产出**更多**（1775 / 932 字符）；
3. **保留原上限不翻倍是对的**：兜底轮 512 预算与首轮同预算，关思考后预算全归正文。

#### 6.9.7 测试

- 新增 `ThinkingOffOptionsTest`（5 例）：关思考形态、保留原预算与温度、
  apiKey/baseUrl/timeout 在场、非 Qwen 端点走 `minimal`、config 为空返回 null。
- 新增 `DesignThinkingBudgetTest`（8 例）：设计轮注入约束 / 骨架轮跳过 / 幂等 /
  三信号判定不误杀 / `contentLength` 回填回归。
- `AiThinkingBudgetTest` 补 2 例：`extractReasoningTokens` 的提取与容错。
- ai-starter 全量：**282 例，2 个失败**（`MockupConverterNavIaTest` /
  `MockupConverterNavMenuifyTest` 的 nav active 断言 —— **改造前既有的基线失败**，
  与思考预算无关）。全量构建 **22 模块 BUILD SUCCESS**。

---

## 附录 A：实测复现命令

```bash
# 档位对照（端点不接受 api key 也可调）
curl -s -X POST http://203.176.93.143:30120/v1/chat/completions \
  -H "Content-Type: application/json" -d '{
    "model":"Qwen3.6-27B","temperature":0.7,"max_tokens":16384,"stream":false,
    "messages":[{"role":"user","content":"1+1=? 只回答数字"}]}'

# 查端点支持的档位（故意传非法值）
# -> "Supported types are xhigh (default), medium, and low."

# 真正关思考
# -> 顶层加 "chat_template_kwargs":{"enable_thinking":false}
```

## 附录 B：现状代码坐标（实施对照表）


| 位置                                                         | 现状                                     | 目标                           |
| ---------------------------------------------------------- | -------------------------------------- | ---------------------------- |
| `AiModelConfigServiceImpl.baseOptionsBuilder` L226-250     | 只设 model/timeout/temperature/maxTokens | 增思考档位注入（`applyReasoning`）    |
| `AgentChatExecutor.prepare` L160-167                       | runtimeOptions 无思考参数                   | 增默认档位（S10 受益）                |
| `AiArticleGenServiceImpl` L179/358/500                     | `createPrompt` 默认 options              | 显式档位（S9）                     |
| `SiteAnalyzer.buildOptions` L213-224                       | 只设 temperature=0.2                     | 增 `off` 档（S1）                |
| `MockupConverter` L953-955                                 | 只设 maxTokens                           | 增 `off` 档 + 规则前置（S3）         |
| `MockupDesignService.buildDesignOptions` L822-834          | `reasoningEffort("low")`               | 走适配层（S2）                     |
| `AiTemplateGenServiceImpl.buildPipelineOptions` L3566-3581 | `reasoningEffort("low")`               | 走适配层 + 按轮次分档（S5/S6/S7）       |
| `FastcmsAiProperties.Design`                               | 无思考相关配置                                | 增 `reasoning-effort` 等（§5.3） |
| `ai_model_config` 表                                        | 无思考列                                   | 增 `reasoning_effort`（§5.1）   |

---

## 7. 规范真相源治理：消除"孤儿技能"（2026-10-29）

### 7.1 起因：一台机器上的技能，一周后凭空消失

排查 `template-spec` 副本漂移时顺带发现：设计方法论技能 `design-brief`
**从未进过版本控制**，只存在于某台开发机的 `~/fastcms/skills/design-brief/SKILL.md`。

追溯完整链条（证据在 `~/.trae-cn/memory/` 的会话记录里）：

| 时间 | 事件 |
| --- | --- |
| 2026-09-13 23:17 | 外部 AI 助手排查"官网生成失败"，判定根因是"缺失 design-brief 技能 + 提示词谎称技能已加载" |
| 2026-09-13 23:28 | **手工创建** `~/fastcms/skills/design-brief/SKILL.md`（1398 字符），用于本地验证 |
| 2026-09-14 16:33 | 提交 `b730f1fd` 引入 `FileSkillRepository` 与 `DESIGN_BRIEF_SKILL_ID` —— **代码引用了技能，但技能文件没进仓库** |
| 2026-09-14 00:37 | 遗留决策记录在会话话题里："design-brief 技能的分发方式（file 源 vs classpath 兜底源）" —— **代码里从未实现** |

结果：换机器 / 重新部署 / 新同事拉代码 → 技能消失，而代码调用点
`skillRegistry.loadContent("design-brief")` 返回 null → **静默降级**：
模板照常生成，只是少了一段方法论约束。从日志里完全看不出来。

`FileSkillRepository` 从不 seed 任何技能（只建目录、只扫描），
所以 `~/fastcms/skills/` 里的东西**全部依赖外部手工放置**——这是设计上就存在的漏洞。

### 7.2 根因：代码侧硬依赖 vs 模型侧按需加载，两套语义被混为一谈

之前把"skill 按需 load、没有 required 标记"这条原则套到了所有技能上，
但代码里实际存在**两类完全不同的技能依赖**：

| | 模型侧 skill manifest（L1 清单） | 代码侧硬依赖 |
| --- | --- | --- |
| 谁决定加载 | 模型按需自主决定（`load_skill` 两段式） | 代码直接读正文注入系统提示词，**无裁量空间** |
| 缺失后果 | 模型少知道一点背景，仍能完成任务 | 提示词少一段硬约束，**产出静默降级且无人察觉** |
| 缺失处理 | 不阻断（这就是"按需 load"的语义） | **必须明确失败** |

`design-brief` 属于后者，却被当成了前者。

### 7.3 治理动作

#### 7.3.1 迁进插件：内容进版本控制

新建 `plugins/design-skills-plugin`（与 `article-skills-plugin` / `template-skills-plugin` 同构）：

- `skills/design-brief/SKILL.md` —— 内容与原文件**逐字节一致**（sha256 校验），未改一字
- `DesignSkillsProvider`（`@Extension`，继承 `ClasspathSkillProvider`）
- `Plugin-Id: design-skills-plugin`，`Plugin-Version: 1.0.0`

技能 id 从裸 slug `design-brief` 改为 `design-skills-plugin/design-brief`。

#### 7.3.2 删除内置副本：`SPEC_PROMPT_BUILTIN`

`TemplateGenPromptBuilder` 里那份 10650 字符的 Java 内置副本**已删除**。
它长期与插件正文漂移（缺 §1.0 CSS/JS 分离、§1.1 栏目专属页、§1.2 _components），
静默回退到它 = 模型拿到残缺规范却毫无察觉。

#### 7.3.3 三道闸门（`RequiredSkills` + `RequiredSkillChecker`）

| 闸门 | 位置 | 行为 |
| --- | --- | --- |
| **构建期** | `RequiredSkillsTest` | 扫描插件源码树，断言每个硬依赖技能都有对应 SKILL.md —— **加了依赖忘放技能 → 编译期测试失败** |
| **启动期** | `RequiredSkillChecker`（`ApplicationReadyEvent`） | 缺失时在启动日志打 ERROR 醒目告警，列出用途与修复方式 |
| **运行期** | `RequiredSkills.requireContent` | 缺失时**抛异常**，不再静默降级 |

新增 `RequiredSkills` 类集中声明代码侧硬依赖清单（skillId → 用途说明）。
未登记的技能直接拒绝 —— 防止新增调用点忘记登记，绕过自检。

### 7.4 实测验证

| 项 | 结果 |
| --- | --- |
| 新插件 jar 运行时解析 | `design-skills-plugin/design-brief` → 1398 字符，内容正确 |
| template-spec 重新部署后 | 13132 字符（此前 11463），**§1.0 / §1.1 / §1.2 三节齐全** |
| 新增测试 | `RequiredSkillsTest` 8 例全绿 |
| ai-starter 全量 | **290 例，2 失败**（`MockupConverterNavIaTest` / `MockupConverterNavMenuifyTest` 的 nav active 断言 —— 已用 `git stash` 验证为**改造前既有基线失败**） |
| 全量构建 | **22 模块 BUILD SUCCESS** |
| 本机 file 源副本 | `~/fastcms/skills/design-brief/` → 迁移至 `~/fastcms/skills-backup/design-brief-migrated-2026-10-29/`（**移动非删除**，可回滚）；迁移后 `skills/` 为空，不再有重复条目 |

### 7.5 本批后续（2026-10-29 第二批）

第 7.3 节的遗留项已在第二批处理完毕，见 [第 8 章](#8-插件版本兼容与正文契约闸门2026-10-29)：
manifest 版本兼容声明、`Plugin-Version` 统一、以及「正文漂移」这道缺失之外的第二道闸门。

---

## 8. 插件版本兼容与正文契约闸门（2026-10-29）

### 8.1 起因：第 7 章只堵了一半

第 7 章治理了「技能**缺失**」（孤儿技能）。但同一次排查暴露了另一半——
**已安装的插件 jar 里的技能正文可能比主程序期望的旧**。

Sep 24 那次事故的真实形态是：主程序代码已要求 template-spec 含 §1.0 CSS/JS 分离、
§1.1 栏目专属页、§1.2 _components，但已安装的 jar 是旧版，**正文里没有这三节**。
这类漂移的特点是：

- 插件**照常加载**，不报任何错
- 正文**照常注入**提示词，只是少了三节
- 模型拿到残缺规范**照常出图**
- 从日志里**完全看不出来**

manifest 版本声明只能拦「插件比主程序新」，**拦不住「插件比主程序旧」**。
两者必须一起堵，才是完整的。

### 8.2 技术根因：pf4j 的版本校验被短路绕过

pf4j 3.13 内置了 `Plugin-Requires` + `DefaultVersionManager.checkVersionConstraint`，
但比对的是 `AbstractPluginManager.systemVersion`，而它默认为 `"0.0.0"`：

```java
// AbstractPluginManager:808
if (systemVersion.equals("0.0.0") || versionManager.checkVersionConstraint(systemVersion, requires)) {
    return true;   // systemVersion 为 0.0.0 → 整个校验短路 → 恒返回 true
}
```

fastcms 从未调用 `setSystemVersion()`，且 `Plugin-Requires` 的语义是
「插件要求的 **Java/Spring 运行环境**版本」，不是宿主应用版本。

**结论：不复用 pf4j 的 requires 机制，自建 fastcms 专属的兼容声明。**

另一处坑：pf4j 用 jSemVer 比较，而 SemVer 规则下**预发布版本 < 正式版本**——
`1.0.0-SNAPSHOT` 不满足 `>=1.0.0`。fastcms 当前版本正是 `1.0.0-SNAPSHOT`，
直接复用会导致所有插件被判不兼容。故自建比较：**只比 major.minor.patch 三段数字，忽略后缀**。

### 8.3 闸门 A：插件版本兼容声明

#### 8.3.1 插件侧声明

新增 manifest 键 `Plugin-Fastcms-Version`（语义：插件要求的 fastcms 主程序最低版本）。
统一在 `plugins/pom.xml` 的 `<fastcms.min.version>` 属性中定义，6 个插件的
`maven-jar-plugin` `manifestEntries` 各自写入。

顺带统一 `Plugin-Version`：原先 `0.0.1` 与 jar 文件名的 `1.0.0-SNAPSHOT` 不一致，
现全部改为 `${revision}`（= `1.0.0-SNAPSHOT`），**manifest 与 jar 文件名一致**，便于排查版本。

> 安全性核实：pf4j 卸载走 `pluginRepository.deletePluginPath(pluginWrapper.getPluginPath())`，
> 用的是实际加载路径，**不按版本拼路径** → 改 `Plugin-Version` 不影响卸载。

#### 8.3.2 主程序侧扩展点

pf4j 的 `PluginDescriptor` 是固定接口，无法携带任意 manifest 键。故新建：

| 类 | 作用 |
| --- | --- |
| `FastcmsPluginDescriptor` | 继承 `DefaultPluginDescriptor`，额外承载 `fastcmsVersion` |
| `FastcmsPluginDescriptorFinder` | 继承 `ManifestPluginDescriptorFinder`，**只覆写创建描述符这一个钩子**，标准字段照常填，多带一个键 |
| `PluginCompatibility` | 兼容性判定（纯函数，无副作用，便于单测） |

`FastcmsPluginManager` 覆写 pf4j 的 `createPluginDescriptorFinder()` 与 `isPluginValid()`。

#### 8.3.3 拦截点选择

pf4j 在 **`loadPlugin` 与 `enablePlugin` 两条路径**上都会调用 `isPluginValid()`，
失败即置 `PluginState.DISABLED` → `startPlugins()` 跳过它 → **其扩展（含 SkillProvider）不会被注册**。

即：**不兼容的插件在加载阶段就被拦下**，不会进入「加载了但用不了」的半吊子状态。

#### 8.3.4 判定规则

| 情形 | 结论 | 理由 |
| --- | --- | --- |
| 插件未声明 `Plugin-Fastcms-Version` | **兼容** | 第三方插件不受 fastcms 版本约束，不做拦截 |
| 主程序版本读不到 | **兼容但告警** | 无法判定 ≠ 不兼容，但必须留下痕迹，否则同样退化成静默 |
| 声明版本 ≤ 主程序版本 | **兼容** | 老插件跑在新主程序上属正常 |
| 声明版本 > 主程序版本 | **不兼容** | 插件可能调用了当前主程序不存在的能力 |

主程序版本来源：`common` 模块的 `fastcms-version.txt`（构建期资源过滤写入 `${project.version}`），
经 `VersionUtils.version` 读取。

### 8.4 闸门 B：技能正文契约

在 `RequiredSkills.SkillRequirement` 中为每个硬依赖技能声明
**主程序依赖的正文锚点**（章节标题）：

| 技能 | 必需锚点 | 对应的代码侧硬依赖 |
| --- | --- | --- |
| `template-spec` | `### 1.0 CSS/JS 分离`<br>`### 1.1 栏目专属页`<br>`### 1.2 _components 组件目录` | `InlineAssetExtractor` 抽取对象 / 合规校验第 10 项<br>`PageSpec.suffixedPageKey` 专属页命名约定<br>`PageSpecRenderer.COMPONENTS_DIR` 组件物化目录 |
| `design-brief` | `## 视觉层次`<br>`## 留白与节奏`<br>`## 色彩语义`<br>`## CTA 设计` | 设计契约的正文侧依赖 |

**锚点必须对应真实的代码依赖，不能凭空捏造** —— 否则闸门就成了摆设。
上表右列即各锚点的合法性依据。

### 8.5 四道闸门全景

| 闸门 | 位置 | 行为 |
| --- | --- | --- |
| **构建期 1** | `RequiredSkillsTest` | 扫描插件源码树，断言每个硬依赖技能都有对应 SKILL.md |
| **构建期 2** | `RequiredSkillsTest` | 断言插件源码里的 SKILL.md **满足正文契约**（防止改了契约却没改技能正文，构建出的 jar 带着残缺规范上线） |
| **启动期** | `RequiredSkillChecker`（`ApplicationReadyEvent`） | 缺失**与漂移**分别打 ERROR，列出缺失章节清单与修复方式 |
| **运行期** | `RequiredSkills.requireContent` | 缺失**与漂移**均**抛异常**，不静默降级 |

另有一条独立于技能链路的闸门：

| 闸门 | 位置 | 行为 |
| --- | --- | --- |
| **加载期** | `FastcmsPluginManager.isPluginValid` | 插件版本不兼容 → 加载阶段拒绝，插件不启动、扩展不注册 |

### 8.6 实测验证

| 项 | 结果 |
| --- | --- |
| 6 个插件 jar 的 manifest | `Plugin-Version=1.0.0-SNAPSHOT`、`Plugin-Fastcms-Version=1.0.0` 全部写入 |
| 主程序版本读取 | `currentFastcmsVersion()` → `1.0.0-SNAPSHOT`（资源过滤生效） |
| 真实插件 jar 解析 | 两个技能插件声明 `1.0.0` → **放行** |
| 声明 `9.9.9` | **正确拦截**，异常含「请升级主程序或换用与当前主程序匹配的插件版本」 |
| 未声明版本 | **放行**（第三方插件不误伤） |
| `-SNAPSHOT` 后缀 | `1.0.0-SNAPSHOT` 正确匹配声明 `1.0.0`（未误判） |
| 正文漂移（模拟旧版 jar） | **明确抛异常**，列出 `### 1.0 CSS/JS 分离`、`### 1.1 栏目专属页`、`### 1.2 _components 组件目录` 三项缺失 |
| 启动期对漂移的识别 | ERROR 日志打出缺失章节清单 + 「请重新构建并部署对应插件」 |
| 新增测试 | `PluginCompatibilityTest` **11 例全绿**；`RequiredSkillsTest` **12 例全绿**（新增 4 例：漂移拦截、缺失项列举、design-brief 漂移、构建期契约一致性） |
| ai-starter 全量 | **294 例，2 失败**（同 §7.4 那两个既有基线失败，与本批无关） |
| 端到端探针 | `SkillBootVerify` 扩到 8 步（新增漂移拦截、manifest 声明验证）；新增 `PluginCompatVerify`（4 步，含拦截与放行双向验证） |

### 8.7 遗留

- `Plugin-Fastcms-Version` 目前统一取 `fastcms.min.version=1.0.0`，
  未随主程序版本自动递增。**升级 fastcms 主版本时需同步提升该值**，
  否则旧插件在新主程序上仍会放行（向后兼容本身没问题，
  但主程序若移除旧能力，旧插件会运行期报错而非加载期被拦）。
- 后台插件列表（`ui/src/views/plugin/index.vue`）未展示兼容状态，
  用户无法从界面看出某个插件是否被版本闸门拦下——目前只能看启动日志。

