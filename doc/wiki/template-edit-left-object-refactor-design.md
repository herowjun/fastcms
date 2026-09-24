# 模板编辑页改造技术方案 · 左侧统一操作对象区

> 状态：待评审
> 日期：2026-09-19
> 交互原型：`doc/wiki/prototype-template-edit-v3.html`（浏览器直接打开可交互）
> 涉及范围：仅前端 `ui/src/views/template/edit.vue`（主改）+ `aiWorkbench.vue`（小改），数据流/弹窗/后端零改动

---

## 1. 背景与目标

### 1.1 现状问题

当前 `edit.vue` 工具栏是两行：

```
第一行：[AI 工作台] [手动编辑]                    （view-tabs）
第二行：[工作对象选择按钮 selbtn（flex 20%+min 190px）] [视图按钮组]   （toolbar-row）
```

其中 `tb-col-template`（selbtn 所在列）的列宽公式 `flex 0 0 20% + min-width 190px` 是**刻意与下方左树列对齐**的对位 hack（见 edit.vue L1092 注释）。问题：

1. 工作对象（模板/会话）选择按钮悬浮在工具栏里，与它"支配"的左列文件树在视觉上分离——对象、树、按钮三者逻辑同属左侧，却被拆在两个区域。
2. 左列是纯 `flex 20%` 比例宽度，随窗口变宽，对象选择按钮跟随飘移，对位 hack 只能保证"起点大致对齐"。
3. 工具栏两行占用纵向空间，且"对象选择"这个**页面级操作**和"保存/上传"这种**视图级操作**混在同一行。

### 1.2 目标布局

```
┌ 工具栏（单行，sticky 吸顶）──────────────────────────────────────┐
│ [🤖 AI 工作台] [✏️ 手动编辑]        [视图按钮组（按视图切换）]    │
├──────────────────────────────────────────────────────────────────┤
│ 左列 280px 固定（统一操作对象区）│ 右列 flex 1（两 tab 同容器保活）│
│ ┌──────────────────────────┐ │  AI 工作台：  预览(flex1) │ 对话30% │
│ │ 对象卡片（点击弹选择器）  │ │  手动编辑：  预览(flex1) │ 代码30% │
│ ├──────────────────────────┤ │                            │
│ │ 文件树（标题+刷新+过滤）  │ │                            │
│ └──────────────────────────┘ │                            │
└──────────────────────────────┴──────────────────────────────────┘
```

要点：

- **左列 = 统一操作对象**：对象选择卡片（原 selbtn 上提、放大）+ 文件树，固定 `280px`。
- **对象卡片与文件树是"对象 → 对象内容"的从属关系**，垂直堆叠、同宽，视觉归属明确。
- 右侧两个 tab **共用左列**（现状数据流已支持，零改动）：AI 工作台编辑 = AI 对话改文件；手动编辑 = 代码编辑器改文件；切 tab 不丢任何状态（现状 v-show 保活已实现）。
- **每个视图有自己的按钮组**（现状 `v-if/v-else` 双工具条逻辑保留），对象选择不再是"按钮行成员"，而是左列常驻卡片。

### 1.3 用户已拍板的取舍

| 项 | 结论 |
|---|---|
| 左列"＋ 新建模板"按钮 | **不要**（"新建模板"保留在 AI 工作台工具条，现状位置不动） |
| AI 工作台工具条"预览指向：xxx"提示 | **删掉**（edit.vue L65 的 `prev-hint` span + aiWorkbench 的 `prevHint` expose 一并清理） |
| 左列宽度 | 固定 `280px`（不再 20% 比例） |
| 视图按钮 | 维持现状按视图切换：AI = 新建模板/回滚最近/应用模板/编辑此模板；手动 = 上传模板文件/保存/删除/未保存提示 |

---

## 2. 现状代码盘点（改造基线）

所有行号基于当前 `ui/src/views/template/edit.vue`（1414 行）。

### 2.1 模板结构

