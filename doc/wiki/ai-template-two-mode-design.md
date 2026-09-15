# AI 生成模板双模式——具体实现与兼容方案

> 版本：v2.0（2026-09-13，取代 v1.0 设计综述版）
> 定位：**实现级技术方案**——类/方法/字段/SQL/端点逐一落到代码坐标，并给出与现有管线模式、存量数据、旧客户端的完整兼容策略。
> 背景论证（为何两种模式、管线 vs 自主的取舍）见 `ai-template-two-mode-design.md` v1.0 与本文附录 C 摘要；本文不再重复论证。
> 约束：动源码前须获用户明确同意。本文所有"改动点"均标注【新增】/【修改】，修改点单独汇总于 §9.3。

---

## 1. 范围与总体决策

### 1.1 做什么
在现有"AI 模板生成器"（管线模式：PageSpec → 组件渲染，`gen-mode=component`）之外，
新增**设计稿先行模式**（design）：

```
① AI 自主设计（自主型智能体，可加载设计技能）→ design/ 下纯 HTML 设计稿
② 机器审计（确定性 A1~A7 清单）→ 不过则 AI 修正（≤2 轮）
③ 确认关口：confirmAuto=true → 审计通过自动放行；false → 前端确认卡片
④ 确定性转化（代码驱动，AI 只做区块→组件语义映射）→ 正式模板文件
⑤ 复用既有 apply/预览/调整管线
```

### 1.2 不做什么（v1 边界）
- 不改模式1（管线）任何生成逻辑——物理隔离，仅入口分流
- 不做设计稿像素级截图对比（需 headless 浏览器）
- 不做设计稿局部热更新（用户提修改 = 整页重出）
- 转化阶段不做自主 AI（只保留"区块映射"一处 AI 决策，见 §6.2）

### 1.3 关键决策表

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| D1 | 模式字段命名 | `createMode`（`pipeline`/`design`） | **现有 `genMode`（L287，`@Value gen-mode:component`）已被占用**为 component/html 全局开关，语义是"管线内部实现选择"；新字段是"用户选择的生成方式"，不可复用同名 |
| D2 | 分流位置 | `doChatStream` 开头一次判断 | 与 `sceneOf`（L747）同层；不扩散到 controller/请求解析 |
| D3 | design 智能体 | 新建 `builtin.template-designer`（`MODE_CHAT` 自主型） | 参照 `builtin.article-writer` 完整消费三通道；`AgentProfile` 现有枚举仅 `MODE_CHAT`/`MODE_PIPELINE`（L42/44），自主型归 `MODE_CHAT` |
| D4 | 确认交互 | 复用 chat 端点 + `AiTemplateChatRequest` 加 `confirmAction` 字段 | 不新开 SSE 生命周期；确认=一次"无输入的特殊对话"，状态机持久化可刷新恢复 |
| D5 | 机器审计 | 纯代码解析，不走 AI | 确定性、可单测、零 token；AI 修正由审计结果驱动 |
| D6 | 转化 AI 边界 | 仅"区块→组件映射"用 AI（输出 JSON + confidence） | 映射是语义问题；其余（切分/tokens 提取/锚点迁移/渲染校验）全确定性 |
| D7 | 总开关 | `fastcms.ai.template.design.enabled`（默认 `false` 灰度） | 一键下线；旧客户端/存量会话零影响 |
| D8 | 用量场景 | 新增 `Scene.TEMPLATE_DESIGN` | 模式2 是付费挂载点，需独立计费/配额口径；新增枚举值不影响存量统计 |

---

## 2. 数据模型改动

### 2.1 版本化 SQL 迁移【新增文件】`doc/sql/fastcms-1.0.0.sql`

遵循 `fastcms-0.2.0.sql` 的既有模式（`ALTER TABLE ... ADD COLUMN ... AFTER ...`，
参照 L41 `template_id`、L121 `prefer_attachment`）：

```sql
-- ----------------------------
-- 双模式生成：会话增加创建模式字段（design=设计稿先行模式，存量数据 NULL 视为 pipeline）
-- 注意：全量脚本与版本脚本的 AFTER 锚点必须一致（§2.1.1 的 DDL 同步说明）
-- ----------------------------
ALTER TABLE ai_template_session ADD COLUMN create_mode varchar(16) DEFAULT NULL
  COMMENT '创建模式: NULL/pipeline=组件管线(默认) design=设计稿先行' AFTER mobile_adaptive;

ALTER TABLE ai_template_session ADD COLUMN design_direction varchar(64) DEFAULT NULL
  COMMENT '设计模式方向资产 key（如 business-elegant / feedback-brighten）' AFTER create_mode;

ALTER TABLE ai_template_session ADD COLUMN confirm_auto tinyint(1) DEFAULT 1
  COMMENT '设计模式：机器审计通过后是否自动转化（1=自动，0=等用户确认）' AFTER design_direction;
```

