# 设计稿管线"推理失控"（无限思考）问题优化设计

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 日期 | 2026-09-13 |
| 状态 | 设计评审 |
| 关联文档 | `ai-template-two-mode-design.md`（§4.3 设计契约、§7 配置） |
| 关联代码 | `starters/ai-starter/.../ai/template/design/`（MockupDesignService / MockupOrchestrator / DesignContractPrompt） |

---

## 1. 问题背景

### 1.1 事件事实（日志确定，2026-09-13 会话）

| 事实 | 数值 | 来源 |
|---|---|---|
| 连续 3 次调用 Qwen3.6-27B 的 completion_tokens | **全部为 0**（模型全程未产出任何正文） | 调用 usage 日志 |
| 每次调用的思考内容累计 | **>256K 字符**（约 12 万+ token 思考量） | reasoning 流（仅推前端，未落盘） |
| 三次失败时长 | 151s / 319s / 71s（差异大，非固定超时） | 会话耗时 |
| 失败模式 | 思考流持续产出 → 撞上 256K 保险丝 → 本轮中止 → 会话 FAILED | 错误信息 |
| 重试行为 | FAILED 后用户点"继续" → **同输入重发 → 同结果**（确定性复现） | 断点续传语义 |
| 对照组 1 | 同模型当日完成 9 个页面（含 26KB 的 article_list_download.html） | 历史会话 |
| 对照组 2 | 同模型在 Hermes 代理环境中无失控现象 | 日常使用 |

**结论**：不是"模型坏了"，是"模型 + 该批次输入 + 该调用结构"的组合触发了推理模型不收敛。触发点在模型侧，放大因素在任务结构与工程侧。

### 1.2 现状影响

- **用户体验**：单页失败一次 = 2~5 分钟纯转圈 + 报错；点"继续"必然同结果 → 死胡同
- **成本**：每次失败烧 12 万+ token 思考量（思考 token 计费），3 次失败 = 一次完整建站成本
- **覆盖缺口**：占位页降级只覆盖"格式校验 3 轮失败"路径；思考失控发生在第 1 轮调用内部，**现有降级路径不覆盖它**

---

## 2. 根因分析

### 2.1 三层因果链

| 层 | 因素 | 证据 | 可控性 |
|---|---|---|---|
| **模型层** | 推理模型面对开放式审美决策（布局/配色/区块取舍）无法收敛，自我盘旋 | 正常任务思考量 3K~30K 字符，本次 >256K（异常 10 倍+）；completion=0 说明始终没进入"写"阶段 | 低（采购/选型） |
| **任务结构层** | 整页单次输出：一次调用决定全部决策（布局+色彩+所有 section+响应式+文案+SVG），决策空间无边界 | 对照：管线模式单次调用决策空间小（选组件+填参），无失控记录；Hermes 代理循环每次思考被具体任务限制在几千字 | **高** |
| **工程层** | 现有保险丝全是"事后刹车"（256K 才熔断）；续传重发同输入 → 确定性复现 | 见 §3 盘点 | **高** |

### 2.2 三个关键问题拆解

**Q1：为什么 reasoningEffort("low") 没挡住？**
代码现状（`MockupDesignService.buildDesignOptions` L623-635）：仅对模型名含 `qwen3` 的设 `reasoningEffort("low")`。但 Qwen 系列 OpenAI 兼容端点的真实思考开关是 `enable_thinking`（布尔）与 `thinking_budget`（token 预算）参数；`reasoning_effort` 是 OpenAI 原生参数，**该端点很可能静默忽略它**。即"压低思考预算"这层设计在 Qwen 端点上实际未生效。

**Q2：为什么重试无效？**
断点续传是幂等设计（正确语义）：FAILED 续传时，done 页跳过、未完成页**原样重发**——提示词、文件内容、参数全部相同。同模型 + 同参数 + 同输入 → 同一条不收敛轨迹。幂等对"进度保护"是美德，对"失败恢复"是陷阱。

