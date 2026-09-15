# 样式组件化升级「焕新可靠性」修复技术方案（问题 1-5）

> 状态：方案评审稿（未动任何源码）
> 范围：`starters/ai-starter` 焕新管线（`LegacyStyleUpgrader` + `TemplateGenPromptBuilder` + `AiTemplateGenServiceImpl` + `DesignDirectionLibrary`）+ `ui/aiChat.vue`（仅问题 2b）
> 前置：`doc/wiki/style-upgrade-iteration-fix-plan.md`（P0-P2 已实施，本方案修复其实施后经代码复核确认仍遗留的问题 1-5）

---

## 0. 问题清单与修复总览

| # | 严重度 | 问题 | 根因 | 修复策略 |
|---|---|---|---|---|
| 1 | 🔴 | 区块级焕新静默失效 | 提示词无 `<section>` 约束 → AI 用 div 分区；对位要求区块数严格相等；降级只留 warn 日志 | 1a 提示词硬条款 + 1b 区块数漂移审计 + 1c 降级可见化 |
| 2 | 🔴 | 深度焕新丢弃对话调整 | 恢复源永远是首次升级前的原始备份；无任何丢弃提示 | 2a 焕新重建基线 + 2b 对话修改计数与提示 |
| 3 | 🔴 | 反馈关键词反语义命中 | 单字关键词 + 纯子串匹配 + 无否定剥离 | 3a 关键词表整编 + 3b 整体不满的自诊提示 + 3c 命中情况透出 |
| 4 | 🟡 | 轮换不排除被否决方向 | 方向解析只看轮次，不看历史 | 拒绝方向集合持久化 + 池耗尽显式提示 |
| 5 | 🟡 | 反馈定向修正拿到最贫瘠输入 | 反馈命中即返回 null（无 tokens/资产注入） | 4 个反馈方向各配资产，命中即注入 |

依赖关系：问题 2a（基线重建）让 1b 的漂移审计参照系更稳；问题 4 的 `nameToKey` 反查与问题 2 共用。实施顺序按 1→2→3→4→5。

---

## 1. 问题 1：区块级焕新静默失效

### 1.1 现状（代码实证）

区块级焕新链路三环节全部脆弱：

- **环节一（源头）**：`buildStyleUpgradePrompt` 设计规范 7 条（`TemplateGenPromptBuilder` L687-697）通篇无 `<section>` 要求，铁律区（L698-727）亦无。AI 升级旧站时用 `<div>` 分顶层区块是常态 → `locateTopLevelSections`（`LegacyStyleUpgrader` L2251）返回空 → 指纹「顶层区块 0 个」→ AI 范围评估无从选择区块级。
- **环节二（对位）**：`restoreSectionsFromBackup`（L2288）要求当前/备份版本顶层 section 数**严格相等**（L2304）。上一轮升级若增删过区块即失败。
- **环节三（可见性）**：失败降级整文件恢复只留 `log.warn`（L444），无 SSE；服务层前置消息（`AiTemplateGenServiceImpl` L1218-1222）用恢复**前**的 `smartScope.sectionRedo().size()` 播报「其中 N 个仅恢复方向耦合的区块」，降级发生后与事实不符。

### 1.2 修复 1a：提示词硬条款（治源头）

`buildStyleUpgradePrompt` 设计规范新增第 8 条：

```
8. 顶层区块语义化：页面的顶层内容区块必须用 <section> 标签包裹（一个语义区块一个
   <section>，区块内部结构自由），且顶层 <section> 数量与本文件旧稿保持一致——样式
   升级只改视觉不改内容结构；确需合并/拆分区块时必须在 reply 中说明对应关系
```

`buildRefreshScopePrompt`（L753-788）区块级说明处同步补一句：区块序号即顶层 `<section>` 序号。

**设计取舍（决策点 D1）**：「区块数量与旧稿一致」限制了 AI 增删区块的自由。接受此约束的理由：

1. 区块对应 CMS 内容语义单元（FreeMarker 指令承载的数据区块），样式组件化升级的契约本就是「视觉焕新、功能/内容结构不动」——增删区块等于动了内容结构，与既有铁律同源；
2. 严格相等对位（环节二）是区块级恢复正确性的根本保证，没有它，「恢复区块 N」可能错位到完全无关的内容上；
3. AI 真需要调整区块组织时留了出口（reply 说明对应关系），不是绝对禁止。