### 2.1.1 全量 DDL 同步【修改：3 处 SQL 需同步】

`ai_template_session` 的 DDL 存在于**三份文件**（实现时逐一核对，防漏）：

| 文件 | 用途 | 同步动作 |
|---|---|---|
| `doc/sql/fastcms-1.0.0.sql`【新增】 | 版本化增量升级 | 上述 3 条 ALTER |
| `doc/sql/fastcms.sql`（L829-847 建表段 + L849-852 增量注释段） | 文档全量 | 建表段 +3 列；增量注释段 +3 条注释（沿用 L850/852 格式） |
| `web/src/main/resources/install/sql/mysql/fastcms.sql`（同结构，L828-852） | **安装程序实际执行的全量 DDL** | 同上 |
| `docker/mysql/initdb/fastcms.sql`（若含 ai_template_session） | docker 初始化 | 核对后同步（无此表则不动） |

**已知不一致（非本方案引入，实现时顺手确认，不扩大范围）**：
`prefer_attachment` 列（`fastcms-0.2.0.sql` L121 有 ALTER）未并入上述两份全量 DDL，
导致全新安装缺该列。本方案的 3 列 AFTER 锚点统一用 `mobile_adaptive`
（三份全量 DDL 均存在），**不再用 `prefer_attachment` 做锚点**以规避此既有不一致。

### 2.2 实体 `service/.../entity/AiTemplateSession.java`【修改：+3 字段 + getter/setter】

```java
/** 创建模式: NULL/"pipeline"=组件管线（默认）; "design"=设计稿先行。null 视为 pipeline（存量兼容） */
private String createMode;

/** 设计模式方向资产 key（design 模式有效，可空=随机内置方向） */
private String designDirection;

/** 设计模式：机器审计通过后是否自动转化（null 视为 true） */
private Boolean confirmAuto;
```

### 2.3 请求体 `ai/template/AiTemplateSessionRequest.java`【修改：+3 同名字段】

全部可选；服务端校验规则：
- `createMode` 非空且非 `design`/`pipeline` → 400（枚举白名单，防注入）
- `createMode=design` 且 `designDirection` 非空 → 必须命中 `DesignDirectionLibrary` 已有 key，否则 400
- `createMode=design` 与 `templateId` **互斥**：design 仅支持生成型会话（新建模板），
  调整型会话（`templateId` 非空）忽略 createMode 并 SSE 提示

### 2.4 设计会话状态机（持久化，支持刷新/断点续传）

状态落 `workDir/design/plan.json`（沿用升级计划文件模式：文件即状态，重启可恢复）：

```json
{
  "version": 1,
  "state": "AWAITING_CONFIRM",        // DESIGNING / AUDITING / AWAITING_CONFIRM / CONVERTING / DONE / FAILED
  "direction": "business-elegant",
  "pages": [
    {"name": "index", "html": "design/index.html", "status": "done", "auditRounds": 1, "issuesFixed": 2},
    {"name": "about", "html": "design/about.html", "status": "done", "auditRounds": 0, "issuesFixed": 0}
  ],
  "mappingCache": [                   // 转化映射缓存（断点续传时已映射区块不重问 AI）
    {"page": "index", "sectionIdx": 2, "component": "cards", "confidence": 0.9, "status": "done"}
  ],
  "history": ["AUDIT_FAIL: A3(index 硬编码色值)→fixed", "CONFIRM: auto"]
}
```

状态迁移（代码强制，非法迁移抛异常 + SSE 播报）：

```
DESIGNING → AUDITING →（审计通过 ∧ confirmAuto）→ CONVERTING → DONE
                 │         └→（审计失败且<2轮）→ DESIGNING
                 │         └→（审计失败≥2轮）→ AWAITING_CONFIRM（降级人工兜底）
                 └→（confirmAuto=false 且审计通过）→ AWAITING_CONFIRM
AWAITING_CONFIRM →（approve）→ CONVERTING → DONE
AWAITING_CONFIRM →（reject+comment）→ DESIGNING（携带 comment 重出）
任意状态 →（FAILED：模型连续失败/格式不可救）→ FAILED（保留已有文件，可重新发消息继续）
```