| 行号 | 元素 | 改造动作 |
|---|---|---|
| L4-13 | `.toolbar > .view-tabs`（视图 tab 第一行） | 保留，移入单行工具栏 |
| L15-26 | `.toolbar-row > .tb-col-template > .selbtn`（对象选择按钮） | **整体剪切**到左列顶部 |
| L27-67 | `.tb-col-actions`（双视图按钮组，v-if/v-else） | 保留，并入单行工具栏；删 L65 `prev-hint` |
| L73-90 | `.main-columns > .left-col`（FileTreePanel + panel-side-bar） | left-col 顶部插入对象卡片 |
| L93-148 | `.right-col`（两 tab 内容区） | **零改动** |
| L152-157 | `WorkObjectDialog` | **零改动**（触发源从"工具栏 selbtn"变"左列卡片"，事件链不变） |

### 2.2 脚本（零改动项确认）

- `currentObject`（L213）：唯一对象事实源 —— 不动。
- `selectObject`（L361）/ `onDialogSelect`（L451）/ `onDialogCreate`（L459）/ `onDialogApply`（L468）/ `onDialogGotoTemplate`（L484）：对象切换全链路 —— 不动。
- `selBtnState` / `selBtnName` / `selBtnTag`（L422-450，三个 computed）：对象卡片三态配色与文案 —— **原样复用**，改名 `objCardState/objCardName/objCardTag`（可选，见 §5.5）。
- `dialogVisible`（L191）：弹窗开关 —— 不动。
- `panelCollapsed`（L207）+ `onNarrowMqChange`（L228）：左列收起逻辑 —— 语义调整，见 §4.3。
- `updateEditorHeight`（L1014-1032）：高度自适应 —— 逻辑不动（见 §4.4 论证）。
- 文件 CRUD / 上传 / 未保存 / 路由拦截 / SSE 相关 —— 全部不动。

### 2.3 样式（需改）

| 行号 | 样式 | 改造 |
|---|---|---|
| L1083-1110 | `.toolbar`（含 `.toolbar-row` 两行结构 + `.tb-col-template` 对位 hack） | 重写为单行；**删除 `.tb-col-template` 整块**（对位 hack 随 selbtn 上提而失去存在理由） |
| L1115-1180 | `.selbtn`（三态配色） | 复用为左列对象卡片样式（高度 32→36，增加副行说明），类名改 `.obj-card` |
| L1181-1193 | `.toolbar-actions`（含 `.prev-hint`） | 删 `.prev-hint` 规则 |
| L1195-1227 | `.view-tabs` | 保留，`margin-bottom: 14px` 调小（单行工具栏内不再需要大间距） |
| L1238-1287 | `.left-col`（`flex 0 0 20% + min-width 190px`，收起 38px） | `flex 0 0 280px`；收起语义调整（§4.3） |
| L1385-1405 | 窄屏 `@media` | `.left-col` 固定宽在窄屏退回 `width: 100%`（现有规则已覆盖，验证即可） |

### 2.4 aiWorkbench.vue（小改）

- 删 `defineExpose` 中的 `prevHint`（唯一消费方 edit.vue L65 已删）。
- 其余 expose（`canRollback/rollingBack/showApply/applying/showEditApplied/onRollback/onApplyTemplate/onEditApplied/openCreateDialog/getSessionBadgeCtx`）不动。

---

## 3. 布局设计

### 3.1 尺寸推导

- 左列：`flex: 0 0 280px`。依据：对象卡片要放下"图标 + 名称 + 标签 + 下拉箭头 + 副行说明"两行内容（13px 字体下约 220px 有效宽度）；文件树过滤框 + 树节点缩进（三级 ≈ 54px 缩进 + 图标 + 文件名）在 280px 内舒适。
- 右列：`flex 1 1 0`（现状不动）。
- 最窄可用验证（1280px 视口，页面 padding 32px）：右列 = 1280−32−280−12 = 956px；代码列 30% ≈ 287px；预览列 = 956−287−12 = 657px（≥ 现状 min-width 260px，宽裕）。

### 3.2 单行工具栏

