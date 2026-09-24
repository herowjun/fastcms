# AI 生成模板规范合规整改技术方案

> 状态：已评审（决议 D1–D3，见下）
> 日期：2026-09-19
> 读者：fastcms 后端开发（ai-starter 模块）
> 关联文档：`doc/wiki/ai-template-two-mode-design.md`（design/import 双模式）、`doc/wiki/html-import-to-template-design.md`（HTML 导入）
>
> 本文所有行号以 2026-09-19 工作区代码为准；代码漂移后请**按方法名检索**定位，行号仅作初始线索。
> 本文所有结论均经逐文件精读验证，关键论断附 `文件:行号` 证据；未验证的部分明确标注"未验证"。

### 评审决议（2026-09-19，本文据此定稿）

| # | 决议（用户拍板） | 对方案的影响 |
|---|---|---|
| D1 | 文章分页**按规范目录生成 `_articlePage.html` 分页宏文件**（即 §4.4 理解①） | R1 方向锁定，按 §6-R1 实现 |
| D2 | `static/js` **有 js 才按规范放到该目录，没有 js 就不建目录**（不强制生成 `main.js`） | R2 由"强制 main.js"改写为"按需落盘"（§6-R2） |
| D3 | **链路 B（直写 HTML）不下线**，暂时保留，作为扩展实现 | R6 不再评估下线，仅做提示词契约升级 + 接入 R4 合规校验（§6-R6）；风险表 R-6 撤销 |

---

## 1. 结论摘要（TL;DR）

上传 HTML 走"AI 自主生成"时，后端存在 **3 条产物生成链路**，规范符合度差异巨大：

| 链路 | 触发条件 | 产物保证机制 | 现状符合度 |
|---|---|---|---|
| A. 组件化渲染引擎 | `gen-mode=component`（默认）；design/import 转化也最终调它 | **Java 代码强制**（`PageSpecRenderer.render()`） | 高：目录/`_template.properties`/`_preview_data.json`/freemarker 标签/菜单标签均由代码保证 |
| B. 直写 HTML 批次管线 | `gen-mode=html` 配置回退，或旧会话残留 plan 自动沿用 | **仅提示词口头契约**，无代码层校验 | 低：产物完全依赖 AI 自觉，截断/漏文件/纯静态 HTML 均可能发生 |
| C. 单轮调整 | 会话已有 `templateId`（编辑正式模板） | 提示词 + 渲染报错修复循环 | 继承既有模板形态 |

**逐条核对用户报告的 4 个问题的真实结论（详见 §4）：**

1. **目录结构不符** —— 链路 A 的根目录结构实际**符合**规范（4 个基础页 + `_layout.html` 无条件生成）；看到不符，说明产物来自链路 B，或预览的是 design 阶段中间态 `design/*.html`（本就是纯 HTML mockup）。
2. **纯 HTML 无 fastcms 标签 / 菜单标签缺失** —— 链路 A 每页硬包 `<#import "_layout.html">`（代码保证），菜单 `<@menuTag>` 硬编码在 navbar 组件 ftl 中（代码保证）。**唯一真实缺口：design/import 链路中 nav 区块降级 custom 时，物化的是设计稿原样静态 HTML，不含 `<@menuTag>`**（`MockupConverter.java:939-944`）。链路 B 则整体无保证。
3. **缺 `_preview_data.json` / `_template.properties`** —— 链路 A **无条件生成**这两个文件（`PageSpecRenderer.java:1019,1065`），不可能缺；缺了说明走的是链路 B，AI 没生成或被截断。
4. **文字分页文件 `_articlePage.html` 未生成** —— **两条链路都缺**（真实缺口）：规范提示词中它只是"可选"（`TemplateGenPromptBuilder.java:1018`），组件化渲染引擎全文件 grep `_articlePage` 为 0 命中，分页条直接内联在 `article_list.ftl:39-65` 正文骨架里。

**附带发现的两条链路共有缺口：** `static/js/` 目录当前从不生成（渲染引擎 grep 0 命中；design 链路 `migrateScripts` 把 js 内联进 `_layout.html`）——按决议 D2，**有 js 才建该目录，没有就不建**；`static/images/` 在 pipeline 模式下不生成（仅 design 链路 `copyDesignAssets` 写占位 SVG）；design 链路菜单提取只认 4 个固定页面名（`MockupConverter.java:1195-1216`），其他页面进不了菜单（§6-R5）。

整改方案共 6 项（R1~R6，§6，含实现级方法签名 / 伪代码 / 产物模板 / 精确插入点），核心思路：**规范合规从"提示词自觉"升级为"代码层确定性保证"**——新增 `TemplateComplianceChecker` 作为所有生成链路的落盘后校验+兜底补齐点，同时补齐 `_articlePage.html`、custom nav 菜单保护、design 菜单信息架构等具体缺口（`static/js` 按决议 D2 按需生成）。链路 B 按决议 D3 **保留不下线**。预计工作量 **2~3 人日**（§9）。

---

## 2. 规范基线（判断"符不符合"的唯一基准）

fastcms 模板规范定义在 `TemplateGenPromptBuilder.java:1005-1030`（`BASE_SYSTEM_PROMPT` 常量），目录结构如下：

```
${templateName}/
├── _template.properties      # 必备（L1016）
├── _layout.html              # 必备：公共布局宏 header/body/script + 递归菜单宏（L1017）
├── _articlePage.html         # 文章分页宏，可选，被 _layout.html include（L1018）
├── index.html                # 必备（L1019）
├── article.html              # 必备（L1020）
├── article_list.html         # 必备（L1021）
├── page.html                 # 必备（L1022）
├── _preview_data.json        # 预览演示数据，"建议生成"（L1023）
└── static/
    ├── css/base.css          # 基础样式（L1026）
    ├── js/main.js            # 基础脚本，可选（L1028）
    └── images/               # 图片资源（L1029）
```

`_template.properties` 必备 6 字段（L1035-1042）：`template.id / name / path / version / i18n / provider / description`（实际 7 行，id 与 path 有格式约束：path 必须 `/` 开头结尾，L1044）。

**fastcms 标签（FreeMarker 指令）清单**（`TemplateGenPromptBuilder.java:1157` 附近指令表）：
`menuTag`（菜单）、`articleListTag`（文章列表）、`articlePageTag`（分页）、`categoryList`、`tagList`、`singlePageList`、`prevArticleTag`、`nextArticleTag`、`relatedArticleList`、`seoTag`、`formatTime` 等。

**关键指令语义澄清（防误改）：**
- `articlePageTag` 是**文章列表分页**指令：`cms/src/main/java/com/fastcms/cms/directive/ArticlePageDirective.java:56-59`，`@Component("articlePageTag")`，`extends BasePaginationDirective`，`PAGE_ATTR = "articleVoPage"`（数据源是文章列表的 IPage）。`data` 结构：`total / current / list / prev / next / last`（L35-46 示例）。
- 所以"文字分页文件" `_articlePage.html` 的职责 = **封装分页条的宏文件**：定义 `<#macro _articlePage>` 内含 `<@articlePageTag>`，由 `_layout.html` include、页面用 `<@layout._articlePage/>` 调用。规范提示词中有完整示例（`TemplateGenPromptBuilder.java:1213-1227`）。
- `articlePageTag` 的预览 mock 已存在：`AiTemplatePreviewMockSupport.java:437` `vars.put("articlePageTag", dataDirective(p -> pagination(ctx.articleListUrl())))`——补了 `_articlePage.html` 后预览自动可用，无需改 mock。

**组件化渲染引擎产物的规范**（链路 A 的"实际规范"，`PageSpecRenderer.java:53-85` 类注释目录树）：在规范目录基础上多了 `_pagespec.json`（AI 微调事实源）与 `_components/*.ftl`（组件源码，运行期 include），`static/css` 下是 `pack-{packId}.css + tokens.css + site.css`（无 base.css 命名）。**这两组文件是 fastcms 模板的合法扩展**（下划线前缀文件被上传/应用流程识别，`_components` 被 freemarker 运行期 include 解析），不冲突。

---

## 3. 生成链路盘点（3 条，精确分流条件）

### 3.1 分流总入口

`AiTemplateGenServiceImpl.doChatStream()`（`AiTemplateGenServiceImpl.java:874-934`）：

