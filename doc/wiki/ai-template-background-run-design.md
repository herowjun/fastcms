# AI 模板任务后台续跑与重开续看——设计与实现

> 版本：v1.0（2026-09-14）
> 关联：`ai-template-two-mode-design.md`（设计稿先行模式）、`html-import-to-template-design.md`（HTML 导入）、`style-upgrade-iteration-fix-plan.md`（旧模板升级）

## 1. 背景与问题

AI 模板的四类长任务（AI 新建模板、设计稿先行模式、旧模板升级/焕新、HTML 导入转化）共同痛点：

1. **任务耗时长**（分钟级），用户不可能长期停留在生成页面；
2. 旧实现中 SSE 连接与任务生命周期强耦合：**关闭页面/断网 = 取消任务**，已完成进度
   依赖 plan.json/文件表兜底恢复，但本轮思考过程全部丢失；
3. 重新打开页面只能看到静态历史消息，无法"继续看 AI 思考"。

### 目标行为

- 用户可随时关闭页面/抽屉：任务**后台续跑**，产物照常落盘；
- 重新打开页面：**回放已发生的思考过程**（journal 事件流），再实时续接；
- 断网/断线：自动重连（增量回放，不重播已收事件）；
- 停止任务：**只能显式点击停止按钮**（区别于关闭页面）；
- 停止/失败后：中断原因 + 进度明细 + 本轮思考过程落库，可追溯、可断点续传。

## 2. 核心设计：任务与连接解耦

### 2.1 RunChannel（任务侧事件中枢）

`ai-starter/support/RunChannel.java`——任务持有通道，页面只是订阅者：

- **事件 journal**：任务运行期间所有事件（reasoning/message/status/file/progress/…）
  追加进 append-only 日志并分配单调递增 seq（SSE 标准 `id` 字段）；
- **多订阅者扇出**：同一会话可多标签页同时观看，单订阅者断开仅摘除该订阅者；
- **显式取消**：`cancel()` 仅由 stop 端点触发（断开连接不再触发取消）；
- **终态短保留**：任务结束后 journal 保留 5 分钟（`SessionRunRegistry.FINISHED_RETENTION_MS`），
  迟到的续连仍能回放完整事件（含 done），过期后惰性清理；
- **线程模型**：单写者（任务线程 send）+ 偶发读者（HTTP 线程 subscribe/回放），
  journal/订阅者操作走方法级 synchronized（回放与实时推送互斥，SseEmitter 非线程安全）。

容量边界：journal 生命周期 = 任务生命周期（reasoning 有后端保险丝封顶），硬上限
10000 条仅防御异常任务无限追加。

### 2.2 SseChannel（管线适配器，管线零改动）

`AiTemplateGenServiceImpl.SseChannel`——RunChannel 的任务侧适配器，事件名/调用点
与旧实现完全一致：`send` → journal+扇出；`isCancelled` → 仅显式取消；`complete` →
`run.finish()`（complete 全部订阅者并标记终态）。

### 2.3 SessionRunRegistry（会话运行注册表）

`sessionId → RunChannel`，职责：

1. 同会话并发任务防御：已有运行中任务（含终态未过期）时新任务被拒绝；
2. run-status 探测与 stream 续连接入；
3. 终态短保留 + get 时惰性清理（不起后台清理线程）。

内存注册表，随 JVM——重启后任务不存在（返回"无运行任务"），会话靠既有断点续传
机制（plan.json / mappingCache / 文件表）自洽恢复。

## 3. 端点协议（`AiTemplateController`）

| 端点 | 方法 | 语义 |
|---|---|---|
| `sessions/{id}/run-status` | GET | 探测运行态：`{running, lastSeq, startedAt}`（终态保留期内 running=false） |
| `sessions/{id}/stream?since=N` | GET | 续看：回放 journal 中 `seq > N` 的历史事件（SSE id 携带原 seq），再挂订阅者实时续接；无运行任务发 `run-status` 事件（running=false）后结束；任务已结束则仅回放后 complete |
| `sessions/{id}/stop` | POST | 显式取消（管线各检查点优雅中断，断点续传语义保留） |

chat（POST）端点行为不变：提交者连接作为第一个订阅者挂上 RunChannel。

### 3.1 终态事件时序（关键不变量）

后端在 `channel.complete()`（即 run.finish() → complete 订阅者）**之前**先落终态消息：

- 失败：`saveMessage(失败原因 + reasoning)` → `sendError` → complete；
- 停止：`saveMessage("生成已停止" + 进度明细 + reasoning)` → complete（无 error 事件）；
- 正常：`done` 事件（管线发送）→ complete。

因此前端"流结束但未收到 done/error"时，重载消息必然能看到终态消息——这是
前端终态兜底重载（`reloadRunTerminal`）正确性的基础。

## 4. 取消与进度落库

### 4.1 停止消息（`buildCancelledNote`）