```html
<div class="toolbar">
    <div class="view-tabs"> [AI 工作台] [手动编辑] </div>
    <div class="toolbar-actions" v-if="currentView === 'edit'"> 上传 | 保存 删除 未保存提示 </div>
    <div class="toolbar-actions" v-else> 新建模板 回滚最近 应用模板 编辑此模板 </div>
</div>
```

- `.toolbar` 内直接 `display: flex; align-items: center`；tab 靠左，按钮组 `margin-left: auto` 靠右。
- 高度从两行（≈78px）降为单行（≈44px），`sticky` 吸顶行为不变。
- 按钮组内容**完全维持现状**（含条件渲染 v-if），仅删除 `prev-hint`。

### 3.3 左列（统一操作对象区）

```html
<div class="left-col" :class="{ collapsed: panelCollapsed }">
    <!-- 对象卡片：原 selbtn 上提，三态配色沿用 -->
    <button class="obj-card" :class="objCardState" title="点击选择工作对象（正式模板 / 生成会话）"
            @click="dialogVisible = true">
        <el-icon class="s-ico"><ele-FolderOpened /></el-icon>
        <span class="s-name">{{ objCardName }}</span>
        <span class="s-tag">{{ objCardTag }}</span>
        <el-icon class="s-caret"><ele-ArrowDown /></el-icon>
        <span class="obj-sub">{{ objCardSub }}</span>   <!-- 新增副行：见 §3.4 -->
    </button>
    <!-- 文件树卡片（现状 FileTreePanel，v-show 收起） -->
    <FileTreePanel v-show="!panelCollapsed" ... />
    <div class="panel-side-bar"> ... </div>
</div>
```

- `.left-col` 改 `flex-direction: column; gap: 10px`。
- 对象卡片高度约 56px（主行 32 + 副行），**不参与收起**（§4.3）。
- `panel-side-bar`（收起/展开把手）从"列右缘"改为**文件树卡片右缘**：实现上 FileTreePanel 与 side-bar 包一层 `.tree-row`（display:flex，flex:1，min-height:0），side-bar 结构不动。

### 3.4 对象卡片副行（新增）

原 selbtn 单行只有"名称 + 标签"，上提为卡片后加一行 12px 灰字副行，承载对象类型信息（工具栏按钮位置放不下两行，卡片位置放得下）：

| 对象态 | 主行 | 副行（`objCardSub` computed 新增） |
|---|---|---|
| 正式模板 | 模板名 + 目录名 | `正式模板 · ID 12 · 点击切换` |
| 草稿/会话（生成中/未应用） | 会话名 | `生成会话 · 点击切换` |
| 已应用回看 | 会话名 | `已应用于「模板名」 · 回看模式` |

computed 逻辑从 `currentObject` 派生，与现有 `selBtnState/selBtnName/selBtnTag` 同源，三态配色 CSS 原样搬。

### 3.5 右列

**零改动**（两 tab 容器、预览列、对话列、代码列、各自收起把手全部保持现状）。

---

## 4. 交互与行为

### 4.1 对象切换链路（不变）

左列卡片点击 → `dialogVisible = true` → WorkObjectDialog 三页签 → `@select/@create/@apply/@goto-template` → `selectObject` → 左树/两 tab 自动跟随（现状机制）。弹窗内"行点击选中不切页签 / 行内按钮指定落点视图"的交互不变。

### 4.2 视图切换（不变）

view-tabs 点击切 `currentView`，两 pane `v-show` 保活；切视图时 `watch(currentView)` 重算高度 + 退出编辑器全屏（L1001-1004）不变。

### 4.3 左列收起语义调整

现状：`panelCollapsed = true` → 整列（含 FileTreePanel）变 38px 竖条 + 竖排"文件树"标签。

改造后语义：**收起只作用于文件树卡片，对象卡片始终可见**（对象是页面级锚点，收起文件树后仍需能切换对象）。

- 收起时列宽保持 280px（对象卡片宽度需求），`.tree-row`（FileTreePanel+side-bar）v-show 隐藏，side-bar 展开按钮移到对象卡片下方常驻（或并入卡片副行右侧小图标，二选一，实现时取"卡片下方独立 22px 把手条"，与现有 mid-side-bar 同构）。
- `onNarrowMqChange`（窄屏默认收起）行为不变：窄屏下左列 `width: 100%` 堆叠，树收起、对象卡片横向铺满。

