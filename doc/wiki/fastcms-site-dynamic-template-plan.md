# FastCMS 官网静态原型 → 动态模板 · 完整转化方案

> 2026-10-16 · 基于当天代码逐行核实（ai-starter htmlimport/design/component 包 + 前端 CreateTemplateDialog + 运行时主题结构）
> 输入资产：`doc/wiki/fastcms-site/`（3 页 + css/ + js/，纯前端零依赖）
> 红线遵守：本方案**零源码改动**，全部利用既有管线；产物落 `doc/wiki/`（豁免区）与运行时数据目录

---

## 0. 结论（TL;DR）

FastCMS 已内置「**设计稿先行 · zip 整站导入**」管线（design-first import mode），专为"上传一个静态 HTML 站包 → 产出 1:1 保真的 FreeMarker 动态模板"设计。**不需要写一行后端代码**，操作步骤：

```
① 打包转换（脚本已写好：doc/wiki/build-import-package.py，1 条命令）
② 后台「模板管理 → AI 新建模板」→ 模式选「AI 自主设计」→ 上传 fastcms-site-import.zip → 开始生成
③ 导入全自动跑完（解析→映射→渲染→合规检查→DONE）→ 预览验证
④ 一轮 AI 调整（恢复页内锚点 + 市场/详情区取舍，话术见 §5.4）
⑤ 应用（applyTemplate）→ 正式模板目录 + 菜单/单页落库
⑥ 后台微调（删 1 个菜单项、配单页内容）→ 验收
```

全程约 10~20 分钟（其中 AI 映射 3~5 分钟）。不满意可丢弃会话重来，或走 §7 手动兜底。

**关键前提**：`fastcms.ai.template.design.enabled=true`（本项目 dev application.yml 已开启；生产环境默认关闭，需先开启，否则后端拒绝 design 会话）。

---

## 1. 代码机制依据（2026-10-16 已核实）

### 1.1 入口链路（前端 → 后端）

| 步骤 | 代码位置 | 行为 |
|---|---|---|
| 弹窗 | `ui/src/views/template/CreateTemplateDialog.vue` | 生成模式两项：`pipeline` 组件编排 / `design` AI 自主设计；design 模式下可上传 `.html`（单文件）或 `.zip`（整站包） |
| 提交 | `onCreateConfirm` | 带参考文件时：`createSession(createMode=design)` → `uploadReference`（**同步** ingest，秒级）→ 自动发首条消息「开始转化导入的模板页面」 |
| 上传 | `AiTemplateController.uploadReference` → `ImportService.doIngest` | 解压 + 归一化 + plan.json 落盘，会话血统归一为 **import** |
| 编排 | `MockupOrchestrator.run` | 导入会话状态机**直接从 CONVERTING 起步**（跳过 AI 设计/审计），**全自动、无人工确认** |

### 1.2 导入转换要点（`ImportService` / `HtmlNormalizer` / `MockupConverter`）

1. **包校验**：≤20MB、≤200 文件、扩展名白名单
   `html,htm,css,js,png,svg,jpg,jpeg,webp,gif,ico,woff,woff2,ttf,eot,map,txt` —— **没有 `md`**（含 README.md 会直接失败）。
2. **页面 key 推导**（`PageKeyResolver`，确定性、零 AI）：
   `index.html→index`；文件名含 `news/blog/article/post/list`→`article_list[+后缀]`；其余→`page_<slug>`。
