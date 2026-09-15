# 样式组件化升级「多轮焕新效果递减」修复技术方案

> 状态：方案评审稿（未动任何源码）
> 范围：`starters/ai-starter` 样式升级管线（`runStyleUpgradePipeline` + `LegacyStyleUpgrader` + `TemplateGenPromptBuilder` 升级相关提示词）
> 关联文档：`doc/wiki/mockup-pipeline-design.md`（设计稿先行管线，本方案的长期治本项）

---

## 1. 问题与结论

### 1.1 现象

用户对模板执行「深度焕新」多次后，效果逐轮变差，最终无法收敛到满意状态。

### 1.2 根因结论（一句话）

**现有刷新机制把「迭代」做成了「重抽」**：每一轮焕新都把重做文件恢复成首次升级前的原始旧稿，AI 在完全失忆上一轮产出的情况下，按一句轮换的方向语「盲重投」一次；同时静态审计看不见「丑」，退出标准与用户满意度脱钩。多轮不是收敛过程，是方差递增的抽奖过程——「越来越差」是该循环的结构性必然，不是偶发 bug。

### 1.3 五条根因（全部有代码实证）

代码坐标：
- 服务层：`com.fastcms.ai.service.impl.AiTemplateGenServiceImpl`（`runStyleUpgradePipeline` L1161 起；`evaluateRefreshScope` L1480 起；`designDirectionName` L1463）
- 组件层：`com.fastcms.ai.component.LegacyStyleUpgrader`（`prepare` L195 / `restartPlan` L262 / `restorePagesFromBackup` L1546 / `auditUpgrade` L572）
- 提示词：`com.fastcms.ai.template.TemplateGenPromptBuilder`（`buildStyleUpgradePrompt` L435 / `buildRefreshScopePrompt` L558 / `buildAuditFixPrompt` L593）

**R1 失忆式恢复（主因）**
`restartPlan`（L305-310）调用 `restorePagesFromBackup`，把重做范围文件用 `Files.copy(src, dst, REPLACE_EXISTING)`（L1567）**无条件覆盖回首次升级前备份的原始旧模板**。随后改造轮读取的 `filesWithContent`（L1264-1267）就是这份旧稿。
→ 每一轮焕新 = 对原始旧模板做一次全新升级，只是换了方向。上一轮做对了什么、做错了什么，本轮全部失忆。多轮期望质量恒定（上下文变长还会微降），不可能逐轮逼近用户满意。

**R2 空约束禁令**
`buildStyleUpgradePrompt` L452-456 要求「布局结构必须明显区别于常规默认版式……严禁输出与上一版相似度高的换汤不换药版式」，但提示词中**根本没有上一版内容**。模型只能把这条理解成「尽量激进、尽量不同」。轮次越深，AI 为满足「必须不同」而加大的结构变形幅度越大，版式离「稳妥好看」越来越远，离「为不同而不同」越来越近。

**R3 新旧版式语言混血**
智能焕新时（`restartPlan` L316-320），未入选 scope 的页面保留上一轮已升级版（上一轮方向的版式语言），入选页面用本轮新方向从旧稿重做。tokens.css 只能换颜色/字体，换不了结构：第 2 轮「杂志编辑风」（去卡片化、不对称网格）与第 1 轮保留页（卡片化、三等分栅格）同站并存，且 `evaluateRefreshScope`（L1480）按文件级数字指纹（dark/gradient/primary 计数）判定，粒度无法处理文件内部一半耦合一半无关的情况。混血面积随轮次累积，观感从「新」变「花」。

**R4 功能修复债务滚存**
上一轮中锚点找回循环（L1305-1363）与渲染修复循环（L1365-1414）在产物上追加过真实功能性修复（宏参数默认值兜底、锚点 class 补全等）。`restorePagesFromBackup` 的无条件覆盖使这些修复**随旧稿恢复而蒸发**，本轮要靠「每批 ≤2 轮」的修复预算重新挣回；文件越多债务越重，修复预算（`STYLE_UPGRADE_MAX_FIX_ROUNDS=2`，L1140）反复消耗，最终交付的往往是「预算耗尽时」的状态。

