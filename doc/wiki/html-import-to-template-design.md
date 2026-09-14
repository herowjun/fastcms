# 外部 HTML 转化为 fastcms 模板（Import-to-Template）设计

| 项 | 内容 |
|---|---|
| 文档版本 | v1.1（吸收评审：P0 二维判定+页型闭环 / P1 skill降级+pageKey+token共存 / P2 资产接线+触发拓扑） |
| 日期 | 2026-09-14 |
| 状态 | 设计评审 |
| 关联文档 | `ai-template-two-mode-design.md`（转化段确定性原则）、`ai-design-reasoning-runaway-optimization.md` |
| 定位 | 第三种模板来源：管线生成（模式1）/ AI 设计（模式2）之外，**导入既有 HTML**（模式3，`createMode=import`） |

---

## 1. 结论

**可行，且架构挂点比预期充分。** 本需求要复用的"转化引擎"——`MockupConverter` 五步转化——其输入不是"AI 设计产物"，而是 `design/` 目录里符合契约的 HTML 文件（`design/<name>.html`，body 顶层 `<section data-block>` 切块为纯代码、零 AI）。因此：

> **外部 HTML → fastcms 模板 = ingest 归一化（新增，确定性 Java，零 AI）+ 转化段复用（现有 1497 行，原样不动）**

**关键设计决策（方向性）**：skill **不是**让模型读 HTML 后手写模板文件——那是管线直写 HTML 的老路，已被证明结构性不稳定，且推理模型在开放式 HTML 生成上有失控风险（见 runaway 优化文档）。分工：

| 层 | 承担者 | 职责 |
|---|---|---|
| 入口/语义 | **skill**（SKILL.md）+ UI 入口 | 识别"用户给了 HTML/zip 要转模板"，引导走 import 流程，播报结果 |
| 引擎 | **Java**（ImportService + 现有 MockupConverter） | 解压、归一化、切块、映射、装配、注册——全确定性 |

该分工与 v2.0 文档"转化必须确定性"原则、以及"skill 是提示词、Java 是确定性能力"的既有体系（article-skills-plugin 同款）完全一致。

---

## 2. 现有架构盘点（已验证坐标）

| # | 挂点 | 状态 | 坐标 |
|---|---|---|---|
| 1 | **转化引擎** `MockupConverter.convert(ConvertContext, sse)` | ✅ 可复用。五步：区块切分（纯代码）→ 区块映射（AI 置信度，低置信→custom 降级）→ 装配 → token 提取（`:root --c-*`）→ 样式升级。AI 仅参与映射一步 | `MockupConverter.java` L68/L266/L383/L422/L1269 |
| 2 | **上传端点** `POST /sessions/{id}/files/upload`（`MultipartFile[]` + `dirName`） | ✅ 已有，写会话工作目录 | `AiTemplateController.java` L316 |
| 3 | **zip 解压 + zip slip 防御** | ✅ 先例可抄 | `PluginController.install` L109+ |
| 4 | **旧模板样式升级机制**：`_style_upgrade.json` 断点状态 + `GET /sessions/{id}/legacy-status` + "样式组件化升级"横幅 | ✅ 已有。**"有 html 页、无 `_pagespec.json`（组件化标志物）的目录走升级"——本质就是本需求的雏形**，c 形态兜底直接复用 | `AiTemplateController` legacy-status 段 |
| 5 | **技能体系**：`SkillProvider`（file 源 `~/fastcms/skills` / 插件 classpath / 内置），frontmatter + 正文，模型 `load_skill` 按需拉取 | ✅ 成熟，4 个真实实例 | `SkillProvider.java` L26-50、`article-skills-plugin/skills/*` |
| 6 | 设计稿路径契约 | ✅ 明确：`design/<name>.html`，PagePlan = (name, title, description, fastcmsPageKey) | `MockupOrchestrator` L119/L476、`DesignPagePlanner` L54 |

