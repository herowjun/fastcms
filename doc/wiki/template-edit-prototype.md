# 模板编辑改造实施指南：达到 AI 工作台原型效果

> **给接手实现的研发**：照本文档分阶段改造，即可把模板编辑页从现在的「手动编辑器 + 全屏 AI 抽屉」
> 变成 `doc/wiki/交互设计稿-模板编辑-AI工作台.html` 里的样子。
>
> - **验收基准**：原型 HTML 文件。浏览器打开它，对照本文 §8 验收总清单逐条核对。
> - **关联方案**：`doc/wiki/模板编辑-内联预览与视口切换-设计方案.md` —— 其正文（内联预览 + 视口切换）是
>   本指南 Phase 0/1 的前置设计；其「附录：AI 工作台」（早期方案，左列是 AI 会话列表）**已被本指南取代**，
>   冲突处以本指南为准。
> - **范围**：纯前端（`ui/src/views/template/` + 必要的组件/组合式函数抽取）。
>   **零后端改动、零新依赖、零新路由、零配置开关**。
> - 文中所有代码坐标均逐一核实过（2026-09），动代码前请以当时代码为准复核一次。

---

## 1. 一句话目标

把「手动编辑器工具栏里两个橙色 AI 按钮 + 一个吞全屏的 el-drawer 抽屉」替换成
**「手动编辑 / AI 工作台」两个平级一级视图**：两者共用同一套三列骨架
（**模板文件树 | 中间工作区 | 内联预览**），中间工作区在手动编辑里是**代码编辑器**，
在 AI 工作台里是 **AI 对话**。

### 为什么要改（根因，不用再讨论）

1. 现状 AI 是 `el-drawer size="100%"`（edit.vue:102）——全屏吞掉手动编辑器，
   用 AI 时用户失去文件树/代码的视线；手动编辑与 AI 两条工作线抢同一块屏。
2. **数据模型本来就分两类**（这是平级视图的依据）：

   | 会话类型 | 后端标志 | 行为 | 预览指向 |
   |---|---|---|---|
   | 调整型 adjust | 绑 `templateId`（edit.vue:425） | AI 输出**直写正式模板目录**（后端自动备份），支持按轮回滚 | 正式模板 `/template/preview/{templateId}/**` |
   | 生成型 generate | 无 `templateId`（edit.vue:466） | 在**会话工作目录**生成完整模板，「应用模板」后才复制到正式目录 | 会话目录 `/ai/template/preview/{sessionId}/**` |

3. 预览后端管线手动/AI 本就走同一条（`AiTemplatePreviewController` 统一 `servePreview`），
   把预览放进主编辑区、放进 AI 对话旁边，前置条件全部具备。

---

## 2. 目标架构（与原型一一对应）

```
模板编辑页（edit.vue 作为页面壳）
├─ 页首栏: [AI 工作台] [手动编辑]      ← 两个平级 tab（原型 :72-73）
│
├─ #view-edit  手动编辑视图（现有主编辑区 + 加预览列）
│   ├─ 工具条: 上传模板文件 | 预览 | — | 保存 | 删除 | ●未保存提示
│   └─ 三列:
│       ├─ 左: 模板文件树（搜索 + ⟳刷新）              [现有，保留]
│       ├─ 中: 代码编辑器（Marscode），可收起 38px      [现有 + 加收起]
│       └─ 右: 内联预览 TemplatePreviewPanel（flex:1）  [新增列，Phase 0 组件]
│
└─ #view-ai  AI 工作台视图（新建 aiWorkbench.vue）
    ├─ 工具条: ✦新建模板 | 🕘历史记录 | — | ↶回滚最近 | ✓应用模板(默认隐藏) | 预览指向提示
    └─ 三列（与手动编辑同构，同 col-hd 样式）:
        ├─ 左: 模板文件树
        │      ├─ scope-tpl 模板切换下拉（跨模板调整）
        │      ├─ 搜索 + ⟳刷新
        │      └─ 点文件 = 告诉 AI 聚焦该文件（选中高亮，AI 改完哪个文件高亮哪个）
        ├─ 中: AI 对话（固定 30% 宽，可收起 38px）      [aiChat.vue 提升]
        │      ├─ 对话头: modePill 状态标签 + 历史会话下拉(右上方，默认最新)
        │      ├─ focus-bar 选区锁定条
        │      ├─ 消息流（进度卡/设计稿确认卡/思考折叠/失败卡）
        │      ├─ filesArea 本轮 AI 修改文件
        │      └─ 输入区: regen-bar | pickTip | 输入框 | 点选换图/选区修改(互斥) | 发送
        └─ 右: 内联预览 TemplatePreviewPanel（flex:1）
               └─ 接通点选换图/选区钩子（AI 专属）
```