**Q3：为什么 Hermes 里同模型不循环？**
代理循环（think → tool → observe → think）：每次思考绑定一个具体小任务（读文件/改一行/查 grep），工具结果把推理拉回地面，单段思考天然有界。设计管线是**单发输出**：无工具、无观察、无外部锚点，模型只能靠内部权衡收敛——而"这个 section 放左还是右"这类审美权衡恰恰是最难收敛的任务类型。

---

## 3. 现有防线盘点（改造基线）

| # | 防线 | 位置 | 作用 | 不足 |
|---|---|---|---|---|
| 1 | 思考 256K 保险丝 | `MockupDesignService` L87/L381 | 防内存 + 防无限思考 | **触发太晚**：烧完 12 万 token 才断 |
| 2 | `design-max-tokens` 24000 | 同上 L617 | 限制正文输出 | 思考不是正文，**不受限** |
| 3 | OkHttp callTimeout 10min | `baseOptionsBuilder` | 流死亡兜底 | 思考流是活的，不触发 |
| 4 | 格式修正 3 轮 | `designPage` L264-295 | 格式错误重试 | 思考失控到不了校验，**不覆盖** |
| 5 | FAILED 续传 | `MockupOrchestrator` L286-288 | 进度保留 | **重发同输入**，确定性复现 |
| 6 | qwen3 reasoningEffort=low | `buildDesignOptions` L631 | 压低思考预算 | 端点可能忽略，**实际未生效** |
| 7 | 空响应检测 | `callDesignModel` L397 | 识别"思考耗尽输出" | 只报不防，且本次是保险丝先断 |
| 8 | 用户手动取消 | `DesignCancelledException` | 主动止损 | 要用户自己发现并操作 |

**缺口总结**：缺"早停"（§6）、缺"变招"（§7）、缺"预防"（§8）、缺"证据"（思考不落盘，§10）。

---

## 4. 总体设计

### 4.1 目标分层

| 层 | 目标 | 指标 |
|---|---|---|
| **止损**（用户侧） | 失败后一键换招，不再死胡同 | 失败恢复路径 1 次点击可用 |
| **检测**（运行时） | 识别不收敛的**早期特征**，秒级早停 | 浪费 token 从 12 万+ 降到 <5 千 |
| **预防**（结构） | 缩小单次调用决策空间，从源头降低不收敛概率 | 失控发生率数量级下降（经验值，需 A/B 验证） |
| **观测**（证据） | 思考落盘留痕，复盘有据 | 每次失败可回看思考全文 |

### 4.2 设计原则

1. **不依赖模型"自己停"**：所有保险丝都是外部强制的（现有口径延续）
2. **降级永远可用**：任何防线触发都不阻断全站流程（占位页兜底已有，扩展覆盖范围即可）
3. **确定性优先**：变招、骨架拆分都是代码控制流，不把"换个姿势"寄托给模型自觉
4. **配置可灰度**：新增行为默认保守（先观察后强制），每项独立开关
5. **零管线影响**：全部改动收在 `design` 包内 + 独立配置段，管线模式代码不碰

### 4.3 分层防护全景

```
用户提交 design 会话
   │
   ▼
[预防层] 骨架优先生成（P2，默认关灰度）
   │  第一次调用：只出骨架（section 清单+职责），思考决策空间小 1 个量级
   │  第二次起：逐 section 生成 HTML，每次只决策一个区块
   ▼
[检测层] 早停三件套（P1，默认开）
   │  ① 静止计时器：思考增量 N 秒无新增 → 判"假死"（流活着但不推进）
   │  ② 重复窗口：最近 K 字符思考与更早窗口相似度 > θ → 判"循环"
   │  ③ 阈值下调：256K → 可配 design-max-reasoning-chars（建议 64K 起步）
   │  任一命中 → 立即中止本轮（不烧到 256K）
   ▼
[止损层] 失败自动变招（P0，默认开）
   │  思考失控中止 → 自动重试 1 次：换姿态（关思考/低思考预算）+ 温度微调
   │  仍失败 → 该页降级占位页（扩展现有降级路径覆盖"思考失控"成因）
   │  会话级统计：同页同因连续失败 2 次 → 整站停摆前提示（不自动烧下一页）
   ▼
[观测层] 思考落盘（P0，默认开，可配采样率）
   思考全文写入 workDir/design-log/page-<name>-r<round>.reasoning.txt（异步、限大小）
```