```java
// L881: design / import 会话 → MockupOrchestrator（转化 DONE 后落回组件化微调路径，L913）
if (AiTemplateConstants.isDesignMode(session) || AiTemplateConstants.isImportMode(session)) {
    ...
    mockupOrchestrator.run(session, designWorkDir, userInput, confirmAction, designSink);
}
// L1039-1045: 生成型会话（无 templateId）且 genMode=component 且（首聊或已有 _pagespec.json）
if (!StringUtils.hasText(session.getTemplateId())
        && "component".equalsIgnoreCase(genMode)
        && (isFirstChat || hasComponentSpec(session))) {
    runComponentPipeline(...);   // ← 链路 A
}
// L1052-1056: 生成型会话首聊或 plan 文件缺失 → 分批直写流水线
if (!StringUtils.hasText(session.getTemplateId())
        && (isFirstChat || hasMissingPlanFiles(session))) {
    runBatchPipeline(...);       // ← 链路 B
}
// L1058+: 单轮路径（调整/微调）  ← 链路 C
```

`genMode` 来源：`AiTemplateGenServiceImpl.java:306-307` `@Value("${fastcms.ai.template.gen-mode:component}")`。**实际配置核查：`application.yml:53-62` 与 `application-prod.yml` 均未显式配置 `gen-mode`，即当前默认走 `component`（链路 A）。** 但注意 L1052 的条件：只要会话目录存在旧版 plan 文件且文件不全（`hasMissingPlanFiles`），即使 genMode=component 也会落进链路 B（避免中途换管线踩坏目录，L1038 注释）。

### 3.2 前端上传 HTML 如何落到链路

`CreateTemplateDialog.vue:99-100`：UI 上 `createMode` 只有 `'pipeline' | 'design'` 两个选项；注释明确 "import 不再是 UI 选项——自主设计 + 参考文件时提交路由改走 import 口径"。后端 `AiTemplateGenServiceImpl.java:518-519`：design 会话上传参考文件时 `session.setCreateMode(CREATE_MODE_IMPORT)` 归一血统。

所以"上传 HTML"的实际路径：**ingest（`ImportService`，确定性零 AI，秒级）→ `design/*.html` 落盘 + `plan.json`（state=CONVERTING）→ chatStream 进入 `MockupOrchestrator` → `MockupConverter` 五步转化 → 最终调 `PageSpecRenderer.render()`（链路 A 的渲染器）**。

### 3.3 链路 A：组件化渲染引擎（产物保证的"黄金链路"）

两个上游共用同一个渲染器：
- pipeline 模式（AI 从需求直接生成）：`runComponentPipeline`（`AiTemplateGenServiceImpl.java:2536` 附近），AI 输出 PageSpec JSON → `PageSpecRenderer.render()`
- design/import 模式（上传 HTML 转化）：`MockupOrchestrator.runConverting`（`MockupOrchestrator.java:451-490`）→ `MockupConverter.convert()`（`MockupConverter.java:270`）→ 五步（区块切分→AI 组件映射→装配→脚本迁移→渲染校验，`doConvert` L307-356）→ **`MockupConverter.java:806` `pageSpecRenderer.render(spec, ctx.workDir(), ctx.mobileAdaptive())`**

`PageSpecRenderer.render()`（`PageSpecRenderer.java:193-208`）的完整写文件清单（**每个 write 方法都是无条件调用**）：

| 方法 | 产物 | 行号 |
|---|---|---|
| `writeStaticAssets` | `static/css/pack-{packId}.css`（每包一份）+ `static/css/tokens.css` + `static/css/site.css` | L199, L983-1001 |
| `writeComponentSources` | `_components/{pack}__{component}__{variant}.ftl`（每个用到的组件变体一份） | L200, L338-340 |
| `writeLayout` | `_layout.html`（含 `<#macro page>`，L796） | L201, L827-828 |
| `writePages` | `index.html` / `article_list.html` / `article.html` / `page.html` + 信息架构 suffix 页 | L202, L266-279（`collectPageKeys` 无条件含 4 基础页） |
| `writePagespec` | `_pagespec.json` | L203, L1011-1017 |
| `writeTemplateProperties` | `_template.properties`（7 字段，UTF-8 手写） | L204, L1019-1040 |
| `writePreviewData` | `_preview_data.json`（seo/menus/categories/singlePages/articles 全量） | L205, L1065-1114 |

每个页面 HTML 的 freemarker 包裹是**代码硬编码**（`buildLayoutPageHtml`，`PageSpecRenderer.java:885-892`）：

```java
html.append("<#import \"_layout.html\" as layout>\n");
...
html.append("<@layout.page>\n");
html.append(body);
html.append("</@layout.page>\n");
```

菜单标签同理：navbar 组件源码 `components/tw/navbar/variants/sticky.ftl:45,66` 硬编码 `<@menuTag>`（桌面 + 移动两份），`component.json` 声明 `cmsBindings:["menu"]`。**只要 nav 区块映射为 navbar 组件，菜单标签必然在场。**

### 3.4 链路 B：直写 HTML 批次管线（无代码保证）

`runBatchPipeline`（`AiTemplateGenServiceImpl.java:1054` 调用）：规划轮（AI 输出文件清单 plan）→ 逐文件轮（AI 每次输出一个文件的完整内容 JSON）→ `fileService.saveOrUpdateFile` 落盘（L1265-1270）。

**产物契约只存在于提示词**（`TemplateGenPromptBuilder.buildGenPrompt` L337-344 / `buildPlanPrompt` L368-374）：

```
1. 必须包含必备文件：_template.properties、_layout.html、index.html、article.html、article_list.html、page.html
2. 必须生成 _preview_data.json 预览演示数据…
3. 至少包含基础样式文件 static/css/base.css…
3.（规划轮）可根据需求补充其他文件（如 static/js/main.js、_articlePage.html），但文件总数控制在 10 个以内
```

落盘后的唯一校验是 **freemarker 能否成功渲染**（`previewRenderer.checkRenderedFiles`，L1280-1359，失败触发修复轮）。**没有**"必备文件是否齐全""是否含 fastcms 标签""目录结构是否规范"的任何校验。AI 输出被 max_tokens 截断、漏文件、或把页面写成纯静态 HTML（无 `<#` 指令），都会原样落盘。

### 3.5 链路 C：单轮调整

会话已有 `templateId`（编辑正式模板）时走 L1058+ 单轮路径，AI 基于现有文件修改。产物形态继承模板既有结构；有渲染报错修复循环（L1292-1359），同样无结构合规校验。

---

## 4. 四个问题逐条事实核查

### 4.1 目录结构不符规范

**链路 A：根目录结构符合。** `collectPageKeys`（`PageSpecRenderer.java:266-279`）无条件并入 `index/article_list/article/page` 四个基础页，suffix 页按 site 信息架构追加；`_layout.html`、`_template.properties`、`_preview_data.json`、`_pagespec.json` 全部在模板根目录；`static/css` 由 `writeStaticAssets` 生成。

**不符的可能来源（按概率排序）：**
1. 产物实际来自链路 B（`gen-mode=html` 或旧 plan 会话），AI 随意组织目录；
2. 用户查看的是 design 阶段中间产物：`MockupConverter.java:371` 读的是 `workDir/design/{page}.html`（纯 HTML 设计稿，转化前的正常中间态）；
3. 用户上传的是单页 HTML（无 about/news 等），`buildSiteContent`（`MockupConverter.java:1186-1226`）只产出首页菜单项，suffix 页虽由 `collectPageKeys` 补齐，但菜单里看不到——用户误以为"页面没生成"。

**结论：链路 A 无需整改目录结构；整改重点是链路 B（见 R4 合规校验器）与链路识别（§5）。**

### 4.2 纯 HTML 无 fastcms 标签 / 菜单标签缺失

**链路 A 的标签保证（代码级，逐页）：**
- 每页 `<#import "_layout.html">` + `<@layout.page>`：`PageSpecRenderer.java:885-892`（硬编码）；
- 菜单 `<@menuTag>`：`components/tw/navbar/variants/sticky.ftl:45,66`（硬编码在组件源码，经 `writeComponentSources` 落盘到 `_components/`，`_layout.html` 以 `<#include>` 引用，`PageSpecRenderer.java:715`）；
- 文章列表 `article_list.ftl:15` `<#list articleVoPage.records>`、L39 `<@articlePageTag>`；文章详情 `article.ftl:7,20,33,49` `<@formatTime>/<@prevArticleTag>/<@nextArticleTag>/<@relatedArticleList>`。

**链路 A 的真实缺口（唯一）——custom 降级丢失菜单标签：**
`MockupConverter.addNavFooterSection`（L928-946）：当 nav 区块的组件映射结果不是 navbar 而是 `custom`（AI 映射置信度低于阈值，或整页降级），nav 区块物化为**设计稿原样 HTML**（`adaptCustomHtml`，L941-944，`CUSTOM_HTML_COMPONENT`）。用户上传的静态 HTML 的 nav 里只有 `<a>` 链接、没有 `<@menuTag>` → **该模板的菜单是写死的静态链接，CMS 后台配菜单不生效**。