### 骨架同构原则（用户硬性要求，实现时不要走样）

- 两个视图**共用同一套三列骨架**：同列宽体系（左树同宽、中列 30% 固定、右预览 flex:1）、
  同 col-hd 列头样式（标题 + 右侧图标按钮）。
- **中列收起是共享交互**：手动编辑的「代码编辑」与 AI 的「AI 对话」都能收起为 38px 细条
  （竖排文字 + 展开按钮），收起时预览列自动扩展。实现一次、两处复用
  （原型 :116-121 样式 + :804 的 `bindMidCollapse` 是可直接抄的参考实现）。
- **不加**后端配置开关、**不新增**菜单/路由——两个 tab 是同一页面的两个视图。
- 手动编辑工具栏**不再有**「AI 工作台」跳转按钮（用户已拍板：没有多大作用），切视图只靠顶部 tab。

---

## 3. 现状盘点（已有什么，直接复用，别重造）

### 3.1 后端 —— 零改动

| 能力 | 位置 | 说明 |
|---|---|---|
| 预览路由（两入口同管线） | `starters/ai-starter/.../AiTemplatePreviewController.java` :69 / :113 | `/ai/template/preview/{sessionId}/{templateName}/**`（会话工作目录）与 `/template/preview/{templateId}/**`（正式模板）汇入同一 `servePreview`（:157）：`.html` → FreeMarker mock 渲染（`_preview_data.json` 实时生效）；css/js/图片/字体 → 原样静态输出 |
| 会话实体 | `service/.../entity/AiTemplateSession.java` | `status` 仅三值 **active/applied/closed**（常量在 `AiTemplateConstants.java` :81-83）；`templateId`（空=生成型）、`workDir`、`planFiles`、`createMode`、`mobileAdaptive` |
| 会话列表 API | `ui/src/api/ai/index.ts` `listSessions()` | `GET /admin/ai/template/sessions`，返回全部会话；前端按 `templateId` 有无 + `status` 自行分组 |
| 回滚 / 应用模板 | 后端已有（现由 aiChat.vue 调用） | 回滚按轮（后端自动备份），应用=工作目录复制到正式模板目录并置 applied；UI 只需挪位置 |

### 3.2 前端 —— 三块可复用积木

| 积木 | 位置 | 说明 |
|---|---|---|
| **预览面板（待抽取）** | 现在内联在 edit.vue :109-130（AI 抽屉里的预览列）+ 样式 :1200-1250 | 页面下拉（`preview.entry`）+ iframe + 空态占位；**两条 URL 都已拼好**：`useAiPreview.ts` :59-68（会话视图 / 非会话视图），`openInNewWindow`（:107-109）也已就绪 |
| **aiChat.vue（745 行，对话大脑）** | `ui/src/views/template/aiChat.vue` | 会话下拉(:8)、模式标签(:17)、已应用仅回看(:25)、进度卡(:33-45)、设计稿确认卡含审计清单(:52-60)、渲染窗口截断(:65)、token 展示(:144)、思考折叠(:154-167)、本轮文件 files-area(:175-178)、点选换图/选区(:191-199)、选区锁定条 focus-section-bar(:209-217)、回滚(:229-236)、应用模板 onApplyTemplate(:244) |
| **点选换图/选区钩子** | `composables/usePreviewIframeHooks.ts` :363-367 | 已导出 `pickMode / sectionMode / selectedSection / toggleImagePickMode / toggleSectionSelectMode / clearSelectedSection / resetModes / onPreviewFrameLoad` —— AI 视图预览列只需把 iframe load 接给 `onPreviewFrameLoad` 即接通 |

### 3.3 手动编辑主编辑区（现有，要动的是这里）

- edit.vue :47-97：`el-row` → `:sm="5"` 文件树 + `:sm="19"` 代码编辑器，**没有预览列**。
- edit.vue :28：工具栏「预览」按钮 → `onPreview`（:386-407）= `window.open` 开新窗口
  ——割裂体验，本次改造**删按钮**，预览唯一入口=内联预览列。
- edit.vue :31-36：✦ AI 调整（:415 `onOpenAiAdjust`）/ ✦ AI 新建模板（:452 `onOpenAiCreate` → `CreateTemplateDialog`）——本次改造**删除**，能力搬进 AI 工作台。
- edit.vue :102：全屏 `el-drawer`（:102-169）——本次改造**删除**。