---

## 3. 智能体注册【新增】

`BuiltinAgents.java` 追加（参照 `TEMPLATE_GENERATOR_ID` L46 的注册模式）：

```java
public static final String TEMPLATE_DESIGNER_ID = "builtin.template-designer";
```

Profile 构造：

```java
AgentProfile.builder()
    .agentId(TEMPLATE_DESIGNER_ID)
    .name("模板设计智能体")
    .description("设计稿先行模式：自主设计站点 HTML 设计稿。自主型——AI 自主决定加载哪个设计技能")
    .executionMode(AgentProfile.MODE_CHAT)          // 现有枚举（L42/44）；自主型参照 article-writer
    .source(AgentProfile.SOURCE_BUILTIN)
    .systemPrompt(DESIGN_SYSTEM_PROMPT)             // 见 §4.3 设计契约
    .skills(List.of("design-brief"))                // L1 清单注入；方向技能运行时按 designDirection 追加
    .tools(Collections.emptyList())                 // 设计阶段无业务工具，纯文本输出
    .modelConfigId(null)                            // 继承全局激活模型（与 template-generator 同策略）
    .build();
```

**与 `builtin.template-generator` 的分界**（写进类注释，防止后人误用）：

| | template-generator（管线） | template-designer（设计） |
|---|---|---|
| executionMode | `PIPELINE` | `CHAT`（自主型） |
| systemPrompt | null（提示词由管线代码拼） | 设计契约（代码拼场景段后追加） |
| skills | 空（管线不用技能——控制权在代码） | 非空（**自主型正是技能两段式的适用场景**） |
| 谁决定输入输出 | 管线代码 | AI 自主 + 格式契约兜底 |

---

## 4. 设计段实现【全部新增，模块：`ai/template/design/`】

### 4.1 新增类清单

| 类 | 职责 | 依赖 |
|---|---|---|
| `MockupDesignService` | 设计段编排：轮次 loop、格式校验、降级分页、SSE 播报 | `AgentChatExecutor`、`SkillRegistry`、`DesignDirectionLibrary` |
| `DesignContractPrompt` | 设计契约提示词拼装（需求+方向资产+上轮审计问题+格式契约） | 纯静态 |
| `DesignHtmlValidator` | 设计稿格式校验（V1~V6，失败给 AI 的修正指令文本） | Jsoup（项目已有） |
| `MockupAuditor` | 机器审计 A1~A7（纯解析，输出 `List<AuditIssue>`） | Jsoup |
| `DesignPagePlanner` | 需求→页面规划（4 页默认：index/about/services/contact，需求含多产品则 services 拆多页） | 确定性规则，不走 AI |

### 4.2 设计 loop 伪代码（`MockupDesignService.design()`）

```
for page in pages:
    for round in 0..2:
        prompt = DesignContractPrompt.build(
            requirement, directionAsset,          // DesignDirectionLibrary 按 key 取 few-shot/do/dont/tokens
            page, prevAuditIssues, prevFormatErr)
        resp = agentChatExecutor.chatStream(prepared, prompt, channel)   // 复用 article-writer 同款调用
        files = parseFileBlocks(resp)            // 复用既有文件块解析（callModelRound 同款）
        errs = DesignHtmlValidator.validate(files)
        if errs empty: 落盘 design/<page>.html; break
        else: prevFormatErr = errs（下一轮修正指令）
    if 3 轮未收敛: 降级——该页单独重出 1 次；仍失败 → SSE 播报该页降级为"占位页"，继续下一页
```

**格式契约校验 V1~V6**（`DesignHtmlValidator`，全确定性）：
- V1 每页文件块齐全（index.html 必有 `<section data-block>` 区块 ≥ 3）
- V2 顶层区块必须是 `<section>`（与升级管线第 8 条硬约束同口径）
- V3 公共 token 变量齐全（`--c-primary` 等 5 个必备 CSS 变量，缺则 AI 补齐）
- V4 锚点齐全：`#nav-toggle`/`#footer` 存在（JS 交互迁移依据）
- V5 图片路径全部为 `design/assets/placeholder-*.svg` 或附件库 URL（不允许写死外链图床）
- V6 无 FreeMarker 语法泄漏（`<#`/`${` 不允许出现在设计稿——设计稿是纯 HTML）