注意：`custom` 区块物化的是可执行 FTL 片段（`PageSpecRenderer.java:395-445`，snippet 展开或 data.html 原文），页面级 freemarker 包裹（`<#import>` + `<@layout.page>`）仍在——所以症状是"菜单标签丢失"而非"整页纯 HTML"。

**链路 B：整体无标签保证。** 提示词 `buildFilePrompt`（`TemplateGenPromptBuilder.java:427-439`）对 `_layout.html` 有 menuTag 强约束，但页面文件依赖 AI 逐文件输出时"记得"用标签；max_tokens 截断后 `plan` 残缺，文件可能以纯静态 HTML 形态落盘（落盘逻辑 L1265-1270 不校验内容）。

### 4.3 缺 `_preview_data.json` / `_template.properties`

**链路 A：不可能缺。** `writeTemplateProperties`（`PageSpecRenderer.java:1019`）与 `writePreviewData`（L1065）在 `render()` 中无条件调用；且 `writeTemplateProperties` 有 `readExistingTemplateId` 保护（L1047-1063，重渲染不篡改已注册 id）。

`_preview_data.json` 内容质量有一个**真实短板**：design/import 链路的菜单来源是 `MockupConverter.buildSiteContent`（L1186-1226），它只按 4 个**固定文件名**匹配页面（L1195-1216 switch：about/services/contact/news），其他页面名（如 products、team、cases、blog）一律不进菜单 → `_preview_data.json` 的 menus 稀疏、预览菜单不完整、suffix 页从菜单不可达。

**链路 B：可能缺。** AI 输出被截断或漏文件时，两个元数据文件直接缺失，落盘与校验（§3.4）均不拦截。

### 4.4 文字分页文件 `_articlePage.html` 未生成（两条链路都缺）

事实链：
1. 规范中 `_articlePage.html` 定位为"**可选**"（`TemplateGenPromptBuilder.java:1018`）；
2. 组件化渲染引擎 `PageSpecRenderer.java` 全文 grep `_articlePage` **0 命中**——不生成该文件；
3. 分页条实际**内联**在 `article_list.ftl:39-65` 正文骨架里（`<@articlePageTag>` 直接展开成 Tailwind 分页样式）；
4. 文章详情页 `article.ftl:16` 用 `${(article.contentHtml)!''}` 一次性输出全文，**没有**长文内容分页（若用户"文字分页"指的是正文按页切分，则 fastcms 现有指令体系里也没有对应指令——`articlePageTag` 是列表分页，§2 已澄清；正文分页需框架层新增指令，超出本方案范围，见 §6.3 说明）。

**两种理解与对应整改：**
- 理解①（按规范目录）："文字分页模板文件" = 传统模板规范的 `_articlePage.html` 分页宏文件 → **本方案 R1 覆盖**（生成独立 `_articlePage.html`，`_layout.html` include，页面改用 `<@layout._articlePage/>`，与内联形态功能等价但结构归一）；
- 理解②（长文正文按页切分展示）：需要框架层新指令（如 `articleContentPageTag`，对 `article.contentHtml` 按页切片），**涉及 cms 核心指令体系，不在本方案内**，如需要请单独立项。

**评审决议 D1：按理解①执行——生成独立 `_articlePage.html` 分页宏文件（§6-R1）。**

---

## 5. 链路识别方法（现场判断一次产物来自哪条链路）

拿到一个生成产物目录后，按序判断：

```
1. 目录根有 _pagespec.json？
   ├─ 是 → 链路 A（组件化引擎）。继续：
   │    ├─ 有 design/ 子目录与 design/plan.json → design/import 上传 HTML 转化
   │    └─ 无 design/ → pipeline 模式（AI 从需求生成）
   └─ 否 → 继续
2. 目录根有 design/plan.json 但无 _pagespec.json？
   ├─ 是 → design/import 链路中断态（转化未完成或 FAILED），产物不完整属预期
   └─ 否 → 继续
3. 页面 html 文件含 `<#`（freemarker 指令）？
   ├─ 否 → 链路 B 产物且 AI 输出了纯静态 HTML（或链路 A 渲染失败残留）
   └─ 是 → 链路 B 产物（AI 直写但含标签），查会话 plan 文件确认
```

同时核对会话表 `create_mode` 字段（`pipeline`/`design`/`import`）与服务配置 `fastcms.ai.template.gen-mode`（当前未配置 = 默认 `component`）。

## 6. 整改方案（R1~R6）

总原则：**规范合规不依赖 AI 自觉，收敛到 Java 代码层确定性保证**。新增一个合规校验/兜底组件，作为所有生成链路的统一收口；具体缺口（`_articlePage.html`、`static/js`、custom nav 菜单、稀疏菜单）逐项补齐。

### R1：`_articlePage.html` 分页宏文件生成（组件化引擎）—— 优先级 P0 【决议 D1】

**现状**：`PageSpecRenderer` 不产该文件；分页条内联在 `components/tw/pages/article_list.ftl:39-65`（`<@articlePageTag>` + 完整 Tailwind 样式 + `data.prev/list/next` 判空）。

**关键实现边界（精读确认，勿遗漏）**：正文骨架 `contentSkeleton(pageKey)`（`PageSpecRenderer.java:965`）**同时被两种页面形态注入**：
- 布局页（`buildLayoutPageHtml`，L873/L882）：页面包 `<#import "_layout.html" as layout>` + `<@layout.page>`；
- **standalone 页**（`buildStandaloneHtml`，L890-923）：**不 import `_layout.html`**（`isStandalone`，L293-297，AI 标记 `standalone` 的页面独立成页），直接拼 `<!DOCTYPE>` + 正文。

→ 骨架中分页的引用方式必须对两种形态**同时可用**，这是 R1 方案设计的核心约束。

**产物模板（新增文件 `_articlePage.html` 的完整内容）**——宏名与规范提示词示例一致（`TemplateGenPromptBuilder.java:1218` 即 `<#macro _articlePage>`），分页块整体搬自 `article_list.ftl:39-65`（样式/判空逻辑逐字保留，不重造）：

```html
<#-- 文章列表分页宏（fastcms 规范文件）：页面用 <@layout._articlePage/> 调用 -->
<#macro _articlePage>
  <@articlePageTag>
    <#if data??>
      <nav class="mt-12 flex flex-wrap items-center justify-center gap-2" aria-label="文章分页">
        <#if data.prev?? && (data.prev.url)?? && ((data.prev.url)!'')?has_content>
          <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
             href="${data.prev.url}">${(data.prev.text)!'上一页'}</a>
        </#if>
        <#if data.list?? && data.list?is_sequence>
          <#list data.list as page>
            <#if page?? && page?is_hash>
              <#if (page.url)?? && ((page.url)!'')?has_content>
                <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
                   href="${page.url}">${(page.text)!}</a>
              <#else>
                <span class="rounded-lg border border-primary-600 bg-primary-600 px-4 py-2 text-sm text-white"
                      aria-current="page">${(page.text)!}</span>
              </#if>
            </#if>
          </#list>
        </#if>
        <#if data.next?? && (data.next.url)?? && ((data.next.url)!'')?has_content>
          <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
             href="${data.next.url}">${(data.next.text)!'下一页'}</a>
        </#if>
      </nav>
    </#if>
  </@articlePageTag>
</#macro>
```

**推荐实现（C-2：与规范示例完全同形，调用统一为 `<@layout._articlePage/>`），3 个精确插入点：**

1. **`PageSpecRenderer` 新增方法**（签名 + 落点）：
   ```java
   /** 规范分页宏文件 _articlePage.html（内容 = ARTICLE_PAGE_HTML 常量） */
   private static final String FILE_ARTICLE_PAGE = "_articlePage.html";   // 或引用 AiTemplateConstants.FILE_ARTICLE_PAGE（R1 顺带新增，见 §8）

   private void writeArticlePageHtml(Path targetDir, List<String> written) throws IOException {
       write(targetDir.resolve(FILE_ARTICLE_PAGE),
               ARTICLE_PAGE_HTML.getBytes(StandardCharsets.UTF_8),
               FILE_ARTICLE_PAGE, written);
   }
   ```
   **插入点**：`render()` 中 `writeLayout(spec, plan, targetDir, written, mobileAdaptive);`（L201）**之后**加一行 `writeArticlePageHtml(targetDir, written);`。
   `ARTICLE_PAGE_HTML` 为 `private static final String` 文本块常量（内容即上方产物模板；`PageSpecRenderer` 已有同类常量先例 `SITE_CSS`，L1000 附近）。