**R5 退出标准失明**
`auditUpgrade`（L572）只有 4 项机检：`missing_class` / `undefined_var` / `legacy_css_residue` / `page_without_utilities`——全部是「没坏」检查，没有一项「好看」评价。管线对「变丑」完全失明，唯一裁判是用户眼睛；用户说「不行」，系统唯一应对是换一个与用户具体不满**零关联**的硬编码方向（`designDirectionName` L1463，三向固定轮换：现代商务→轻盈优雅→杂志编辑→循环），且方向只有一两句自然语言，无 tokens 预设、无参考片段。

### 1.4 死循环全景

```
用户不满意（无具体反馈入口）
   │ 系统只能盲换方向（3 向硬编码轮换）
   ▼
restartPlan：重做文件恢复【原始旧稿】→ 上一轮成果与功能修复全部失忆（R1/R4）
   │ 保留文件 = 上一轮版式语言（旧方向）→ 混血（R3）
   ▼
AI 拿旧稿 + 一句新方向 + 「必须与上版不同」空约束（R2）→ 为求不同而激进变形
   ▼
静态审计通过（看不见丑，R5）→ 报「焕新完成」
   ▼
用户更不满意 → 再点焕新 → 回到顶部（方差递增，无收敛机制）
```

---

## 2. 设计原则

1. **从「重抽」改「精修」**：每一轮的输入必须包含上一轮的产出与失败信息，让多轮成为有记忆的迭代
2. **反馈必须具体**：把「用户不满意」从布尔量变成结构化意见，方向/修正指令由意见驱动而非硬编码轮换
3. **修复不丢失**：功能性修复视为「资产」，跨轮持久化，不随旧稿恢复蒸发
4. **方向即资产**：设计方向从「一句话」升级为「tokens 预设 + 区块级参考 HTML」，质量由资产保证，并为付费「设计方向包」插件铺路
5. **小步可灰度**：P0/P1 只改提示词与计划文件结构（风险低、可即时回滚），P2 动管线流程，P3 是结构性替代（mockup 管线），分期落地、互不阻塞

---

## 3. 总体方案（四层）

| 层 | 改动 | 治什么根因 | 改动量 | 风险 |
|---|---|---|---|---|
| P0 止血 | 提示词与计划文件增补（上一轮摘要回喂 + 用户具体不满注入 + 方向去硬编码） | R1 R2 R5 | 小（2 文件 + SQL 1 列） | 低 |
| P1 修债 | 功能修复补丁跨轮持久化 | R4 | 中（LegacyStyleUpgrader +1 方法 + 计划文件新段） | 低 |
| P2 收敛 | 版式语言一致性审计 + 范围评估升级（区块级指纹） | R3 R5 | 中（auditUpgrade + 指纹生成） | 中 |
| P3 治本 | mockup 管线接棒「不满意再改」场景（另文详述） | 全部 | 大（新 gen-mode） | 中 |

P0 单独上线即可显著止血；P1/P2 逐轮验证后合入；P3 完成后，升级管线的「多轮焕新」按钮降级为「一键组件化 + 转 mockup 精修」入口。

---

## 4. P0 止血方案（提示词 + 计划文件，预计 1-2 天）

### 4.1 上一轮结构摘要回喂（治 R1/R2）

**目标**：让「必须明显不同」成为真实约束，让 AI 知道上一版长什么样、哪里被用户嫌弃。

**改动点 1**：`LegacyStyleUpgrader` 新增方法

```java
/**
 * 提取文件当前（上一轮）版本的结构指纹摘要，用于焕新时回喂给 AI。
 * 输出示例（每个文件 5-8 行，纯文本）：
 *   index.html:
 *     - hero: 深色渐变背景 + 居中双行大标题 + 主按钮×2
 *     - 栅格: 3 列卡片网格 ×2 区块; 分区节奏 5 区块
 *     - 卡片: 白底圆角卡片 + shadow-sm（方向耦合: 高）
 */
public Map<String, String> buildStructureDigest(Path workDir, List<String> relPaths) throws IOException
```