**缺口（本方案要补的）**：
1. **ingest 归一化层**——外部 HTML 不天然符合 `data-block`/tokens 契约（§4）
2. **zip → 转化触发**的编排——现有上传端点只存文件，不触发转化
3. 会话类型——import 会话跳过 DESIGNING 段，需编排器放行

---

## 3. 总体架构（三层）

```
用户侧：模板新建对话框 / aiChat
   │  ① 选择「导入 HTML」（单文件或 zip 站包）
   ▼
L1 入口层  ─ skill: html-to-template（语义 + 播报）
   │  ② 上传 zip/html → ③ POST /sessions/{id}/import（SSE 播报）
   ▼
L2 ingest 层 ─ ImportService（新增，确定性，零 AI）
   │  解压(防 slip) → 文件分类 → 逐页归一化(§4 三形态)
   │  → 写 design/*.html + plan.json(pages=PagePlan, mappingCache=∅)
   ▼
L3 转化层 ─ MockupConverter（现有，原样复用）
   │  切块(纯代码) → 映射(AI 置信度→custom 降级) → 装配 → tokens → 样式升级
   ▼
产物：_pagespec.json + 组件化模板目录 → 注册 + 预览（与设计模式完成态同路径）
```

**数据流要点**：
- import 会话的 `plan.json` 由 ingest 直接生成（页面清单来自 zip 内 HTML 文件名，`fastcmsPageKey` 从文件名推导，首页 `index.html`→`index`），**不经 `DesignPagePlanner`**（那是需求→规划的 AI 环节，导入场景不需要）
- 转化触发复用 orchestrator 的 CONVERTING 段，跳过 DESIGNING（编排器小改：`createMode=import` 的会话允许从 CONVERTING 起步）
- 断点续传天然继承：`mappingCache` 机制对导入页同样生效（映射失败重发消息可续）

---

## 4. ingest 归一化：二维判定 + 页型闭环（全程零 AI）

> v1.0 教训：判定只看"结构语义"，`fastcms-landing.html` 因此被误判 b（body 顶层有 nav/section/footer），
> 实为深度自定义 CSS（20+ 自管变量、无 5 个必备 `--c-*`）——AI 映射会把它判成 `tw:hero` 等组件，
> 渲染出组件库样式、原稿视觉全丢。**"结构相似但视觉不同"的错配，置信度判断不了视觉。**
> 修正：判定 = **结构语义 × 样式体系** 二维，非 Tailwind 自定义 CSS 一律走 c（保真优先，与 legacy 升级哲学同构：先保真跑通，再逐步换组件）。

### 4.1 二维判定矩阵

| 样式体系 \ 结构语义 | 有契约（`section[data-block]` + `:root --c-*` 5 个齐） | 有语义（`<nav>/<footer>` 或顶层 `<section>`） | 无（顶层自定义 div） |
|---|---|---|---|
| **Tailwind 系**（tailwind class/CDN，CSS 变量少且以 `--c-*` 为主） | **a** 零转换直通 | **b** AI 映射（漂移小、收益大） | **c** 切块 + custom |
| **自定义 CSS 系**（自有变量体系 / 外部 css 依赖 / 非 `--c-*` 为主） | **a**（契约齐即可） | **c** 全 custom 保真 | **c** 全 custom 保真 |

判定规则（纯代码）：
1. **样式体系**：存在 `tailwind` class 属性 / Tailwind CDN 引用 → Tailwind 系；否则（存在自有 `:root` 变量群、外部 `.css` 引用、或 `--c-*` 缺失）→ 自定义 CSS 系
2. **契约齐**：body 顶层 `section[data-block]` 存在 **且** `:root` 含全部 5 个必备变量（`--c-primary/--c-accent/--c-bg/--c-text/--c-muted`，`DesignHtmlValidator` V3 同口径）
3. **有语义**：body 顶层有 `<nav>`/`<footer>`，或顶层 `<section>` ≥1
4. `fastcms-landing.html`（c 形态验收基准，实测坐标见附录 A）：6 个顶层语义元素 + 20+ 自管变量 + 0 个 `--c-*` → **c**