---

## 5. P0：失败自动变招（快速止损）

**优先级最高，改动最小，直接消灭"死胡同"体验。**

### 5.1 现状问题（代码定位）

`MockupDesignService.callDesignModel` L381-384：保险丝抛 `IllegalStateException` → 向上冒泡到 `MockupOrchestrator.run` L284-290 → 状态落 `FAILED` 后**原样重抛** → 宿主落库失败消息。用户点"继续" → `runDesigning` 对未完成页**原提示词原参数重发** → 同轨迹复现。

### 5.2 变招策略（同一会话内自动重试 1 次）

```
designPage 单页调用失败且成因为"思考失控/空响应"时（非客户端取消、非格式错误）：
  if (autoRetried == false) {
      autoRetried = true;
      sse.status("该页首尝未收敛，切换轻量姿态重试中…");
      重新调用 callDesignModel，options 叠加：
        - 思考姿态降级：Qwen 端点 → enable_thinking=false（见 §8 参数适配）；
                         通用 → reasoningEffort("minimal")（若端点支持）
        - 温度 +0.2（打破确定性轨迹，上限 ≤1.0）
  } else {
      降级占位页（复用现有 placeholder 路径，错误信息区分"思考失控"成因）
  }
```

**为什么有效**：
- 关思考 = 消灭不收敛的载体（思考流没了，模型直接产出正文；牺牲深度换确定性，对"写 HTML"这种模式化任务损失可接受）
- 温度扰动 = 即使同输入，采样轨迹不同，大概率走出另一个局部最优
- 自动重试 1 次 = 用户无感；仍失败才降级，降级文案告知"已占位，可对该页重新生成"

### 5.3 防抖约束

| 约束 | 值 | 理由 |
|---|---|---|
| 每页自动变招次数 | **1**（autoRetried 标记） | 防烧钱；第 2 次失败必降级 |
| 变招仅对"思考失控/空响应"成因 | 精确匹配异常类型 | 格式错误走原有 3 轮修正，不混入 |
| 客户端取消不触发变招 | `DesignCancelledException` 先于判断 | 尊重用户意图 |
| 重试不继承上轮思考 | 新一轮 reasoningBuf 全新 | 失控内容不污染重试 |

### 5.4 改动点

| 文件 | 改动 | 量级 |
|---|---|---|
| `MockupDesignService` | `designPage` 加 autoRetried 分支；`callDesignModel` 接受"姿态参数"（normal/light）；区分异常成因 | ~40 行 |
| `DesignContractPrompt` | 轻量姿态时追加一句场景段："直接输出文件，无需长篇规划" | ~5 行 |
| `FastcmsAiProperties.Design` | +`autoRetryOnRunaway`(默认 true) | 3 行 |
| 前端 | 无（复用 status/message 事件；降级文案后端拼） | 0 |

---

## 6. P1：早停检测（运行时识别不收敛）

**目标：把"烧 12 万 token 才知道失控"提前到"几千 token 就发现"。**

### 6.1 信号 ①：思考静止计时器（catch 假死）

**现象**：推理模型有时进入"卡住"状态——流连接活着、偶尔吐无意义 token 或空帧，但无实质内容增长。现有保险丝只盯总量，不盯**速率**。

**实现**（`callDesignModel` 内，纯内存）：