### 4.3 设计契约（`DESIGN_SYSTEM_PROMPT` 要点）

```
你是资深网站设计师。按用户描述设计一个完整网站的静态设计稿（纯 HTML + Tailwind CDN + 内联样式变量）。
【可转化性契约】（违反将导致无法转成 CMS 模板）：
1. 每个页面 ≥3 个顶层 <section data-block="语义名"> 区块（hero/features/gallery/footer 等）
2. 色彩统一走 :root CSS 变量（--c-primary/--c-accent/--c-bg/--c-text/--c-muted），禁止区块内硬编码色值
3. 导航与页脚在所有页面保持相同结构与 id（#nav-toggle / #footer）
4. 图片用占位：design/assets/placeholder-<语义>.svg（简单几何 SVG），并在 img 上加 data-src-hint="真实图描述"
5. 交互 JS 只允许：汉堡菜单切换 + 锚点平滑滚动 + 简单滚动渐显（其余交互会被丢弃）
【审美契约】：留白优先、字号三级、每个区块一个视觉重点；遵循当前设计技能的方向规范（已加载）。
【输出契约】：文件块格式（===FILE: path===），一次输出该页全部文件。
```

**方向资产注入方式**（与模式1 的差异点，需在注释中写明）：
模式1 方向资产由**代码轮换**注入；设计模式把方向资产内容**写进设计技能 SKILL.md 正文**
（`design-directions/` 下 4 个 JSON 改写为技能包），由 **AI 自主 `load_skill`** 加载——
这正是自主型与管线型的技能投递分界（管线不用技能的理由在这里不适用：方向盘在 AI 手里）。
方向选择：`designDirection` 非空 → 该方向技能进 L1 清单并标注"本次必须加载"；
空 → 全部方向技能进清单，AI 按需求自选（选择结果 SSE 播报 + 写入 plan.json 可审计）。

### 4.4 机器审计 A1~A7（`MockupAuditor`，审计通过才放行转化）

| # | 检查 | 实现 | 失败动作 |
|---|---|---|---|
| A1 | 区块可切分性：顶层 section 数与 V1 一致、无嵌套 section | Jsoup 结构遍历 | 退回设计（附具体区块路径） |
| A2 | token 收敛：正文硬编码色值数 ≤ 阈值（10） | 正则扫 style 属性 + 内联 style | 退回设计 |
| A3 | 响应式：`<meta viewport>` + `@media` 断点 ≥2（768/1024） | 解析 CSS | 退回设计 |
| A4 | 跨页一致性：各页 nav/footer 结构哈希一致 | 结构序列化 + hash | 退回设计（指认差异页） |
| A5 | 占位图可替换性：所有 img 有 `data-src-hint` | 属性遍历 | 退回设计 |
| A6 | JS 白名单：script 内容仅命中 5 条契约交互模式 | 正则 | 退回设计 |
| A7 | 体积护栏：单页 HTML ≤ 60KB（防转化后模板膨胀） | 文件尺寸 | 退回设计（要求精简） |

审计 → 修正 loop ≤2 轮（与升级管线 `MAX_AUDIT_ROUNDS=2` 同口径）；
仍失败 → 进 `AWAITING_CONFIRM` 人工兜底（confirmAuto 用户此时必能看到问题清单）。


---

## 5. 转化段实现【全部新增，模块：`ai/template/design/`】

> 原则（D6）：**转化是确定性管线，AI 只回答"这个区块该映射到哪个组件"**。
> 设计稿阶段 AI 握方向盘；进入转化后方向盘回到代码——错误面不叠加。

### 5.1 五步转化（`MockupConverter.convert()`）

```
输入：workDir/design/*.html + design/assets/ + plan.json
输出：workDir/ 下正式模板文件集（与管线模式产物同构 → 后续复用 applyTemplate L3569）
```

**Step 1 区块切分（纯代码）**
- 每页按顶层 `<section data-block>` 切块，产出 `SectionUnit{page, idx, name, html}`
- 公共块提取：nav（跨页哈希一致段）→ `layout/navigation.ftl`；footer → `layout/footer.ftl`
- tokens 提取：`:root` 变量 → `static/css/tokens.css`（全页 `<link>` 引入）