2. **`writeLayout`（L791-830）顶部加 include**——`<#include>` 放 `_layout.html` 的**顶层**（`<#macro page>` 定义**之外**），这样页面 `<#import "_layout.html" as layout>` 时，include 随 import 静态解析执行，`<#macro _articlePage>` 成为 `_layout.html` 的顶层宏，页面方可用 `<@layout._articlePage/>` 调用（与规范示例 `TemplateGenPromptBuilder.java:1213` 同形）：
   **插入点**：L796 `sb.append("<#macro page>\n");` **之前**插一行：
   ```java
   sb.append("<#include \"_articlePage.html\">\n");   // 规范分页宏文件，顶层 include → 页面可 <@layout._articlePage/>
   ```
   机制依据：顶层 `<#include>` 属静态解析期展开，FreeMarker 标准行为；本类以 `<#include "_components/*.ftl"` 引用组件源码的机制已被渲染校验长期验证（`PageSpecRenderer.java:56-58` 类注释明确该约定，校验器为 `AiTemplatePreviewRenderer.checkRenderedFiles`，L101）。

3. **`buildStandaloneHtml`（L888 起）头部加 import**——standalone 页当前不引用 `_layout.html`，需借 import 获得 `layout._articlePage` 宏（import 只静态解析、不执行宏体，无副作用）：
   **插入点**：L895 `html.append("<#assign mobileAdaptive = ...>\n");` **之后**插一行：
   ```java
   html.append("<#import \"_layout.html\" as layout>\n");   // 仅为引用 _articlePage.html 分页宏（import 静态解析，无执行副作用）
   ```

4. **骨架资产替换**：`components/tw/pages/article_list.ftl` 的 L39-65 内联分页块（整段 `<@articlePageTag>...</@articlePageTag>`）替换为一行：
   ```html
   <@layout._articlePage/>
   ```
   **影响面**：包资产变更影响**所有后续新生成**模板；存量模板目录已各自落盘内联版 `article_list.html`，不受影响（功能等价）。

**FreeMarker 行为验证点（实施第一步，10 分钟）**：C-2 依赖"import 文件顶层 include 定义的宏可按 alias 调用"（FreeMarker 2.3.x 标准行为，但本项目此前无同类用例）。实施时先改 `article_list.ftl` + `writeLayout`，用既有预览管线 `previewRenderer.checkRenderedFiles`（`AiTemplatePreviewRenderer.java:101`，`MockupConverter.java:827` 同款调用）跑一次渲染校验即可实证，失败会在既有校验循环报错，不会静默。若验证失败，退路 C-1（见下）。

**退路 C-1（片段 include，不依赖宏作用域）**：`_articlePage.html` 内容改为**非宏片段**（直接是 `<@articlePageTag>...</@articlePageTag>` 块，去掉 `<#macro>` 包裹），骨架引用改 `<#include "_articlePage.html">`。与既有 `<#include "_components/*.ftl">`（L715）同机制、零宏作用域风险、layout/standalone 两形态天然可用；代价是偏离规范示例的宏调用形（`<@layout._articlePage/>`）。**默认不采用**，仅当 C-2 验证失败时切换。

**存量兼容**：R4 的 `missing-article-page` 检查把"`article_list.html` 已含 `<@articlePageTag>`（内联版）"视为**合规等价态**，不回改存量模板目录（只作用于会话 workDir，§6-R4 边界）。

**验证点**：新生成模板根目录含 `_articlePage.html`（宏形）；`article_list.html` 含 `<@layout._articlePage/>`；standalone 形态页（构造 standalone spec）渲染校验通过；预览 mock `articlePageTag` 已就绪（`AiTemplatePreviewMockSupport.java:437`）无需改。

### R2：`static/js/` 按需落盘 —— 优先级 P1 【决议 D2：有 js 才建目录，没有就不建】

**决议落点（明确否定"强制生成 main.js"）**：
- **不新增** `static/js/main.js`。组件化引擎（`PageSpecRenderer`）**保持现状，不产任何 js 文件**——navbar 等组件的交互脚本已内联在组件 ftl 中（`sticky.ftl`），`writeStaticAssets`（L983-1001）只产 `static/css/*`，**零改动**。
- 规范目录树里 `js/main.js` 本就标注"可选"（`TemplateGenPromptBuilder.java:1028`），"没有 js 不建目录"与规范自洽，**不改提示词**。
- 合规校验器（R4）**不把"缺 `static/js`"判为问题**（撤销原 `static-js` 检查项）；只校验"若存在 `static/js`，其中不得为空目录"。

**唯一要做的：design 链路大脚本从内联改为独立 js 文件（按规范落位）**。

现状：`MockupConverter.migrateScripts`（方法体 L1335-1377，`JS_MIGRATION_MARKER` 常量 L1327）把设计稿提取的全部 inline script 以 `<script>…</script>` 内联注入 `_layout.html` 的 `</body>` 前（L1368）。设计稿脚本大时（常见数 KB~数十 KB），全部堆进 `_layout.html`，布局文件膨胀、且与规范"js 放 `static/js/`"的目录约定不符。

**改造（`migrateScripts` 内，方法签名/调用点均不变）**：

```java
// 原：inject 恒为 <script>{bundle.scripts()}</script> 内联（L1368）
// 改：按阈值分流
private static final int JS_INLINE_THRESHOLD = 2048;  // 2KB，与 migrateScripts 既有注释"脚本"量级匹配
private static final String FILE_IMPORTED_JS = "static/js/imported.js";  // 新增常量（AiTemplateConstants 或本类私有）

String scripts = bundle.scripts();
if (scripts.length() > JS_INLINE_THRESHOLD) {
    // 大脚本 → 独立文件落 static/js/（"有 js 才建目录"：仅在真的有大脚本时才创建）
    Path jsFile = ctx.workDir().resolve(FILE_IMPORTED_JS);
    Files.createDirectories(jsFile.getParent());
    Files.writeString(jsFile, scripts, StandardCharsets.UTF_8);
    injectedJs = "<script src=\"${ctx()}/js/imported.js\"></script>";   // 与 _layout.html 现有 css 引用同款 ${ctx()}/css/xxx 形态（L809-811）
} else {
    injectedJs = "<script>\n" + scripts + "\n</script>";                // 小脚本维持现状内联
}
```

**配套**：
- 锚点防御垫片（L1350-1364，缺失锚点时注入的 `getElementById` 兜底 IIFE）**始终保留内联**（它必须在业务脚本之前执行，且极小，文件化无收益）；
- 文件事件注册：`persistProducts`（`MockupConverter.java:1404` 起）当前的 extra 注册列表（`static/css/tokens.css` 等）追加一条——`Files.isRegularFile(workDir/"static/js/imported.js")` 时把 `static/js/imported.js` 加入 `ordered`（与 SVG 注册同机制，L1412-1418），保证该文件进会话文件表 + SSE file 事件；
- 幂等：方法入口既有 `content.contains("fastcms-design-scripts")` 幂等守卫（L1337），改造后该标记移到注入的 `<script src=…>` 注释行上（`<#-- fastcms-design-scripts: … -->` 形态保留），防重渲染重复文件化；
- 渲染校验覆盖：`checkRenderedFiles` 只校验 `.html`（L825-827 filter），`imported.js` 不进校验对象——js 语法错误不在本方案校验范围（设计稿原样搬运，风险同现状内联）。

**验证点**：上传含大脚本（>2KB）的设计稿 → 产物含 `static/js/imported.js`，`_layout.html` 内出现 `<script src="${ctx()}/js/imported.js">`，`persistProducts` 文件事件含该文件；上传无脚本/小脚本设计稿 → **不出现 `static/js` 目录**（决议 D2 直接验收项）。

### R3：custom nav 降级保留菜单标签 —— 优先级 P0

**现状**：`MockupConverter.addNavFooterSection`（L939-944）nav 降级 custom 时物化设计稿原样 HTML（无 `<@menuTag>`），CMS 菜单不生效。