3. **形态判定**（`HtmlNormalizer.detectForm`）：本原型无 Tailwind/Bootstrap → **形态 C（自定义 CSS 体系）→ 确定性保真映射**，不套组件骨架。
4. **资产归位**：非 HTML 白名单文件 → `static/import/<相对路径>`；HTML 内引用改写成 `${ctx()}/import/<相对路径>`（如 `href="css/site.css"` → `href="${ctx()}/import/css/site.css"`）。
5. **导航/页脚**：导入模式**强制 MAP_CUSTOM 保真**（原结构 + 原 CSS 类，不套 tw:navbar）；但 2026-09-25 起 `menuifyNav` 会尝试菜单化——`div.nav-links > a` 这类链接组，锚文本命中站点信息架构的项替换为 `<@menuTag>`（CMS 动态菜单），未命中项保留静态（`MockupConverter` L420-440、L1544-1671）。
6. **内容区映射**：页面内容 section 走语义标记确定性映射 + AI 区块映射（每批 ≤5 区块，最多 2 轮）；AI 可将某区块映射为 `content-body`（替换为该页 CMS 正文骨架）或 `custom`（原样保留）。
7. **CSS 接线**（`postConvertWiring`）：所有外部 css 的 `<link>` 注入 `_layout.html` head（全站生效）；首页 inline `<style>` 若存在同样注入且优先级最高。
8. **JS 迁移**（`migrateScripts`）：**只提取首页的 inline script**（`extractInlineScripts` 取首个非空页）；总量 >2048 字节 → 外置 `static/js/imported.js` 并由 layout 在 `</body>` 前引用（全站生效）；脚本引用的 id 锚点在产物中缺失时自动注入防御垫片（detached getElementById，防 TypeError）。**body 尾部的外部 `<script src>` 不在迁移范围——转化中直接丢弃。**
9. **合规自检**（R4 `TemplateComplianceChecker`）：layout/include/菜单/正文宏等结构性合规自动修复。
10. **站点信息架构**（`buildSiteContent`）：导入页各自生成菜单项（`page_plugins`→菜单名取页面 `<title>`）；zip 导入无 site-ia.json 时，`deriveNavMenus` 再把 nav 中**每个非首页、非外链、非 CTA 的链接**派生 `page_<slug>` 单页并入 IA（菜单/栏目页/预览数据三者同源，apply 时一起落库）。

### 1.3 渲染与落盘