```java
// 字段（每次 callDesignModel 调用独立）
long lastReasoningAdvanceAt = System.currentTimeMillis();
// doOnNext 中，每次思考增量 > 0 时：
long now = System.currentTimeMillis();
if (now - lastReasoningAdvanceAt > stallThresholdMs) {
    throw new ReasoningStalledException(now - lastReasoningAdvanceAt);
}
lastReasoningAdvanceAt = now;
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `design-stall-timeout-ms` | **90_000**（90s） | 思考流 90 秒无任何实质增量即判假死；正常思考流增量间隔 <10s，90s 阈值误杀率≈0 |

### 6.2 信号 ②：重复窗口检测（catch 循环）

**现象**：典型死循环 = 思考内容周期性重复（"让我重新考虑布局…不对，还是用卡片…还是用网格…"）。

**实现**（低开销，不引入 NLP 依赖）：

```java
// 环形缓冲：保留最近 4K 字符思考 + 之前 4K 字符（共 8K 窗口）
// 每累积 2K 新增触发一次比对：
//   当前 4K 窗口 与 前 4K 窗口 的 64-gram 重合率
//   重合率 > 0.85 → 判循环
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `design-repeat-window-chars` | 4096 | 比对窗口大小 |
| `design-repeat-threshold` | 0.85 | 重合率阈值（0.8 起步过报，0.9 漏报，0.85 为经验平衡点） |

**开销**：64-gram 用 `LongAdder` 计数表（char[64] 哈希为 long），每 2K 增量比对一次，单次 <1ms——对分钟级调用可忽略。

### 6.3 信号 ③：总量阈值下调（catch 慢速盘旋）

盘旋不一定重复（可能在缓慢换话题），前两信号漏掉时兜底：

- 现有 `REASONING_RUNAWAY_MAX_CHARS` 硬编码 256K → 改为配置 `design-max-reasoning-chars`
- **默认 64K**（约 3 万 token 思考）：正常设计页思考 3K~30K 字符，64K 已是 2 倍余量；失控场景平均 40K~80K 即进入重复区
- 保留 256K 作为绝对上限（配置上限 clamp），防误配

### 6.4 检测器装配与降级衔接

```java
// callDesignModel 内，思考增量处理段（L358-385 附近）统一接入：
//   ① 静止计时器检查（每 chunk）
//   ② 重复窗口检查（每 2K 增量）
//   ③ 总量阈值检查（每 chunk，现状保留）
// 任一命中 → 抛 ReasoningRunawayException(cause)——新异常类型，
// cause ∈ {STALLED, REPEATING, OVERBUDGET}，上层变招/日志/文案按 cause 区分
```

| 改动点 | 量级 |
|---|---|
| `MockupDesignService`：新异常 `ReasoningRunawayException` + 检测逻辑（静态工具 `ReasoningStallDetector`，可单测） | ~120 行 |
| `FastcmsAiProperties.Design`：+3 配置项 | ~15 行 |
| 单测：detector 纯逻辑，喂构造的重复/静止流验证 | ~80 行 |

### 6.5 误杀防护

| 场景 | 风险 | 防护 |
|---|---|---|
| 网络慢导致增量间隔大 | 静止计时器误杀 | 阈值 90s（网络抖动秒级）；且只在**思考阶段**计时，正文增量到达即重置 |
| 长思考正常页（26KB 页） | 总量阈值误杀 | 64K 阈值是正常 2 倍；误杀后果=走变招重试（多花 1 次调用）而非全站崩，风险可接受 |
| 思考内容"相似但不重复"（正常对比论证） | 重复窗口误杀 | 0.85 阈值 + 4K 窗口（正常对比通常换词，64-gram 全重合难达 0.85）；误杀同样走变招兜底 |

**核心安全网**：任何早停信号命中后的路径都是"变招重试 1 次 → 仍失败才占位"，**不存在"误杀直接毁全站"的路径**（§5.2 约束）。

---

## 7. P2：骨架优先拆分（结构预防）

**从源头降低不收敛概率：把"一次决策整页"拆成"先定骨架 → 逐 section 填充"。**

### 7.1 原理

失控的根因是**决策空间无边界**（§2.1 任务结构层）。整页单次输出时，模型在一次思考里要权衡：区块数量、区块顺序、每区布局、配色、响应式策略、文案——几十个开放决策互相纠缠，推理链越长分叉越多，不收敛概率随决策数指数上升。