**方案**（二选一，推荐 A）：
- **方案 A（确定性替换）**：custom nav 物化时，解析设计稿 nav 内的 `<ul><li><a>` 列表结构（Jsoup 已在 `MockupConverter` 使用，L377），若锚文本与站点信息架构菜单项（`buildSiteContent` 产物）能对应，则用 navbar 组件的 `<@menuTag>` 块替换静态 `<ul>`；对应不上（AI 映射全部失败的极端降级）则保留静态 HTML 并**在 SSE 播报中明确提示**"菜单为静态链接，CMS 后台菜单配置不生效"（现状是静默降级，用户无感知——这是比丢标签更糟的体验）。
  **锚文本匹配规则（确定性，不引入 AI）**，按序尝试、首个命中即停：
  1. **归一化精确匹配**：锚文本与菜单项标题各自归一化后相等。归一化 = trim + 转小写 + 去首尾空白；中文直接比对，英文额外比对小写形态（如 "Home" vs "首页" 不匹配，"Home" vs "home" 匹配）。
  2. **中英同义词表匹配**：内置小型映射表（`Home/主页/首页`、`About/关于`、`Services/服务/业务`、`Contact/联系/联系我们`、`News/新闻/动态/博客/Blog`、`Products/产品`、`Team/团队`、`Cases/案例`），锚文本与菜单标题命中同组即算对应。表内容常量化、可扩充，**不写死单个站点的具体文案**。
  3. **包含匹配**：归一化后一方包含另一方且长度 ≥2（防单字误命中）。
  4. 全部失败 → 该 `<li>` 保留静态链接；**全部 `<li>` 都失败** → 整块保留静态 HTML + SSE 告警（见上）。
  匹配结果计数写入 `design/convert-report.json`（`nav_anchor_matched` / `nav_anchor_total`），便于事后审计替换率。
- **方案 B（映射阈值下调 + 兜底宏）**：nav 区块永不映射 custom——映射失败时强制用 navbar 组件 default 变体（牺牲视觉保真换取菜单可用性），`degradeAllMapped`（L860-868）对 nav 单元特殊处理。

**配套**：无论 A/B，`persistProducts`（L1403-1442）收尾时扫描 `_layout.html` + 各页 HTML，**无 `menuTag` 字样即输出告警**（SSE message + 落 `design/convert-report.json`，供前端在文件树标红提示），把"菜单标签丢失"从静默事故变成显式事件。

### R4：`TemplateComplianceChecker` 统一合规校验/兜底 —— 优先级 P0（本方案核心）

**现状**：三条链路各自落盘，无统一的"产物符合 fastcms 规范"校验。

**新增类**：`starters/ai-starter/src/main/java/com/fastcms/ai/template/compliance/TemplateComplianceChecker.java`（Spring `@Component`，无状态，所有链路复用）

**公共 API（两个重载，`Sink` 为函数式接口）**：

```java
package com.fastcms.ai.template.compliance;

/** 合规播报回调（各链路用不同实现把结果送进自己的 SSE 通道，见调用点表） */
@FunctionalInterface
public interface Sink {
    void send(String messageJson);   // 发送 SSE message 事件（data = 文案 JSON 字符串）
}

public class TemplateComplianceChecker {

    public record ComplianceIssue(String code, String file, Severity severity,
                                  boolean fixable, String detail) {}
    public enum Severity { ERROR, WARNING }

    public record Report(List<ComplianceIssue> issues, List<String> fixed) {
        public boolean hasErrors() { return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR); }
        /** 播报文案："模板合规检查：通过 6 项 / 已自动修复 2 项（…）/ 待处理 1 项（…）"；全通过时省略后两段 */
        public String summary() { /* 拼接 */ }
    }

    /**
     * @param workDir    模板工作目录（生成型=会话产物目录；调整型=正式模板目录）
     * @param link       链路标识（决定修复策略差异，见检查表"自动修复"列）
     * @param readOnly   调整型会话（workDir 是正式模板目录）传 true：只校验+播报，不写任何文件
     * @param sink       播报通道；null 表示静默（只返回 Report）
     * @return 校验报告（调用方可据 hasErrors() 决定是否触发链路 B 修复轮）
     */
    public Report check(Path workDir, Link link, boolean readOnly, Sink sink) throws IOException;

    public enum Link { PIPELINE, DESIGN_IMPORT, BATCH_HTML }
}
```

**三个调用点（均已精读定位，插入式改动，不改变既有流程结构）：**

| 链路 | 精确插入点 | Sink 实现 | readOnly |
|---|---|---|---|
| A-pipeline | `AiTemplateGenServiceImpl.java`：渲染成功 break 出 fix 循环、**`allWrittenFiles` 持久化循环之前**（L2816 注释行 `// ===== 持久化 + 文件事件…` 之前）。此时 workDir 产物完整，修复文件随后被持久化循环天然带上 | `json -> sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, json)` | false |
| A-design/import | `MockupOrchestrator.runConverting`（L479-482）：`importService.postConvertWiring(...)` **之后**、`buildDoneSummary` 播报**之前**。此时产物已注册、DONE 未发，合规修复文件需补注册（见下"修复文件注册"） | `json -> sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, json)` | false |
| B 直写 HTML | `AiTemplateGenServiceImpl.java` `runBatchPipeline`：`writeToFile` 落盘循环**结束**（L1286 `totalFiles += successCount;` 之后）、`checkRenderedFiles` 校验（L1292+）**之前**——修复后的文件进入渲染校验对象 | `json -> sendEvent(channel, AiTemplateConstants.SSE_EVENT_MESSAGE, json)` | false |
| C 单轮调整 | 同 B 的插入点（B/C 共用 `writeToFile` 循环之后的位置） | 同上 | **true**（workDir 是正式模板目录，防覆盖用户手改，风险表 R-3） |

**修复文件注册**（仅 design/import 链路需要）：checker 返回的 `Report.fixed` 中若含 `persistProducts` 未注册过的路径（如补写的 `_template.properties`），由 `MockupOrchestrator` 在 checker 之后补一次 `fileService.saveOrUpdateFile` + SSE file 事件（复用 `persistProducts` 的注册写法，`MockupConverter.java:1422-1437`）。pipeline / B 链路无需此步（修复发生在持久化循环之前/之中）。

**检查项与判定逻辑（每项的完整判定，伪代码级）：**

```java
// 读取集合：workDir 下 *.html（含 _ 前缀）+ _template.properties + _preview_data.json
// BASE_PAGES = [index.html, article.html, article_list.html, page.html]   // AiTemplateConstants L111-114

// 1. missing-base-page（ERROR，B 链路 fixable，A 链路恒不触发）
for (String p : BASE_PAGES)
    if (!Files.isRegularFile(workDir/p))
        issues.add(ERROR missing-base-page p, fixable = link == BATCH_HTML);
// 修复（仅 B）：从组件包资产取对应正文骨架（PageSpecRenderer.contentSkeleton 同源，
//   需把 contentSkeleton 抽为可复用——建议提为 package-visible 静态方法或下沉到公共类），
//   包裹最小 <#import "_layout.html" as layout>/<@layout.page> 头尾（buildLayoutPageHtml 同款，L885-892）

// 2. missing-layout（ERROR，不修复）
if (!Files.isRegularFile(workDir/_layout.html)
        || !read(_layout.html).contains("<#macro page"))
    issues.add(ERROR missing-layout "_layout.html");

// 3. missing-template-props（ERROR，fixable）
// 判定：文件缺失，或 7 字段（id/name/path/version/i18n/provider/description）任一缺失
// 修复：按 PageSpecRenderer.writeTemplateProperties（L1019-1040）同款 7 行手写 properties 补齐；
//   template.id 优先 readExistingTemplateId（L1047-1063 同款：已有则复用），否则取 workDir 目录名
//   （与渲染器 L1022 的取法一致）；name 取目录名；path = "/" + 目录名 + "/"；version=1.0.0；i18n=zh-CN；provider=ai

// 4. missing-preview-data（ERROR，fixable）
// 判定：_preview_data.json 缺失，或 menus 数组为空
// 修复：缺失 → 写最小结构 {seo:{title,description,keywords}, menus:[{id:1,menuName:"首页",
//   menuType:"LINK",url:"/"}], categories:[], singlePages:[], articles:[]}（字段集与
//   writePreviewData L1065-1114 输出一致）；menus 为空但文件存在 → 只补"首页"一项（保留其余内容）

// 5. plain-html-page（ERROR，不自动修）
for (String p : BASE_PAGES 中存在的文件)
    if (!read(p).contains("<#"))
        issues.add(ERROR plain-html-page p, detail="该页无 FreeMarker 指令，疑似 AI 直写静态 HTML");
// 链路 B：报告返回后由 runBatchPipeline 触发既有修复轮（见 R6-2），不在此内联

// 6. no-menu-directive（WARNING，不修复）
// 判定：workDir 下全部 *.html/*.ftl 拼接后 indexOf("menuTag") < 0
issues.add(WARNING no-menu-directive, detail="未发现 <@menuTag>：菜单为静态链接，CMS 后台菜单配置不生效");

// 7. missing-article-page（WARNING，fixable）—— 与 R1 配套
boolean hasMacroFile = Files.isRegularFile(workDir/_articlePage.html);
boolean hasInlinePage = Files.isRegularFile(workDir/article_list.html)
        && read(article_list.html).contains("articlePageTag");   // 内联版 = 合规等价态（存量兼容）
if (!hasMacroFile && !hasInlinePage)
    issues.add(WARNING missing-article-page, fixable=true);
// 修复：写 R1 产物模板（与 PageSpecRenderer.ARTICLE_PAGE_HTML 同一常量源——建议该常量提为
//   AiTemplateConstants.ARTICLE_PAGE_HTML 公共常量，渲染器与 checker 共用，防两处漂移）
//   + 若 _layout.html 顶层无 <#include "_articlePage.html"> 则补插一行（<#macro page 之前）
//   + 若 article_list.html 含内联分页块则不动（等价态）；无分页则不补（页面本身可能无列表区）

// 8. static-structure（ERROR，不修复）
if (!Files.isDirectory(workDir/static/css))
    issues.add(ERROR static-structure, detail="static/css 目录缺失");

// 9. empty-js-dir（WARNING，fixable）—— 决议 D2
// 注意：缺 static/js 目录【不是问题】（组件链路无 js 正常）
if (Files.isDirectory(workDir/static/js) && 目录内无任何常规文件)
    issues.add(WARNING empty-js-dir, fixable=true);   // 修复：删除空目录
```