### 4.2 c 形态页型闭环（CMS 价值底线，M1 必做）

c 形态"全 custom"对单页 landing 成立，但**多页 zip 站的内容页若全 custom，导入的"新闻页"只是死的静态 HTML——CMS 文章流不进去。导入的终极价值是 CMS 管得住，不是保真搬迁。**
故 c 形态豁免"页型必备区块"，确定性映射（按 pageKey 页型 + 位置判定，**无需 AI 置信度**）：

| 区块 | 页型条件 | 确定性映射 | 理由 |
|---|---|---|---|
| 列表/正文主体 | `article_list` / `article` / `page` | `content-body`（复用 converter L542 既有约束：仅内容页主体区块可用） | CMS 文章数据流的唯一入口 |
| nav | 全部页型 | `navbar` 组件 | 布局区，与 custom 正文共存无样式冲突 |
| footer | 全部页型 | `footer` 组件 | 同上 |
| 其余区块 | — | `custom`（保真原样） | 视觉保真 |

主体区块识别（纯代码）：页内面积最大/唯一含文章卡片结构（`<li>` 列表或 `h1/h2`+段落组）的顶层 section；识别失败 → 该页整体 custom + 报告标注"未识别内容主体，CMS 文章未接入"（降级不失败，但**必须显式报告**，不静默）。

### 4.3 设计原则

1. **不猜语义**：c 形态不强行拆"一块大 div"（拆错比不拆伤害大）；块过大（> 页面 30%）只在报告标注"建议拆分"
2. **降级不失败**：归一化任何一步失败 → 该页整体单 custom 块 + note，整站不中断（与转化段降级哲学一致）
3. **多页站公共块**：nav/footer 取首页口径（与 converter L-1/L-2 约定一致），各页差异进报告 note，不自动合并
4. **zip 嗅探**：magic number 判断，非 zip 的"伪装 zip"按单 HTML 处理

---

## 5. 代码清单（新增/修改）

| 对象 | 类型 | 说明 |
|---|---|---|
| `.../ai/template/import/ImportService` | 新增（~350 行） | ingest 主流程：解压→分类→**pageKey 推导（§5.1）**→归一化→**资产接线（§5.2）**→写 design/ + plan.json（`state=CONVERTING`，供断点续传） |
| `.../ai/template/import/HtmlNormalizer` | 新增（~250 行） | 二维判定（§4.1）+ data-block 注入 + `:root` CSS 变量**别名共存**整理（§5.3，不重命名不删原变量） |
| `.../ai/template/import/PageKeyResolver` | 新增（~60 行） | 文件名→pageKey 关键词表推导（§5.1），决定 §4.2 页型策略 |
| `AiTemplateController: POST /sessions/{id}/import` | 新增 | 收 zip/单文件，**同步仅做 ingest 并落 plan.json（CONVERTING），随后复用 `chatStream` 驱动转化**（触发拓扑 §5.4，SSE 断开靠既有续传） |
| `AiTemplateGenServiceImpl: importAndConvert` | 新增（~100 行） | 编排：ingest 产物 → `converter.convert`（跳过 DESIGNING） |
| `MockupOrchestrator` | 小改 | `createMode=import` 允许从 CONVERTING 起步；状态机新增 `IMPORTING`（ingest 阶段状态，供前端展示） |
| `FastcmsAiProperties.Import` | 新增配置 | `max-zip-size`(20MB) / `max-files`(200) / `allow-exts`(html,htm,css,js,png,svg,jpg,webp,ico) |
| `skills/html-to-template/SKILL.md` | **M3 新增**（v1.1 降级，见 §6） | 入口语义；M1/M2 不提供 |
| `ui` 模板新建对话框 | 小改 | 「导入 HTML」选项（createMode 第三项）+ zip 上传控件（M1 唯一入口） |
| `MockupConverter` | **零改动** | — |
| 管线模式（模式1） | **零影响** | createMode 新增枚举值，既有分支不受影响 |