**备选方案**（若不接受 D1）：不加数量约束，`section_count_drift` 审计只警告不要求修复，对位失败容忍降级——即维持现状的静默降级，仅做 1c 可见化。不推荐：区块级功能等于名存实亡。

### 1.3 修复 1b：区块数漂移审计（确定性兜底）

`auditUpgrade`（`LegacyStyleUpgrader` L572 起）新增第 5 项机检 `section_count_drift`：

- 对每个计划文件：`locateTopLevelSections(当前内容).size()` 与 `locateTopLevelSections(备份内容).size()` 不一致 → 产出 issue，detail 注明「当前 N 个顶层区块 / 基线 M 个」；
- 零 token 成本，与既有 4 项机检同构；
- `buildAuditFixPrompt` 补修复说明：`[section_count_drift] 顶层区块数与基线不一致：把区块结构对齐基线（以基线的内容区块为准重新组织，视觉按本轮方向设计）`。

**与问题 2 的依赖**：drift 审计的参照系是 `backupDir`。问题 2a（基线重建）落地后，参照系 = 最近一轮焕新基线，一致性语义更稳；不依赖问题 2 也可独立工作（参照原始旧稿，多轮焕新后 drift 会更常见——这本身是真实信号）。

### 1.4 修复 1c：降级可见化（防静默）

1. `StyleUpgradePlan` record（L121-129）加字段 `List<String> sectionDowngrades`（区块级恢复失败、降级整文件重做的文件清单），现有构造点补空清单默认值；
2. `restartPlan` 区块级恢复失败分支（L443-446）把文件记入该清单；
3. 服务层：
   - 前置 SSE 消息（L1218-1227）改为基于 `plan.sectionDowngrades()` 实况播报：降级发生时明确输出「区块 N 恢复失败，X 已改为整文件重做」，不再用恢复前计数；
   - 收尾摘要 `scopeNote`（L1529-1534）中「其中 N 个为区块级重做」改为按实际成功数播报。

---

## 2. 问题 2：深度焕新静默丢弃对话调整

### 2.1 现状（代码实证）

- 对话调整「直接写入正式模板」（`buildAdjustPrompt` L563），写盘走 `writeToFile`（`AiTemplateGenServiceImpl` L3957，有 messageId 粒度备份但无跨焕新保护）；
- `restartPlan`（L367）的恢复源 `backupDir` 永远是首次升级创建的原始备份（L374-375 读计划字段，焕新不重建）；
- `fixPatches` 只捕获「升级产物 vs 原始备份」的**功能维度** diff（宏默认值/锚点/脚本，L1515-1518），不含对话调整的任何内容；
- 用户路径「升级 → 对话微调三轮 → 深度焕新」中，微调成果被原始旧稿无条件覆盖，无拦截、无提示。

### 2.2 修复 2a：焕新重建基线（治本）

`restartPlan`（L367）开头增加基线重建：

```
1. backupFiles(workDir, ...) 重新快照当前正式目录
   → 新目录 {name}_style_upgrade_backup_{timestamp}（时间戳命名，历史备份不覆盖、不删除）
2. 计划文件 backupDir 字段更新为新目录
3. 后续恢复/fixPatches/drift 审计全部自然指向新基线
```

**语义变化**：重做页面恢复的底稿从「原始旧稿」变为「当前版本（含对话调整 + 历史功能修复）」。用户点焕新的预期本就是「在现有网站基础上换个设计方向」，而不是「回到升级前的旧站重来」。

**自洽性推演**：

- 基线含历史功能修复 → `buildFixPatches` 对新基线 diff 自然为空 → fixPatches 回喂段自动消失（底稿已含修复，无需重放）——机制自我退化，不产生冗余注入；
- 锚点校验清单（`anchors`，L373 透传自首次扫描）不受影响，功能保证链完整；
- `lastRoundDigest` 回顾段保留（底稿即上一轮产物，结构指纹冗余但「用户反馈/审计问题」信息仍有效，冗余无害）。

**风险与缓解（决策点 D2）**：多轮焕新后基线逐轮漂移（第 N 轮基线 = 第 N-1 轮 AI 产物）。缓解：每轮产物都经过锚点校验 + 渲染校验 + 审计三道闸；原始备份目录始终保留在磁盘（不删），收尾摘要追加一句「首次升级前的原始备份位于 {旧备份目录}」保留找回路径。

### 2.3 修复 2b：对话修改计数与知情提示

**计数**：