**执行顺序与短路**：检查 1-2 若命中（基础页/layout 缺失）时，后续检查 5/6/7 跳过（对象不在场无意义），但 3/4/8/9 仍执行（元数据文件补齐不受页面缺失影响）。`fixable && !readOnly` 才执行修复；修复本身失败（IO 异常）捕获后降级为"待处理"播报，**不抛出、不阻断生成流程**（合规检查是兜底增强，绝不能把原本成功的生成变成失败）。

**播报**：`Report.summary()` 经 `Sink` 发**一条** SSE message。三个调用点的播报时机均在 done 事件之前（pipeline：状态条清空前；design：DONE 播报前；B：总结播报前），前端零改动（既有 `message` 事件直接渲染进对话流）。

**不做的事（边界）**：
- 不回改已应用的正式模板目录的**内容**（C 链路 readOnly=true，只播报）；
- 不校验视觉/样式质量（那是 `MockupAuditor` 审计器职责，职责分离）；
- 不校验 `static/js` 存在性（决议 D2）；不校验 `static/images` 存在性（有图才建，同 D2 精神）；
- 不重复渲染校验（`checkRenderedFiles` 已各自持有，本 checker 只做结构/内容存在性，不引入 freemarker 引擎依赖）。

### R5：design 链路菜单信息架构补全 —— 优先级 P1

**现状**：`MockupConverter.buildSiteContent`（L1186-1226）按**文件名 switch** 提取菜单（L1195-1216 只认 about/services/contact/news 等 4 个固定名），上传多页站点（如 products.html、team.html、cases.html）时这些页面**进不了菜单** → `_preview_data.json` 的 menus 稀疏、suffix 页从菜单不可达。

**改造点：`buildSiteContent` 内部，方法签名/返回结构不变（`SiteContent` 数据类不动）**：

1. **固定名映射表化**——把 switch 分支抽为常量表（新增 `private static final Map<String, NavTypeMapping>`，与现有 4 个分支语义完全一致，只是可扩充）：
   ```java
   // NavTypeMapping = record(菜单标题, 菜单类型 TYPE_PAGE/TYPE_ARTICLE_LIST, NavItem.suffix 值)
   // ⚠ suffix 是裸名（如 "about"），消费端 SiteContentSpec.NavItem(name, type, suffix, children)
   //   按 {type}_{suffix}.html 组合定位文件——传 "page_about" 会拼成 page_page_about.html，指向不存在的文件。
   // 现有 switch 的 4 个分支原样搬入（对照 MockupConverter.java:1195-1216：about/services/contact 均为 TYPE_PAGE，仅 news 为 TYPE_ARTICLE_LIST）：
   //   about      → 关于我们,        TYPE_PAGE,         about
   //   services   → 服务/业务,       TYPE_PAGE,         services    // 注意：现有代码是单页（singlePages），不是 ARTICLE_LIST
   //   contact    → 联系我们,        TYPE_PAGE,         contact
   //   news       → 新闻动态,        TYPE_ARTICLE_LIST, news
   // 建议扩充常见名（语义与上表一致，type 按页面内容性质定）：
   //   blog       → 博客,            TYPE_ARTICLE_LIST, blog        ← 新增
   //   products   → 产品中心,       TYPE_PAGE,         products    ← 新增
   //   team       → 团队介绍,        TYPE_PAGE,         team        ← 新增
   //   cases      → 案例展示,        TYPE_ARTICLE_LIST, cases       ← 新增
   ```
   **约束**：新增分支的 suffix 必须与 `DesignPagePlanner` 为该页实际分配的 suffix 页 key 一致（规划器产物 `page.plan().fastcmsPageKey()` 形如 `page_about`/`article_list_news`，NavItem 的 suffix 取其去前缀部分），否则菜单指向的页面不存在——**实施时先核对 `DesignPagePlanner.fastcmsPageKey()` 的 suffix 分配规则再定表**（见风险表 R-4）。

2. **未命中固定名的页面 → 确定性兜底**（不引入新 AI 调用）：
   ```java
   // 对 bundle.pages() 中未命中映射表、且非基础页（index/article/article_list/page 本身）的页面：
   String title = page.title();                    // DesignPagePlanner 已解析的 <title>/h1（bundle 内已有，L876 同源）
   String pageKey = page.plan().fastcmsPageKey();  // 规划器已分配的 suffix 页 key
   if (pageKey == null) continue;                  // 规划器未分配 suffix 的页不进菜单（保持现状）
   // 类型判定：规划器产物已含页面分类（article_list_xxx 形态 → ARTICLE_LIST；page_xxx → PAGE），
   // 直接由 pageKey 前缀判定，不再二次解析正文
   menus.add(new Menu(title, type, pageKey));
   ```

3. **上限与排序**：菜单项保持**规划器页面顺序**（即设计稿站点导航顺序，`bundle.pages()` 的原始顺序）；上限 **8 项**，超出丢弃尾部并在 `convert-report.json`（R3 既有落盘点）记 `menu_overflow` 计数。**不做"更多"折叠**——navbar 组件对子菜单渲染支持未验证（风险表 R-1），折叠需要 `<@menuTag>` 的 children 结构支持，贸然实现可能渲染异常；直接截断 + 报告记录是确定性且无渲染风险的取舍。

**验证点**：上传含 5 个非固定名页面（products/team/cases/pricing/blog）的多页站点 → `_preview_data.json` menus ≥ 6 项（首页 + 5 页面项），每个 suffix 页从菜单可达；`convert-report.json` 无 `menu_overflow`（≤8 项时）。

### R6：链路 B 提示词契约升级 + 接入合规校验 —— 优先级 P2 【决议 D3：链路 B 不下线，保留作扩展实现】

**决议落点**：**不删除** `runBatchPipeline` / `buildPlanPrompt` / `buildGenPrompt`，不做旧会话迁移。链路 B 保留价值：`gen-mode=html` 回退能力 + 旧 plan 会话兼容（§3.1 分流逻辑零改动）。本项只做两件事：把提示词契约补齐 + 接入 R4 兜底，使其产物质量与链路 A 收敛到同一合规底线。

**6.1 提示词契约升级（`TemplateGenPromptBuilder.java`，3 处纯文本改动）：**

| 位置 | 现文案 | 改后 |
|---|---|---|
| `buildGenPrompt` 文件清单（L337-344） | 必备 6 文件 + "建议生成 `_preview_data.json`" | 必备清单扩为 **8 项**：`_template.properties`、`_layout.html`、`_articlePage.html`（**新增**，并附 §6-R1 产物模板的宏定义示例，直接贴进提示词）、`index.html`、`article.html`、`article_list.html`、`page.html`、`_preview_data.json`（"建议"→"必备"） |
| `buildPlanPrompt`（L368-374） | "可根据需求补充其他文件（如 `static/js/main.js`、`_articlePage.html`），但文件总数控制在 10 个以内" | "必备 8 文件（同上）+ 可选 `static/js/`（**有交互脚本才建该目录**，脚本放 `static/js/main.js`，没有交互脚本则不建目录）；文件总数控制在 10 个以内"（决议 D2 语义同步进提示词） |
| `BASE_SYSTEM_PROMPT` 规范目录树（L1011-1030） | `_articlePage.html` 标"可选"、`_preview_data.json` 标"建议生成" | 两者均改"必备"；`js/main.js` 行注明"可选——有交互脚本时才生成"（L1028） |