**Step 2 组件映射（唯一 AI 决策点）**
- 每批 ≤5 个 SectionUnit 摘要（区块名 + 结构特征 + 文本量）发给 designer 智能体：
  输出 JSON `[{sectionIdx, component: "cards"|"hero"|..., confidence: 0-1, reason}]`
- `confidence < 0.7` → 强制 `custom_macro`（宁可自定义宏，不可错配组件）
- 映射结果写 `plan.json.mappingCache`（断点续传时已映射区块不重问 AI，省 token 且保一致）
- 映射 prompt 附**组件清单**：`ComponentRegistry.buildManifest()`（L167+，内置 5 组件 + PF4J 插件组件）——组件库扩容后映射能力自动扩展，零改动

**Step 3 模板装配（纯代码）**
- 命中内置组件 → 组件 FTL + 宏参数（文本/图从区块 DOM 抽取；`data-src-hint` 进 `_preview_data.json` 的 imageOverrides，沿用 L574 既有机制）
- custom_macro → 区块 HTML 原样入 `macros/custom.ftl`，补宏签名
- 页面文件 = layout include + 组件调用序列（与管线模式产物结构一致）
- `template.properties` 由 `DesignPagePlanner` 的页面规划生成（title/description/pages）

**Step 4 锚点迁移（纯代码）**
- 复用 `LegacyStyleUpgrader.scanAnchors`：设计稿 `#nav-toggle` 等交互脚本迁移进模板 JS，
  锚点缺失 → 播报 + 按 V4 契约补默认交互（汉堡菜单是确定性实现，不走 AI）

**Step 5 渲染校验（复用）**
- 与升级管线同套：FreeMarker 渲染每页 → 失败进修复轮（`MAX_RENDER_FIX_ATTEMPTS=2`，L132）
- 修复轮提示词携带渲染错误原文（同升级管线既有实现）

### 5.2 降级策略（每级 SSE 实况播报，原则同升级管线）

| 失败点 | 降级 |
|---|---|
| 单区块 Step 2 映射 2 次失败 | 该区块 → custom_macro（页面完整，播报"第 N 区块已转为自定义区块"） |
| 单页 Step 5 渲染 2 轮修不好 | 该页整页 → 单 custom_macro（页面可见，播报降级） |
| 多页失败 ≥ 半数 | 暂停转化，`AWAITING_CONFIRM` 展示失败清单（approve 带伤转化 / reject 回设计段） |
| 全失败 | `FAILED`，保留 design/ 目录，用户可"从设计稿继续"（plan.json 断点续传） |

### 5.3 产物同构性（兼容 apply 的关键）

转化产物**必须**满足 `applyTemplate`（L3569）的既有前提：
- `template.properties` 存在（L3582 校验）
- 目录结构与管线模式产物一致（layout/、static/、宏目录）
→ 满足则 `applyTemplate` **零改动复用**（含同名并发锁、initialize+refreshStaticMapping）。
**验收标准**：design 模式产出的模板与 component 模式产出通过同一渲染冒烟测试。

---

## 6. 会话入口分流与交互协议

### 6.1 `doChatStream` 分流（唯一修改的既有方法）

`doChatStream`（L683）开头插入（在 `prepared` 装配 L691 之前）：

```java
// 双模式分流：design 模式独立编排（设计→审计→确认→转化），管线逻辑零改动
if (AiTemplateConstants.CREATE_MODE_DESIGN.equalsIgnoreCase(session.getCreateMode())) {
    if (StringUtils.hasText(session.getTemplateId())) {
        sendError(channel, "设计稿模式仅支持新建模板会话");
        channel.complete();
        return;
    }
    if (!designModeEnabled) {                       // §9.1 总开关
        sendError(channel, "设计稿模式暂未开启，请联系管理员");
        channel.complete();
        return;
    }
    mockupOrchestrator.run(session, userInput, requestConfirmAction, channel);
    return;
}
// ↓ 以下全部既有管线代码，不动
```

配套：
- `chatStream`（L615）签名 +1 参数 `String confirmAction`（`APPROVE`/`REJECT`/null）
- `IAiTemplateGenService`（接口）同步 +1 参数
- `AiTemplateController.chat`（L169）从 `AiTemplateChatRequest` 透传

**为何不新开端点**：确认动作本质是"对设计会话的一次状态推进"，走 chat 端点则
SSE 生命周期、`requireOwnedSession` 鉴权（L172）、取消/断线处理（SseChannel）、
失败落库（MSG_FAIL_PREFIX 前缀消息）全部免费复用；新开端点 = 重写一套。