拆成两段后：

| 调用 | 决策空间 | 类比 |
|---|---|---|
| ① 骨架调用 | 只定"有哪些 section、各自职责、顺序"（5~10 个决策） | 写文章先列提纲 |
| ② 逐 section 调用 | 每次只定一个区块的布局/内容（1~3 个决策） | 按提纲逐段写 |

**每次调用的思考量天然有界**——这正是 Hermes 代理循环不失控的同款原理（§2.2 Q3），只是把"工具调用"换成了"代码控制流的多次调用"。

### 7.2 执行流

```
designPage(page)
  ├─ 第 1 次调用（骨架）：
  │    提示词：输出 design/<page>.skeleton.json——
  │    [{ "block": "hero", "intent": "首屏品牌主张+主 CTA" },
  │      { "block": "features", "intent": "三栏卖点" }, ...]
  │    maxTokens 2000（骨架很短）；思考失控阈值同样生效（骨架调用思考应 <5K 字符，可单独设 16K）
  │    产出落盘 design-log/<page>.skeleton.json（断点续传锚点）
  │
  ├─ 骨架解析/校验（代码）：
  │    - JSON 解析失败 → 重试 1 次 → 仍失败 → 跳过骨架，回退整页模式（现状路径）
  │    - section 数 <3 或 >12 → 代码 clamp（V1 契约要求 ≥3）
  │    - 每个 block 的语义名归一化（去空格/转小写，对齐 data-block 命名）
  │
  ├─ 第 2..N 次调用（逐 section）：
  │    每次提示词 = 全站上下文 + 方向资产 + 【本 section 骨架条目】+ 已生成 section 的
  │    nav/footer 结构摘要（一致性锚点）→ 输出该 section 的 HTML 片段
  │    每次 maxTokens 4000（单 section 足够）
  │    每个 section 独立失败独立变招（§5），失败 2 次 → 该 section 用模板片段占位
  │
  └─ 拼装（代码，零 AI）：
       页面 HTML 模板（<!DOCTYPE> + head + 导航 + :root 变量）
       + 逐 section 拼接（<section data-block="..."> 包裹）
       + 页脚 + 占位 SVG（第 1 次骨架调用一并声明）
       → 完整 design/<page>.html，走现有 DesignHtmlValidator 校验
```

### 7.3 与现有管线的兼容性

| 现有机制 | 骨架模式下的行为 |
|---|---|
| `DesignHtmlValidator` V1~V6 | **拼装完成后校验**，规则不变（V1 的 ≥3 section 由骨架保证，校验器仍跑兜底） |
| 格式修正 3 轮 | 作用于**拼装后的整页**（拼装是确定性代码，格式错误基本不会发生；保留兜底） |
| 断点续传 | skeleton.json 落盘 = 新锚点；done 页跳过逻辑不变 |
| 审计（MockupAuditor） | 审计对象仍是完整页面 HTML，**零改动** |
| 转化（MockupConverter） | 按 data-block 区块解析，骨架模式下区块结构更规整，**只赚不亏** |
| 移动端断点 | head 模板含 viewport，section 片段内要求响应式类（提示词约束） |

### 7.4 成本与灰度

- **token 变化**：总输出量略增（每 section 重复携带少量上下文，约 +15~25% 输出 token），但**思考 token 大幅下降**（每次思考有界）——推理模型场景下思考 token 是大头，净成本大概率下降；具体需 A/B 实测
- **耗时变化**：调用次数 1→N+1（N≈5），每次更短，总耗时预计持平或略增（并发受 SSE 单会话串行约束，不并行）
- **灰度开关**：`design-skeleton-first`（默认 **false**），按模型灰度——只对已知"爱盘旋"的模型族开启，观察 2 周失控率后再评估默认

### 7.5 改动点