- 计划文件加 `adjustCount`（int）：调整型会话每轮成功写盘文件数 > 0 时递增；升级/焕新收尾时清零；
- 实现落点：对话流程收尾（渲染校验通过后）调 `styleUpgrader.incrAdjustCount(workDir)`；清零在 `restartPlan`/`prepare` 内随计划重写一并处理；
- **透传陷阱（必须处理）**：`writePlanRaw`（L1834-1853）是全量重写，现有 `lastRound` 字段就是靠调用方手动透传才不丢。`adjustCount` 与问题 4 的 `rejectedDirections` 必须在 `restartPlan`（L464）、`updatePlanLastRound`（L1919）、`updatePlanProgress`（L511）三条透传链同步搬运，任一遗漏即静默丢字段。

**提示（前端，决策点 D4）**：

- status 接口（`legacyRefreshCount` 所在返回体，前端 aiChat.vue L475 消费）加 `adjustCount`；
- aiChat.vue 智能焕新（L1074）/全量焕新（L1105）的 confirm 文案动态追加：「检测到升级后已有 N 轮对话/手工修改，焕新将以当前版本为底稿重新设计，这些修改会被整合进新设计」；
- 基线重建（2a）落地后，对话调整**不再丢失**（成为底稿的一部分），提示语是知情性质（视觉会整体改变）而非丢失警告；
- ui/ 改动需构建并同步 `web/src/main/resources/static/`（项目规则）。

**边界说明**：断点续传场景（升级进行中 + 对话调整并发）不在本次范围——续传走 `readPlan` 不重建基线，计划内文件的对话调整仍会被备份恢复覆盖；「升级中不应同时调整」是既有语义，维持现状。

---

## 3. 问题 3：反馈关键词反语义命中

### 3.1 现状（代码实证）

`FEEDBACK_DIRECTION_TABLE`（`TemplateGenPromptBuilder` L76-89）含大量单字关键词（暗/黑/沉/密/挤/满/素/平/乱/花/杂/塞/昏/阴），`matchFeedbackDirectionIndex`（L170-182）纯 `contains` 匹配：

- 「再暗一点 / 我要深色 hero」→ 命中「暗」→ 方向段变成「提亮留白」（L627-628），与同屏注入的原文「再暗一点」（L635-638）直接矛盾，且连带触发问题 5（资产注入被抑制）；
- 「别这么花」否定句命中「花」；
- 「太丑/不好看/土」一个词都不命中 → 落回盲轮换。

### 3.2 修复 3a：关键词表整编 + 否定剥离

1. **删除全部单字关键词**，替换为过反语义检查的多字词：

| 方向 | 新关键词集 |
|---|---|
| 提亮留白 | 太暗、太黑、发暗、发黑、昏暗、太深了、压抑、沉闷 |
| 疏朗留白 | 太密、太挤、太满、拥挤、紧凑、密密麻麻、塞满 |
| 强对比层次 | 太素、太平、单调、平淡、没特色、没亮点、没层次 |
| 统一语言 | 混乱、花哨、不统一、风格不一、太乱、杂乱 |

   逐词验证：「再暗一点」不含任何新词 ✓（不误命中）；「太暗了」命中「太暗」✓；「不够暗」不含「太暗」✓。

2. **否定剥离**：`matchFeedbackDirectionIndex` 对每个 `contains` 命中点，向前检查 1-3 字符是否为否定前缀（`不`、`别`、`莫`、`勿`、`没那么`、`不要`、`不太`）→ 该命中作废，继续扫描后续命中。「别这么花」→「花哨」不命中（单字「花」已删）+ 无其他命中 → 落回原文直传 + 轮换，行为正确。

3. **「太丑/不好看/土」刻意不加入任何方向**：整体不满不可靠地映射到具体修正方向（丑可能是暗、可能是密、可能是乱），错误映射比不映射更糟。落回原文直传（L635 已注入且优先级最高）+ 轮换兜底是正确行为。

### 3.3 修复 3b：整体不满的自诊提示

`buildStyleUpgradePrompt`「本轮修正策略」（L639-644）追加第 4 条：

```
4. 用户意见为整体评价（如不好看/土/没设计感）时：先对照上一轮结构摘要自我诊断
   具体短板（层次/密度/一致性/明度），把诊断结论写进 reply，再针对诊断做定向改进
```

给 AI 处理「整体不满」的抓手，把不可映射的反馈转化为可执行的自诊流程。

### 3.4 修复 3c：命中情况透出（低成本高价值）