---

## 4. 分阶段实施步骤

> 建议顺序：**Phase 0（预览组件抽取）→ Phase 1（手动编辑视图成形）→ Phase 2（AI 工作台视图）→ Phase 3（细节对齐）**。
> 每个 Phase 结束都能在浏览器里看到可运行形态，且不影响其他 Phase 的回滚。

### Phase 0：抽 `TemplatePreviewPanel.vue`（前置，两视图共用）

**目标**：把 edit.vue AI 抽屉里的预览列（:109-130 模板 + :1200-1250 样式）抽成独立组件，
后续手动编辑和 AI 工作台各挂一份。

**Props 设计**：

```
props:
  mode: 'adjust' | 'generate'        // 预览指向（决定用哪条 URL，useAiPreview 已拼好）
  templateId?: number                // adjust 模式
  sessionId?: string                 // generate 模式
  templateName?: string              // generate 模式
  entry?: string                     // 当前页面（页面下拉选中值）
  withPickHooks?: boolean            // 是否接通点选换图/选区（默认 false，手动编辑不接）
  viewport?: 'desktop'|'tablet'|'mobile'   // 视口档位（来自 关联方案 §2）
```

**列头工具（对齐原型 .prev col-hd）**：

| 元素 | 行为 | 现状依据 |
|---|---|---|
| 页面下拉 `el-select` | 选项 = 该模板/工作目录的 HTML 页面列表；选中 → `entry` 变化 → iframe 换地址 | 现有「选择预览页面」下拉（edit.vue 抽屉内），数据源 `preview.entry` |
| `⟳ 刷新` | 改 iframe `key` 强制重载 | 现有 `previewKey` 机制 |
| `⤢ 新窗口` | `window.open(aiPreviewUrl)` | 现有 `openInNewWindow`（useAiPreview.ts :107-109），URL 直接复用 |
| 视口 `el-select` | 桌面/768/375 三档，stage 容器 CSS 缩放 | 关联方案 §2 已设计（`viewport` 字段 + stage 容器 CSS） |

**刷新策略**（与关联方案 §3 一致，保持极简）：
- adjust：AI 每轮直写完成后（aiChat 收到完成事件）→ 刷新一次；**不**防抖实时。
- generate：AI 每写完一个文件（现有 `onCreateDialogCreated` 的刷新行为）→ 刷新。
- 手动编辑：**保存后刷新**（`onSaveFile` 成功 → `refresh()`）。预览 = 已保存内容，
  与后端预览路由（读磁盘）一致，不存在"预览了未保存内容"的假象。

**空态**：模板/工作目录没有任何 HTML 文件时显示占位（现有 `.preview-empty` 样式直接搬）。

**`withPickHooks=true` 时**：组件内部引入 `usePreviewIframeHooks()`，iframe `@load` 绑
`onPreviewFrameLoad`；`pickMode/sectionMode/selectedSection` 通过 `defineExpose` 或
`v-model` 暴露给父组件（AI 工作台要用它驱动「点选换图/选区修改」两个按钮的状态和 focus-bar）。

### Phase 1：手动编辑视图成形（edit.vue 主编辑区改造）

**改动清单**：

1. **加第三列（内联预览）**
   - edit.vue :47-97 的 `el-row` 改为三列：`:sm="5"` 文件树（不变）+ 代码编辑器列 + 预览列挂
     `<TemplatePreviewPanel :mode="'adjust'" :template-id="templateId" :entry="..." />`（手动编辑不传
     `withPickHooks`）。
   - 布局参数对齐原型：中列 `width:30%;flex-shrink:0`，预览列 `flex:1`；
     或沿用 `el-col` 断点（`:sm="5"` 树 / `:sm="14"` 代码 / `:sm="5"` 预览）——**二选一，
     但两视图必须用同一套**，见下条。
   - 预览列**默认常显**，不门控（用户偏好：选项永远显示，不要配置开关）。

2. **中列收起/展开**（原型 :116-121、:256、:279）
   - 代码编辑器列（含编辑器 tab 头）右上角加 `⟨` 收起按钮；收起后列宽 → **38px**，
     显示竖排文字「代码编辑」+ `⟩` 展开按钮；预览列 `flex:1` 自动吃满。
   - 实现建议：抽一个组合式函数 `useMidCollapse(colRef)` 或纯 CSS class `.collapsed`，
     与 Phase 2 AI 对话列**共用同一套**（原型 `bindMidCollapse(midEl, container, onToggle)`
     是参考实现：切换 class → CSS 控制列宽与 `> :not(.mid-expand)` 隐藏）。