### 6.2 请求/事件协议

`AiTemplateChatRequest`【修改：+1 可选字段】：
```java
/** 设计稿模式确认动作: APPROVE（审计通过稿放行转化）/ REJECT（附 input 作为修改意见重出）。仅 design 会话有效 */
private String confirmAction;
```

SSE 事件（**全部复用既有事件名**，不新增协议；`confirm_request` 为新增事件名，处理见 §7.4）：

| 时刻 | event | data 要点 |
|---|---|---|
| 设计段启动 | `message` | "正在设计设计稿（方向：商务简约）…" |
| 每页设计稿就绪 | `preview`（既有） | url 指向 `design/<page>.html` 预览路径 |
| 审计问题/修复 | `message` | "审计发现 2 个问题（A2 硬编码色值 / A3 缺响应式），正在修正…" |
| 等人工确认 | `confirm_request`（**新增**） | `{state:"AWAITING_CONFIRM", issues:[...], previewUrl:...}` |
| 转化进度 | `message` | "组件映射 3/7… 渲染校验 2/5…" |
| 降级/完成 | `message` / `done` | 降级说明；结构一致报告（区块数/tokens 数/锚点数 设计稿 vs 模板） |

### 6.3 预览路径（零后端改动验证点）

`AiTemplatePreviewController` 路由 `/ai/template/preview/{sessionId}/{templateName}/**`：
`.html` 走 FreeMarker 渲染、其余静态直出。设计稿是**纯 HTML（V6 保证无 FTL 语法）**，
`design/` 子路径落在静态直出分支 → 直接可预览。
**实现时验证项**（写入 M1 出口标准）：
1. `design/` 相对路径不被"模板目录假设"拦截（需读预览控制器路径解析代码确认）
2. FreeMarker 分支若对 `design/*.html` 误触发渲染——V6 契约已排除 FTL 语法，
   误渲染也只会原样输出，可接受；若控制器有目录白名单则需放行 `design/`（小改）

---

## 7. 兼容方案（重点）

### 7.1 与存量数据/存量会话兼容

| 兼容项 | 策略 | 落点 |
|---|---|---|
| 存量会话行 `create_mode=NULL` | 全代码统一判空：**`null`/`"pipeline"` = 管线模式**。工具方法 `AiTemplateConstants.isDesignMode(session)` 唯一出口，禁止各处散落 `equals` 判断 | §2.2/§6.1 |
| 存量 `plan_files` 语义 | design 模式的新状态文件是 `workDir/design/plan.json`，**不复用** `ai_template_session.plan_files`（该字段语义 = 管线分批文件清单，刷新进度卡在用）；两种 plan 物理隔离，互不解析 | §2.4 |
| 存量 `workDir` | design 产物全部在 `workDir/design/` 子目录；正式模板文件仍写 `workDir/` 根 → 工作目录结构约定不变，`resolveEffectiveWorkDir`（L454）零改动 | §5.3 |
| 已 applied 的 design 会话 | 转化产物与管线产物同构 → 后续"AI 调整"走统一调整管线（`templateId` 非空即调整型，`sceneOf` L747 天然正确），**不感知设计稿血统**；`design/` 目录仅留档（会话删除时清理） | §5.3 |
| 数据库升级失败兜底 | SQL 迁移仅 `ADD COLUMN`（无数据回填、无索引重建、无锁表风险）；若迁移未执行而代码已上线 → 首次读写 `create_mode` 报"Unknown column"，表现为**创建 design 会话失败**（明确报错），管线模式会话不受影响（列缺失只影响 design 分支）。发布顺序：**先发 SQL 后发代码**（沿用既有发版流程） | §2.1 |

### 7.2 与旧客户端/API 兼容