| 文件 | 改动 | 量级 |
|---|---|---|
| `MockupDesignService` | 新增 `designPageSkeletonFirst` 分支 + 骨架解析 + 拼装器 | ~250 行 |
| `DesignContractPrompt` | +骨架提示词构建 + section 提示词构建（复用现有场景段的大部分拼装逻辑） | ~80 行 |
| `DesignPagePlanner` | 无（页面级规划不变，骨架是页内结构） | 0 |
| `FastcmsAiProperties.Design` | +`skeletonFirst` + 骨架 maxTokens | ~8 行 |
| 单测 | 骨架解析/clamp/拼装 | ~120 行 |

**P2 工作量约为 P0+P1 的 2 倍，故排最后**——P0/P1 上线后失控的**伤害**已封顶（早停+变招+占位），P2 解决的是失控的**概率**，可在有观测数据（§10）后决定默认开关。

---

## 8. 模型侧参数适配（变招的技术基础）

### 8.1 问题：`reasoningEffort` 对 Qwen 端点无效

现状 `buildDesignOptions` L631-633：模型名含 `qwen3` 时设 `reasoningEffort("low")`。但 `reasoning_effort` 是 OpenAI o 系列原生参数；Qwen 的 OpenAI 兼容端点识别的是 `enable_thinking`（布尔，chat_template_kwargs 或顶层透传）与 `thinking_budget`（整数，思考 token 上限）。**传它不认识的参数通常被静默丢弃**——这就是"压了 low 还爆 256K"的最可能解释。

### 8.2 端点能力适配层

```java
// 新增：DesignModelAdapter（静态工具，按模型名路由，口径对齐现有 contains("qwen3") 风格）
record DesignModelCapability(
    boolean supportsReasoningControl,  // 是否支持思考开关
    Map<String,Object> offThinking,    // "关思考"参数：qwen → {enable_thinking:false}
    Map<String,Object> lowThinking)    // "低思考"参数：qwen → {thinking_budget:2048}
// 
// Qwen 族（qwen3 / qwen-plus / qwen-max）：
//   offThinking = {enable_thinking: false}
//   lowThinking = {thinking_budget: 2048}
// Claude 族（claude-3-7/4）：
//   offThinking = {}（扩展思考不可关，仅 budget 限）→ 用 max_tokens 约束兜底
//   lowThinking = {}
// GPT o 族 / 其他：
//   offThinking = {reasoning_effort: "minimal"}
//   lowThinking = {reasoning_effort: "low"}
// 未知模型：保守处理 = offThinking 不传（变招退化为纯温度扰动）
```

**透传方式**：Spring AI 的 `OpenAiChatOptions` 有 `extraBody`/额外参数通道（`OpenAiChatOptions.builder().extraBody(Map)` 或 `httpHeaders` 外的 payload 扩展），将上述 Map 合并进请求体。**实现时先对目标端点发一次最小请求验证参数落地**（看 usage 里 reasoning_tokens 是否归零），再合入——这是本项唯一需要"实测确认"的点，不靠猜。

### 8.3 变招参数矩阵（§5.2 的具体参数）

| 姿态 | Qwen | GPT o | Claude | 其他 |
|---|---|---|---|---|
| 正常（首次） | `enable_thinking:true`（默认） | 默认 | 默认 | 默认 |
| 轻量（变招后） | `enable_thinking:false` + 温度+0.2 | `reasoning_effort:minimal` + 温度+0.2 | 温度+0.2（无法关思考） | 温度+0.2 |

---

## 9. 配置清单（新增项汇总）

全部挂在 `fastcms.ai.template.design` 下，与现有 5 项并列：