焕新前置 SSE 消息追加一句：

- 命中时：「已识别反馈方向：提亮留白，原文意见将同步注入」；
- 未命中且反馈非空时：「未识别到明确修正方向，将按轮换方向 + 原文意见修正」。

用户看到系统如何理解了自己的话，发现反语义误解可立即中断重填，形成人机互验闭环。

---

## 4. 问题 4：轮换不排除被否决方向

### 4.1 现状与「一行改动」的推演

`rotationKey`（`DesignDirectionLibrary` L117-121）纯 `(round-1) % 3`，第 4 轮必然回到第 1 轮方向。`lastRound.direction` 只用于 digest 注释（`AiTemplateGenServiceImpl` L1204-1205），方向解析完全不查。

**推演**：原建议「跳过 lastRound 方向」不解决兜圈——第 4 轮轮换位指向 key1，上一轮是 key3，无冲突照选 key1，用户连拒三轮后仍会见到第 1 轮方向。单槽位排除只防「连续两轮同方向」（轮换本身就不会发生）。要真正不兜圈，必须持久化**全部被否决方向**。

### 4.2 修复：拒绝方向集合持久化

1. **判定语义**：焕新被触发 = 用户对上一轮方向不满（满意就不会再焕新）。`restartPlan` 时把 `lastRound.direction` 反查 key 加入 `rejectedDirections`；
2. `DesignDirectionLibrary` 加 `nameToKey(String name)` 静态方法（遍历资产表按 name 精确匹配；资产库加载失败/名未命中返回 null，跳过排除逻辑）；
3. 计划文件加 `rejectedDirections: [key...]`（透传链同 2b，三处同步搬运）；
4. `resolveDirectionAssetKey` 签名加参（`Set<String> rejectedKeys`）：从 `ROTATION_ORDER` 过滤 rejected 后按轮换位取；
5. **池耗尽**（3 个全被拒）：不硬选——返回 null + 服务层 SSE/收尾摘要明确提示「内置设计方向已全部尝试过，建议：① 补充具体反馈做定向修正（效果最好）② 基于当前版本继续对话微调」，同时降级用兜底文案轮换（不至于无方向可给，但用户已知这是重复尝试）；
6. 反馈定向修正（问题 3 命中）不受 rejected 影响——定向修正不是轮换方向；反馈资产 key（问题 5）不进 `ROTATION_ORDER`、不进 `rejectedDirections`。

---

## 5. 问题 5：反馈定向修正拿到最贫瘠输入

### 5.1 现状（代码实证）

- `resolveDirectionAssetKey`（L131-136）：反馈命中 → 返回 **null** → 无 tokens 覆写；
- `appendDirectionAssetSection`（L194-200）：反馈命中 → **直接 return** → 无 few-shot / do / dont 注入；
- 结果：用户意图最强的场景（明确说了哪里不好）恰好没有方向资产加持，而盲轮换（意图最弱）反而有完整资产。`directionLanguage`（L143，P2-1 混血修复基准）同样被连带削弱。

### 5.2 修复：4 个反馈方向各配资产

1. **新增 4 个资产 JSON**（`resources/ai/design-directions/`）：

| 资产 key | 方向 | tokensOverride | 资产重点 |
|---|---|---|---|
| `feedback-brighten` | 提亮留白 | stylePreset=minimal（**不动 primaryColor**） | do：hero 用浅色渐变 from-primary-50、大面积 slate-50/白、深色仅用于 footer/小徽章；dont：深色大面积 hero、重渐变 |
| `feedback-spacious` | 疏朗留白 | stylePreset=minimal | do：同屏卡片 ≤3、区块间距 py-20+、每区块只留核心元素；dont：密集网格、信息塞满 |
| `feedback-contrast` | 强对比层次 | stylePreset=bold（radiusScale 0.5，更锐利） | do：标题/正文字号字重对比拉大、主色强调关键信息、区块节奏起伏；dont：通篇同字号同灰度 |
| `feedback-unify` | 统一语言 | stylePreset=corporate | do：全站一致的卡片/圆角/间距规范、hero 形态统一；dont：同站混搭不同版式语言 |

   每个资产配 1-2 个与方向强一致的 `referenceBlocks`（few-shot）。**不动 primaryColor 的理由（决策点 D3）**：用户反馈是明度/密度/层次/一致性诉求，不是换色诉求；反馈方向换色会引入用户未要求的视觉变量。`stylePreset` 只影响字体栈与圆角系数，是安全的辅助表达。