实现方式：复用现有 `evaluateRefreshScope` 的指纹统计逻辑（`AiTemplateGenServiceImpl` L1493 起的 dark/gradient/primary/hero 计数），**扩展为可描述结构**：hero 形态（深色/渐变/浅色、标题行数、按钮数）、栅格列数分布、卡片密度、区块数、去卡片化程度。统计是确定性正则/计数，不需要 LLM，零 token 成本，且与现有指纹代码同构（可抽公共方法）。

**改动点 2**：计划文件 `_style_upgrade.json` 增补字段（向后兼容，旧计划无此字段时跳过）

```json
{
  "anchors": [...], "pending": [...], "done": [...],
  "backupDir": "...", "refreshCount": 1,
  "lastRound": {                       // ★ 新增
    "direction": "现代商务风",
    "structureDigest": { "index.html": "...", "_layout.html": "..." },
    "userFeedback": "配色太暗，卡片太密",
    "auditIssues": ["index.html:missing_class:..."]
  }
}
```

`writePlanRaw`（L1525）追加写入；`runStyleUpgradePipeline` 收尾阶段（L1430 附近）组装 `lastRound` 落盘。

**改动点 3**：`buildStyleUpgradePrompt` 增参 `String lastRoundDigest, String userFeedback`（L435 签名），`refreshRound > 0` 时插入新段落（置于「本次设计方向」之前）：

```
## 上一轮焕新回顾（第 N-1 轮：{方向名}）
{各重做文件的结构指纹摘要}
## 用户具体不满（本轮修正目标，优先级最高）
{用户反馈原文；为空时输出「用户未给出具体意见，按本轮设计方向整体优化」}
## 本轮修正策略
1. 针对「用户具体不满」逐条修正，这是本轮首要目标
2. 用户未点名的部分，在上一轮结构基础上做**定向改进**，
   不要全盘推翻重排（避免每次焕新版式都剧烈变化）
3. 与上一版的差异必须体现在用户不满意的具体维度上，
   而非盲目换 hero 形态/换栅格列数
```

同时把 L455-456 的禁令改写为上述「修正策略」第 3 条——**空约束变成有参照物的约束**。

**改动点 4**：前端 + 会话 API 增加焕新意见入口

- `AiTemplateSession` / 升级请求体（`AiTemplateController` 对应端点）增加可选字段 `feedback`（textarea，可空）
- `runStyleUpgradePipeline` 入参透传 `feedback`，写入 `lastRound.userFeedback` 与本轮提示词
- 前端：「深度焕新」按钮旁增加可展开的意见输入（默认折叠，不打断现有流程）

### 4.2 方向去硬编码（治 R5，与 4.1 联动）

`designDirectionName`（L1463）与 `buildRefreshScopePrompt`/`buildStyleUpgradePrompt` 中的三向硬编码数组（L444-451 / L559-563）收敛为**单一来源**：

```
方向选择规则（runStyleUpgradePipeline 焕新分支）：
1. 用户反馈命中关键词（暗/黑/沉→「提亮留白」；密/挤→「疏朗留白」；
   素/平→「强对比层次」；乱/花→「统一语言」）→ 用命中方向 + 反馈原文做定向修正
2. 无命中 → 轮换表取「上一轮未用过的下一个方向」（现状保留，但方向描述
   升级为 4.3 的资产化版本）
```

关键词表放常量（约 8-10 条），不做 NLP。这一步把「猜」降级为「先听用户说话」，是性价比最高的单点改动。

### 4.3 方向资产化（治 R5，为付费插件铺路，可与 P2 合并排期）

每个方向从「一句话」升级为三件套，存于 `resources/ai/design-directions/{key}.json`：

```json
{
  "key": "editorial-magazine",
  "name": "杂志编辑风",
  "tokensOverride": { "radius": "md", "font-display": "...", ... },
  "referenceBlocks": [ "hero 区块参考 HTML（30 行内）", "内容区参考 HTML" ],
  "do": ["大图视觉", "超大标题"],
  "dont": ["小卡片密集排列", "全圆角"]
}
```