| 兼容项 | 策略 |
|---|---|
| 旧客户端创建会话（请求体无 `createMode`） | 服务端默认 `pipeline` → 行为与现在 100% 一致 |
| 旧客户端 chat 请求（无 `confirmAction`） | `null` = 普通对话；管线会话收到该字段直接忽略（`isDesignMode` 前置判断） |
| 旧客户端遇 `confirm_request` 新事件 | 前端按"未知事件忽略"原则处理（现有 SSE 前端已有未知事件忽略逻辑则零改动；若没有，需前端补一行 `default: break`——**实现时核对**） |
| 新客户端对旧服务端 | 请求多带的字段被旧服务端 JSON 反序列化忽略（`FAIL_ON_UNKNOWN_PROPERTIES` 配置需核对，通常 fastcms 默认宽松） |
| `GET /sessions`、`/files`、`/messages` 等读端点 | `AiTemplateSession` 序列化自动多出 3 个可空字段，旧前端忽略；`design/` 文件出现在 `/files` 树中 → 前端文件树对 `design/` 目录显示为"设计稿"分组（前端小改，可延后，不阻塞） |
| 权限模型 | 复用既有 `ai:template:chat`/`ai:template:apply` 资源点（`@Secured` L168），**不新增权限项**——模式是功能形态不是新权限域 |
| 用量/配额 | 新 `Scene.TEMPLATE_DESIGN`（§8.2）：存量报表按 scene 分组，新枚举值只影响新数据；`prepare`（L689）的配额检查逻辑零改动（按 userId+agentId 聚合） |

### 7.3 与模式1（管线）兼容——零改动承诺

**模式1 允许触碰的既有代码仅 3 处**（全部是"加分支"型修改，管线路径行为不变）：

1. `AiTemplateGenServiceImpl.chatStream`（L615）：签名 +1 参数 `confirmAction`——
   管线分支不消费该参数
2. `AiTemplateGenServiceImpl.doChatStream`（L683）：开头 +design 分流块（§6.1）——
   管线会话（`isDesignMode=false`）不进入分支
3. `IAiTemplateGenService` 接口：同步签名

其余全部新增文件。验证手段：
- **回归基线**：实现前对管线模式跑一遍既有冒烟（生成/微调/升级/apply 各一条路径），
  留产物快照；实现后同参数重跑，产物 diff 必须为空（提示词/参数未变则产物应一致，
  除 AI 固有随机性——用 2 次跑通 + 人工抽查替代逐字节 diff）
- **开关回滚**：`fastcms.ai.template.design.enabled=false` → design 入口全关，
  代码层面等价于"该功能不存在"

### 7.4 新 SSE 事件 `confirm_request` 的兼容处理

- 事件名新增（非破坏性：SSE 事件按名字分发，旧前端忽略）
- data 结构固定：`{state, issues[], previewUrl, confirmAuto}`
- 超时语义：`AWAITING_CONFIRM` **不占线程**（orchestrator 在该状态直接 return，
  线程池归还；下一次 chat 调用时从 `plan.json` 恢复状态推进）——
  与 SseEmitter 30min 超时（`SSE_TIMEOUT`）不冲突，无长连接挂起风险
- 前端拒绝确认后的修改意见走 `input` 字段（REJECT + input=意见文本），
  设计 loop 把意见拼入下一轮提示词"用户具体不满"段（与升级管线 feedback 同口径）

### 7.5 配置项汇总（`application.yml`，全部带默认值，不配也能跑）

```yaml
fastcms.ai.template:
  design:
    enabled: false            # 总开关（灰度用），默认关
    max-audit-rounds: 2       # 审计→修正轮上限（对齐升级管线 MAX_AUDIT_ROUNDS）
    max-format-rounds: 3      # 设计格式校验轮上限
    design-max-tokens: 24000  # 单页设计稿输出上限（防大输出失控，沿用 callModelRound 保险丝机制）
    section-confidence-threshold: 0.7   # 组件映射置信阈值（低于→custom_macro）
```

---

## 8. 智能体/用量侧改动清单

### 8.1 `BuiltinAgents`【修改：+1 常量 +1 profile】
（§3 已给完整代码）

### 8.2 `IAiUsageLogService.Scene`【修改：+1 枚举值】
`TEMPLATE_DESIGN`（设计段+转化段统一记此场景；转化段 AI 调用也归 designer 智能体
→ usageRecorder.record 的 agentId 参数用 `TEMPLATE_DESIGNER_ID`）。
**兼容**：枚举新增值不破坏既有按 scene 的聚合查询（未知值只会出现在新数据）。

### 8.3 `AgentChatExecutor`【零改动】
自主型装配三件套（`getBaseSystemPrompt`/`load_skill`/`createChatClient`）article-writer 已在用，
designer 是第二个消费者，不引入新机制。

---

## 9. 实施顺序与验证计划

### 9.1 里程碑（依赖序，每级可独立验收）