| 配置键 | 类型 | 默认 | 所属层 | 说明 |
|---|---|---|---|---|
| `auto-retry-on-runaway` | bool | `true` | P0 止损 | 思考失控后自动变招重试 1 次 |
| `max-reasoning-chars` | long | `65536` | P1 检测 | 思考总量早停阈值（硬上限 clamp 262144） |
| `stall-timeout-ms` | long | `90000` | P1 检测 | 思考静止判定（0=关闭该信号） |
| `repeat-window-chars` | int | `4096` | P1 检测 | 重复检测窗口（0=关闭该信号） |
| `repeat-threshold` | double | `0.85` | P1 检测 | 重复窗口重合率阈值 |
| `skeleton-first` | bool | `false` | P2 预防 | 骨架优先拆分（灰度开关） |
| `skeleton-max-tokens` | int | `2000` | P2 预防 | 骨架调用输出上限 |
| `reasoning-log-enabled` | bool | `true` | 观测 | 思考落盘开关 |
| `reasoning-log-sample` | double | `1.0` | 观测 | 落盘采样率（1.0=全量；高流量可降 0.2） |

**向后兼容**：全部新增项有默认值，yml 不加不报错；老配置（`enabled/max-audit-rounds/...`）语义不变。

---

## 10. 观测层：思考落盘

### 10.1 现状缺口

思考流只推 SSE 给前端、**不落盘**——复盘失控"在想什么"无据（本次事件只能靠"256K + completion=0"间接推断是循环）。前端刷新后思考也丢了。

### 10.2 方案

```
workDir/design-log/
  ├── <page>.skeleton.json                    （P2 骨架产物）
  ├── <page>-r1.reasoning.txt                 （第 1 页第 1 轮思考全文）
  ├── <page>-r2.reasoning.txt
  └── ...
```

| 设计点 | 决定 | 理由 |
|---|---|---|
| 写入时机 | 思考流式到达即异步追加（每 4K 批量 flush，非同步写） | 不阻塞 SSE 主链；中途失败也留下"想了一半"的现场 |
| 单文件上限 | 512KB（超限截断 + 行尾标注 truncated） | 磁盘保护；512K 远超任何正常思考 |
| 保留策略 | 会话 DONE 后由工作目录清理机制统一回收（随 workDir 生命周期，不另建清理任务） | 零新增运维 |
| 采样 | `reasoning-log-sample`，按页 hash 稳定采样（同页全落或全不落，便于对比） | 高流量降成本 |
| 隐私 | 思考内容含用户需求，落盘在服务器本地 workDir（与现有设计稿文件同级别），**不上传** | 与现有文件安全口径一致 |
| 管理端查看 | 本期**不做** UI（文件本身可运维查看）；后续如做，走 `GET /sessions/{id}/design-log` 列文件 + 下载 | 控制范围 |

### 10.3 与前端配合

思考落盘后，失败消息可携带 `reasoning-log` 文件相对路径（status 事件），前端展示"查看该页思考记录"链接（可选，P0 不做，留文件路径在后端日志即可）。

---

## 11. 实施里程碑

| 里程碑 | 内容 | 工作量 | 验证门槛 |
|---|---|---|---|
| **M1（P0）** | 变招重试 + 端点能力适配（§5+§8，含最小请求实测参数落地）+ 思考落盘（§10） | ~1.5 天 | ① 复现用例：构造"同输入必失控"场景（可用已知触发批次），变招后该页成功或占位，**不再 FAILED 卡死**；② 落盘文件存在且含思考全文；③ 单测：变招分支/防抖约束 |
| **M2（P1）** | 早停三件套（§6）+ 配置化阈值 | ~1 天 | ① 单测：静止/重复/超限三信号各自触发 + 正常长思考不误杀；② 复现用例：浪费 token 从 12 万+ 降到 <1 万（实测 usage 对比）；③ `mvn test` 全绿 |
| **M3（P2）** | 骨架优先（§7），默认关灰度 | ~2 天 | ① 单测：骨架解析/clamp/拼装/回退；② 灰度模型上 A/B：失控率与单页成本对比整页模式；③ 拼装产物过 DesignHtmlValidator V1~V6 全绿 |
| **M4（评估）** | 依据 M1~M3 观测数据定默认值 | 0.5 天 | 决策记录入本文档 §12 附录 |

**总工作量 ~5 人天**，M1 可独立上线（不依赖 M2/M3），上线顺序严格 M1→M2→M3。

---

## 12. 验收标准（端到端）