### 5.1 pageKey 推导规则（`PageKeyResolver`，纯确定性）

文件名（去扩展名、小写）→ pageKey，关键词表按序匹配，未命中 → `page_<slug>`：

| 文件名模式 | pageKey | 页型 |
|---|---|---|
| `index` | `index` | 首页 |
| 含 `news`/`blog`/`article`/`post`/`list` | `article_list` | 文章列表（§4.2 content-body） |
| 含 `about`/`contact`/`team`/`price`/`faq` 等单页词 | `page_<slug>` | 单页（page 页型，主体可映射 content-body） |
| 其余 | `page_<slug>` | 单页 |

**冲突规则**：同 pageKey 多个文件 → 后者改名为 `page_<slug>-2` 并报告；首页缺失（zip 无 index）→ 取字典序第一页为 index 并**报告标注**（不静默）。
页型决定 §4.2 的确定性映射策略，故本规则是 M1 必做（非 M3）。

### 5.2 外部 CSS/JS 资产接线

zip 站一般含 `css/`、`js/` 目录，custom 块的 class 依赖外部样式——**不接线即裸奔**。规则：
1. 非 HTML 静态资源按原相对路径归位到模板 assets 目录（`assets/css/...`、`assets/js/...`、`assets/images/...`）
2. 归一化时**改写引用**：design HTML 中 `href/src` 的 css/js/img 相对路径重写为 assets 相对路径（纯字符串替换，Jsoup 属性遍历）
3. layout 装配时 head 区注入被引用的 `<link rel=stylesheet>`（converter 既有 head 装配点；custom 块引用的样式必须随 layout 生效，不随块走）
4. inline `<style>` 保留原样（与 custom 块同生命周期）；外部 js 仅归位 + 报告列出，**不自动注入**（交互脚本是否进 layout 由用户确认，避免行为漂移）

### 5.3 b 形态 token 整理：别名共存（不重命名）

把外部站 CSS 变量"整理为 `--c-*`"若做**重命名**，区块内 `var(--brand)` 引用全部断裂。规则：
1. `:root` **追加** 5 个必备 `--c-*`（`--c-primary/--c-accent/--c-bg/--c-text/--c-muted`），值映射自原变量（按语义启发：brand/primary→primary、bg/background→bg、text/ink/fg→text 等；映射不到 → 取原 `:root` 首个近似值并报告）
2. **原变量全部保留**（`--brand` 与 `--c-primary` 指向同值，别名共存），区块内 `var(--brand)` 引用不受影响
3. 5 个 `--c-*` 在场是组件库组件样式生效的前提（V3 校验同口径）——b 形态 AI 映射出的组件靠它渲染

### 5.4 触发拓扑（与既有会话同构）

| 阶段 | 载体 | 断线后 |
|---|---|---|
| ingest（解压/归一化/接线，~秒级，零 AI） | `POST /sessions/{id}/import` 同步执行，产物落 workDir + `plan.json`（`state=CONVERTING`、pages 就绪、mappingCache=∅） | 端点幂等：重调直接进转化 |
| 转化（AI 映射，分钟级） | 复用 `chatStream` SSE 驱动 `converter.convert` | **既有断点续传**（mappingCache 机制）原样生效，不新增恢复路径 |
| IMPORTING 状态 | 仅服务于 ingest 失败时的定位（前端展示"导入解析失败+原因"），不承载长任务 | — |

---

## 6. SKILL.md 设计（v1.1：M3 再上）