3. **删工具栏「预览」按钮**（edit.vue :28）
   - 删按钮 + 删 `onPreview`（:386-407）里的 `window.open` 路径（`openInNewWindow` 能力移到
     预览列头 `⤢`，不丢功能）。
   - 工具栏最终 = 上传模板文件 | — | 保存 | 删除 | ●未保存提示（对齐原型 :234-240）。

4. **删两个 AI 按钮 + 全屏抽屉**
   - 删 edit.vue :31-36「✦ AI 调整」「✦ AI 新建模板」按钮及其处理函数调用入口
     （`onOpenAiAdjust`/`onOpenAiCreate` 的**函数体逻辑保留**，移到 Phase 2 的 AI 工作台左列按钮复用）。
   - 删 :102-169 的 `el-drawer` 及其内部预览列（已抽成 Phase 0 组件）、aiChat 挂载（Phase 2 重挂）。
   - `state.aiMode`（:415/:452 设置）的语义保留为「当前 AI 会话类型」，不再与抽屉显隐绑定。

5. **页首加视图 tab**
   - 在页首栏（现模板标题那一行）加两个平级 tab：`[AI 工作台] [手动编辑]`，默认**手动编辑**
     （现有用户从模板列表点进来就是编辑，保持默认行为不变）。
   - 视图切换用 `v-show`（不是 `v-if`）保活两个视图的 DOM——AI 对话状态、文件树选中态、
     编辑器内容切走再切回**不能丢**。
   - tab 状态存 `ref currentView: 'edit' | 'ai'`，**不做成路由**（同一页面两个视图）。

6. **文件树列头加 `⟳` 刷新**（原型 :243）
   - 现有文件树已支持搜索；列头加一个刷新按钮，点 → 重新拉取模板文件列表。

### Phase 2：AI 工作台视图（新建 `aiWorkbench.vue`）

**文件**：`ui/src/views/template/aiWorkbench.vue`（新组件，约 300-400 行：工具条 + 三列骨架 + 状态编排）。
**对话大脑 `aiChat.vue` 不重写**，从「抽屉子组件」提升为「工作台中间列组件」，props 基本不变
（补 `scopeFile`，见下）。

#### 2.1 工具条（对齐原型 :299-308）

| 按钮 | 行为 | 现状依据 |
|---|---|---|
| `✦ 新建模板` | 弹 `CreateTemplateDialog`（现有），创建成功 → 生成型会话自动进入对话列 | 现有 `onOpenAiCreate`（edit.vue :452）→ `CreateTemplateDialog.vue`，**函数体复用** |
| `🕘 历史记录` | 弹历史生成记录弹窗（见 §5 状态机/历史四态） | 见 Phase 3 |
| `↶ 回滚最近` | 调回滚 API（现有） | 现有 aiChat.vue :229-236 的回滚入口，**上提**到工具条 |
| `✓ 应用模板`（默认 `display:none`） | 仅 generate 会话存在时显示；点 → `onApplyTemplate` | 现有 aiChat.vue :244；应用后该会话变 applied（仅回看） |
| `预览指向：正式模板 xxx / 会话工作目录` | 右侧 hint，随 mode 切换文案 | 原型 :307 `prevHint` |

#### 2.2 左列：模板文件树（同构复用，**不是** AI 会话列表）

> 早期附录方案（AI 工作台左列=AI 会话列表）**已废弃**。原型定稿：左列与手动编辑同构 = 模板文件树，
> 点选语义不同：

- **scope-tpl 下拉**（原型 :309-314）：顶部一个模板切换下拉——「当前模板 · my-company / 其他模板 · xxx」。
  切模板 = 切换 AI 调整作用对象（跨模板调整），文件树与预览跟着切。
  数据源：模板列表 API（现成）。
- **文件节点**：每个节点带 `data-scopefile`（原型 :319+），**点文件 = 告诉 AI 聚焦该文件**：
  - 选中节点高亮（`.node.sel`）。
  - 选中值 → `aiChat.vue` 的 `:current-file`（现有 prop，原由抽屉「选择预览页面」下拉驱动，现在由文件树驱动）。
  - **AI 正在改/刚改完的文件**自动高亮选中（原型需求：「AI 正在调整的文件需在文件树选中高亮」）——
    实现：aiChat 的「本轮修改文件」列表变化时，emit `updated-files` → 父组件把该文件设为选中节点。
- 文件树**不渲染**代码内容（AI 工作台不写代码），只是「作用对象 + 聚焦」的导航。

