# 活体验证探针（LiveProbe）

真实端点活体验证工具。**单元测试全绿 ≠ 功能可用** —— 这个探针就是用来补上那一段的。

## 为什么需要它

2026-10-28 的教训：B2 补丁协议写完、11 例单测全绿、构建成功，但拿真实端点 +
真实 CRLF 文件一跑，补丁 **100% 失效**（模型输出 LF、磁盘文件 CRLF → 逐字匹配必失败）。
单测里所有人写的都是 LF 文件，**这个 bug 靠读代码永远发现不了**。

## 关键设计：复用生产同款代码路径

探针**只把 HTTP 层换成裸调用**，其余全部用生产类：

| 环节 | 复用的生产类 |
| --- | --- |
| 提示词 | `TemplateGenPromptBuilder`（构造传 `new SkillRegistry(List.of())`，技能缺失回退内置规范副本） |
| 响应解析 | `AiTemplateResponseParser` |
| 补丁落盘 | `FilePatchApplier` |
| 调用参数 | `AiModelConfigServiceImpl.baseOptionsBuilder` + `AiReasoningSupport.apply`（与 `doBuildChatModel` 完全一致） |

这样观测到的行为就是生产行为，而不是「另写一套简化版」的行为。

## 编译与运行

```bash
cd F:/wangjun/ideaProjects/fastcms

# 1) 导出类路径（离线模式下 dependency 简写找不到插件，要写全坐标）
cd starters/ai-starter
export JAVA_HOME=C:/Users/PC1/.jdks/ms-21.0.9
M=$HOME/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn
$M org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
  -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
cd ../..

# 2) 编译主代码 + 探针
$M -o -q -Dmaven.repo.local=F:/maven/repository -pl starters/ai-starter compile
CP="starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)"
mkdir -p .workbuddy/probe/out
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/LiveProbe.java

# 3) 跑（b2 / b4 / b3 / all）
CP2=".workbuddy/probe/out;$CP"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$CP2" probe.LiveProbe b2
```

## ⚠️ 坑

- **输出必须 `| tr -d '\000'`**：GBK 中文会让后续 `grep` 报 `Binary file (standard input) matches`。
- **`JAVA_HOME` 必须用 Windows 风格路径**（`C:/Users/...`），Git Bash 的 `/c/...` 会报
  `JAVA_HOME environment variable is not defined correctly`。
- **类路径分隔符是 `;`**（Windows），不是 `:`。

## 场景

| 参数 | 验证什么 | 2026-10-28 实测结果 |
| --- | --- | --- |
| `b2` | 补丁协议是否被模型遵守、补丁能否真正落盘 | 输出 532 字符 vs 全文 22681（**降 42 倍**），2/2 成功、失败 0 |
| `b4` | 骨架轮能否稳定输出合法 JSON | JSON 可解析、6 个 section、id 规范、546 token / 12.9s |
| `b3` | 结构摘要是否丢信息（漏栏目风险） | 摘要 2922 字符 = 原文 12.9%，导航/标题/链接完整，**未漏栏目** |

---

# PatchChannelsProbe（2026-10-29 新增）

验证**补丁协议推广到 4 条修改型链路**（微调 / 锚点修复 / 审计修复 / 深度焕新）。

```bash
CP="starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/PatchChannelsProbe.java

# 用法：<场景> [maxTokens] [思考档位：off|low|medium|xhigh]
CP2=".workbuddy/probe/out;$CP"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP2" probe.PatchChannelsProbe refine 8192 low
```

| 场景 | 验证什么 | 实测结果 |
| --- | --- | --- |
| `refine` | 微调轮补丁（含 **CSS 单行补丁**） | 输出 382 字符 vs 全文 22681（**降 59 倍**），2/2 成功 |
| `anchor` | 锚点修复轮补丁 | 输出 184 字符，1/1 成功 |
| `audit` | 审计修复轮补丁 | 输出 524 字符，2/2 成功 |
| `upgrade` | 深度焕新轮（整页重做，files 通道） | 8192 → 撞顶白跑；32768 → 产出完整文件 |
| `upgradeStream` | **深度焕新轮（流式，与生产一致）** | 小页面：54.6s / `finish_reason=STOP` / 思考 2233 / 正文 1869 字符 → 链路正常 |
| `trunc` | **多文件批次截断抢救** | out=8192 撞顶、输出 24465 字符、JSON 截断 → **抢救回 1 个完整文件** |