2. **代码改动**：

   - `FEEDBACK_DIRECTION_TABLE` 每行追加资产 key 列（第 2.5 列或独立并行数组 `FEEDBACK_ASSET_KEYS`）；
   - `resolveDirectionAssetKey`：反馈命中 → 返回对应反馈资产 key（不再返回 null）→ tokens 覆写生效，且 `directionLanguage` 自动拿到该资产 do/dont（混血修复基准联动增强）；
   - `appendDirectionAssetSection`：反馈命中 → 按 `FEEDBACK_ASSET_KEYS[hit]` 取资产注入（去掉 early return），few-shot 段对轮换与反馈两个来源统一走资产注入。

---

## 6. 改动清单（按文件）

| 文件 | 改动 | 对应修复 |
|---|---|---|
| `LegacyStyleUpgrader.java` | 设计规范相关无；`StyleUpgradePlan` 加 `sectionDowngrades`；`restartPlan` 基线重建 + 拒绝方向记录 + 降级清单 + 透传链；`auditUpgrade` 加 `section_count_drift`；`incrAdjustCount`/`readAdjustCount`；计划文件读写加 `adjustCount`/`rejectedDirections` | 1b 1c 2a 2b 4 |
| `TemplateGenPromptBuilder.java` | 设计规范第 8 条；`buildRefreshScopePrompt` 区块说明；`buildAuditFixPrompt` 加 drift 修复说明；关键词表整编 + 否定剥离；`resolveDirectionAssetKey` 加 rejectedKeys 参 + 反馈命中返回资产 key；`appendDirectionAssetSection` 反馈注入；修正策略第 4 条 | 1a 1b 3a 3b 4 5 |
| `AiTemplateGenServiceImpl.java` | SSE 实况播报（降级/命中情况/池耗尽提示）；`directionKey` 计算传 rejected；对话收尾 `incrAdjustCount`；摘要文案（区块级实数/原始备份路径/池耗尽提示）；status 加 `adjustCount` | 1c 2b 3c 4 |
| `DesignDirectionLibrary.java` | `nameToKey` 静态方法 | 4 |
| `resources/ai/design-directions/feedback-*.json` × 4 | 新增资产 | 5 |
| `ui/src/views/template/aiChat.vue` | 焕新 confirm 文案动态追加对话修改提示（需构建同步 web/static） | 2b |

## 7. 验证方案

1. **编译**：`mvn -pl starters/ai-starter -am compile -DskipTests`；
2. **问题 1**：对含 `<div>` 分区的旧模板跑升级 → 验证产物用 `<section>` 且区块数与旧稿一致；人为构造区块数不等的场景 → 验证 drift 审计产出 + 降级 SSE 可见；
3. **问题 2**：升级 → 对话改 2 轮 → 智能焕新 → 验证对话修改保留在重做页面的底稿中、`adjustCount` 清零、原始备份目录仍存在；
4. **问题 3**：「再暗一点」→ 验证不再命中提亮；「太暗了」→ 命中提亮；「别这么花」→ 不命中；
5. **问题 4**：连做 4 轮焕新 → 验证第 4 轮不重复前三轮任一方向、第 4 轮出现池耗尽提示；
6. **问题 5**：带反馈焕新 → 验证 tokens 覆写 + few-shot 注入 + `directionLanguage` 含 do/dont。

## 8. 决策点（需确认后动码）

| # | 决策 | 推荐 | 备选 |
|---|---|---|---|
| D1 | 是否接受「顶层区块数与旧稿一致」硬约束（限制 AI 增删区块自由，换取区块级对位可靠性） | 接受（区块=内容语义单元，样式升级本不该动） | 不加约束仅做可见化（区块级功能维持名存实亡） |
| D2 | 焕新基线重建导致逐轮漂移是否可接受（原始备份保留在磁盘可找回） | 接受（每轮过三道校验闸，且符合「在现有网站上换方向」的用户预期） | 仅做 2b 提示不重建基线（对话调整仍会丢） |
| D3 | 反馈方向资产不动 primaryColor，仅 stylePreset + do/dont + 参考块 | 确认（反馈是版式诉求非换色诉求） | 反馈方向同时换主色（引入用户未要求的变量，不推荐） |
| D4 | ui/aiChat.vue 前端提示是否本轮一并做（需 npm 构建 + 同步 web/static） | 一并做（改动小，confirm 文案追加一句） | 先做后端 SSE 提示，前端下轮 |