#### 2.3 中列：AI 对话（aiChat.vue 提升 + 少量新增）

列宽固定 **30%**（`width:30%;flex-shrink:0`，用户拍板），可收起 38px（Phase 1.2 同一套交互）。

**对话头**（对齐原型 :322-338）：

| 元素 | 行为 |
|---|---|
| `modePill` 状态标签 | 四态：`● AI 调整`（adjust 会话）/ `● 会话工作目录`（generate 未应用）/ `已应用（仅回看）`（generate applied）/ `生成失败`（generate 本轮失败）。**注意**：后端 `status` 只有 active/applied/closed 三值，「调整 vs 生成」由 `templateId` 有无判定，「失败」是**会话期瞬时态**（本轮 SSE 失败、还没落库 applied/closed），前端在内存里维护，刷新后若无 applied 则回退到「会话工作目录」 |
| 历史会话下拉（右上方） | `el-select`，选项 = `listSessions()` 按类型分组（adjust 组 / generate 组），**默认选中最新**；切换 = 恢复该会话（现有 `onOpenHistorySession` 逻辑，edit.vue :484） |
| `＋ 新建会话` | 按当前 mode 新建一个空会话进入对话（adjust=开调整会话、generate=弹 CreateTemplateDialog） |
| focus-bar 选区锁定条 | 已有（aiChat.vue :209-217 `focus-section-bar`），保持不动 |

**消息流 / 输入区**：aiChat.vue 现有能力**全部保留**（进度卡、设计稿确认卡含审计清单、
思考折叠、token、本轮文件 files-area、回滚、应用模板、点选换图/选区按钮、失败重生成）。
原型 v3 对齐时已逐条核对过，**无缺失**。

**输入区两个互斥工具**（对齐原型 :356-360，aiChat.vue :191-199 已有）：
- `⊙ 点选换图` / `▦ 选区修改` 互斥切换（一个开另一个自动关）。
- 下方 `pick-tip` 提示条：开点选换图 = 「点选预览中的图片即可替换」；开选区修改 = 「框选预览中的区域描述修改」；都关 = 隐藏。
  （aiChat.vue 现有 pick 相关状态 + `usePreviewIframeHooks` 的 `pickMode/sectionMode` 直接驱动，只需把提示条 UI 补上。）

**接通点选钩子**（AI 工作台比抽屉强的核心收益）：
- 预览列 `<TemplatePreviewPanel :with-pick-hooks="true" />`，iframe load 绑 `onPreviewFrameLoad`。
- 对话与预览**并排**：点选 → 发送 → 看效果，一气呵成（抽屉时代预览和对话挤在同一个全屏抽屉里，
  且文件树完全丢失）。

#### 2.4 右列：内联预览（Phase 0 组件直接挂）

- `<TemplatePreviewPanel :mode="mode" :template-id / :session-id / :template-name / :entry"`
  `:with-pick-hooks="true"`。
- adjust → `/template/preview/{templateId}/**`；generate → `/ai/template/preview/{sessionId}/**`。
- AI 每轮/每文件完成后刷新（Phase 0 刷新策略）。
- 列头 `⟳ 刷新` / `⤢ 新窗口` / 页面下拉 / 视口下拉（Phase 0 组件自带）。


### Phase 3：细节对齐（历史记录弹窗、文件区、失败态等）

#### 3.1 历史记录弹窗（原型 :431-448 + :871 打开逻辑）

原型行为：AI 工作台工具条「🕘 历史记录」→ 弹窗，内部**两个 tab**：
- `未应用`（badge 计数）：status ≠ applied 的会话（active 生成中/失败回看 + closed 未应用）。
- `已应用`（badge 计数）：status = applied。

表格列（原型 :521 grid `132px 1fr 66px 80px 92px`）：**创建时间 | 模板名/标题 | 类型(调整/生成) | 状态徽章 | 操作**。

**状态徽章四态**（原型 :529 附近 + 历史表渲染）：

| 徽章 | 颜色 | 判定 |
|---|---|---|
| 生成中 | 蓝 | 会话期瞬时态（SSE 进行中，内存标记） |
| 生成失败 | 红（`.hb.e` #fef0f0） | 会话期瞬时态（本轮 SSE 失败未落库） |
| 待应用 | 黄 | generate 且 status=active/closed 且未 applied |
| 已应用 | 绿 | status=applied |

> 实现注意：「生成中/失败」不落库（后端无此状态值），弹窗打开时对**当前正在跑的会话**用内存状态
> 覆盖徽章；其余会话按 `status` + `templateId` 映射。刷新页面后，未 applied 的失败会话显示「待应用」
> 可接受（重生成入口在对话列的 regen-bar，不在历史表）。