- `buildStyleUpgradePrompt` 方向段改为渲染 `referenceBlocks` + `do/dont` 清单（few-shot 质量远高于一句形容）
- `tokensOverride` 供 `TokenEngine` 按方向覆写 tokens.css（确定性换肤，减少 AI 发挥空间 = 减少方差）
- **插件化预留**：目录结构按 `{pluginId}/directions/{key}.json` 命名空间设计，后续「行业设计方向包」插件只需携带该目录即可上架，无需改引擎代码

### 4.4 P0 验收标准

1. 连续 2 轮焕新，第 2 轮提示词中必须包含第 1 轮结构摘要与用户反馈（日志断言）
2. 用户输入「配色太暗」后，第 2 轮 hero 区深色/渐变计数 ≤ 第 1 轮（指纹自动对比）
3. 无用户反馈时行为与现状一致（轮换表 + 全量/智能 scope 判定不变），保证灰度安全
4. 旧计划文件（无 `lastRound` 字段）焕新不报错，走现状逻辑

---

## 5. P1 功能修复债务持久化（治 R4，预计 2-3 天）

### 5.1 问题精确定义

`restorePagesFromBackup`（L1546）恢复旧稿后，上一轮产物中的**功能性修复**丢失。这些修复的特征：不改变视觉、改变功能正确性——
- 宏参数默认值兜底（`<#macro menuChildren children=[] ...>`）
- 锚点 class 补全（`missing_class` 修复追加的 class）
- script 块内 JS 初始化参数修正
- 渲染修复追加的结构保护（`<#if>` 判空包裹）

### 5.2 方案：修复补丁文件

**改动点 1**：每轮收尾（`markDone` 全量完成 + 审计通过后）生成 `lastRound.fixPatches`：

对每个 done 文件，对「恢复后的旧稿 vs 上一轮产物」做**功能维度 diff**（确定性提取，非全量 diff）：
- 正则提取两侧 `<#macro ...>` 签名行 → 默认值差异
- 提取两侧全部 `id=` / 锚点 class 集合 → 差集
- 提取两侧 `<script>` 块 → 内容差异行

产出的补丁是**结构化清单**（非 unified diff，避免 AI 打补丁引入新错误）：

```json
"fixPatches": {
  "_layout.html": {
    "macroDefaults": ["menuChildren children=[] currentUri=\"\""],
    "addedAnchors": ["search-toggle"],
    "scriptDiffs": ["第 42 行: 新增 swiper autoplay 配置"]
  }
}
```

**改动点 2**：改造轮提示词（`buildStyleUpgradePrompt`）增加段落：

```
## 历史功能修复（上一轮已验证，本轮必须保留，禁止删除/回退）
- _layout.html: 宏 menuChildren 参数带默认值 children=[]；
  元素 #search-toggle 上的 class 不可移除；...
```

**改动点 3**：写盘校验（`writeToFile` 升级路径）在现有锚点校验之外增加**补丁校验**：fixPatches 中声明的宏默认值/锚点 class 在新文件中必须存活，缺失即走既有锚点修复循环（复用 L1305 起的重试链，不新增循环）。

### 5.3 P1 验收标准

1. 构造场景：第 1 轮锚点修复补了 class `x` → 焕新恢复旧稿 → 提示词包含「class x 必须保留」→ 第 2 轮产物含 `x`
2. 宏默认值跨轮存活（无子菜单数据渲染不 500）
3. 补丁缺失时修复循环触发且 ≤2 轮内收敛（与现状预算一致）

---

## 6. P2 收敛增强（治 R3，预计 3-4 天）

### 6.1 版式语言一致性审计（新增第 5 类审计 issue）

`auditUpgrade`（L572）在现有 4 项之后追加：

**`layout_language_mix`：同站版式语言混血检测**（确定性，无需 LLM）

检测维度（每页提取特征向量后跨页比对）：
- 卡片化程度：页面中 `rounded-xl|rounded-lg` + `shadow-*` + `border` 组合卡片数 / 总区块数
- 栅格密度：`grid-cols-3|4` 密集网格占比
- 去卡片化信号：`border-t` 分隔线区块、`text-6xl~8xl` 超大标题出现数
- hero 语言：深色/渐变 hero（与保留页的浅色 hero 冲突）