> v1.0 评审指正：模型调用端点需要**工具定义**，而内置 agent 的 tools 均为空
> （`BuiltinAgents` L97/L111/L128 `tools(Collections.emptyList())` 实测确认）——
> SKILL.md 写"调用 import 端点"无工具可挂，消费路径不闭环；且 UI 对话框入口根本不经过 skill。
> **决定：M1/M2 砍 skill，纯 UI 对话框入口（诚实简单，无隐性工作量）；skill 降级 M3**，
> 前置补：`import_template` 工具定义 + 注册到模板 agent + aiChat 附件上传能力。

M3 的 SKILL.md 内容（届时工具已就位）：

```markdown
---
name: html-to-template
description: 把用户提供的 HTML 页面或 zip 站包转化为 fastcms 组件化模板
version: 1.0.0
---

当用户提供 .html 文件或包含网页的 .zip 包，并要求"转成 fastcms 模板/站点"时：
1. 确认站点名（模板目录名），未提供则从文件名推导并请用户确认
2. 调用 import_template 工具（上传 → 触发导入转化），不要读取文件内容后自行改写
3. 播报转化结果：页面数、区块数、custom 区块清单、"CMS 文章已接入的页"清单
4. 完成后给出预览入口，并说明：对 custom 区块可在会话中继续对话要求"重新映射/调整"
约束：模板文件一律由 import 工具生成，禁止手写或改写模板产物；zip 解压与大小校验由端点负责。
```

---

## 7. 安全

| 风险 | 对策 |
|---|---|
| zip slip（`../` 逃逸） | 解压路径 normalize 后校验在 workDir 内（`PluginController` 现成先例） |
| 恶意/超大包 | 扩展名白名单 + zip ≤20MB + 文件数 ≤200（可配置） |
| zip 内嵌恶意 js | 外部 js 存 assets 目录（随模板静态资源，与现有模板同等信任级）；inline script 走转化段既有 A6 白名单提取，不原样放行 |
| 上传端点越权 | 复用 `requireOwnedSession` + `@Secured`（ai:template:files/upload 同权限组） |
| 内容安全（XSS 等） | 模板产物经现有预览沙箱，与 AI 生成模板同等处理，本期不额外设防 |

---

## 8. 里程碑与工作量

| 里程碑 | 内容 | 工作量 | 验证门槛 |
|---|---|---|---|
| **M1** | 二维判定（§4.1）+ a/b/c 归一化 + **页型闭环确定性映射（§4.2）** + **pageKey 推导（§5.1）** + 资产接线（§5.2）+ token 别名共存（§5.3）+ import 端点 + 会话类型 + **触发拓扑（§5.4）** + UI 对话框入口（单文件 + 多页 zip） | ~3 天 | ① b 形态 zip（造 Tailwind 3 页站）转化出组件化模板、`_pagespec.json` 完整；② a 形态零转换直通；③ **多页 zip 含 news.html → article_list 页且 content-body 接入（CMS 文章可流入，P0-2 验收）**；④ 单测：zip slip / 白名单 / pageKey 推导 / 二维判定矩阵 |
| **M2** | c 形态打磨（切块阈值 + 主体识别 + 样式升级兜底）+ SSE 播报完善 | ~1 天 | ① `fastcms-landing.html` 实转成功（c 形态验收基准，产物可预览、视觉与原稿一致）；② 样式升级 `_style_upgrade.json` 与 legacy 机制无冲突（**重点回归**，两机制共用状态文件）；③ c 形态页型闭环生效 |
| **M3** | skill（`import_template` 工具定义 + aiChat 附件上传）+ 报告增强（区块统计/过大块/CMS 接入清单）+ 参数调优 + 文档 | ~1.5 天 | ① skill 在 chat 会话中正确引导流程（工具可调用、播报完整）；② 导入报告含页面/区块/custom/CMS 接入明细 |

**总 ~5.5 人天**（v1.0 估 4 天，P0 两页型闭环与二维判定补入 +1 天、skill 工具链 +0.5 天），M1 可独立上线（a/b/c 三形态全覆盖，c 靠降级兜底）。