> 2026-10-29 复跑（CSS/JS 分离措辞调整 + 思考预算约束接入后）：
> refine 382 字符 / 2 补丁全成功 / 28.7s，anchor 184 字符 / 1 补丁 / 13.5s，
> audit 524 字符 / 2 补丁 / 16.8s —— **补丁协议未被新改动破坏**。

> ⚠️ **深度焕新轮不要用非流式跑**：非流式（`.call()`）要等模型全部生成完才返回，
> 32768 预算下实测跑了 **55 分钟仍未返回**（理论上限约 11 分钟）。
> 改用流式后立刻看到真相：正文已正常写出 16173 字符，只是尚未收尾 —— 不是思考吃满，
> 而是长输出时端点吞吐显著下降（300 秒约 54 字符/秒）。生产本来就是流式。

> ⚠️ **大页面会被 10 分钟 HTTP 读超时打断**：22KB 页面 + 16384 预算 → OkHttp
> `InterruptedIOException: timeout`。小页面复跑完全正常 → 是端点吞吐问题，**不是思考吃满**。


## ⚠️ 本轮新增的坑

- **思考会吃满 maxTokens 导致正文 0 字符**：`low` 档下同一提示词连跑三次 8192 上限，
  一次输出 17397 字符、两次**正文 0 字符**。`off` 档（关思考）才稳定拿到全部预算。
  → 推论：**输出上限必须预留思考空间**（本项目用 +8192）。
- **截断抢救在单文件批次下救不回**：唯一那个元素本身就是残缺的（按设计必须丢弃）。
  → 真正的根治手段是**动态上限**，抢救只是兜底（多文件批次才有价值）。
- **写探针文件前先 `Files.createDirectories`**：写到 `static/css/base.css` 会
  `NoSuchFileException`（父目录不存在）。
- **Java 文本块里的 JSON 反斜杠**：`{"path":"./a\\\\b"}` 才是合法的 JSON 转义；
  写成 `\c` 会让 Jackson 解析失败（单测里踩过）。

---

# AssetSeparationProbe（2026-10-29 新增）

验证 **CSS/JS 分离规范**（B7）的三层机制：提示词 → 合规校验 → 确定性抽取。

```bash
CP="starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/AssetSeparationProbe.java

# 用法：<场景> [maxTokens] [思考档位：off|low|medium|xhigh]
CP2=".workbuddy/probe/out;$CP"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP2" probe.AssetSeparationProbe generate 16384 low
```

| 场景 | 验证什么 | 实测结果（2026-10-29） |
| --- | --- | --- |
| `generate` | AI 是否**主动**把 CSS/JS 写成独立文件 | **0 内联违规**，HTML 含 `<link rel="stylesheet">` + `<script src=`（两次独立跑一致） |
| `induce` | 诱导需求（「样式都写在这个 HTML 里」）下 AI 是否仍守规矩 | **仍主动分离**，0 内联违规 |
| `manual` | 人工写的内联 CSS 会不会被 AI 顺手拆掉 / 被校验器判违规 | AI 用**单行补丁**精确改色（`color: #fff` → `#F59E0B`），**未拆块未重写整页**；校验器 `已自动修复=[]`、`inline-assets=[]`、人工 CSS 原样保留 |
| `extract` | 兜底层能否确定性抽取内联 CSS/JS | **HTML 9794 → 275 字符（降 96%）**，抽出 `inline-index.css` / `inline-index.js`，HTML 就地改写为引用 |

## ⚠️ 本探针用到的口径