- design 会话：统计 plan.json 已完成页数；
- 管线/微调会话：统计已生成文件数；
- 附带本轮已累积的思考过程（`run.reasoningSoFar()` 从 journal 聚合）——
  旧实现取消时丢弃 reasoning，现在停止/失败后仍可回看。

### 4.2 进度点即时持久化（中断后重入不重复处理）

- 设计段：单页 DONE 即回调 `pageDoneSink` → `markPageDone` 写回 plan.json 落盘；
- 转化段：每批映射完成即回调 `mappingProgressSink` → 全量映射快照写回 plan.json。

## 5. 前端实现（`ui/views/template/aiChat.vue`）

### 5.1 结构重构

原 `onSend` 内联的流式机制抽取为可复用函数：

| 函数 | 职责 |
|---|---|
| `createRunContext(styleUpgrade, resume)` | 单轮上下文：assistant 占位消息 + 事件分发（含节流渲染/防爆保险丝）+ finish 收口 |
| `consumeSse(resp, ctx)` | 按行解析 SSE（event/data/**id**），空行分发事件；`id` 记入 `state.lastSeq` 续连游标 |
| `observeLoop(ctx)` | 观察循环：探测运行态 → 连 stream 端点消费；未终态断开则指数退避重连（1s/2s/…上限 30s，共 8 次） |
| `observeRunning(since)` | 重开页面续看入口（loadSessionData 探测到 running 时调用） |
| `reloadRunTerminal()` | 终态兜底重载：复用 loadSessionData 全量恢复 |

事件分发新增 `run-status` 分支（no-op，由流结束后的观察循环收口）。

### 5.2 生命周期行为

| 场景 | 行为 |
|---|---|
| 发送消息 | POST chat → consumeSse；流结束未终态/网络异常 → `observeLoop` 续连（任务后台仍在跑） |
| 重开页面 | loadSessionData → run-status 探测 → running 则 `observeRunning(0)` 全量回放到新占位消息 + 实时续接 |
| 断线 | observeLoop 指数退避重连，`since=lastSeq` 增量回放 |
| 停止按钮 | 调 stop 端点（**不再本地 abort**——abort 会跳过"流结束→重载终态消息"路径）；停止后服务端 complete 流 → 前端自动重载"已停止"消息（含进度明细） |
| 切换会话/卸载 | 仅断开连接（abort），任务不受影响 |
| 关闭 AI 抽屉 | `edit.vue` before-close 弹窗提示"任务将后台继续处理"（确认后才关） |
| 多标签页 | 各自独立订阅同一 RunChannel，互不影响 |

### 5.3 续连游标（lastSeq）

- 新一轮任务开始（onSend）归零（新 RunChannel seq 从 1 重新计数）；
- 每收到 SSE `id` 更新为最大值；
- 重连/续看时作为 `since` 参数。

## 6. 边界与自洽性

1. **服务重启**：注册表内存态丢失 → run-status 返回无任务 → 前端重载终态
   （此时无终态消息，展示为"无进行中任务"）；任务若未完成，重新发消息走
   既有断点续传（plan.json/mappingCache/文件表）。
2. **stop 与断连窗口竞态**：停止后任务线程在下一个检查点优雅中断（模型调用在途
   由 callTimeout 兜底），期间 run-status 仍 running → 前端续连 → 流随后 complete →
   探测 false → 重载终态。
3. **终态保留期（5 分钟）内重开**：run-status running=false，不续看；
   若恰好任务刚结束（保留期内）且前端直连 stream → `replayAndComplete` 回放完整
   事件（含 done/error）后 complete，前端照常收尾。
4. **journal 硬上限**：超 10000 条丢最老（`seq > since` 回放本就不需要更老的），
   正常任务远达不到（reasoning 后端保险丝 256KB）。
5. **停止请求失败**（网络）：前端兜底仅断开本地连接并提示，任务后台继续，
   重开页面可续看/再停。

## 7. 验证清单

| # | 场景 | 预期 |
|---|---|---|
| V1 | AI 新建模板生成中关页面 → 重开 | 任务后台续跑；重开回放思考过程 + 实时续接；SSE id 递增 |
| V2 | 设计稿先行模式生成中关页面 → 重开 | 同 V1；设计段已 done 页不重出（plan.json 页级进度） |
| V3 | 旧模板升级中关页面 → 重开 | 同 V1；升级断点续传横幅正确 |
| V4 | HTML 导入转化中关页面 → 重开 | 同 V1；已映射批次不重问 AI（mappingCache 批级进度） |
| V5 | 生成中点击停止 | 落"已停止"消息（进度明细 + 思考过程）；重新发消息可续传 |
| V6 | 生成中断网 → 恢复 | 指数退避自动重连，增量回放不重播 |
| V7 | 双标签页同时观看 | 两路独立订阅，互不影响 |
| V8 | 关闭 AI 抽屉（任务进行中） | 弹窗提示后台继续；重开抽屉续看 |
| V9 | 会话已有运行任务时再发消息 | 后端拒绝并提示（会话级单任务防御） |