操作列：applied = `回看`（打开会话，仅回看态）；其余 = `打开`（进入对话可续聊/重生成）。
行点击 = 同操作列。

#### 3.2 本轮修改文件区 files-area（aiChat.vue :175-178 已有，补两处）

- 文件区头部右侧加 `fa-acts`：`预览`（刷新预览列指向该文件）/ `编辑文件`（**仅 adjust 会话**有；
  切到手动编辑 tab 并选中该文件——「编辑文件」是把 AI 的改动接回手动编辑的桥）。
  generate 会话无「编辑文件」（工作目录文件在应用前不属于正式模板）。
- 每行：`[新建/修改] 文件名 [查看]`（原型 :686 渲染逻辑），查看 = toast/切预览到该文件。

#### 3.3 失败会话（原型 regen-bar :339-341）

- 会话本轮生成失败时：输入区顶部出现 `regen-bar`（浅红 `#fef0f0`）：
  「上次生成失败（详见上方错误信息）。模型配置修复后可重新生成。」+ `↻ 重新生成` 按钮。
- 重新生成 = 复用该会话上一轮的用户 prompt 重新发送（对齐现有 `onRegenerate` 逻辑，aiChat.vue 已有）。
- 失败消息渲染为红色错误气泡（`bubble fail`）。

#### 3.4 应用模板后跳转（用户已拍板）

generate 会话点「✓ 应用模板」成功后：toast 提示 + 给一个「查看正式模板」按钮
（切到手动编辑 tab 并加载该模板），**不强制跳转**。

#### 3.5 窄屏适配

- `<768px`：两视图的三列退回堆叠（现有 edit.vue :1002-1016 已有响应式逻辑，扩展到 AI 视图）。
- 中列收起在窄屏默认开启（代码编辑/AI 对话默认收起，预览占主屏）。

---

## 5. 状态机（AI 工作台核心，先想清楚再写代码）

```
                    ┌─────────────────────────────────────────────┐
                    │              会话生命周期                     │
                    └─────────────────────────────────────────────┘

  [＋新建会话/新建模板]
        │
        ├─ adjust 分支（绑 templateId）
        │     running ⇄ idle   （每轮 SSE；AI 直写正式模板目录）
        │     · modePill = 「AI 调整」
        │     · 预览 = 正式模板 URL，每轮完成刷新
        │     · 回滚最近：按轮回退（后端备份）
        │     · 无「应用模板」（直写即生效）
        │
        └─ generate 分支（无 templateId，workDir=会话工作目录）
              running ⇄ pending（待应用）⇄ applied
              │  本轮SSE失败 → failed（内存态，regen-bar 出现）
              │
              · running:  modePill「会话工作目录」+ 进度卡推进
              · failed:   红色错误气泡 + regen-bar「重新生成」
              · pending:  设计稿确认卡（预览设计稿/驳回修改/确认转化）
                          审计清单 dc-issues（IMG-xxx/SEO-xxx）
                          确认后转化 → 生成 s.files → files-area 刷新
              · applied:  modePill「已应用（仅回看）」
                          输入框 placeholder 变「该会话已应用…仅支持回看」（aiChat.vue:184 现有）
                          工具条「✓应用模板」按钮消失
                          历史表该行走「回看」
```

**模式判定一句话**：`session.templateId ? 'adjust' : 'generate'`。
`currentView`（edit/ai tab）、`currentMode`（adjust/generate）、`currentSessionId` 三个 ref
是 AI 工作台的全部顶层状态，其余（pickMode/sectionMode/selectedSection/entry/viewport）
都是子状态，挂在各自组件里。

---

## 6. 原型 ↔ 真实代码 逐行映射表（实现时照这张表找）