---

## 9. 风险与未决

| # | 风险 | 缓解 |
|---|---|---|
| 1 | b 形态 CSS 变量提取质量（命名千差万别） | 提取失败自动降 c 形态（整页 custom + 样式升级），**降级不失败** |
| 2 | c 形态切块出"一块大 div" | 阈值标注 + 报告提示，不强拆（不猜语义原则） |
| 3 | `_style_upgrade.json` 与 legacy 旧模板升级机制的状态文件冲突 | M2 前置任务：核对两者写入/读取时序，必要时加 `upgradeSource=legacy|import` 字段区分 |
| 4 | import 会话的"调整"交互（转完想改某块） | 继承既有调整型会话能力（改文件/回滚），本期不新增机制 |
| 5 | 多页站 nav/footer 不一致（各页导航不同） | 取首页口径（与 converter 一致）；差异进报告 note，不做自动合并 |

**未决（不阻塞 M1）**：
- 是否支持"只导入部分页面"（zip 全选 vs 勾选）——M3 后视反馈
- 导入模板的版本管理（重导同一站名是覆盖还是新建会话）——默认新建会话，与既有会话模型一致

---

## 附录 A：验证过的现状事实（实施对照）

| 事实 | 坐标 |
|---|---|
| 区块切分纯代码：body 顶层 `<section data-block>` + nav(#-1)/footer(#-2) 公共块 | `MockupConverter` L68/L237-238/L383/L422 |
| 转化入口 `convert(ConvertContext, DesignSseSink)`，ConvertContext = (session, workDir, requirement, direction, mobileAdaptive, pages, mappingCache) | `MockupConverter` L174-181/L266 |
| content-body 约束：仅 article_list/article/page 类内容页的"列表/正文主体"区块可用（§4.2 页型闭环依据） | `MockupConverter` L542 |
| 产物：`_pagespec.json`（Tailwind v4 foundation）+ `tokens.css` + `upgrade.css` + layout 文件 | `MockupConverter` L900/L1269-1365 |
| 上传端点 `POST /sessions/{id}/files/upload` | `AiTemplateController` L316-329 |
| legacy 升级：`GET /sessions/{id}/legacy-status`（有 html、无 `_pagespec.json`、样式升级未完成 → 横幅） | `AiTemplateController` legacy-status 段 |
| 设计稿路径契约 `design/<name>.html`；PagePlan = (name, title, description, fastcmsPageKey)；pageKey 口径 `index / page_about / article_list` | `MockupOrchestrator` L119/L476-498、`DesignPagePlanner` L54、`MockupConverter` L205 |
| 技能体系：SkillProvider 三来源（file `~/fastcms/skills` / 插件 classpath / 内置）+ `load_skill` | `SkillProvider` L26-50 |
| **内置 agent 工具全空**（TEMPLATE_GENERATOR/DESIGNER/其他均 `tools(Collections.emptyList())`）——skill 需补工具定义（§6 降级 M3 的依据） | `BuiltinAgents` L97/L111/L128 |
| V3 校验：5 个必备 token `--c-primary/--c-accent/--c-bg/--c-text/--c-muted` 必须在 `:root`（§5.3 别名共存依据） | `DesignHtmlValidator` L59 |
| zip 安装先例（zip slip 防御、jar/zip 判断） | `PluginController.install` L109+ |
| `fastcms-landing.html` 实测（v1.1 修订）：467 行 / **body 顶层 6 个 nav/section/footer** / **20+ 自管 CSS 变量（`--bg/--brand/--card/...`）** / **0 个 `--c-*` 必备 token** / 非 tailwind / 内联 style → **c 形态**（v1.0 误标 b 的教训样本，§4 判定矩阵依据） | `doc/wiki/fastcms-landing.html`（c 形态验收基准） |