| 级 | 内容 | 前置 | 验收（出口标准） |
|---|---|---|---|
| S0 | SQL 迁移 + 实体/请求体 3 字段 + `isDesignMode` 工具方法 + 配置项 | 无 | 迁移脚本在空库/存量库各跑一次通过；管线模式冒烟回归无差异 |
| S1 | designer 智能体注册 + 设计契约 + `MockupDesignService` 设计 loop + 格式校验 + 设计稿预览 | S0 | 开关打开后：需求 → 4 页可预览设计稿；V1~V6 2 轮内收敛；设计稿在预览控制器可见 |
| S2 | `MockupAuditor` A1~A7 + 审计修正 loop + confirmAuto/确认卡片协议 | S1 | 故意构造坏设计稿（硬编码色值/缺断点）→ 审计拦截率 100%；confirmAuto=false 时确认卡片可 approve/reject 且刷新后状态不丢 |
| S3 | `MockupConverter` 五步转化 + 降级 + mappingCache 断点续传 | S1（可并行 S2） | design 产物过 applyTemplate 零改动 + 渲染冒烟；单区块/单页降级路径各验证一次；中断重连续传不重问已映射区块 |
| S4 | `doChatStream` 分流 + controller 透传 + 前端模式选择对话框 + 收尾报告 | S1~S3 | 端到端：选 design → 全流程 SSE 播报完整；选 pipeline → 与现状一致；开关关闭 → design 入口隐藏 |
| S5 | 设计方向技能包 2~3 个（4 JSON 改写）+ 插件市场挂载验证 | S1 | 选方向 vs 不选：设计稿风格差异人工可辨识；`load_skill` 调用记录可查（审计日志） |

### 9.2 全量改动文件汇总

**新增（10）**：
1. `doc/sql/fastcms-1.0.0.sql`
2. `ai/template/design/MockupDesignService.java`
3. `ai/template/design/MockupConverter.java`
4. `ai/template/design/MockupAuditor.java`
5. `ai/template/design/DesignHtmlValidator.java`
6. `ai/template/design/DesignPagePlanner.java`
7. `ai/template/design/DesignContractPrompt.java`
8. 设计技能包目录 `resources/ai/skills/design-*/SKILL.md`（2~3 个）
9. 前端：模式选择对话框组件（模板生成器入口页）
10. 前端：`confirm_request` 确认卡片组件

**修改（8，最小集）**：
| # | 文件 | 改动量 |
|---|---|---|
| 1 | `entity/AiTemplateSession.java` | +3 字段（§2.2） |
| 2 | `ai/template/AiTemplateSessionRequest.java` | +3 字段（§2.3） |
| 3 | `web/controller/admin/AiTemplateController.java` | chat 端点透传 confirmAction（§6.1） |
| 4 | `service/IAiTemplateGenService.java` | chatStream 签名 +1 |
| 5 | `service/impl/AiTemplateGenServiceImpl.java` | chatStream 签名 +1；doChatStream 开头分流块；@Value 配置注入（§6.1/§7.5） |
| 6 | `ai/template/AiTemplateChatRequest.java` | +1 字段 confirmAction |
| 7 | `ai/agent/BuiltinAgents.java` | +1 智能体（§3） |
| 8 | `IAiUsageLogService`（Scene 枚举） | +1 值 |
（前端 SSE 未知事件忽略逻辑、文件树 design/ 分组——待核对后可能追加 1~2 处小改）

### 9.3 红线核对（实现前逐条确认）
- [ ] SQL 先发后代码（§7.1 发布顺序）
- [ ] 管线模式回归基线留档（§7.3）
- [ ] `design.enabled=false` 为生产默认值（灰度打开）
- [ ] 所有新增类不引用模式1 私有方法（隔离性 code review 检查项）
- [ ] confirmAction 在管线分支被忽略（§7.2 表）

---

## 附录 C：背景论证摘要（详见 v1.0 文档）

- 两模式 = 客群分层：管线（快/稳/省 token/精美度受 5 组件库约束）vs 设计稿（灵活/AI 想象力决定/费 token）
- 设计稿模式核心风险不在"设计丑"而在**转化损耗 + 难归因** → 转化必须确定性（§5 原则 D6）
- 机器审计替代人工确认（人确认降级为 confirmAuto=false 可选）
- 技能体系在设计段是正确投递方式（自主型方向盘在 AI），在管线段是错误的（方向盘在代码）
- 商业化：design 模式 + 方向技能包 = 插件市场付费挂载点