> 备选方案（如评审倾向保持整列可收成 38px）：对象卡片收起态压缩为 38px 竖条（图标 + 竖排"对象"标签），点击仍开弹窗。实现成本多一段 CSS，不影响其他设计。默认不采用。

### 4.4 高度自适应（论证：逻辑零改动）

`updateEditorHeight` 以 `rightColRef`（`.right-col`）的 `getBoundingClientRect().top` 实测起点，`viewportH − top − 48` 注入 `mainColumnsStyle` 高度。

- 改造后工具栏从两行变一行，`right-col` 的 top 变小 → 计算出的高度自动变大，逻辑自洽。
- 左列固定 280px 不影响右列 top。
- "两 tab 起点恒等"的前提（right-col 恒在 DOM + v-show）不变。
- 300/800ms 冷启动收敛、ResizeObserver 观察 `.layout-main`、keep-alive `onActivated` 兜底 —— 全部不动。

### 4.5 键盘/快捷键

- Ctrl/Cmd+S（仅手动编辑视图）、Esc 退出编辑器全屏 —— 不动。

---

## 5. 变更清单

### 5.1 edit.vue · template（约 30 行移动 + 15 行新增）

1. 删 L15-27 `.toolbar-row`（含 `.tb-col-template`），工具栏改单行：`.view-tabs` + 两组 `.toolbar-actions`（`margin-left: auto`）。
2. 删 L65 `<span class="prev-hint">…</span>`。
3. L74 `.left-col` 内、FileTreePanel 之前插入对象卡片（§3.3 结构）；FileTreePanel + panel-side-bar 包进 `.tree-row`。
4. L150 注释更新（"工具栏 selbtn 触发"→"左列对象卡片触发"）。

### 5.2 edit.vue · script（约 10 行）

1. 新增 computed `objCardSub`（§3.4 三态副行文案）。
2. `selBtnState/selBtnName/selBtnTag` → 改名 `objCardState/objCardName/objCardTag`（纯重命名，逻辑不动）。
3. 其余脚本零改动。

### 5.3 edit.vue · style（约 120 行增删）

1. `.toolbar` 重写为单行 flex；删 `.toolbar-row` / `.tb-col-template` / `.tb-col-actions`。
2. `.selbtn` 改 `.obj-card`：`height: 56px` + 两行布局（主行 flex + 副行 `font-size:12px; opacity:.8`）；三态配色规则原样保留。
3. 删 `.prev-hint` 规则。
4. `.left-col`：`flex: 0 0 280px; flex-direction: column; gap: 10px`；新增 `.tree-row`（flex:1, min-height:0, display:flex）；`.panel-side-bar` 收起态规则适配"列宽不变、仅树区隐藏"（§4.3）。
5. `.view-tabs` 的 `margin-bottom: 14px` → 并入单行后调整为行内间距。
6. 窄屏 `@media` 验证：`.left-col { width: 100% }` 现有规则覆盖新结构。

### 5.4 aiWorkbench.vue（约 3 行删除）

1. `defineExpose` 删 `prevHint` 及其 computed（确认无其他消费方：`grep prevHint ui/src` 仅 edit.vue L65）。

### 5.5 不改的文件（明确排除）

`WorkObjectDialog.vue` / `FileTreePanel.vue` / `TemplatePreviewPanel.vue` / `aiChat.vue` / `ImageWorkbench.vue` / 4 个 composable / 全部后端。

---