判定规则：
- 存在 ≥2 种互斥语言特征（如「卡片化 ≥60% 的页」与「去卡片化页」并存）→ 报 issue，detail 列出冲突页面与特征
- 修复提示（`buildAuditFixPrompt` L593 增补）：要求 AI 以**重做页的新方向语言**为准统一保留页中冲突区块（保留页只做区块级微调，不整页重做——控制 token 与风险）

闭环复用现有 `STYLE_UPGRADE_MAX_AUDIT_ROUNDS=2` 审计循环（L1427），不新增轮次预算。

### 6.2 范围评估从文件级到区块级（缓解 R3 粒度问题）

`evaluateRefreshScope`（L1480）现状：文件级指纹 → 整文件二选一。升级：

- 指纹粒度细化到**区块级**（按 `<section>`/`<div class="...container...">` 切分后逐块统计方向耦合特征）
- AI 输出从 `["index.html", ...]` 扩展为 `[{"file": "index.html", "sections": ["hero", "products"], "keepSections": [...]}]`
- `restartPlan` 相应支持区块级重做：文件未整体入选时，仅对入选区块恢复旧稿区块（备份文件中按 section 边界截取，确定性操作），其余区块保留当前版本
- **降级保证**：AI 输出区块级 JSON 解析失败 → 回退文件级（现状逻辑），解析失败率由日志监控，稳定后再收紧

### 6.3 P2 验收标准

1. 构造混血场景（卡片页 + 去卡片页并存）→ `layout_language_mix` 命中且 detail 正确
2. 区块级焕新后，保留区块与重做区块共存于同一文件且渲染通过
3. 区块级解析失败自动回退文件级，管线不中断（与现状行为一致）

---

## 7. P3 结构性替代：mockup 管线接棒（另文）

详见 `doc/wiki/mockup-pipeline-design.md`。与本方案的关系：

| 场景 | 现状（升级管线） | P3 之后 |
|---|---|---|
| 存量旧模板首次组件化 | 升级管线（保留功能、焕新视觉） | **不变**，仍走升级管线（一次性，无多轮问题） |
| 焕新后不满意「再改一轮」 | 重抽式多轮焕新（本方案 P0-P2 止血） | 引导至 **mockup 管线**：基于当前模板生成设计稿 → 用户对话改稿确认 → 转译回模板 |
| 「多轮焕新」按钮 | 硬编码方向轮换 | 文案与行为改为「不满意？转为设计稿精修」，入口保留 mockup 管线 |

P0-P2 的价值在 P3 落地前独立成立（首次升级后 1-2 轮内的不满意，走 P0 的反馈驱动精修即可解决，不必全量进 mockup）；P3 落地后多轮焕新使用率预期显著下降，届时可将该功能收敛为「深度重做（换方向）」单一语义，移除失忆式恢复（直接以当前版本为输入重做），R1 根因物理消除。

---

## 8. 排期与灰度

| 阶段 | 内容 | 工期 | 上线策略 |
|---|---|---|---|
| P0-1 | 上一轮摘要回喂 + 用户意见入口（4.1） | 1-2 天 | 直接上线（无意见时行为与现状一致，天然灰度） |
| P0-2 | 方向关键词命中（4.2） | 0.5 天 | 随 P0-1 |
| P0-3 | 方向资产化（4.3） | 2-3 天 | 独立上线；资产目录可先放 3 个内置方向 |
| P1 | 修复补丁持久化（5.2-5.3） | 2-3 天 | 与 P0-3 错开一周，独立验证 |
| P2 | 混血审计 + 区块级 scope（6.1-6.2） | 3-4 天 | P1 稳定后；区块级可再拆一次灰度 |
| P3 | mockup 管线（另文排期） | — | 独立里程碑 |

**灰度监控指标**（`ai_usage_log` + 现有 SSE 日志）：
- 每轮焕新的 `auditIssues` 数量趋势（收敛应为递减）
- 焕新后 24h 内再次焕新的比例（现状的「多轮越来越差」直接度量；P0 后应下降）
- 修复循环触发率（P1 后应下降）
- 用户反馈字段填写率（产品信号，非技术指标）