| 原型元素 | 原型位置 | 真实代码现状 | 动作 |
|---|---|---|---|
| 页首 tab `[AI 工作台][手动编辑]` | :72-73 | 无（AI 藏在工具栏按钮+抽屉里） | **新增** `currentView` ref + v-show 双视图 |
| 手动编辑工具条 上传\|保存\|删除\|●未保存 | :234-240 | 有「预览」按钮(:28)和两个 AI 按钮(:31-36) | 删 3 个按钮，保留上传/保存/删除/未保存 |
| 手动编辑三列 树\|代码(可收)\|预览 | :241-295 | 只有 树(:sm5)\|代码(:sm19) 两列 | **加预览列**（Phase 0 组件）+ 代码列收起（Phase 1.2） |
| 文件树列头 `⟳` | :243 | 无 | 加按钮 → 重拉文件列表 |
| 代码列收起 38px + 竖条 | :116-121,:256,:279 | 无 | **新增**，与 AI 对话列共用一套 |
| AI 工具条 新建\|历史\|回滚\|应用\|预览指向 | :299-308 | 能力都在（onOpenAiCreate:452 / 回滚 aiChat:229 / onApplyTemplate aiChat:244），入口是抽屉按钮 | **搬运**：函数体不动，入口换到工作台工具条 |
| scope-tpl 模板切换下拉 | :309-314 | 无（调整只能调当前模板） | **新增**，数据源模板列表 API，切换 → 重载文件树+预览+新建 adjust 会话 |
| 文件节点 `data-scopefile` | :319+ | 无（AI 聚焦靠抽屉「选择预览页面」下拉） | **新增**点选 → aiChat `:current-file` |
| AI 改的文件自动高亮 | 原型需求 | 无 | aiChat emit 本轮文件 → 父组件设选中节点 |
| modePill 四态标签 | :324 | aiChat:17 有简单模式标签、:25 有「已应用仅回看」 | **扩展**为四态（加「生成失败」内存态） |
| 历史会话下拉（右上，默认最新） | :333 | aiChat:8 会话下拉 | 移位到对话头右上方 + 默认最新 + 分组 |
| focus-bar 选区锁定条 | :339 | aiChat:209-217 已有 | 不动 |
| 消息流（进度/设计卡/思考/token/失败） | :655 等 | aiChat:33-167 全有 | 不动 |
| files-area 本轮文件 | :342 | aiChat:175-178 有 | **补** fa-acts（预览/编辑文件，adjust 才有编辑文件） |
| regen-bar 重新生成 | :339-341 | onRegenerate 已有 | 位置不变（输入区顶部），样式对齐浅红 |
| pick-tip 互斥提示条 | :358 | 按钮互斥已有（:191-199），无提示条 | **补**提示条 UI（由 pickMode/sectionMode 驱动） |
| 预览列头 ⟳/⤢/页面下拉/视口下拉 | :364-371 | 抽屉内有页面下拉+iframe（:109-130）；新窗口在 useAiPreview:107 | **抽取**为 Phase 0 组件，列头四件套齐 |
| 预览接通点选钩子 | AI 视图 | 钩子在 usePreviewIframeHooks，抽屉内已接 | 组件 `:with-pick-hooks="true"` 再接一次 |
| 历史记录弹窗 双tab+四态徽章 | :431-448 | 无独立弹窗（历史会话只在 aiChat 下拉里） | **新增**弹窗 + listSessions 数据 + 四态映射 |

### 三处「与真实代码故意不同」（不要改回去）

1. **el-drawer 弃用**：真实代码 AI 是全屏抽屉（edit.vue:102）；原型是平级同构三列。
   抽屉是旧「AI 与手动编辑融合」架构的产物，新架构下**删除**。
2. **「AI 改哪个文件」的驱动方式**：真实代码靠预览工具条「选择预览页面」下拉
   （`preview.entry` → `:current-file`）；原型改为**文件树点选** `data-scopefile`
   （更直观：文件树本来就是文件导航，选中即聚焦）。预览页面下拉保留在预览列头，
   但「告诉 AI 聚焦」的语义归文件树。
3. **预览列头 ⟳/⤢、文件树头 ⟳**：真实代码这些操作在抽屉/新窗口路径里零散存在；
   原型统一进列头。这是补齐，不是新能力。

---

## 7. 文件改动清单（最终交付物一览）

| 文件 | 动作 | 主要改动 |
|---|---|---|
| `ui/src/views/template/edit.vue` | 改 | 删两 AI 按钮(:31-36)/预览按钮(:28)/全屏抽屉(:102-169)；主编辑区加预览列；中列收起；页首加双视图 tab；onPreview 的 window.open 删除 |
| `ui/src/views/template/aiWorkbench.vue` | **新增** | AI 工作台视图：工具条 + 三列骨架 + 顶层状态（currentMode/currentSessionId）+ 历史弹窗 |
| `ui/src/views/template/TemplatePreviewPanel.vue` | **新增** | 预览面板组件（Phase 0），两视图共用 |
| `ui/src/views/template/aiChat.vue` | 小改 | 补 `scopeFile`/`updated-files` emit、modePill 四态、pick-tip 提示条、fa-acts；**对话逻辑不重写** |
| `ui/src/views/template/composables/useAiPreview.ts` | 小改 | 视口字段（关联方案 §2）；URL 拼接已齐 |
| `ui/src/views/template/composables/usePreviewIframeHooks.ts` | 不动 | 钩子已齐 |
| `ui/src/api/ai/index.ts` | 不动 | listSessions 已齐 |
| 后端 | **零改动** | 预览路由/回滚/应用模板全部已具备 |