- **阈值 = 2000 字符 / 60 行**（`InlineAssetExtractor.INLINE_MAX_CHARS` / `INLINE_MAX_LINES`）。
  取值依据：扫全部存量模板的内联块（共 7 个，最大 1290 字符 / 44 行），此阈值**零误伤**。
  初版行数阈值取 20 会误判存量 `xjd2022/article_pics.html` 的 44 行 Swiper 块 → 已改为 60。
- **分级**：AI 生成产物（`readOnly=false`）超阈值 → 确定性抽取；
  人工产物 / 正式模板目录（`readOnly=true`）→ 只提示（WARNING），**绝不改内容、绝不触发 AI 重写**。
- **探针构造的内联块必须超过阈值**：初版用 342 字符的小块 → 落在阈值内 → 抽取不触发（不是 bug，是阈值设计）。

---

# ThinkingBudgetProbe（2026-10-29 新增）

验证**「长思考吃满输出预算 → 正文 0 token」**的故障复现、判定可靠性与兜底有效性。

```bash
CP="starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/ThinkingBudgetProbe.java

# 用法：<场景> [maxTokens]   场景 = flood | floodOff | hint | easy | stream | all
CP2=".workbuddy/probe/out;$CP"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP2" probe.ThinkingBudgetProbe all 512
```

| 场景 | 验证内容 | 实测结果（2026-10-29，512 预算） |
| --- | --- | --- |
| `flood` | 故障可复现 + 三信号判定可靠 | `finish_reason=LENGTH`、正文 **0 字符**、思考 2067 字符 → 判定 **true** |
| `floodOff` | **关思考重试**（保留原预算，不翻倍） | 思考 **0**、正文 **2497 字符** |
| `floodOff` 对照组 | **翻倍上限**（旧解法，已废弃） | 1024 预算 → 思考 **4075 字符**（更长！）正文仍 **0** → 翻倍治不了思考吃满 |
| `hint` | 提示词「思考预算约束」的效果 | 思考 2120 → 1585 字符（-25%），**正文 0 → 361 字符** |
| `easy` | 判定**不误杀**正常响应 | `finish_reason=STOP`、正文 70 字符 → 判定 **false** |
| `stream` | **流式**下 `finish_reason` 能否采集（生产走流式） | 采集成功、判定 **true** → 生产兜底确实能触发 |

## ⚠️ 本探针用到的口径

- **options 组装与生产一致**：`AiModelConfigServiceImpl.baseOptionsBuilder` +
  `AiReasoningSupport.apply`（关思考走 `AiThinkingBudget.forceOff`），
  与 `AiTemplateGenServiceImpl.buildPipelineOptions` / `buildOptionsWithThinkingOff` 同一口径。
- **流式下 `reasoningContent` 是累积快照**（每帧带全部历史思考），直接累加会平方级膨胀
  （实测原始累计 529250 字符，真实思考量 2126 字符，**差 249 倍**）。
  **必须用生产同款 `ReasoningStreamAccumulator` 做差分**，否则观测数据失真
  （探针初版就是直接累加的，误报 529764，已修）。
- **该差分同时保证了生产的 256KB 思考保险丝不会误杀**：差分后真实思考量 2126 字符，
  距 256KB 阈值差 123 倍。
- **端点返回的 `finish_reason` 是大写 `LENGTH`** → 判定用 `equalsIgnoreCase`。
- **端点档位实际只放行 `low` / `medium`**（网关只放行 none/low/medium/high，vLLM 拒 high/none/minimal），
  探针里 `xhigh` 会被 `AiReasoningSupport.normalize` 归一到 `medium`。

---

# ThinkingOffFallbackProbe（2026-10-29 新增，§6.9 配套）

验证 **S2 设计段** / **S9 文章段** 这两条**不走 `callModelRound`** 的链路，
新增的「思考预算约束 + 三信号判定 + 关思考兜底」三层防线在真实端点上确实有效。