## 6. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 对象卡片不参与收起导致窄屏左列占位过大 | 低 | 窄屏下左列 `width:100%` 堆叠，卡片横向铺满仅占 ~56px 高；可接受 |
| 工具栏单行后按钮多（手动视图 4 按钮 + 未保存提示）在小屏换行 | 低 | 现状已有 `flex-wrap: wrap`，保持；<1100px 时未保存提示可 `display:none`（可选） |
| `selbtn` 改名牵连遗漏 | 低 | 改名范围仅 edit.vue 三 computed + 模板 3 处绑定；改完 `grep selBtn ui/src` 验证归零 |
| 高度实测边界（工具栏变矮后高度变大 34px） | 无 | 数学上严格：top 减小 ⇒ height 增大，下限 400px 不受影响 |
| 对位 hack 删除后工具栏 tab 与左列视觉关系 | 无 | 单行工具栏横跨全宽，左列在其正下方，天然对齐，无需 hack |

**回滚方案**：改动集中两个文件、无接口/数据结构变化，`git revert` 单个 commit 即回滚。

---

## 7. 验收清单

### 布局
- [ ] 工具栏单行：左侧视图 tab，右侧按钮组靠右；sticky 吸顶正常。
- [ ] 左列固定 280px：顶部对象卡片（两行）+ 文件树卡片；列宽不随窗口变化。
- [ ] 对象卡片三态配色正确（正式=蓝 / 草稿=紫 / 已应用=绿），副行文案随对象类型显示。
- [ ] 文件树卡片头部"模板文件树/会话工作目录 + 刷新 + 过滤框"不变。
- [ ] AI 工作台工具条：新建模板 / 回滚最近(条件) / 应用模板(条件) / 编辑此模板(条件)，**无**"预览指向"。
- [ ] 手动编辑工具条：上传模板文件 | 保存 删除 ●未保存提示。
- [ ] 左列无"新建模板"按钮。

### 行为
- [ ] 点对象卡片弹出三页签选择器；选对象后左树 + 两 tab 同步跟随（未保存确认链不变）。
- [ ] 切视图 tab：AI 会话/编辑器内容/树高亮/对话输入框全部保留（保活）。
- [ ] 收起文件树：仅树区收起，对象卡片仍可见可点；展开复原。
- [ ] 对象切换时旧文件未保存 → 保存/放弃/取消三态确认正常。
- [ ] Ctrl+S（手动视图）、Esc（编辑器全屏）、路由离开拦截、beforeunload 正常。
- [ ] 窗口 resize / F5 冷启动 / 其他菜单切回（keep-alive）后高度正常，无整页滚动。
- [ ] 窄屏（<768px）：左列 100% 宽堆叠，预览占主屏。

### 回归
- [ ] AI 生成/调整流式输出、文件进度卡、预览自动刷新不变。
- [ ] 应用模板 / 回滚 / 编辑此模板 全链路不变。
- [ ] 图片工作台（点选图片文件覆盖编辑器）不变。

---

## 8. 实施拆分

| 步骤 | 内容 | 预估 |
|---|---|---|
| 1 | edit.vue template：工具栏单行化 + selbtn 上提为对象卡片 + 删 prev-hint | 0.5h |
| 2 | edit.vue style：左列 280px 列布局 / .obj-card 三态 / 收起语义 / 窄屏 | 1h |
| 3 | edit.vue script：objCardSub computed + 改名 | 0.5h |
| 4 | aiWorkbench.vue 删 prevHint expose | 0.1h |
| 5 | 浏览器走查 §7 验收清单（含窄屏/keep-alive/未保存链路） | 1h |

合计 ≈ 3 小时。单一 commit：`refactor(template-edit): 工作对象选择上提左列，左列固定 280px 统一操作对象区，工具栏单行化`。

---

## 9. 附：原型对照

原型 `prototype-template-edit-v3.html` 与本方案一一对应：

| 原型元素 | 本方案落点 |
|---|---|
| 左列 obj-card（两行 + 三态） | §3.3 / §3.4 |
| 左列 tree-card（标题+刷新+过滤+树） | 现状 FileTreePanel 零改动 |
| 单行工具栏：tab + 两组视图按钮 | §3.2 |
| 预览列头（页面临时下拉+视口+刷新+新窗口） | 现状 TemplatePreviewPanel 零改动 |
| 三页签选择弹窗 | 现状 WorkObjectDialog 零改动 |
| 双 tab 切换按钮组互换 | 现状 v-if/v-else 保留 |