---

## 8. 验收总清单（对照原型 HTML 逐条勾）

**手动编辑视图**
- [ ] 页首 `[AI 工作台][手动编辑]` 两个平级 tab，默认手动编辑；切换不丢任何一边的状态
- [ ] 工具条 = 上传模板文件 | 保存 | 删除 | ●未保存提示（无预览按钮、无 AI 按钮）
- [ ] 三列：文件树 | 代码编辑 | 内联预览；代码列 30%、预览 flex:1
- [ ] 代码列 `⟨` 收起 → 38px 竖条「代码编辑」，预览扩展；`⟩` 展开还原
- [ ] 文件树列头 `⟳` 刷新可用
- [ ] 内联预览：页面下拉/⟳/⤢新窗口/视口三档；保存后刷新；无 HTML 时显示空态

**AI 工作台视图**
- [ ] 工具条 = ✦新建模板 | 🕘历史记录 | ↶回滚最近 | ✓应用模板(仅generate显示) | 预览指向hint
- [ ] 三列与手动编辑**同构**（同列宽/同列头样式）：文件树 | AI 对话(30%) | 预览
- [ ] scope-tpl 切换模板 → 文件树/预览/会话作用对象跟着切
- [ ] 文件树点选 → AI 聚焦该文件（`:current-file`）；AI 改完的文件自动高亮
- [ ] modePill 四态正确（调整/会话工作目录/已应用仅回看/生成失败）
- [ ] 历史会话下拉在对话头右上方，默认最新，切换恢复会话
- [ ] focus-bar 选区锁定条正常
- [ ] 消息流：进度卡/设计稿确认卡(审计清单+驳回/预览/确认)/思考折叠/token/失败红气泡 全在
- [ ] files-area：本轮文件列表；adjust 会话有「编辑文件」(切手动编辑选中该文件)
- [ ] regen-bar：失败会话显示浅红条 + 重新生成可用
- [ ] 点选换图/选区修改互斥；pick-tip 提示条随状态显隐；**预览列点选真的能点中 iframe**
- [ ] AI 对话列可收起 38px / 展开还原
- [ ] 预览列：adjust 指正式模板、generate 指工作目录；AI 每轮/每文件完成自动刷新
- [ ] 历史记录弹窗：未应用/已应用双 tab + 计数 + 四态徽章；applied=回看、其余=打开
- [ ] 应用模板成功后：toast + 「查看正式模板」跳手动编辑（不强制）

**全局**
- [ ] 无 el-drawer 残留（grep `el-drawer` 应只剩无关处）
- [ ] 窄屏 <768px 堆叠不炸
- [ ] 零后端改动、零新依赖、零新路由、零配置开关

---

## 9. 风险与注意事项

1. **v-show 保活的内存代价**：两个视图都常驻 DOM，Marscode 编辑器 + 两个预览 iframe 同存。
   若内存吃紧，退化方案：手动编辑视图 v-show、AI 工作台 v-if（AI 侧状态由会话服务端持久化，
   切回时 `loadSessionData` 重拉即可，原型 :542 已有「切换会话回滚到底部」的加载逻辑）。
2. **`onCreateDialogCreated` 现在驱动抽屉刷新**（edit.vue），抽组件后刷新事件要改派给
   `TemplatePreviewPanel`，别漏。
3. **aiChat.vue 的 `:current-file` 原由抽屉下拉驱动**，改由文件树驱动后，抽屉时代的
   `preview.entry` 下拉（现在挪进预览列头）只管「预览哪个页面」，不再管「AI 聚焦哪个文件」——
   两个语义彻底分开，别混。
4. **scope-tpl 跨模板调整**是原型新增能力：切到「其他模板」后发起的 adjust 会话，
   `templateId` 绑定的是**被选中的那个模板**，不是页面加载时的那个。会话列表分组时按
   会话自己的 `templateId` 归组，别按当前页面模板归组。
5. **状态徽章「生成中/失败」不落库**：后端 `status` 只有 active/applied/closed。
   历史弹窗对非当前会话只能显示 待应用/已应用；只有当前正在跑的会话能显示 生成中/失败
   （内存标记）。这是数据模型决定的，不是实现遗漏。