```bash
CP="starters/ai-starter/target/classes;service/target/classes;$(cat starters/ai-starter/target/cp.txt)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/ThinkingOffFallbackProbe.java

# 用法：<场景> [maxTokens]   场景 = design | article | hint | all
CP2=".workbuddy/probe/out;$CP"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP2" probe.ThinkingOffFallbackProbe design 512
```

| 场景 | 实测结果（2026-10-29，512 预算 / low 档） |
| --- | --- |
| `design`（S2 形态） | 首轮：思考 **86 字符**、正文 1213 字符 → 判定 false（不误杀）；兜底轮：思考 **0**、正文 **1775 字符** |
| `article`（S9 形态） | 首轮：思考 **230 字符**、正文 700 字符 → 判定 false；兜底轮：思考 **0**、正文 **932 字符** |
| `hint` | 提示词约束：思考 380 → **215 字符（-43%）**，正文 5027 → 5241 字符 |

## ⚠️ 本探针与 `ThinkingBudgetProbe` 的差别

- **探针复刻的是生产兜底链路的完整形状**：首轮（注入约束）→ 三信号判定 → 兜底轮（关思考）。
  即使首轮没吃满（约束已压住思考），也仍跑一次兜底轮做**对照**——验证兜底本身有效。
- **兜底轮直接调用生产的公共方法** `AiModelConfigServiceImpl.thinkingOffOptions(config, origin)`，
  不手写 `chat_template_kwargs` —— 验证的是**生产代码真正发出的请求体**。
- **口径与生产一致**：流式差分累加器（`ReasoningStreamAccumulator`）+ `finish_reason` 采集
  + `AiThinkingBudget.isThinkingFlooded` 三信号判定。
- **保留原上限不翻倍**：兜底轮的 `maxTokens` 与首轮相同（512 → 512），
  这是**故意的**——翻倍治不了思考吃满（见 `ThinkingBudgetProbe` 的对照组）。

---

# SpecRuntimeProbe（2026-10-29 新增，规范副本漂移核查配套）

**不调模型**。模拟 pf4j 插件类加载器，按 `ClasspathSkillProvider` 的方式解析
`classpath*:skills/*/SKILL.md`，用来证明**运行时到底加载的是哪一份 SKILL.md**。

```bash
CP="starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -proc:none -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/SpecRuntimeProbe.java

# 用法：<插件 jar 路径> <cp.txt 路径>
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 \
  -cp ".workbuddy/probe/out;$CP" SpecRuntimeProbe \
  "C:/Users/PC1/fastcms/plugins/template-skills-plugin-1.0.0-SNAPSHOT.jar" \
  "starters/ai-starter/target/cp.txt"
```

## 为什么需要它

`ClasspathSkillProvider` 的资源解析**显式绑定插件类加载器**
（`PathMatchingResourcePatternResolver(getClass().getClassLoader())`），
所以**主程序 classpath 里的副本不生效，只有插件 jar 里的生效**。
只看源码目录会得出错误结论，必须按类加载器实际解析一次。

## 实测（2026-10-29）

| jar | 解析结果 | 章节 |
| --- | --- | --- |
| `~/fastcms/plugins/…jar`（Sep 24 打包，运行时实际加载） | 11463 字符 | **缺** §1.0 CSS/JS 分离、§1.1 栏目专属页、§1.2 _components |
| `plugins/template-skills-plugin/target/…jar`（Oct 29 16:44 打包） | 13132 字符 | 三节齐全 |

两个 jar 的 `Plugin-Id` 都是 `template-skills-plugin`，pf4j 按 id 识别 →
**只装其一即可生效**，重装需先卸载或直接覆盖 jar 文件。


---

# SkillBootVerify（2026-10-29 新增，孤儿技能治理配套）

**不调模型**。用真实插件 jar 模拟 pf4j 的插件类加载器，走完整链路验证：
`插件类加载器 → ClasspathSkillProvider → SkillRegistry → RequiredSkillChecker`。