**回滚方案**：P0/P1 全部改动集中在提示词构建与计划文件字段，计划文件向后兼容（旧字段缺失走现状分支）；每阶段独立 feature 开关（`ai.template.upgrade.feedback-driven=true` / `...fix-patches=true` / `...layout-audit=true`），可单独关闭回退现状行为。

---

## 9. 风险与边界

1. **结构指纹的准确性上限**：正则/计数式指纹无法理解语义（如「hero 居中 vs 分栏」能识别，「整体气质」不能）。P0 的修正策略因此只承诺「针对用户点名的维度收敛」，不承诺「整体审美提升」——后者是 P3 的职责
2. **区块级重做的截取边界**：section 切分在嵌套异常时可能截断错误；降级回退文件级是硬保证，但需监控解析失败率（>10% 时收紧上线节奏）
3. **用户反馈质量**：关键词命中表覆盖不了所有表达；未命中时走轮换表兜底（现状行为），不会比现在更差
4. **token 预算**：上一轮摘要（每文件 5-8 行 × scope 文件数）+ 修复补丁段 + few-shot 参考区块，合计约 +800~1500 token/轮。scope 上限 6 文件（现状 `evaluateRefreshScope` 已有「宁少勿多」约束）下增幅 <10%，可接受
5. **不解决的问题**：本方案不引入视觉校验（截图比对）——「AI 看不见成品」的根因由 P3 mockup 管线（用户确认稿 = ground truth）解决，P0-P2 范围内不承诺

---

## 10. 验收总纲（P0-P2 全部合入后）

1. **收敛性**：构造「第 1 轮效果平庸 + 用户给出具体意见」的标准场景，第 2 轮焕新后，用户意见点名的维度指纹指标必须改善（如「太暗」→ 深色区块计数下降）
2. **不劣化**：无反馈场景连续 3 轮焕新，`auditIssues` 数量不递增，功能修复（宏默认值/锚点）存活率 100%
3. **一致性**：焕新 2 轮后混血审计无 `layout_language_mix` 残留
4. **回滚语义**：任意轮次可回滚到首次升级前备份（现状能力不回归）
5. **商业化预留**：设计方向资产目录可通过插件 jar 挂载（`{pluginId}/directions/`），不改引擎代码即可新增方向

---

## 附：改动文件清单（供评审核对）

| 文件 | 改动 | 阶段 |
|---|---|---|
| `AiTemplateGenServiceImpl.java` | `runStyleUpgradePipeline` 增 feedback 透传 + lastRound 组装；`designDirectionName` 收敛为单一来源；混血审计接入 | P0/P2 |
| `LegacyStyleUpgrader.java` | `buildStructureDigest` 新增；`writePlanRaw`/计划文件 +lastRound 字段；`restorePagesFromBackup` 支持区块级；fixPatches 生成；`auditUpgrade` +layout_language_mix | P0/P1/P2 |
| `TemplateGenPromptBuilder.java` | `buildStyleUpgradePrompt` 增参（lastRoundDigest/userFeedback/fixPatches/方向资产）；`buildAuditFixPrompt` 增混血修复段；`buildRefreshScopePrompt` 区块级输出格式 | P0/P1/P2 |
| `AiTemplateSession` / `AiTemplateController` | 升级请求体 +feedback 字段 | P0 |
| `doc/sql/fastcms.sql` + 迁移脚本 | `ai_template_session` 相关表 +upgrade_feedback 列（如反馈需跨会话留存） | P0 |
| `resources/ai/design-directions/*.json`（新增） | 内置 3 方向资产 | P0-3 |
| 前端「深度焕新」入口 | 意见输入框 + P3 后入口文案改造 | P0/P3 |
| `TokenEngine.java` | 按方向 tokensOverride 覆写 | P0-3 |

> 红线声明：本方案为评审稿，未修改任何源码。实施前需用户逐项确认，按 P0-1 → P0-3 → P1 → P2 顺序逐阶段评审动工。