- `PageSpecRenderer.render`：产物 = **4 基础页（index/article/article_list/page，无条件）+ 导入页 + IA 派生 suffix 页**，每页 `<type>[_<suffix>].html`；公共 nav/footer 抽入 `_layout.html`；组件源码落 `_components/`；另有 `_template.properties`（主题元数据）、`_pagespec.json`、`_preview_data.json`（预览数据）。
- `applyTemplate`（`AiTemplateGenServiceImpl` L3923）：workdir → **`$FASTCMS_HOME/templates/<模板名>/`**（本机即 `C:\Users\PC1\.fastcms\templates\fastcms-site\`），刷新模板注册 + 静态资源映射（`/<模板名>/static/**` 立即可访问，无需重启）。
- 运行时 FreeMarker 约定：`${ctx()}` 静态前缀、`<@menuTag>` 后台菜单驱动、单页路由 `page_<suffix>.html`。

---

## 2. 本原型的页面映射（导入结果预测）

| 原型文件 | 模板文件 | 保真/动态行为 |
|---|---|---|
| `index.html` | `index.html` | 形态 C 保真：nav/footer 原样 + 7 个内容区（hero/features/AI 演示/market 预览/pricing/stack/cta）AI 映射（营销区预期多落 custom，1:1） |
| `plugins.html` | `page_plugins.html` | 单页。`page-head` 标题区 + `mkt-page`（页签+搜索+21 卡网格）由 AI 映射：**网格区大概率落 content-body**（CMS 正文流，后台可编辑）或 custom（保留示例视觉）——二选一，见 §5.4 |
| `plugin-detail.html` | `page_plugin-detail.html` | 单页。详情内容区同理（content-body / custom）；`#mask`+`#drawer` 购买抽屉预期 custom 保留 |
| —（自动生成） | `article.html` / `article_list.html` / `page.html` | 4 基础页骨架，接文章体系；不配菜单则前台不可达（无害） |
| —（IA 派生，T2 抑制后**不产生**） | `page_features` 等 4 页 | 见 §3-G3 |
| `css/site.css` `css/pages.css` | `static/import/css/*` | layout head 全局 `<link>` |
| `js/*.js` ×4（11.5KB） | `static/js/imported.js` | T1 内联后迁移，layout `</body>` 前全局引用（移动端菜单/AI 对话动画/市场页签搜索/购买抽屉四状态全部保留） |

**IA/菜单预测（T2 抑制后）**：apply 落库菜单 = `首页` + `插件市场 — FastCMS`（来自 plugins.html `<title>`）+ `微信支付 · 插件详情 — FastCMS 插件市场`（来自 detail 页 `<title>`，建议后台删除）。导航渲染 = logo + 4 个静态锚点（调整轮改回 `/#features` 等）+ `<@menuTag>`（插件市场）+ 2 个 CTA。

---

## 3. 已核实的 4 个缺口与处理

| # | 缺口（代码事实） | 影响 | 处理 |
|---|---|---|---|
| G1 | 白名单无 `md`（`FastcmsAiProperties.Import.allowExts`） | README.md 进包 → 导入直接失败 | 打包剔除（T3） |
| G2 | 外部 `<script src>` 转化中丢弃；inline 只取**首页** | 4 个交互 js 全丢（菜单折叠/AI 动画/页签搜索/购买抽屉） | 打包时 4 个 js 以 IIFE 内联进 index.html（T1）；11.5KB>2KB 阈值 → 自动外置 `imported.js` 全局生效；缺锚点自动垫片 |
| G3 | `deriveNavMenus` 把 nav 页内锚点（`index.html#features` 等）派生 4 个冗余单页 + 菜单变 7 项 | 整站多 4 个空栏目页，菜单与原型不符 | 打包时 4 锚点 href 改 `#`（T2，`#` 在 NAV_HOME_HREFS 白名单被跳过）；最终锚点由 §5.4 调整轮统一恢复为 `/#features` 形式 |
| G4 | 归一化只改写**资产**引用，不处理跨页/锚点链接（footer、CTA 里的 `index.html#xxx` 保持原样） | 运行站点上 `index.html#xxx` 404 | 调整轮统一改为 `/#xxx`（§5.4 一条消息覆盖） |

> 脚本重名风险已排查：home.js 与 detail.js 存在顶层 `s`/`i` 重名 → T1 一律 IIFE 包裹，零泄漏。

---

## 4. 动态化映射（哪些部分真"动态"）

| 部分 | 动态化程度 | 数据源 |
|---|---|---|
| 导航菜单 | **真动态**：`<@menuTag>` 由后台「菜单管理」驱动，增删改即时生效 | CMS 菜单表 |
| 插件市场/插件详情页 | 单页实体（apply 时与菜单一起落库）；若映射为 content-body → 正文后台可编辑 | CMS 单页 |
| 文章列表/详情/标签 | 4 基础页骨架，标准文章流 | 文章体系（本原型未展示，配了才有） |
| 首页营销区（Hero/能力/定价/AI 演示/架构） | 静态保真（custom 1:1）；如想动态化，调整轮可把特定区映射为 `article-list` 等 CMS 组件 | — |
| 插件市场 21 张卡片 | **示例数据**（诚实标注）：2026-09 审计确认插件市场为架构预留——无远程索引、无 DB 表、无 license 链路。短期 = 静态示例/CMS 正文手工维护；长期 = 远程插件索引（独立项目） | 无（预留） |
| 购买抽屉 | 纯前端演示（4 状态机，无后端支付/订单 API）；动态模板中保持演示定位 | 无（演示） |

---

## 5. 实施步骤

### 5.1 打包（1 条命令，幂等）

```bat
cd /f/wangjun/ideaProjects/fastcms
python doc/wiki/build-import-package.py
```

产物 `doc/wiki/fastcms-site-import.zip`（5 文件，index.html 置顶）。脚本内置全部断言（内联 4 块、导航 4 锚点、zip 顺序），失败即中止。

### 5.2 启动服务

本地 dev：后端（JDK21，`-pl web -am` 或 IDEA 直接跑 web）+ 前端 `ui/`（Vite dev）。确认 `fastcms.ai.template.design.enabled=true`（本项目 dev yml 已开）。

### 5.3 导入

后台 → 模板管理 → **AI 新建模板**：

- 模板目录名：`fastcms-site`
- 生成模式：**AI 自主设计**
- 参考 HTML：上传 `fastcms-site-import.zip`
- 需求描述（可选）：`FastCMS 官方营销站：深色科技风落地页 + 插件市场 + 插件详情（含演示购买抽屉）。营销区块保持原样保真。`
- 移动端适配：勾选
- 点「开始生成」→ 自动上传 ingest → 自动发「开始转化导入的模板页面」→ 全自动转化（SSE 实时播报）

### 5.4 AI 调整轮（一轮，话术照抄）

转化 DONE 预览确认后，在会话里发：

> 1. 导航栏 4 个占位链接（当前 href="#"）改为页内锚点：核心能力→`/#features`、AI 建站→`/#ai`、定价→`/#pricing`、技术架构→`/#stack`（保持原 class 与顺序，纯静态链接，不接菜单）。
> 2. 模板内所有 `index.html#xxx` 形式的链接统一改为 `/#xxx`（含页脚、CTA 按钮）。
> 3. 插件市场页（page_plugins）：页签+搜索+插件卡网格区保持 custom 原样保留（21 张卡为示例数据），在网格区**下方**追加一个 content-body 正文区（CMS 单页正文，用于市场公告/说明）。
> 4. 插件详情页（page_plugin-detail）：左侧说明/功能列表/右侧价格卡全部保持 custom 原样（演示内容），购买抽屉（#mask/#drawer）保持 custom；页面底部追加 content-body 正文区。

（若第 3/4 条想走"整区 CMS 正文"而非"保留示例 + 底部正文"，把话术改为对应区块映射 content-body 即可——两种形态都在管线能力内。）

### 5.5 应用

会话内点「应用」→ `applyTemplate`：
- 落盘 `C:\Users\PC1\.fastcms\templates\fastcms-site\`
- 菜单 + 单页实体（插件市场、插件详情）落库
- 模板注册 + 静态映射刷新（前台 `/<模板名>/static/**` 立即可访问）

### 5.6 后台收尾（约 2 分钟）

1. 菜单管理：删除「微信支付 · 插件详情 — FastCMS 插件市场」菜单项（详情页从市场卡进入，不进主导航）；确认「插件市场」链接指向 page_plugins 单页 URL。
2. 单页管理：给 page_plugins / page_plugin-detail 补正文（若 §5.4 选了 content-body 形态）。
3. 站点配置把前台默认模板切到 `fastcms-site`。

### 5.7 验收清单

- [ ] 首页：7 区块视觉与原型一致；AI 对话演示动画可播放；移动端汉堡菜单可用
- [ ] 导航：4 锚点点击 → 回首页并滚动定位；插件市场点击 → page_plugins
- [ ] 市场页：页签切换 / hash 定位 / 搜索过滤可用（imported.js 生效证据）
- [ ] 详情页：购买抽屉 confirm→paying→done/timeout 四状态 + 金额三处同步（¥499/¥1,299/券 -50）
- [ ] 菜单后台增删 → 前台导航即时变化（menuTag 证据）
- [ ] `static/import/css/*` 与 `static/js/imported.js` 200
- [ ] 基础 4 页可渲染（无内容时不 500）

---

## 6. 风险与回退

| 风险 | 等级 | 回退 |
|---|---|---|
| AI 映射把营销区组件化破坏视觉 | 低（形态 C 保真优先，预期多落 custom） | 调整轮点名某区块"改回 custom 原样保留"；或整页降级（管线内置两轮降级：区块级→整页单 custom） |
| imported.js 某页缺锚点报错 | 低（管线自动注入防御垫片 + 各 js 自带 null 守卫） | 垫片兜底；必要时调整轮删对应交互 |
| 模板名占用/误操作 | 低 | apply 前产物只在会话 workdir；丢弃会话即可，正式目录不动；已 apply 可删 `templates/fastcms-site/` 重新 apply |
| 插件市场/购买为示例 | — | 页面标注"示例/即将开放"（原型已含口径）；真实市场 = 后续独立项目 |

---

## 7. 备选路线（对比）

| 路线 | 做法 | 工时 | 评价 |
|---|---|---|---|
| **A（推荐）** zip 导入 + 调整轮 | 本文 §5 | 10~20 分钟 | 零代码、管线原生、保真度高；AI 映射有少量不确定性（§6 回退兜底） |
| B 单文件导入 | 只传 index.html：首页 1:1 + AI **仿写** 3 个基础子页 | 10 分钟 | 得不到市场/详情页（AI 仿写的是通用 article_list/article/page），不符本原型 |
| C 手动模板化 | 复制站点到 `templates/fastcms-site/`，css/js 移入 `static/`，3 个 html 改 ftl（`${ctx()}`/`<@menuTag>`/正文宏），手写 `_layout.html`+`_template.properties` | 0.5~1 天 | 完全可控零 AI 不确定性；但违背"AI 模板生成是产品主线"的自证场景，留作兜底 |
| D 纯静态托管 | nginx 直接伺服 `doc/wiki/fastcms-site/` | 10 分钟 | 零成本但非动态、不接 CMS，适合过渡期对外展示 |

**建议**：A 为主（同时是"AI 生成动态模板"能力的第一次真实端到端验证）；D 可作为官网过渡期并行方案。