**6.2 接入 R4 合规校验 + error 级问题进修复轮（`runBatchPipeline`）：**

```java
// 插入点：writeToFile 落盘循环结束后、checkRenderedFiles 之前（R4 调用点表"B 直写 HTML"行）
Report compliance = templateComplianceChecker.check(workDir, Link.BATCH_HTML, false,
        json -> sendEvent(channel, SSE_EVENT_MESSAGE, json));
// 链路 B 特有：error 级问题（缺页/纯静态页/缺 layout）触发【既有】修复轮，不新建循环
if (compliance.hasErrors() && fixRound < MAX_COMPLIANCE_FIX) {
    // 把 Report.issues 序列化成问题清单文本，注入下一轮 buildGenPrompt 的上下文
    // （复用既有 render-fix 的"报错清单回喂 AI"机制，L1292-1359 同款）
    pendingComplianceIssues = compliance;   // 下一轮文件生成前拼进 prompt
}
```

- 修复轮**上限 1 轮**（`MAX_COMPLIANCE_FIX = 1`）：合规修复是兜底，防止与既有 render-fix 循环（`MAX_RENDER_FIX_ATTEMPTS`）叠加导致轮次膨胀/耗时失控；1 轮后仍有 error 则播报待处理清单，流程照常完成（生成不失败）；
- 修复轮的对象：仅 `missing-base-page`（checker 已自动补齐，通常无需回喂）与 `plain-html-page` / `missing-layout`（需 AI 重写文件）——前两项 checker 已 fixable 处理，实际回喂的主要是纯静态页/缺 layout 两类；
- **C 链路（调整型）不触发修复轮**（readOnly=true，只播报）——正式模板目录的用户手改不自动覆盖（风险表 R-3 已定：只校验播报）。

**6.3 不做的事**：不评估下线（D3）；不改 `hasMissingPlanFiles` 分流逻辑（L1052-1056 原样）；不改 `gen-mode` 默认值（维持 `component`）。

---

## 7. 风险与未验证项（如实声明）

| # | 风险/未验证项 | 影响 | 处置 |
|---|---|---|---|
| R-1 | navbar 组件 `sticky.ftl` 对 **8 个以上菜单项**的渲染表现**未验证**（只读了 L22-92） | R5 菜单上限设为 8 项，超出截断；若 navbar 在 6+ 项时已换行挤压，上限需下调 | 实施 R5 前用 8 项菜单数据实测 navbar 渲染；子菜单/children 结构支持同样未验证，**R5 已不做折叠，此风险只剩"项数上限取值"** |
| R-2 | `menuTag` 后端指令实现位置**未定位到**（本次只确认了 `articlePageTag` 在 `cms/.../ArticlePageDirective.java`；`menuTag` 应在 `cms/directive/` 同类，未逐一打开） | 低——R3/R5 只依赖菜单数据来自 `_preview_data.json`，不依赖指令实现细节 | 实施前确认 `MenuDirective`（或同名）的 data 结构（menuName/url/children/target） |
| R-3 | 链路 C（编辑正式模板）的 workDir 是正式模板目录，合规检查若自动修复会覆盖用户手改 | 中 | **已定**：链路 C 接入 R4 时 `readOnly=true`，只校验播报、不写文件（§6-R4 调用点表）；正式目录的结构补齐仍由用户手动或重新生成完成 |
| R-4 | `DesignPagePlanner` 的 suffix 命名规则（`fastcmsPageKey()` 生成逻辑）**未精读** | 中——R5 映射表新增分支的 pageKey 前缀若与规划器实际产出不一致，菜单指向不存在的页面 | 实施 R5 前先精读 `DesignPagePlanner.fastcmsPageKey()` 与 suffix 分配规则，映射表前缀以其为准 |
| R-5 | R1 改 `article_list.ftl` 包资产影响**所有后续新生成**模板；正在进行的 design 会话在渲染中途升级代码会导致新旧产物混合（新 `_layout.html` + 旧内联分页） | 中 | R1 与 R4 同批发布（R4 的 `missing-article-page` 对"已有内联分页"判合规等价态，不会把旧产物判成缺失乱补）；发布说明注明"升级后重新生成一次可对齐产物形态" |
| R-6 | `contentSkeleton` 目前是 `PageSpecRenderer` 私有（L965 `private String contentSkeleton(String pageKey)`），R4 的 `missing-base-page` 自动补页需复用同资产 | 低 | 实施时把 `contentSkeleton(pageKey)` 提为 public static 或下沉公共类（纯可见性调整，不改逻辑），并注释"仅供合规兜底补页" |
| R-7 | 本方案不含"长文正文按页切分"（正文分页）能力；`article.html` 仍是 `article.contentHtml` 整段输出 | 低（D1 已确认按宏文件方向） | 若未来需要正文分页，属 cms 框架层新指令（仿 `articlePageTag` 的 `BasePaginationDirective` 加 content 维度），单独立项 |
| R-8 | **漏盘点的第 5 条链路**：`LegacyStyleUpgrader`（旧模板样式焕新）会把 `_layout.html` 置于 AI 重写首位（L2556），其余 `_` 前缀文件（含 `_articlePage.html`）虽被排除（L2551），但 R1 上线后新生成模板的 `_layout.html` 顶层 `<#include "_articlePage.html">` 行可能在 AI 重写时丢失 → 页面 `<@layout._articlePage/>` 断链（unknown macro）。R4 调用点表（4 个）未覆盖该链路 | 中（R1 上线后新生成模板再被焕新时触发） | 焕新链路在改写 `_layout.html` 落盘后，后处理补插 include 行（若缺失）：检测既有含 `<#include "_articlePage.html">` 的布局被改写后丢失该行，则在 `<#macro page` 之前补插；或焕新链路接入 R4 校验复用 `missing-article-page` 的补插修复。实施 R1 时顺带验证 |

---

## 8. 涉及文件清单（改动面）

| 文件 | 改动 | 关联项 |
|---|---|---|
| `starters/ai-starter/.../ai/template/compliance/TemplateComplianceChecker.java` | **新增**（含 `Sink` 接口、9 项检查、自动修复） | R4 |
| `starters/ai-starter/.../ai/component/PageSpecRenderer.java` | `render()` 写文件清单加 `writeArticlePageHtml` 调用；`writeLayout` 宏体加 `<#include "…">` 行 + `migrateScripts` 加 `extractExternalScripts` 调用；`contentSkeleton` 可见性调整（R-6） | R1/R2 |
| `starters/ai-starter/.../resources/components/tw/pages/article_list.ftl` | 内联分页块（L39-65）→ `<@layout._articlePage/>` | R1 |
| `starters/ai-starter/.../ai/template/design/MockupConverter.java` | `addNavFooterSection` custom nav 菜单替换/告警（R3）；`buildSiteContent` 映射表化 + suffix 页兜底（R5）；`persistProducts` 收尾菜单扫描告警 | R3/R5 |
| `starters/ai-starter/.../ai/template/design/MockupOrchestrator.java` | `runConverting` L481-483 后插入 R4 校验调用 + 修复文件补注册 | R4 |
| `starters/ai-starter/.../ai/service/impl/AiTemplateGenServiceImpl.java` | `runComponentPipeline`（持久化循环前，~L2816）、`runBatchPipeline`（落盘循环后、`checkRenderedFiles` 前）两处插入 R4 校验调用；链路 B 修复轮注入（R6-2）；链路 C `readOnly=true` | R4/R6 |
| `starters/ai-starter/.../ai/template/TemplateGenPromptBuilder.java` | 规范目录树（L1011-1030）`_articlePage.html`/`_preview_data.json` 改必备、`js/main.js` 注明按需；`buildPlanPrompt`/`buildGenPrompt` 文件清单同步（R6-1 表） | R6 |
| `starters/ai-starter/.../ai/template/AiTemplateConstants.java` | 新增 `FILE_ARTICLE_PAGE = "_articlePage.html"`（当前 L109-127 无此常量）+ `ARTICLE_PAGE_HTML` 模板常量（R1 模板与 checker 共用单一源） | R1/R4 |