```bash
CP="starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -proc:none -cp "$CP" -d .workbuddy/probe/out \
  doc/wiki/tools/SkillBootVerify.java

# 用法：<插件目录> <cp.txt 路径>
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp ".workbuddy/probe/out;$CP" SkillBootVerify \
  "$USERPROFILE/fastcms/plugins" "starters/ai-starter/target/cp.txt"
```

## 验证什么

| 步骤 | 检查项 |
| --- | --- |
| 1 | 技能清单（id / 来源 / 版本 / 名称） |
| 2 | 启动自检 `RequiredSkills.checkAll` 的真实输出 |
| 3 | `requireContent` 实际取到的正文字符数 |
| 4 | template-spec 正文是否真含 §1.0 CSS/JS 分离、§1.1 栏目专属页、§1.2 _components |
| 5 | **反向验证**：空注册表下 `requireContent` 是否明确抛异常（而非静默返回 null） |
| 6 | **反向验证**：正文漂移（模拟旧版 jar 缺 §1.0/§1.1/§1.2）时是否明确抛异常并列出缺失章节 |
| 7 | 真实插件 jar 的 manifest 是否写入了 `Plugin-Version` / `Plugin-Fastcms-Version` |
| 8 | 启动期自检对漂移场景的识别（应打出正文漂移 ERROR） |

## 实测（2026-10-29）

| 项 | 结果 |
| --- | --- |
| 插件加载 | template-skills-plugin / design-skills-plugin 各 1 个技能 |
| 技能注册表 | file 源 0 个、插件源 2 个 |
| 启动自检 | 通过：2 个硬依赖技能全部就绪**且正文满足契约** |
| 正文长度 | design-brief 1287 字符、template-spec 12955 字符 |
| §1.0 / §1.1 / §1.2 | 全部 `true`（此前已安装 jar 缺这三节） |
| 缺失时 | 明确抛 `IllegalStateException`，异常信息含用途与修复指引 |
| 正文漂移时 | 明确抛 `IllegalStateException`，列出三个缺失章节 |
| manifest | `Plugin-Version=1.0.0-SNAPSHOT`、`Plugin-Fastcms-Version=1.0.0` |

> **注意**：Windows 控制台默认 GBK，中文输出会乱码；加 `-Dstdout.encoding=UTF-8` 或只看数字/布尔值。

---

## PluginCompatVerify —— 插件版本兼容闸门验证

```bash
# 用法：<插件目录>
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)" \
  -d .workbuddy/probe/out doc/wiki/tools/PluginCompatVerify.java
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 \
  -cp ".workbuddy/probe/out;starters/ai-starter/target/classes;$(cat starters/ai-starter/target/cp.txt)" \
  PluginCompatVerify "$USERPROFILE/fastcms/plugins"
```

## 验证什么

| 步骤 | 检查项 |
| --- | --- |
| 1 | 当前 fastcms 主程序版本能否从 `fastcms-version.txt` 读出（读不到会告警） |
| 2 | 真实插件 jar 的 manifest 解析（走 `FastcmsPluginDescriptorFinder.find` 公开入口）→ 兼容则放行 |
| 3 | **反向验证**：插件声明更高版本（9.9.9）→ 应被拦截并给出修复指引 |
| 4 | **反向验证**：第三方插件未声明版本 → 应放行（不做误伤） |

## 实测（2026-10-29）

| 项 | 结果 |
| --- | --- |
| 主程序版本 | `currentFastcmsVersion()` = `1.0.0-SNAPSHOT`（资源过滤生效） |
| 真实插件 jar | 两个技能插件声明 `1.0.0` → **兼容（放行）** |
| 声明 `9.9.9` | **不兼容（正确拦截）**，原因含「请升级主程序或换用与当前主程序匹配的插件版本」 |
| 未声明版本 | **兼容（放行，正确）** |

> **注意**：pf4j 的 `PluginDescriptorFinder.find(Path)` 是公开入口，
> 而 `createPluginDescriptor(Manifest)` 是 protected —— 外部探针只能走 `find`。