1. **死胡同消灭**：任何页思考失控 → 自动变招 → 成功或占位页；用户最多看到一条 status 提示，**无需手动干预即可推进到全站完成**
2. **成本封顶**：单页单次失控浪费 <1 万思考 token（现状 12 万+）；单页失败总浪费 <3 万 token（含变招重试）
3. **全站不崩**：最坏情况（所有页都失控）= 全占位站 + 明确的失败明细消息，SSE 正常结束，会话状态可查
4. **有证据**：每次失控落盘思考全文 + plan.json history 记录 cause（STALLED/REPEATING/OVERBUDGET）
5. **零回归**：管线模式（createMode=pipeline）行为零变化（改动全部收在 design 包 + 新配置项）；审计/转化/断点续传既有行为不变
6. **构建门槛**：`mvn compile` 全绿（JDK21 环境）+ 前端无改动（本方案纯后端）

---

## 13. 风险与未决问题

| # | 风险 | 缓解 |
|---|---|---|
| 1 | `enable_thinking` 透传在 Spring AI 版本的落地方式需实测（不同端点参数位置不同：顶层 vs chat_template_kwargs） | M1 前置任务：最小请求验证，验证不过则变招退化为纯温度扰动（仍有效，只是弱一档）——**不阻塞** |
| 2 | 早停阈值误杀"深度思考的正常页" | 三层信号独立可调（置 0 关闭单项）；误杀代价仅是 1 次变招重试，不毁站 |
| 3 | 骨架模式 section 间风格不一致（每次独立生成） | 提示词携带"已生成 section 的 nav/配色变量摘要"作一致性锚点；审计 A4 跨页一致性规则兜底；灰度期人工抽查 |
| 4 | 温度+0.2 对某些端点无效（temperature 不被推理模型支持） | 参数矩阵按端点能力降级；无温度支持则变招只剩姿态切换（仍有效） |
| 5 | 64K 默认阈值对个别"思考型"强模型偏紧 | 配置化 + M4 依据观测数据调参；阈值是"触发变招"而非"毁站"，调参风险低 |

**未决（不阻塞 M1/M2）**：
- 失败页的"重新生成单页"前端入口（现在靠重发消息触发，体验可优化，属产品范畴）
- 多模型自动 failover（失控 2 次后自动切到会话配置的备用模型——需会话级模型配置支持，超出本方案范围，记入 backlog）

---

## 附录 A：现状代码坐标（实施对照表）

| 位置 | 现状 | 本方案改动 |
|---|---|---|
| `MockupDesignService` L87 `REASONING_RUNAWAY_MAX_CHARS=256K` | 硬编码常量 | 改读配置 `max-reasoning-chars`（§6.3） |
| `MockupDesignService` L358-385 思考增量处理 | 缓冲+256K 保险丝 | 插入静止计时器 + 重复窗口检测（§6.1/6.2） |
| `MockupDesignService` L269-313 `designPage` 格式修正循环 | 3 轮格式修正 | 外层包一层"失控变招重试 1 次"（§5.2），与格式轮次正交 |
| `MockupDesignService` L617-635 `buildDesignOptions` | qwen3→reasoningEffort low | 改走 `DesignModelAdapter`（§8.2），接受姿态参数 |
| `MockupOrchestrator` L284-290 FAILED 捕获 | 原样重抛 | 不变（变招在 designPage 内完成，到不了这里已是占位） |
| `DesignContractPrompt` L53-62 系统提示词 | 设计契约 | 不变；轻量姿态时场景段追加一句（§5.4） |
| `FastcmsAiProperties.Design` L175-214 | 5 项配置 | +9 项（§9） |
| 新增 `ReasoningStallDetector`（静态工具） | — | §6.2 检测逻辑，纯函数可单测 |
| 新增 `ReasoningRunawayException` | — | 带 cause 枚举（STALLED/REPEATING/OVERBUDGET） |
| 新增 `DesignModelAdapter`（静态工具） | — | §8.2 端点能力矩阵 |