**不改动**：`cms/` 核心指令（`articlePageTag`/`menuTag` 等）、预览 mock（`AiTemplatePreviewMockSupport`，`articlePageTag` mock 已存在 L437）、前端（SSE 事件格式不变，合规播报走既有 `message` 事件）、`ImportService` ingest 逻辑、`DesignPagePlanner` 规划逻辑（R5 只消费其产物，不改规划）。

---

## 9. 实施顺序与工作量

| 批次 | 内容 | 工作量 | 说明 |
|---|---|---|---|
| 第 1 批（先行） | R4 `TemplateComplianceChecker`（9 项检查 + 自动修复 + 4 个调用点接入）+ R1 `_articlePage.html`（含 FreeMarker 行为验证点 10 分钟） | 1.5~2 人日 | R4 是其余项的兜底底座；R1 与 R4 的 `missing-article-page` 检查互相配套，必须同批；R1 先跑渲染校验验证 C-2 宏作用域，失败切 C-1 |
| 第 2 批 | R3 custom nav 菜单（先完成 R-1 的 8 项菜单渲染实测）+ R5 菜单信息架构（先精读 `DesignPagePlanner.fastcmsPageKey()`，R-4） | 1~1.5 人日 | 依赖第 1 批的合规播报通道（`no-menu-directive` 告警复用）；R5 映射表前缀以规划器实际产物为准 |
| 第 3 批 | R2 `migrateScripts` 大脚本外置（阈值 2KB）+ R6 提示词契约升级 + 链路 B 合规修复轮（上限 1 轮） | 0.5~1 人日 | 独立小改；R6 提示词改动为纯文本，可最先提交 |
| 测试 | 每条链路各跑一次真实生成（pipeline / 上传单页 HTML / 上传多页 HTML zip / 链路 B `gen-mode=html` 回退 / 链路 C 编辑正式模板只读校验），按 §10 验收 | 0.5~1 人日 | 含降级场景（构造 nav 映射失败验证 R3 告警；构造 9 项菜单验证 R5 截断；构造 AI 漏文件验证 R4 自动补齐） |

**合计约 3.5~5.5 人日**（含测试）。每批可独立提交独立回滚；第 3 批的 R6 提示词改动无依赖，可提前。

---

## 10. 验收清单

**链路 A-pipeline（AI 从需求生成）：**
- [ ] 产物根目录含 4 基础页 + `_layout.html` + `_template.properties`（7 字段）+ `_preview_data.json`（menus 非空）+ `_pagespec.json` + `_articlePage.html`（R1 后）
- [ ] `_layout.html` 含顶层 `<#include "_articlePage.html">` 与 `<#macro page`
- [ ] 任一基础页含 `<#import "_layout.html"`；`_layout.html`/`_components/*.ftl` 含 `menuTag`
- [ ] `static/css/` 含 `pack-*.css` + `tokens.css` + `site.css`；**不生成 `static/js/`**（决议 D2：无 js 不建目录）
- [ ] 合规播报 SSE message 出现且全项通过（含 `missing-article-page`/`empty-js-dir` 无告警）

**链路 A-design/import（上传 HTML）：**
- [ ] 上传单页 HTML：产物含 4 基础页 + 菜单至少"首页"；nav 映射 navbar 时无 `no-menu-directive` 告警
- [ ] 上传多页 HTML（含非固定名页面如 products.html/team.html/cases.html）：菜单项覆盖规划器已分配 suffix 的页面（R5 后），各 suffix 页从菜单可达；9 项以上站点菜单截断为 8 项且 `convert-report.json` 记 `menu_overflow`
- [ ] 构造 nav 映射失败（低置信度）：SSE 出现菜单告警文案，`convert-report.json` 记 `no-menu-directive`（R3 后）
- [ ] 设计稿含 >2KB 脚本：产物含 `static/js/imported.js`，`_layout.html` 引用 `${ctx()}/js/imported.js` 而非内联（R2 后）；脚本 ≤2KB 时仍内联、不建 `static/js`
- [ ] `design/` 中间态不被注册进模板产物（`persistProducts` 只注册根目录 + `static/` + `_components/`）

**链路 B（`gen-mode=html` 回退，决议 D3 保留）：**
- [ ] 提示词文件清单含 `_articlePage.html` 且标注必备（R6-1 后）；`buildPlanPrompt` 含"有交互脚本才建 `static/js`"措辞
- [ ] 合规校验触发；AI 漏生成 `_template.properties`/`_preview_data.json` 时自动补齐并播报（R4 后）
- [ ] AI 产出纯静态 HTML 页（无 `<#`）：触发 `plain-html-page` error 播报 + 1 轮合规修复（问题清单回喂），修复后渲染校验通过；修复仍失败则播报待处理清单、流程照常完成（生成不失败）
- [ ] `hasMissingPlanFiles` 旧 plan 会话分流逻辑不变（回归：旧会话继续走链路 B 正常完成）

**链路 C（编辑正式模板，只读校验）：**
- [ ] 会话 workDir 为正式模板目录：合规检查**只播报不写文件**（构造缺 `_preview_data.json` 场景确认目录文件 mtime 不变）
- [ ] 播报文案区分"待人工处理"（正式目录不回改）

**回归：**
- [ ] 存量已应用模板（含旧内联分页的 `article_list.html`）打开/预览/编辑无异常（R4 对存量会话 workDir 的 `missing-article-page` 判内联版为合规等价态，不乱补文件）
- [ ] 预览 mock 分页条（`articlePageTag`）在含/不含 `_articlePage.html` 两种模板下均正常渲染（mock 已就绪 `AiTemplatePreviewMockSupport.java:437`）
- [ ] C-2 宏作用域：`<#import "_layout.html" as layout>` 后 `<@layout._articlePage/>` 渲染通过（`checkRenderedFiles` 实证）；standalone 形态页同样通过
- [ ] 合规检查自身异常（IO 失败）不阻断生成：构造不可写目录场景，生成流程照常完成、播报降级为待处理

---

## 附：本文引用的全部代码证据索引

| 论断 | 证据位置 |
|---|---|
| 规范目录树（含 `_articlePage.html` 可选、`static/images` 复数） | `TemplateGenPromptBuilder.java:1011-1030` |
| `_template.properties` 7 字段与 id 保护 | `PageSpecRenderer.java:1019-1063` |
| `_preview_data.json` 无条件生成 | `PageSpecRenderer.java:1065-1114` |
| 4 基础页无条件生成 | `PageSpecRenderer.java:266-279` |
| 每页 freemarker 包裹硬编码 | `PageSpecRenderer.java:885-892` |
| `render()` 写文件清单 | `PageSpecRenderer.java:193-208` |
| 菜单 `<@menuTag>` 硬编码于 navbar 组件 | `components/tw/navbar/variants/sticky.ftl:45,66`、`navbar/component.json`（cmsBindings:["menu"]） |
| design/import 最终调渲染器 | `MockupConverter.java:806` |
| custom nav 物化原样 HTML（无 menuTag） | `MockupConverter.java:928-946`（`adaptCustomHtml` L941-944） |
| 菜单提取仅 4 固定页面名 | `MockupConverter.java:1186-1226`（switch L1195-1216） |
| design 中间态 `design/*.html` | `MockupConverter.java:371-373`、`ImportService.java:188,228` |
| 分流条件（gen-mode 默认 component、旧 plan 落链路 B） | `AiTemplateGenServiceImpl.java:306-307,881,1039-1056` |
| yml 未显式配置 gen-mode | `web/src/main/resources/application.yml:53-62`、`application-prod.yml`（grep 无 gen-mode） |
| 链路 B 仅渲染校验、无结构校验 | `AiTemplateGenServiceImpl.java:1265-1270,1280-1359` |
| `articlePageTag` = 列表分页指令 | `cms/.../ArticlePageDirective.java:56-59`（`@Component("articlePageTag")`、`extends BasePaginationDirective`、`PAGE_ATTR="articleVoPage"`） |
| 预览 mock articlePageTag 已就绪 | `AiTemplatePreviewMockSupport.java:437` |
| 渲染引擎不产 `_articlePage.html` / `static/js` | `PageSpecRenderer.java` 全文 grep 0 命中 |
| 文章正文一次性输出（无内容分页） | `components/tw/pages/article.ftl:16` |
| 常量 `FILE_*`/`DIR_STATIC_*`（无 `FILE_ARTICLE_PAGE`） | `AiTemplateConstants.java:109-127` |
| 前端上传 HTML → import 口径归一 | `CreateTemplateDialog.vue:99-100`、`AiTemplateGenServiceImpl.java:518-519` |

