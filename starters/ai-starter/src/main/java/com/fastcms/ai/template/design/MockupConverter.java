/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * http://www.xjd2020.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template.design;

import com.fastcms.ai.agent.AgentChatExecutor;
import com.fastcms.ai.agent.BuiltinAgents;
import com.fastcms.ai.autoconfigure.FastcmsAiProperties;
import com.fastcms.ai.component.AttachmentImageSearcher;
import com.fastcms.ai.component.ComponentDescriptor;
import com.fastcms.ai.component.ComponentRegistry;
import com.fastcms.ai.component.DesignDirectionLibrary;
import com.fastcms.ai.component.LegacyStyleUpgrader;
import com.fastcms.ai.component.PageSpec;
import com.fastcms.ai.component.PageSpecPage;
import com.fastcms.ai.component.PageSpecRenderer;
import com.fastcms.ai.component.SectionSpec;
import com.fastcms.ai.component.SiteContentSpec;
import com.fastcms.ai.component.TokenEngine;
import com.fastcms.ai.service.IAiTemplateFileService;
import com.fastcms.ai.template.AiTemplateConstants;
import com.fastcms.ai.template.AiTemplatePreviewRenderer;
import com.fastcms.ai.audit.AiUsageRecorder;
import com.fastcms.entity.AiTemplateSession;
import com.fastcms.service.IAiUsageLogService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 转化段编排（设计稿先行模式，见 doc/wiki/ai-template-two-mode-design.md §5）
 *
 * <p><b>五步转化</b>（D6 原则：转化是确定性管线，AI 只回答"这个区块映射到哪个组件"）：</p>
 * <ol>
 *     <li>区块切分（纯代码）：每页按 body 顶层 {@code <section data-block>} 切块 + nav/#footer
 *     公共块识别 + {@code :root --c-*} tokens 提取 + 交互脚本提取</li>
 *     <li>组件映射（唯一 AI 决策点）：SectionUnit 摘要分批（≤5）问 designer 智能体，
 *     confidence &lt; 阈值 / 组件不存在 / appliesTo 不符 → 强制 custom；mappingCache 断点续传</li>
 *     <li>模板装配（纯代码）：<b>构建 PageSpec 复用 {@link PageSpecRenderer}</b>——产物与管线模式
 *     100% 同构（§5.3），applyTemplate 零改动复用；CSS 后处理 = tokens.css 追加设计变量 +
 *     upgrade.css（{@link LegacyStyleUpgrader#generateUpgradeCss()} 复用，覆盖 custom 区块任意
 *     Tailwind 类）+ _layout.html 注入</li>
 *     <li>锚点/脚本迁移（纯代码）：设计稿交互脚本注入 _layout.html；锚点缺失时注入确定性
 *     防御垫片（detached 元素兜底 getElementById，防交互脚本因组件替换中断）</li>
 *     <li>渲染校验（复用）：{@link AiTemplatePreviewRenderer#checkRenderedFiles}——失败页
 *     确定性降级（组件区块→custom 重渲染，仍失败→整页单 custom），不走 AI 修复</li>
 * </ol>
 *
 * <p><b>降级策略</b>（§5.2，逐级 SSE 播报）：单区块映射失败→custom；单页渲染 2 轮失败→整页单
 * custom；失败页 ≥ 半数→{@code MAJOR_FAILURE}（编排器进 AWAITING_CONFIRM 由用户裁决）。
 * 降级不抛异常——返回事实，状态推进是编排器（S4）职责。</p>
 *
 * <p><b>产物同构的关键决策</b>：不手拼 FTL（文档 §5.1 Step 3 的伪代码是抽象口径），而是构建
 * PageSpec 走渲染引擎——layout/组件源码/静态资产/元数据文件全部由同一生成器产出，
 * 结构漂移风险为零；custom 区块经 {@code tw:custom-html} 逃生舱物化（data.html 原样保留，
 * img 路径重写为 {@code ${ctx()}/images/design/}、页面链接重写为 CMS 语义路径）。</p>
 *
 * <p><b>与管线模式的隔离</b>：不引用 AiTemplateGenServiceImpl 任何成员；AI 调用走
 * {@link AgentChatExecutor}；SSE 经 {@link DesignSseSink}；plan.json 读写归编排器，
 * 本类只收/返 mappingCache（断点续传数据）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
@Component
public class MockupConverter {

    private static final Logger log = LoggerFactory.getLogger(MockupConverter.class);

    /** AI 映射输出的特殊标记：不匹配任何组件，区块原样保留 */
    public static final String MAP_CUSTOM = "custom";
    /** AI 映射输出的特殊标记：区块替换为该页正文骨架（仅内容页区块可用） */
    public static final String MAP_CONTENT_BODY = "content-body";
    /** custom-html 逃生舱组件全名 */
    static final String CUSTOM_HTML_COMPONENT = "tw:custom-html";
    /** 正文占位虚拟组件全名（PageSpec.CONTENT_BODY_SECTION） */
    static final String CONTENT_BODY_COMPONENT = PageSpec.CONTENT_BODY_SECTION;

    /** 映射批大小（§5.1 Step 2：每批 ≤5 个区块摘要） */
    private static final int MAPPING_BATCH_SIZE = 5;
    /** 单批映射重试上限（JSON 不合法/组件不存在 → 携错误重问，仍失败全批降 custom） */
    private static final int MAX_MAPPING_ROUNDS = 2;
    /** 渲染降级轮上限（对齐管线 MAX_RENDER_FIX_ATTEMPTS=2：轮1 组件区块全降 custom，轮2 整页单 custom） */
    private static final int MAX_RENDER_DEGRADE_ROUNDS = 2;
    /** 映射调用输出上限（JSON 数组，远小于设计稿输出） */
    private static final int MAPPING_MAX_TOKENS = 3000;
    /** R2 交互脚本外置阈值：超过 2KB 写 static/js/imported.js，layout 引外链；小脚本仍内联 */
    private static final int EXTERNAL_SCRIPT_THRESHOLD = 2048;
    /**
     * R3 菜单锚文本中英同义词组（匹配第 2 级）：设计稿菜单英文锚文本 ↔ 站点信息架构
     * 中文菜单名的跨语言对应；比较前统一 {@link #normalizeAnchor} 归一化，
     * 锚文本或菜单名包含组内任一关键词即视为同组同义
     */
    private static final List<List<String>> NAV_SYNONYM_GROUPS = List.of(
            List.of("首页", "home", "主页", "main"),
            List.of("关于", "about"),
            List.of("服务", "services", "service"),
            List.of("产品", "products", "product"),
            List.of("新闻", "news"),
            List.of("博客", "blog"),
            List.of("联系", "contact"),
            List.of("案例", "cases", "portfolio", "works"),
            List.of("团队", "team"));
    /**
     * R5 固定规划名 → 信息架构条目映射表（name → 菜单类型 + 归属）：
     * 单页（TYPE_PAGE，进 singlePages）：about/services/contact/products/team；
     * 分类（TYPE_ARTICLE_LIST，进 categories）：news/blog/cases。
     * suffix 一律取规划名裸名（NavItem.suffix 与 {type}_{suffix}.html 路由约定一致）
     */
    private static final Map<String, PlanNavEntry> PLAN_INFO_ARCH = Map.of(
            "about", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_PAGE, false),
            "services", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_PAGE, false),
            "contact", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_PAGE, false),
            "products", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_PAGE, false),
            "team", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_PAGE, false),
            "news", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_ARTICLE_LIST, true),
            "blog", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_ARTICLE_LIST, true),
            "cases", new PlanNavEntry(SiteContentSpec.NavItem.TYPE_ARTICLE_LIST, true));
    /** 顶级菜单上限（与 PageSpecValidator.MAX_TOP_MENUS 同口径，含首页） */
    private static final int MAX_NAV_ITEMS = 8;

    /** R5 规划名信息架构条目：navType=菜单类型，category=true 进分类（否则单页） */
    private record PlanNavEntry(String navType, boolean category) {
    }

    /** :root 变量提取（--c-primary: #2563eb 等） */
    private static final Pattern ROOT_VAR_PATTERN = Pattern.compile(
            "(-{2}[A-Za-z][\\w-]*)\\s*:\\s*([^;{}]+)");
    /** 设计稿内页链接重写目标：design/assets/ 下占位 SVG（复制进模板 static/images/design/） */
    private static final Pattern DESIGN_ASSET_SRC_PATTERN = Pattern.compile(
            "(src=[\"'])design/assets/([\\w.-]+)([\"'])");
    /** 脚本锚点提取：getElementById('x') / $('#x')（垫片注入判定依据） */
    private static final Pattern SCRIPT_ID_ANCHOR_PATTERN = Pattern.compile(
            "getElementById\\(\\s*['\"]([\\w-]+)['\"]\\s*\\)|\\$\\(\\s*['\"]#([\\w-]+)['\"]\\s*\\)");

    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER = new tools.jackson.databind.ObjectMapper();

    private final AgentChatExecutor agentChatExecutor;
    private final ComponentRegistry componentRegistry;
    private final PageSpecRenderer pageSpecRenderer;
    private final AttachmentImageSearcher attachmentImageSearcher;
    private final AiTemplatePreviewRenderer previewRenderer;
    private final LegacyStyleUpgrader styleUpgrader;
    private final TokenEngine tokenEngine;
    private final IAiTemplateFileService fileService;
    private final AiUsageRecorder usageRecorder;
    private final FastcmsAiProperties aiProperties;

    public MockupConverter(AgentChatExecutor agentChatExecutor, ComponentRegistry componentRegistry,
                           PageSpecRenderer pageSpecRenderer, AttachmentImageSearcher attachmentImageSearcher,
                           AiTemplatePreviewRenderer previewRenderer, LegacyStyleUpgrader styleUpgrader,
                           TokenEngine tokenEngine, IAiTemplateFileService fileService,
                           AiUsageRecorder usageRecorder, FastcmsAiProperties aiProperties) {
        this.agentChatExecutor = agentChatExecutor;
        this.componentRegistry = componentRegistry;
        this.pageSpecRenderer = pageSpecRenderer;
        this.attachmentImageSearcher = attachmentImageSearcher;
        this.previewRenderer = previewRenderer;
        this.styleUpgrader = styleUpgrader;
        this.tokenEngine = tokenEngine;
        this.fileService = fileService;
        this.usageRecorder = usageRecorder;
        this.aiProperties = aiProperties;
    }

    // ==================== 输入/输出模型 ====================

    /**
     * 转化段上下文
     *
     * @param session        会话实体（templateName 为产物目录名/注册名）
     * @param workDir        会话工作目录（设计稿在 workDir/design/，产物写 workDir/ 根）
     * @param requirement    站点需求（映射提示词背景段）
     * @param direction      设计方向资产（primaryColor/stylePreset 供 tokens 生成，可空）
     * @param mobileAdaptive 是否移动端适配（透传 PageSpecRenderer）
     * @param pages          设计稿页面规划（DesignPagePlanner 输出）
     * @param mappingCache   既有映射缓存（断点续传：已映射区块不重问 AI；可为 null）
     * @param mappingProgressSink 映射批次进度回调（可空）：每完成一批映射即回传<b>当前全量</b>
     *                            映射快照——编排器即时写回 plan.json（中断重入已映射批次不重问 AI；
     *                            旧实现整段结束才写回，中途停止会丢失已映射批次）
     */
    public record ConvertContext(
            AiTemplateSession session,
            Path workDir,
            String requirement,
            DesignDirectionLibrary.DesignDirectionAsset direction,
            boolean mobileAdaptive,
            List<DesignPagePlanner.PagePlan> pages,
            List<SectionMapping> mappingCache,
            java.util.function.Consumer<List<SectionMapping>> mappingProgressSink) {
    }

    /**
     * 区块映射结果（AI 决策或确定性降级的产物；写回 plan.json.mappingCache 供断点续传）
     *
     * @param page      设计稿页名
     * @param sectionIdx 区块序号（该页 body 顶层 section 顺序，0 起；-1 = nav 公共块，-2 = footer 公共块）
     * @param component 组件全名（tw:hero）/ custom / content-body
     * @param variant   变体 id（custom/content-body 为 null）
     * @param confidence AI 置信度（确定性来源为 1.0）
     * @param reason    映射理由（播报/诊断用）
     * @param source    来源：ai=AI 映射 / cache=断点续传 / fallback=确定性降级
     */
    public record SectionMapping(String page, int sectionIdx, String component,
                                 String variant, double confidence, String reason, String source) {
    }

    /** 单页转化结果状态 */
    public enum PageOutcome { OK, DEGRADED, FAILED }

    /**
     * 单页转化结果
     *
     * @param pageKey fastcms 页面 key（index / page_about / article_list…）
     * @param title   页面中文标题（播报用）
     */
    public record PageResult(String pageKey, String title, PageOutcome outcome, String note) {
    }

    /** 转化整体状态（编排器据此推进状态机：DONE→收尾；MAJOR_FAILURE→AWAITING_CONFIRM） */
    public enum ConvertStatus { DONE, DONE_WITH_DEGRADATION, MAJOR_FAILURE }

    /**
     * 转化段整体结果
     *
     * @param status           整体状态（失败页 ≥ 半数为 MAJOR_FAILURE，§5.2）
     * @param pageResults      逐页结果
     * @param mappings         最终映射全量（含降级 custom 项）——编排器写回 plan.json
     * @param report           结构一致性报告行（§6.2 收尾播报：区块数/tokens/锚点 设计稿 vs 模板）
     * @param writtenFiles     产物文件相对路径清单（编排器做文件表注册时可用；本类已自行注册）
     * @param promptTokens     映射段聚合输入 token
     * @param completionTokens 映射段聚合输出 token
     * @param totalTokens      映射段聚合总 token
     */
    public record ConvertOutcome(ConvertStatus status, List<PageResult> pageResults,
                                 List<SectionMapping> mappings, List<String> report,
                                 List<String> writtenFiles,
                                 long promptTokens, long completionTokens, long totalTokens) {
    }

    /**
     * 设计稿页面解析产物（Step 1 切块结果，单页）
     *
     * @param plan      页面规划
     * @param doc       Jsoup 文档
     * @param nav       body 顶层 nav 元素（可空——审计 V4 保证在场，防御式容忍）
     * @param sections  内容区块（body 顶层 section[data-block]，footer 区块除外）
     * @param footer    footer 区块（#footer 或 data-block 含 footer 的 section，可空）
     */
    private record PageDesign(DesignPagePlanner.PagePlan plan, Document doc,
                              Element nav, List<Element> sections, Element footer) {
    }

    /**
     * 设计稿全站解析产物
     *
     * @param pages        逐页解析（规划顺序）
     * @param rootTokens   :root --c-* 变量（首个非空页提取；custom 区块 var() 引用依据）
     * @param scripts      交互脚本原文（A6 白名单内的全部 inline script，取首个非空页）
     * @param scriptAnchors 脚本引用的 id 锚点（垫片注入判定）
     */
    private record DesignBundle(List<PageDesign> pages, Map<String, String> rootTokens,
                                String scripts, List<String> scriptAnchors) {
    }

    // ==================== 主入口 ====================

    /**
     * 执行五步转化
     *
     * <p>模型调用级异常（网络断/配额超）直接抛出（调用方按 FAILED 处理，产物文件保留）；
     * 客户端断开抛 {@link DesignCancelledException}；区块级/页面级失败<b>不抛异常</b>——
     * 降级继续（§5.2），事实进返回值。</p>
     */
    public ConvertOutcome convert(ConvertContext ctx, DesignSseSink sse) {
        long startTime = System.currentTimeMillis();
        long[] usageAgg = {0L, 0L, 0L};
        // prepare 置于 try 外：装配失败无模型调用，不产生审计记录（与设计段同口径）
        AgentChatExecutor.Prepared prepared = agentChatExecutor.prepare(
                BuiltinAgents.TEMPLATE_DESIGNER_ID, ctx.session().getUserId());

        boolean succeeded = false;
        String errorMessage = null;
        try {
            ConvertOutcome outcome = doConvert(ctx, prepared, sse, usageAgg);
            succeeded = true;
            return outcome;
        } catch (Exception e) {
            errorMessage = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            try {
                if (succeeded) {
                    usageRecorder.record(BuiltinAgents.TEMPLATE_DESIGNER_ID, ctx.session().getUserId(),
                            IAiUsageLogService.Scene.TEMPLATE_DESIGN, ctx.session().getSessionId(),
                            prepared.getModelName(),
                            (int) Math.min(Integer.MAX_VALUE, usageAgg[0]),
                            (int) Math.min(Integer.MAX_VALUE, usageAgg[1]),
                            (int) Math.min(Integer.MAX_VALUE, usageAgg[2]),
                            System.currentTimeMillis() - startTime);
                } else {
                    usageRecorder.recordError(BuiltinAgents.TEMPLATE_DESIGNER_ID, ctx.session().getUserId(),
                            IAiUsageLogService.Scene.TEMPLATE_DESIGN, ctx.session().getSessionId(),
                            prepared.getModelName(), System.currentTimeMillis() - startTime, errorMessage);
                }
            } catch (Exception auditEx) {
                log.warn("转化段用量审计落库失败: sessionId={}", ctx.session().getSessionId(), auditEx);
            }
        }
    }

    private ConvertOutcome doConvert(ConvertContext ctx, AgentChatExecutor.Prepared prepared,
                                     DesignSseSink sse, long[] usageAgg) {
        // ===== Step 1 区块切分（纯代码）=====
        sse.send(AiTemplateConstants.SSE_EVENT_STATUS, "正在切分设计稿区块…");
        DesignBundle bundle = loadDesignBundle(ctx);
        List<SectionUnitRef> units = collectSectionUnits(bundle);
        int totalUnits = units.size();
        sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, "设计稿切分完成："
                + bundle.pages().size() + " 页 / " + totalUnits + " 个区块"
                + "（含 nav/footer 公共块），开始组件映射…");

        // ===== Step 2 组件映射（唯一 AI 决策点，mappingCache 断点续传）=====
        Map<String, SectionMapping> mappingByUnit = new LinkedHashMap<>();
        if (ctx.mappingCache() != null) {
            for (SectionMapping m : ctx.mappingCache()) {
                mappingByUnit.put(mappingKey(m.page(), m.sectionIdx()), m);
            }
        }
        List<SectionUnitRef> pending = units.stream()
                .filter(u -> !mappingByUnit.containsKey(mappingKey(u.page(), u.idx()))).toList();
        for (int i = 0; i < pending.size(); i += MAPPING_BATCH_SIZE) {
            if (sse.isCancelled()) {
                throw new DesignCancelledException();
            }
            List<SectionUnitRef> batch = pending.subList(i, Math.min(i + MAPPING_BATCH_SIZE, pending.size()));
            sse.send(AiTemplateConstants.SSE_EVENT_STATUS,
                    "正在映射区块 " + (i + 1) + "~" + (i + batch.size()) + "/" + pending.size() + "…");
            mapBatch(ctx, prepared, bundle, batch, mappingByUnit, sse, usageAgg);
            // 进度点即时持久化：每批映射完成即回传全量快照（编排器写回 plan.json——中断重入不重问本批）
            if (ctx.mappingProgressSink() != null) {
                ctx.mappingProgressSink().accept(List.copyOf(mappingByUnit.values()));
            }
        }

        // ===== 导入型会话 nav/footer 强制保真（确定性改判，2026-09-22 用户决议）=====
        // 用户上传 HTML 由 AI 照写时，原生 nav/footer 是设计骨架；被 AI 映射成组件（tw:navbar 等）
        // 会用组件自带骨架 + Tailwind 样式替换原结构，与保真主体视觉打架（白底导航条压深色页面）。
        // 改判 custom → addNavFooterSection 的 custom 路径：原结构 + 原 CSS 类逐页原样保留
        // （adaptCustomHtml 只重写链接 href），布局 head 注入的上传站 inline CSS 随即生效。
        // 锚点型导航（#features 等）本就不接 CMS 菜单，保留静态锚点是上传站原状；
        // 带 ul&gt;li&gt;a 菜单的导航仍走既有 R3 menuifyNav 接 CMS 动态菜单。
        if (AiTemplateConstants.isImportMode(ctx.session())) {
            boolean rejudged = false;
            for (SectionMapping m : new ArrayList<>(mappingByUnit.values())) {
                if ((m.sectionIdx() == -1 || m.sectionIdx() == -2)
                        && !MAP_CUSTOM.equals(m.component())
                        && !MAP_CONTENT_BODY.equals(m.component())) {
                    boolean isNav = m.sectionIdx() == -1;
                    mappingByUnit.put(mappingKey(m.page(), m.sectionIdx()), new SectionMapping(
                            m.page(), m.sectionIdx(), MAP_CUSTOM, null, 1.0,
                            "导入型会话：" + (isNav ? "导航" : "页脚") + "强制保真（照上传 HTML）", "import-fidelity"));
                    sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "\n" + (isNav ? "导航" : "页脚") + "按上传 HTML 保真保留（原结构 + 原 CSS，不套组件样式）");
                    rejudged = true;
                }
            }
            if (rejudged && ctx.mappingProgressSink() != null) {
                ctx.mappingProgressSink().accept(List.copyOf(mappingByUnit.values()));
            }
        }

        // ===== Step 3~5 装配 + 脚本迁移 + 渲染校验（确定性降级循环）=====
        sse.send(AiTemplateConstants.SSE_EVENT_STATUS, "正在装配组件化模板…");
        RenderAttempt attempt = assembleAndValidate(ctx, bundle, mappingByUnit, sse);

        // ===== 收尾：产物文件注册 + 报告 =====
        persistProducts(ctx, attempt.writtenFiles(), sse);
        List<PageResult> pageResults = buildPageResults(bundle, attempt);
        ConvertStatus status = classifyStatus(pageResults);
        List<String> report = buildReport(ctx, bundle, mappingByUnit, attempt);
        // R3 收尾扫描：全站产物无 <@menuTag>（custom nav 菜单化未命中/失败）→ 告警 + report 行
        // （组件 navbar 的 ftl 自带 menuTag，只有 custom nav 且菜单化失败才会触发）
        if (!anyProductHasMenuTag(ctx, attempt.writtenFiles())) {
            String menuWarn = "菜单未接入 CMS：全站产物未发现 <@menuTag>，菜单为静态链接，后台菜单配置不生效";
            sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, "\n⚠ " + menuWarn);
            report.add(menuWarn);
        }

        sse.send(AiTemplateConstants.SSE_EVENT_STATUS, "");
        log.info("设计稿转化完成: sessionId={}, status={}, pages={}, mappedUnits={}",
                ctx.session().getSessionId(), status, pageResults.size(), mappingByUnit.size());
        return new ConvertOutcome(status, pageResults, List.copyOf(new ArrayList<>(mappingByUnit.values())),
                report, attempt.writtenFiles(), usageAgg[0], usageAgg[1], usageAgg[2]);
    }

    // ==================== Step 1 区块切分 ====================

    /** 区块引用（切分产物 → 映射/装配的传递单元；Element 不入 record，用下标回溯） */
    private record SectionUnitRef(String page, int idx, String blockName, Element element,
                                  boolean navUnit, boolean footerUnit) {
    }

    private DesignBundle loadDesignBundle(ConvertContext ctx) {
        List<PageDesign> pages = new ArrayList<>();
        Map<String, String> rootTokens = null;
        String scripts = null;
        List<String> scriptAnchors = new ArrayList<>();
        for (DesignPagePlanner.PagePlan plan : ctx.pages()) {
            Path file = ctx.workDir().resolve("design").resolve(plan.name() + ".html");
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("设计稿文件缺失: design/" + plan.name() + ".html");
            }
            Document doc;
            try {
                doc = Jsoup.parse(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("设计稿读取失败: design/" + plan.name() + ".html", e);
            }
            Element nav = pickTopLevelNav(doc);
            Element footer = pickFooterSection(doc);
            List<Element> sections = new ArrayList<>();
            for (Element section : doc.body().children()) {
                if (!"section".equals(section.tagName()) || !section.hasAttr("data-block")) {
                    continue;
                }
                if (section == footer) {
                    continue;
                }
                sections.add(section);
            }
            pages.add(new PageDesign(plan, doc, nav, sections, footer));
            // tokens / scripts 取首个非空页（A2/A4 审计保证跨页收敛，取一页即可）
            if (rootTokens == null) {
                rootTokens = extractRootTokens(doc);
            }
            if (scripts == null) {
                String s = extractInlineScripts(doc);
                if (StringUtils.hasText(s)) {
                    scripts = s;
                }
            }
        }
        if (rootTokens == null) {
            rootTokens = Map.of();
        }
        if (scripts != null) {
            Matcher m = SCRIPT_ID_ANCHOR_PATTERN.matcher(scripts);
            while (m.find()) {
                String id = m.group(1) != null ? m.group(1) : m.group(2);
                if (!scriptAnchors.contains(id)) {
                    scriptAnchors.add(id);
                }
            }
        }
        return new DesignBundle(pages, rootTokens, scripts, scriptAnchors);
    }

    /** body 直接子级中的 nav 元素（契约结构；非顶层 nav 兜底 selectFirst） */
    private static Element pickTopLevelNav(Document doc) {
        for (Element child : doc.body().children()) {
            if ("nav".equals(child.tagName())) {
                return child;
            }
        }
        return doc.body().selectFirst("nav");
    }

    /** footer 区块：body 顶层 section 中 id=footer 或 data-block 含 footer 者（后者兜底 #footer 任意元素） */
    private static Element pickFooterSection(Document doc) {
        for (Element child : doc.body().children()) {
            if (!"section".equals(child.tagName()) || !child.hasAttr("data-block")) {
                continue;
            }
            if ("footer".equals(child.id()) || child.attr("data-block").toLowerCase(Locale.ROOT).contains("footer")) {
                return child;
            }
        }
        Element byId = doc.body().selectFirst("#footer");
        return byId != null && "section".equals(byId.tagName()) ? byId : null;
    }

    /** 提取 :root 块的 --c-* 变量（首个含 :root 的 style 标签） */
    private static Map<String, String> extractRootTokens(Document doc) {
        for (Element style : doc.select("style")) {
            String css = style.data();
            int rootIdx = css.indexOf(":root");
            if (rootIdx < 0) {
                continue;
            }
            int braceEnd = css.indexOf('}', rootIdx);
            if (braceEnd < 0) {
                continue;
            }
            Map<String, String> tokens = new LinkedHashMap<>();
            Matcher m = ROOT_VAR_PATTERN.matcher(css.substring(rootIdx, braceEnd));
            while (m.find()) {
                tokens.put(m.group(1).trim(), m.group(2).trim());
            }
            if (!tokens.isEmpty()) {
                return tokens;
            }
        }
        return null;
    }

    /** 提取页面全部 inline script（A6 白名单已在审计拦截，这里信任原文；多段拼接） */
    private static String extractInlineScripts(Document doc) {
        StringBuilder sb = new StringBuilder();
        for (Element script : doc.select("script:not([src])")) {
            String js = script.data().trim();
            if (js.isEmpty()) {
                continue;
            }
            sb.append(js).append("\n;\n");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 全站区块清单：nav（-1）/footer（-2）公共块 + 各页内容区块（0 起顺序） */
    private List<SectionUnitRef> collectSectionUnits(DesignBundle bundle) {
        List<SectionUnitRef> units = new ArrayList<>();
        PageDesign anchor = bundle.pages().get(0);
        if (anchor.nav() != null) {
            units.add(new SectionUnitRef(anchor.plan().name(), -1, "nav", anchor.nav(), true, false));
        }
        if (anchor.footer() != null) {
            units.add(new SectionUnitRef(anchor.plan().name(), -2, "footer", anchor.footer(), false, true));
        }
        for (PageDesign page : bundle.pages()) {
            List<Element> sections = page.sections();
            for (int i = 0; i < sections.size(); i++) {
                units.add(new SectionUnitRef(page.plan().name(), i,
                        sections.get(i).attr("data-block"), sections.get(i), false, false));
            }
        }
        return units;
    }

    private static String mappingKey(String page, int idx) {
        return page + "#" + idx;
    }

    // ==================== Step 2 组件映射（AI 决策点） ====================

    private void mapBatch(ConvertContext ctx, AgentChatExecutor.Prepared prepared, DesignBundle bundle,
                          List<SectionUnitRef> batch, Map<String, SectionMapping> mappingByUnit,
                          DesignSseSink sse, long[] usageAgg) {
        String manifest = buildMappingManifest();
        String batchPrompt = buildBatchPrompt(ctx, bundle, batch);
        String lastError = null;
        for (int round = 1; round <= MAX_MAPPING_ROUNDS; round++) {
            if (sse.isCancelled()) {
                throw new DesignCancelledException();
            }
            String raw = callMappingModel(prepared, manifest, batchPrompt, lastError, usageAgg);
            List<SectionMapping> parsed;
            try {
                parsed = parseMappings(raw, batch, bundle);
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                        "组件映射第 " + round + " 轮输出不合法（" + e.getMessage() + "），正在重试…");
                continue;
            }
            for (SectionMapping m : parsed) {
                mappingByUnit.put(mappingKey(m.page(), m.sectionIdx()), m);
            }
            return;
        }
        // 重试耗尽：全批确定性降级 custom（页面完整性优先）
        sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                "组件映射 " + MAX_MAPPING_ROUNDS + " 轮未收敛，本批 " + batch.size()
                        + " 个区块已降级为自定义区块（原样保留设计）");
        for (SectionUnitRef unit : batch) {
            mappingByUnit.put(mappingKey(unit.page(), unit.idx()),
                    new SectionMapping(unit.page(), unit.idx(), MAP_CUSTOM, null, 1.0,
                            "映射轮耗尽降级", "fallback"));
        }
    }

    /** 组件清单：registry manifest + 特殊标记说明（custom-html 逃生舱对 AI 隐藏——输出用 custom 标记） */
    private String buildMappingManifest() {
        StringBuilder sb = new StringBuilder();
        sb.append(componentRegistry.buildManifest());
        sb.append("""
                ## 特殊映射标记
                - "custom"：区块不匹配任何组件，原样保留设计 HTML（结构差异大时果断使用，宁可 custom 不可错配）
                - "content-body"：区块替换为该页 CMS 正文骨架（文章列表/单页正文），仅 article_list/article/page 类内容页的"列表/正文主体"区块可用
                """);
        return sb.toString();
    }

    /** 批量区块摘要提示词（区块名 + 结构特征 + 文本量 + 首标题，§5.1 Step 2 口径） */
    private String buildBatchPrompt(ConvertContext ctx, DesignBundle bundle, List<SectionUnitRef> batch) {
        Map<String, String> pageKeyByName = new LinkedHashMap<>();
        for (PageDesign page : bundle.pages()) {
            pageKeyByName.put(page.plan().name(), page.plan().fastcmsPageKey());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【站点需求】").append(ctx.requirement() == null ? "" : ctx.requirement().trim()).append("\n\n");
        sb.append("【待映射区块（本批 ").append(batch.size()).append(" 个）】\n");
        for (SectionUnitRef unit : batch) {
            Element el = unit.element();
            String pageKey = pageKeyByName.getOrDefault(unit.page(), "index");
            sb.append("- page=").append(unit.page())
                    .append("（fastcms页面类型 ").append(pageKey).append("）")
                    .append(" idx=").append(unit.idx())
                    .append(" block=").append(unit.blockName())
                    .append(" 特征：").append(describeStructure(el))
                    .append('\n');
        }
        sb.append("""

                【映射规则】
                1. 仅输出 JSON 数组，无任何其他文本：[{"page":"index","idx":1,"component":"tw:hero","variant":"split","confidence":0.9,"reason":"大标题+双按钮首屏"}]
                2. component 取值：组件清单中的组件全名（如 tw:hero），或 "custom"，或 "content-body"（仅内容页正文主体区块）
                3. confidence < 0.7 时必须输出 "custom"（结构对不上宁可原样保留，不可错配组件）
                4. variant 从该组件变体清单中选，拿不准时省略 variant 字段
                5. 本批每个区块都必须出现在输出数组中，逐项对应（page+idx 定位）
                """);
        return sb.toString();
    }

    /** 区块结构特征摘要（h/p/img/按钮/子卡片计数 + 文本量 + 首标题） */
    private static String describeStructure(Element el) {
        int headings = el.select("h1,h2,h3,h4").size();
        int paras = el.select("p").size();
        int images = el.select("img").size();
        int links = el.select("a").size();
        int buttons = el.select("button").size();
        int textLen = el.text().length();
        String firstHeading = firstHeadingText(el);
        StringBuilder sb = new StringBuilder();
        sb.append("标题×").append(headings)
                .append(" 段落×").append(paras)
                .append(" 图片×").append(images)
                .append(" 链接×").append(links)
                .append(" 按钮×").append(buttons)
                .append(" 文本量").append(textLen).append("字");
        if (firstHeading != null) {
            sb.append(" 首标题「").append(truncate(firstHeading, 20)).append("」");
        }
        return sb.toString();
    }

    private static String firstHeadingText(Element el) {
        Element h = el.selectFirst("h1,h2,h3,h4");
        return h == null ? null : h.text().trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 映射模型调用（非流式：输出小 JSON，reasoning 不播报） */
    private String callMappingModel(AgentChatExecutor.Prepared prepared, String manifest,
                                    String batchPrompt, String prevError, long[] usageAgg) {
        String system = "你是模板组件映射专家。任务：把设计稿区块映射到组件库组件或降级标记。"
                + "只输出 JSON 数组，不要输出任何解释文字。\n\n" + manifest;
        StringBuilder user = new StringBuilder(batchPrompt);
        if (prevError != null) {
            user.append("\n【上一轮输出错误，必须修正】").append(prevError).append('\n');
        }
        OpenAiChatOptions options = com.fastcms.ai.service.impl.AiModelConfigServiceImpl
                .baseOptionsBuilder(prepared.getModelConfig())
                .maxTokens(MAPPING_MAX_TOKENS)
                .build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user.toString())), options);
        var response = prepared.getChatClient().prompt(prompt).call().chatResponse();
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            usageAgg[0] += u.getPromptTokens() != null ? u.getPromptTokens() : 0;
            usageAgg[1] += u.getCompletionTokens() != null ? u.getCompletionTokens() : 0;
            usageAgg[2] += u.getTotalTokens() != null ? u.getTotalTokens() : 0;
        }
        String text = response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("映射模型返回空响应");
        }
        return text;
    }

    /** 解析 + 校验 AI 映射输出（返回与本批区块一一对应的映射；违规项确定性降 custom） */
    private List<SectionMapping> parseMappings(String raw, List<SectionUnitRef> batch, DesignBundle bundle) {
        String json = extractJsonArray(raw);
        if (json == null) {
            throw new IllegalArgumentException("输出中未找到 JSON 数组（只允许输出 JSON 数组）");
        }
        List<Map<String, Object>> items;
        try {
            items = JSON_MAPPER.readValue(json,
                    JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
        Map<String, SectionUnitRef> batchByKey = new LinkedHashMap<>();
        for (SectionUnitRef unit : batch) {
            batchByKey.put(mappingKey(unit.page(), unit.idx()), unit);
        }
        List<SectionMapping> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object pageRaw = item.get("page");
            Object idxRaw = item.get("idx");
            if (pageRaw == null || idxRaw == null) {
                continue;
            }
            String page = String.valueOf(pageRaw);
            int idx;
            try {
                idx = Integer.parseInt(String.valueOf(idxRaw).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            SectionUnitRef unit = batchByKey.remove(mappingKey(page, idx));
            if (unit == null) {
                continue;
            }
            result.add(validateMapping(bundle, unit, item));
        }
        if (!batchByKey.isEmpty()) {
            // 漏映射区块：确定性补 custom（不整批重试——部分成功也保留，漏项降级）
            for (SectionUnitRef unit : batchByKey.values()) {
                result.add(new SectionMapping(unit.page(), unit.idx(), MAP_CUSTOM, null, 1.0,
                        "输出遗漏，确定性降级", "fallback"));
            }
        }
        return result;
    }

    /** 单条映射合法性：custom / content-body / registry 命中（appliesTo + variant + confidence） */
    private SectionMapping validateMapping(DesignBundle bundle, SectionUnitRef unit, Map<String, Object> item) {
        String component = String.valueOf(item.getOrDefault("component", "")).trim();
        String variant = item.get("variant") == null ? null : String.valueOf(item.get("variant")).trim();
        double confidence = 0.0;
        try {
            confidence = Double.parseDouble(String.valueOf(item.getOrDefault("confidence", "0")).trim());
        } catch (NumberFormatException ignored) {
            // 置信度缺失/非法按 0 处理 → 走阈值拦截
        }
        String reason = item.get("reason") == null ? "" : String.valueOf(item.get("reason")).trim();

        if (MAP_CUSTOM.equals(component)) {
            return new SectionMapping(unit.page(), unit.idx(), MAP_CUSTOM, null, confidence, reason, "ai");
        }
        if (MAP_CONTENT_BODY.equals(component)) {
            return new SectionMapping(unit.page(), unit.idx(), MAP_CONTENT_BODY, null, confidence, reason, "ai");
        }
        // 组件命中校验：存在 + appliesTo + 变体 + 置信阈值
        var rc = componentRegistry.find(component).orElse(null);
        if (rc == null) {
            return degrade(unit, component, "组件不存在");
        }
        String basePageKey = basePageKeyOfUnit(bundle, unit);
        ComponentDescriptor descriptor = rc.descriptor();
        if (descriptor.safeAppliesTo() != null && !descriptor.safeAppliesTo().isEmpty()
                && basePageKey != null && !descriptor.safeAppliesTo().contains(basePageKey)) {
            return degrade(unit, component, "组件不适用于 " + basePageKey + " 页");
        }
        if (confidence < aiProperties.getTemplate().getDesign().getSectionConfidenceThreshold()) {
            return degrade(unit, component, "置信度 " + confidence + " 低于阈值");
        }
        if (variant != null && !descriptor.hasVariant(variant)) {
            variant = null;
        }
        if (variant == null && !descriptor.safeVariants().isEmpty()) {
            variant = descriptor.safeVariants().get(0).id();
        }
        return new SectionMapping(unit.page(), unit.idx(), component, variant, confidence, reason, "ai");
    }

    private SectionMapping degrade(SectionUnitRef unit, String rejected, String why) {
        log.info("区块映射降级 custom: page={}, idx={}, rejected={}, why={}",
                unit.page(), unit.idx(), rejected, why);
        return new SectionMapping(unit.page(), unit.idx(), MAP_CUSTOM, null, 1.0,
                (rejected.isEmpty() ? "空输出" : rejected) + " → " + why, "fallback");
    }

    private String basePageKeyOfUnit(DesignBundle bundle, SectionUnitRef unit) {
        // nav/footer 公共块按 index 页口径校验（navbar/footer 组件 appliesTo 全页型）
        if (unit.navUnit() || unit.footerUnit()) {
            return PageSpec.PAGE_INDEX;
        }
        for (PageDesign page : bundle.pages()) {
            if (page.plan().name().equals(unit.page())) {
                return PageSpec.basePageKeyOf(page.plan().fastcmsPageKey());
            }
        }
        return null;
    }

    /** 从模型输出提取 JSON 数组文本（剥围栏/前后杂文） */
    static String extractJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int fenceEnd = text.lastIndexOf("```");
            if (firstNl > 0 && fenceEnd > firstNl) {
                text = text.substring(firstNl + 1, fenceEnd).trim();
            }
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    // ==================== Step 3~5 装配 + 渲染校验 ====================

    /** 渲染尝试结果（降级循环最终态） */
    private record RenderAttempt(PageSpec finalSpec, List<String> writtenFiles,
                                 List<String> failedPages, Map<String, Integer> degradedByPage) {
    }

    private RenderAttempt assembleAndValidate(ConvertContext ctx, DesignBundle bundle,
                                              Map<String, SectionMapping> mappingByUnit, DesignSseSink sse) {
        // 降级账本：page → 已降级轮次（0=未降级；1=组件区块全降 custom；2=整页单 custom）
        Map<String, Integer> degradeLevel = new LinkedHashMap<>();
        PageSpec spec = buildPageSpec(ctx, bundle, mappingByUnit, degradeLevel, sse);
        List<String> writtenFiles = List.of();
        List<String> failedPages = List.of();

        for (int round = 0; round <= MAX_RENDER_DEGRADE_ROUNDS; round++) {
            if (sse.isCancelled()) {
                throw new DesignCancelledException();
            }
            // 图片槽位解析（media 槽位 search: 引用 → 附件库/演示图，与管线渲染前预处理同机制）
            try {
                AttachmentImageSearcher.Result imageResult = attachmentImageSearcher.resolve(spec, ctx.workDir());
                spec = imageResult.spec();
            } catch (Exception e) {
                log.warn("图片槽位解析失败（不阻塞，未解析引用走组件占位兜底）: sessionId={}",
                        ctx.session().getSessionId(), e);
            }
            // 渲染（PageSpecRenderer 产物与管线模式同构——§5.3 的实现载体）
            PageSpecRenderer.RenderResult renderResult;
            try {
                renderResult = pageSpecRenderer.render(spec, ctx.workDir(), ctx.mobileAdaptive());
            } catch (Exception e) {
                // 渲染器级异常（组件缺失等）：确定性降级全部映射区块为 custom 再试
                if (round < MAX_RENDER_DEGRADE_ROUNDS) {
                    sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "模板装配失败（" + e.getMessage() + "），全部组件区块降级为自定义区块重试…");
                    degradeAllMapped(bundle, mappingByUnit);
                    spec = buildPageSpec(ctx, bundle, mappingByUnit, degradeLevel, sse);
                    continue;
                }
                throw new IllegalStateException("模板装配失败: " + e.getMessage(), e);
            }
            writtenFiles = renderResult.writtenFiles();
            // CSS 后处理（每轮必做——render 覆盖 tokens.css/_layout.html）
            applyCssPostProcess(ctx, bundle, sse);
            // 脚本迁移 + 锚点垫片（每轮必做，同上）
            migrateScripts(ctx, bundle, sse);

            // Step 5 渲染校验
            List<String> pageFiles = writtenFiles.stream()
                    .filter(f -> f.endsWith(".html") && !f.startsWith("_")).toList();
            List<String> errors = previewRenderer.checkRenderedFiles(ctx.workDir(), pageFiles);
            if (errors.isEmpty()) {
                return new RenderAttempt(spec, writtenFiles, List.of(), degradeLevel);
            }
            failedPages = errors.stream().map(MockupConverter::pageFileOfError).distinct().toList();
            if (round >= MAX_RENDER_DEGRADE_ROUNDS) {
                break;
            }
            // 确定性降级：失败页的组件映射区块 → custom（原样 HTML 不会渲染失败）
            for (String pageKey : failedPages) {
                int level = degradeLevel.getOrDefault(pageKey, 0);
                if (level == 0) {
                    degradeLevel.put(pageKey, 1);
                    sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "页面 " + pageKey + ".html 渲染失败（" + errors.size() + " 处），该页组件区块已降级为自定义区块重试…");
                } else {
                    degradeLevel.put(pageKey, 2);
                    sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "页面 " + pageKey + ".html 仍渲染失败，该页已降级为整页自定义（设计原样保留）…");
                }
            }
            spec = buildPageSpec(ctx, bundle, mappingByUnit, degradeLevel, sse);
        }
        return new RenderAttempt(spec, writtenFiles, failedPages, degradeLevel);
    }

    /** 从渲染错误文本提取页文件名（checkRenderedFiles 错误含文件与行号） */
    private static String pageFileOfError(String error) {
        Matcher m = Pattern.compile("([a-z_]+\\.html)").matcher(error);
        return m.find() ? m.group(1).substring(0, m.group(1).length() - ".html".length()) : "";
    }

    /** 全部映射区块确定性降级 custom（渲染器级异常的兜底） */
    private void degradeAllMapped(DesignBundle bundle, Map<String, SectionMapping> mappingByUnit) {
        for (Map.Entry<String, SectionMapping> entry : mappingByUnit.entrySet()) {
            SectionMapping m = entry.getValue();
            if (!MAP_CUSTOM.equals(m.component())) {
                entry.setValue(new SectionMapping(m.page(), m.sectionIdx(), MAP_CUSTOM, null, 1.0,
                        "装配失败降级", "fallback"));
            }
        }
    }

    // ==================== PageSpec 构建（Step 3 核心） ====================

    private PageSpec buildPageSpec(ConvertContext ctx, DesignBundle bundle,
                                   Map<String, SectionMapping> mappingByUnit, Map<String, Integer> degradeLevel,
                                   DesignSseSink sse) {
        Map<String, PageSpecPage> pages = new LinkedHashMap<>();
        for (PageDesign page : bundle.pages()) {
            String pageKey = page.plan().fastcmsPageKey();
            List<SectionSpec> sections = new ArrayList<>();
            // 整页单 custom 降级（degradeLevel=2）：全部区块合并为一个 custom section
            if (Integer.valueOf(2).equals(degradeLevel.get(pageKey))) {
                sections.add(customSection(pageKey, page, bundle, page.sections(), mappingByUnit, true, sse));
            } else {
                // nav 公共块：映射 navbar → 布局区（放 index 编排即可进 zones）；
                // custom → 每页正文首位原样保留（视觉位置不变，非 DRY 是降级路径的可接受代价）
                boolean navMapped = addNavFooterSection(sections, page, bundle, mappingByUnit, true, sse);
                for (int i = 0; i < page.sections().size(); i++) {
                    Element el = page.sections().get(i);
                    SectionMapping m = mappingByUnit.get(mappingKey(page.plan().name(), i));
                    sections.add(toSectionSpec(pageKey, m, el, bundle,
                            Integer.valueOf(1).equals(degradeLevel.get(pageKey))));
                }
                addNavFooterSection(sections, page, bundle, mappingByUnit, false, sse);
                // nav custom 时每页都要放（映射 navbar 时仅 index 放，其余页由布局区统一提供）
                if (!navMapped && mappingOf(bundle, mappingByUnit, true) != null
                        && !MAP_CUSTOM.equals(mappingOf(bundle, mappingByUnit, true).component())) {
                    // navbar 映射：非 index 页不放（布局区提供）——此分支不触发；保持逻辑显式
                }
            }
            pages.put(pageKey, new PageSpecPage(sections));
        }
        // 内容页未编排（article 基础页等）由渲染器补正文骨架；布局区 nav/footer 组件从 index 抽取

        String templateName = StringUtils.hasText(ctx.session().getTemplateName())
                ? ctx.session().getTemplateName() : "ai-design-site";
        String siteName = deriveSiteName(bundle);
        String primaryColor = derivePrimaryColor(bundle, ctx.direction());
        String stylePreset = ctx.direction() != null && StringUtils.hasText(ctx.direction().stylePreset())
                ? ctx.direction().stylePreset() : TokenEngine.DEFAULT_PRESET;
        return new PageSpec(PageSpec.SPEC_VERSION, BuiltinFoundation.TAILWIND_V4, templateName,
                siteName, null, stylePreset, primaryColor, buildSiteContent(bundle), pages, null);
    }

    /** 内置地基常量（避免直接依赖 BuiltinTailwindPackProvider 具体类） */
    private static final class BuiltinFoundation {
        static final String TAILWIND_V4 = "tailwind-v4";
    }

    private SectionMapping mappingOf(DesignBundle bundle, Map<String, SectionMapping> mappingByUnit, boolean nav) {
        PageDesign anchor = bundle.pages().get(0);
        int idx = nav ? -1 : -2;
        return mappingByUnit.get(mappingKey(anchor.plan().name(), idx));
    }

    /**
     * nav/footer 公共块落位：
     * nav=true 时处理 nav（映射组件 → 返回 true 且仅加入 sections 由布局区机制抽取；
     * custom → 每页加入首位）；nav=false 时处理 footer（同理，custom 加每页末位）
     */
    private boolean addNavFooterSection(List<SectionSpec> sections, PageDesign page, DesignBundle bundle,
                                        Map<String, SectionMapping> mappingByUnit, boolean nav, DesignSseSink sse) {
        SectionMapping m = mappingOf(bundle, mappingByUnit, nav);
        if (m == null) {
            return false;
        }
        PageDesign anchor = bundle.pages().get(0);
        Element el = nav ? anchor.nav() : anchor.footer();
        if (el == null) {
            return false;
        }
        if (MAP_CUSTOM.equals(m.component())) {
            // custom 公共块：每页原样保留（位置：nav 首位 / footer 末位）；
            // nav 额外做 R3 菜单化（静态 ul → <@menuTag>，CMS 菜单可管），播报只在锚点页做一次
            String blockHtml = nav
                    ? menuifyNav(adaptCustomHtml(el, page, bundle), bundle, page == anchor, sse)
                    : adaptCustomHtml(el, page, bundle);
            SectionSpec spec = new SectionSpec(
                    (nav ? "nav-" : "footer-") + page.plan().name(),
                    CUSTOM_HTML_COMPONENT, "default",
                    Map.of("html", blockHtml));
            if (nav) {
                sections.add(0, spec);
            } else {
                sections.add(spec);
            }
            return false;
        }
        if (MAP_CONTENT_BODY.equals(m.component())) {
            return false;
        }
        // 组件映射（tw:navbar / tw:footer）：仅 index 页编排（extractLayoutZones 从首个编排页抽取布局区）
        if (PageSpec.PAGE_INDEX.equals(page.plan().fastcmsPageKey())) {
            sections.add(buildComponentSection(m, el, bundle, false));
        }
        return true;
    }

    /** 单个内容区块 → SectionSpec（映射结果 + 降级账本决定物化方式） */
    private SectionSpec toSectionSpec(String pageKey, SectionMapping m, Element el,
                                      DesignBundle bundle, boolean pageDegradeLevel1) {
        if (m == null || MAP_CUSTOM.equals(m.component()) || pageDegradeLevel1 && !MAP_CONTENT_BODY.equals(m.component())) {
            // 映射缺失 / custom / 页面降级轮1（组件区块全降 custom，content-body 除外）
            return new SectionSpec(sectionId(el, pageKey), CUSTOM_HTML_COMPONENT, "default",
                    Map.of("html", adaptCustomHtml(el, null, bundle)));
        }
        if (MAP_CONTENT_BODY.equals(m.component())) {
            return new SectionSpec(sectionId(el, pageKey), CONTENT_BODY_COMPONENT, null, Map.of());
        }
        return buildComponentSection(m, el, bundle, true);
    }

    private String sectionId(Element el, String pageKey) {
        String block = el.attr("data-block");
        return (StringUtils.hasText(block) ? block : "sec") + "-" + shortHash(el.outerHtml());
    }

    private static String shortHash(String input) {
        int h = input.hashCode();
        return Integer.toHexString(h & 0xffffff);
    }

    /** 整页单 custom（渲染两轮失败的兜底）：全部区块 HTML 拼接为一个 custom section */
    private SectionSpec customSection(String pageKey, PageDesign page, DesignBundle bundle,
                                      List<Element> sections, Map<String, SectionMapping> mappingByUnit,
                                      boolean wholePage, DesignSseSink sse) {
        StringBuilder html = new StringBuilder();
        if (wholePage) {
            // 整页 = nav（如有，custom 或组件一律原样——渲染失败兜底优先保完整）+ 全部区块 + footer；
            // nav 同样做 R3 菜单化（与 addNavFooterSection 的 custom 分支口径一致）
            if (page.nav() != null) {
                html.append(menuifyNav(adaptCustomHtml(page.nav(), page, bundle), bundle,
                        page == bundle.pages().get(0), sse)).append('\n');
            }
            for (Element el : page.sections()) {
                html.append(adaptCustomHtml(el, page, bundle)).append('\n');
            }
            if (page.footer() != null) {
                html.append(adaptCustomHtml(page.footer(), page, bundle)).append('\n');
            }
        } else {
            for (Element el : sections) {
                html.append(adaptCustomHtml(el, null, bundle)).append('\n');
            }
        }
        return new SectionSpec("fallback-" + pageKey, CUSTOM_HTML_COMPONENT, "default",
                Map.of("html", html.toString()));
    }

    /** 组件映射区块：槽位数据确定性抽取（§5.1 Step 3"文本/图从区块 DOM 抽取"） */
    private SectionSpec buildComponentSection(SectionMapping m, Element el, DesignBundle bundle,
                                              boolean contentSection) {
        var rc = componentRegistry.find(m.component()).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        if (rc != null) {
            for (var slot : rc.descriptor().safeSlots()) {
                Object value = extractSlot(slot, el);
                if (value != null) {
                    data.put(slot.name(), value);
                }
            }
        }
        return new SectionSpec(sectionId(el, m.page()), m.component(),
                StringUtils.hasText(m.variant()) ? m.variant() : null, data);
    }

    /**
     * 槽位启发式抽取（按槽位名 + 类型；确定性，无 AI）
     */
    private Object extractSlot(com.fastcms.ai.component.ComponentSlot slot, Element el) {
        String name = slot.name();
        String type = slot.type() == null ? "string" : slot.type();
        if ("string".equals(type)) {
            String value = extractStringSlot(name, el);
            if (value == null) {
                return null;
            }
            Integer max = slot.maxLength();
            return max != null && value.length() > max ? value.substring(0, max) : value;
        }
        if ("number".equals(type)) {
            return null; // 数值槽位（count 等）用组件默认
        }
        if ("media".equals(type)) {
            Element img = el.selectFirst("img");
            if (img == null) {
                return null;
            }
            String hint = img.attr("data-src-hint").trim();
            return StringUtils.hasText(hint) ? "search:" + hint : null;
        }
        if ("list".equals(type)) {
            List<Map<String, Object>> items = extractListItems(el);
            return items.isEmpty() ? null : items;
        }
        return null;
    }

    private String extractStringSlot(String name, Element el) {
        switch (name) {
            case "title", "heading" -> {
                return firstHeadingText(el);
            }
            case "subtitle", "subtext", "desc", "description" -> {
                Element p = el.selectFirst("p");
                return p == null ? null : p.text().trim();
            }
            case "brand" -> {
                Element brand = el.selectFirst("a,span,strong");
                return brand == null ? el.text().trim() : brand.text().trim();
            }
            case "copyright" -> {
                for (Element e : el.select("p,span,div")) {
                    String t = e.text().trim();
                    if (t.contains("©") || t.contains("版权")) {
                        return t;
                    }
                }
                return null;
            }
            default -> {
                if (name.startsWith("cta")) {
                    return extractCtaSlot(name, el);
                }
                return null;
            }
        }
    }

    /** cta 槽位：主按钮 = 第一个有 href 的 a/button；次按钮 = 第二个 */
    private String extractCtaSlot(String name, Element el) {
        List<Element> actions = new ArrayList<>();
        for (Element candidate : el.select("a,button")) {
            String href = candidate.attr("href");
            if (candidate.tagName().equals("button") || (StringUtils.hasText(href) && !href.startsWith("#"))) {
                actions.add(candidate);
            }
        }
        boolean secondary = name.toLowerCase(Locale.ROOT).contains("secondary");
        Element target = secondary
                ? (actions.size() > 1 ? actions.get(1) : null)
                : (actions.isEmpty() ? null : actions.get(0));
        if (target == null) {
            return null;
        }
        return name.endsWith("Href") ? rewriteLink(target.attr("href").trim()) : target.text().trim();
    }

    /** list 槽位：h3/h4 重复结构 → 项（title=heading，desc=相邻 p，icon=项内首字符/emoji） */
    private List<Map<String, Object>> extractListItems(Element el) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Element heading : el.select("h3,h4")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", heading.text().trim());
            // 相邻段落：heading 所在项容器内第一个 p（父容器优先，兜底 nextElementSibling）
            Element container = heading.parent();
            Element p = container != null ? container.selectFirst("p") : null;
            if (p == null) {
                p = heading.nextElementSibling() != null && "p".equals(heading.nextElementSibling().tagName())
                        ? heading.nextElementSibling() : null;
            }
            if (p != null) {
                item.put("desc", p.text().trim());
            }
            items.add(item);
        }
        return items;
    }

    // ==================== custom 区块 HTML 适配 ====================

    /**
     * custom 区块原样保留 + 确定性适配：
     * img src=design/assets/*.svg → ${ctx()}/images/design/*.svg（SVG 复制由 CSS 后处理统一做）；
     * 页面链接 → CMS 语义路径（/page/about 等，规划页名确定性映射）
     */
    private String adaptCustomHtml(Element el, PageDesign page, DesignBundle bundle) {
        String html = el.outerHtml();
        html = DESIGN_ASSET_SRC_PATTERN.matcher(html).replaceAll("$1${ctx()}/images/design/$2$3");
        // 链接重写：遍历 a[href] 逐个替换（Jsoup 序列化保真）
        Document fragment = Jsoup.parseBodyFragment(html);
        boolean changed = false;
        for (Element a : fragment.select("a[href]")) {
            String href = a.attr("href").trim();
            String rewritten = rewriteLink(href);
            if (!rewritten.equals(href)) {
                a.attr("href", rewritten);
                changed = true;
            }
        }
        return changed ? fragment.body().html() : html;
    }

    /** 设计稿链接 → CMS 语义路径（规划页名确定性映射；未知/锚点原样） */
    private String rewriteLink(String href) {
        if (!StringUtils.hasText(href) || href.startsWith("#") || href.startsWith("http")
                || href.startsWith("${")) {
            return href;
        }
        String h = href.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (h.equals("/") || h.equals("./") || h.contains("index")) {
            return "/";
        }
        if (h.contains("about")) {
            return "/page/about";
        }
        if (h.contains("service") || h.contains("product")) {
            return "/page/services";
        }
        if (h.contains("contact")) {
            return "/page/contact";
        }
        if (h.contains("news") || h.contains("article") || h.contains("blog")) {
            return "/article/category/news";
        }
        return href;
    }

    // ==================== R3 custom nav 菜单化（menuTag 替换） ====================

    /** 菜单化结果（审计口径：matched/total + 未命中锚文本清单） */
    private record MenuifyResult(String html, int matched, int total, List<String> misses) {
    }

    /** 锚文本 × 站点信息架构匹配结果：indexItem=命中的是首页项（menuTag 数据不含首页，静态保留） */
    private record NavMatch(boolean indexItem) {
    }

    /**
     * R3 custom nav 菜单化入口：静态 ul 菜单替换为 {@code <@menuTag>} 动态渲染块
     * （字符串级变换，每页独立解析自己拿到的 HTML 副本，不改共享 DOM）。
     *
     * <p>nav 公共块每页物化一次，播报只在锚点页（announce=true）做一次防重复；
     * 审计口径 nav_anchor_matched/nav_anchor_total 进播报文案，未命中项保留静态链接并提示。</p>
     *
     * @return 变换后的 nav HTML；无可菜单化 ul（或异常）时原样返回
     */
    private String menuifyNav(String navHtml, DesignBundle bundle, boolean announce, DesignSseSink sse) {
        MenuifyResult result = doMenuifyNav(navHtml, bundle);
        if (announce && sse != null && result.total() > 0) {
            String note = "\n菜单接入：custom 导航已菜单化 " + result.matched() + "/" + result.total()
                    + " 个静态菜单项（CMS 后台菜单配置生效）";
            if (!result.misses().isEmpty()) {
                note += "；未匹配项 [" + String.join("、", result.misses())
                        + "] 保留为静态链接（规划中无对应页面，可后台补配菜单或让 AI 补页）";
            }
            sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, note);
        }
        return result.html();
    }

    /**
     * 菜单化实现：候选 ul（≥2 个 li&gt;a 直接子项）内锚文本四级匹配站点信息架构
     * （buildSiteContent 的 NavItem 名单）：归一化精确 → 中英同义词组 → 包含匹配
     * （较短一方 ≥2 字）→ 失败。≥1 项命中即视为菜单 ul 并替换：ul 属性原样保留，
     * 首页项与未命中项保留静态 li（不丢菜单项、预览数据口径不含首页防双首页），
     * 其余交给 menuTag（li/a 类名取首个命中项，样式不变）；无命中则原样返回。
     */
    private MenuifyResult doMenuifyNav(String navHtml, DesignBundle bundle) {
        int totalMatched = 0;
        int totalAnchors = 0;
        List<String> misses = new ArrayList<>();
        try {
            Document fragment = Jsoup.parseBodyFragment(navHtml);
            List<SiteContentSpec.NavItem> navItems = buildSiteContent(bundle).menus();
            boolean changed = false;
            for (Element ul : fragment.select("ul")) {
                List<Element> lis = new ArrayList<>();
                for (Element child : ul.children()) {
                    if ("li".equals(child.tagName()) && child.selectFirst("> a") != null) {
                        lis.add(child);
                    }
                }
                if (lis.size() < 2) {
                    continue;
                }
                int matched = 0;
                Element firstMatchedLi = null;
                Element firstMatchedA = null;
                List<Element> staticKeep = new ArrayList<>();
                for (Element li : lis) {
                    Element a = li.selectFirst("> a");
                    totalAnchors++;
                    NavMatch r = matchNavAnchor(a.text(), navItems);
                    if (r == null) {
                        staticKeep.add(li);
                        String t = a.text().trim();
                        if (!t.isEmpty() && !misses.contains(t)) {
                            misses.add(t);
                        }
                    } else {
                        matched++;
                        totalMatched++;
                        if (firstMatchedLi == null) {
                            firstMatchedLi = li;
                            firstMatchedA = a;
                        }
                        if (r.indexItem()) {
                            staticKeep.add(li);
                        }
                    }
                }
                if (matched < 1 || firstMatchedA == null) {
                    continue;
                }
                ul.replaceWith(new DataNode(buildMenuifiedUl(ul, staticKeep, firstMatchedLi, firstMatchedA)));
                changed = true;
            }
            if (changed) {
                return new MenuifyResult(fragment.body().html(), totalMatched, totalAnchors, misses);
            }
        } catch (Exception e) {
            log.warn("custom nav 菜单化失败（原样保留）: {}", e.getMessage());
        }
        return new MenuifyResult(navHtml, totalMatched, totalAnchors, misses);
    }

    /**
     * 组装菜单化 ul：ul 全部属性原样保留 + 静态项（首页/未命中，原 outerHtml）+
     * menuTag 动态块（li/a 类名取首个命中项；url=='/' 项跳过，与组件 navbar 防双首页口径一致）
     */
    private String buildMenuifiedUl(Element ul, List<Element> staticKeep,
                                    Element firstMatchedLi, Element firstMatchedA) {
        StringBuilder sb = new StringBuilder("<ul");
        for (Attribute attr : ul.attributes()) {
            sb.append(" ").append(attr.getKey()).append("=\"").append(attr.getValue()).append("\"");
        }
        sb.append(">\n");
        for (Element li : staticKeep) {
            sb.append(li.outerHtml()).append('\n');
        }
        sb.append("<@menuTag>\n<#if data??>\n<#list data as item>\n")
                .append("<#if item.menuName?? && item.menuName?has_content")
                .append(" && (item.url!'') != '' && (item.url!'') != '/'>\n")
                .append("<li");
        if (StringUtils.hasText(firstMatchedLi.className())) {
            sb.append(" class=\"").append(firstMatchedLi.className()).append("\"");
        }
        sb.append("><a href=\"${item.url!''}\" target=\"${item.target!'_self'}\"");
        if (StringUtils.hasText(firstMatchedA.className())) {
            sb.append(" class=\"").append(firstMatchedA.className()).append("\"");
        }
        sb.append(">${item.menuName}</a></li>\n")
                .append("</#if>\n</#list>\n</#if>\n</@menuTag>\n")
                .append("</ul>");
        return sb.toString();
    }

    /** 锚文本四级匹配站点信息架构名单；命中返回 NavMatch，四级全败返回 null（告警由调用方） */
    private NavMatch matchNavAnchor(String anchorText, List<SiteContentSpec.NavItem> navItems) {
        String a = normalizeAnchor(anchorText);
        if (a.isEmpty()) {
            return null;
        }
        // 1. 归一化精确
        for (SiteContentSpec.NavItem item : navItems) {
            if (a.equals(normalizeAnchor(item.name()))) {
                return new NavMatch(item.type() == SiteContentSpec.NavItem.TYPE_INDEX);
            }
        }
        // 2. 中英同义词组（锚文本与菜单名分别包含组内关键词即视为同义）
        for (List<String> group : NAV_SYNONYM_GROUPS) {
            if (group.stream().noneMatch(a::contains)) {
                continue;
            }
            for (SiteContentSpec.NavItem item : navItems) {
                String n = normalizeAnchor(item.name());
                if (!n.isEmpty() && group.stream().anyMatch(n::contains)) {
                    return new NavMatch(item.type() == SiteContentSpec.NavItem.TYPE_INDEX);
                }
            }
        }
        // 3. 包含匹配（较短一方 ≥2 字符，防单字符误命中）
        for (SiteContentSpec.NavItem item : navItems) {
            String n = normalizeAnchor(item.name());
            if (n.isEmpty()) {
                continue;
            }
            String shorter = a.length() <= n.length() ? a : n;
            if (shorter.length() >= 2 && (a.contains(n) || n.contains(a))) {
                return new NavMatch(item.type() == SiteContentSpec.NavItem.TYPE_INDEX);
            }
        }
        // 4. 失败
        return null;
    }

    /** 锚文本归一化：trim + 去空白 + 全角转半角 + 小写 */
    private String normalizeAnchor(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            sb.append(c >= 0xFF01 && c <= 0xFF5E ? (char) (c - 0xFEE0) : c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    // ==================== 站点信息架构 / 派生元数据 ====================

    /**
     * 从页面规划确定性构建信息架构（零 AI，R5 映射表化）：固定规划名查表定型，
     * 未命中的非基础页按 fastcmsPageKey 前缀确定性兜底（page_xxx → 单页、
     * article_list* → 分类），菜单与规划一致——apply 时建实体（/page/about 等可达），
     * 预览数据与设计稿导航对应
     */
    private SiteContentSpec buildSiteContent(DesignBundle bundle) {
        List<SiteContentSpec.NavItem> menus = new ArrayList<>();
        menus.add(new SiteContentSpec.NavItem("首页", SiteContentSpec.NavItem.TYPE_INDEX, null, null));
        List<SiteContentSpec.CatalogItem> singlePages = new ArrayList<>();
        List<SiteContentSpec.CatalogItem> categories = new ArrayList<>();
        for (PageDesign page : bundle.pages()) {
            String name = page.plan().name();
            String title = page.plan().title();
            // R5 固定名映射表：suffix=规划名裸名（与 {type}_{suffix}.html 路由约定一致）
            PlanNavEntry entry = PLAN_INFO_ARCH.get(name);
            String navType;
            boolean isCategory;
            if (entry != null) {
                navType = entry.navType();
                isCategory = entry.category();
            } else {
                // 未命中固定名的非基础页兜底：type/suffix 由 fastcmsPageKey 前缀推导（不猜不 AI）
                String key = page.plan().fastcmsPageKey();
                if (!StringUtils.hasText(key) || PageSpec.PAGE_INDEX.equals(key)) {
                    continue;
                }
                if (key.startsWith("page_")) {
                    navType = SiteContentSpec.NavItem.TYPE_PAGE;
                    isCategory = false;
                } else if (key.startsWith("article_list")) {
                    navType = SiteContentSpec.NavItem.TYPE_ARTICLE_LIST;
                    isCategory = true;
                } else {
                    continue; // article 详情等非菜单页
                }
            }
            if (isCategory) {
                categories.add(new SiteContentSpec.CatalogItem(title, name));
            } else {
                singlePages.add(new SiteContentSpec.CatalogItem(title, name));
            }
            menus.add(new SiteContentSpec.NavItem(title, navType, name, null));
        }
        // 菜单上限截断（与 PageSpecValidator.MAX_TOP_MENUS 同口径防规划失控；首页恒在首位）
        if (menus.size() > MAX_NAV_ITEMS) {
            menus = new ArrayList<>(menus.subList(0, MAX_NAV_ITEMS));
        }
        // 演示文章（预览用，通用占位与管线内置演示数据同性质；有分类才有列表页可展示）
        List<SiteContentSpec.PreviewArticle> articles = new ArrayList<>();
        if (!categories.isEmpty()) {
            articles.add(new SiteContentSpec.PreviewArticle("公司动态持续更新中", "站点最新动态与公告，正式内容发布后自动替换。"));
            articles.add(new SiteContentSpec.PreviewArticle("行业资讯与深度观察", "精选行业相关资讯，帮助访客了解领域趋势。"));
            articles.add(new SiteContentSpec.PreviewArticle("团队故事与幕后花絮", "介绍团队日常工作与产品背后的故事。"));
        }
        return new SiteContentSpec(menus, categories, singlePages, articles);
    }

    /** 站点名：nav brand 文本 → 设计稿 title → 默认 */
    private String deriveSiteName(DesignBundle bundle) {
        PageDesign anchor = bundle.pages().get(0);
        if (anchor.nav() != null) {
            Element brand = anchor.nav().selectFirst("a,span,strong");
            if (brand != null && StringUtils.hasText(brand.text())) {
                return brand.text().trim();
            }
        }
        String title = anchor.doc().title();
        return StringUtils.hasText(title) ? title.trim() : "AI 设计站点";
    }

    /** 主色：设计稿 --c-primary → 方向资产主色 → 引擎默认 */
    private String derivePrimaryColor(DesignBundle bundle, DesignDirectionLibrary.DesignDirectionAsset direction) {
        String c = bundle.rootTokens().get("--c-primary");
        if (StringUtils.hasText(c) && c.startsWith("#")) {
            return c;
        }
        if (direction != null && StringUtils.hasText(direction.primaryColor())) {
            return direction.primaryColor();
        }
        return TokenEngine.DEFAULT_PRIMARY_COLOR;
    }

    // ==================== CSS 后处理（Step 3 收尾） ====================

    /**
     * CSS 三件套落定（每轮渲染后执行——render 覆盖产物）：
     * tokens.css = TokenEngine 色阶 + 语义别名 + 设计稿 :root --c-* 原值（custom 区块 var() 引用依据）；
     * upgrade.css = 运行时 utility 全集（custom 区块任意 Tailwind 类的覆盖网）；
     * _layout.html = </head> 前注入 upgrade.css link（幂等）+ 占位 SVG 复制进 static/images/design/
     */
    private void applyCssPostProcess(ConvertContext ctx, DesignBundle bundle, DesignSseSink sse) {
        try {
            Path cssDir = ctx.workDir().resolve("static/css");
            Files.createDirectories(cssDir);
            // tokens.css 追加（幂等：去掉上次追加段再写，重渲染覆盖后必然不含，直接追加即可）
            String primary = derivePrimaryColor(bundle, ctx.direction());
            String preset = ctx.direction() != null && StringUtils.hasText(ctx.direction().stylePreset())
                    ? ctx.direction().stylePreset() : TokenEngine.DEFAULT_PRESET;
            StringBuilder tokens = new StringBuilder();
            tokens.append(tokenEngine.generateTokens(primary, preset)).append('\n');
            tokens.append(styleUpgrader.tokenAliases());
            if (!bundle.rootTokens().isEmpty()) {
                tokens.append("\n/* 设计稿 :root 变量（转化段追加，custom 区块引用依据） */\n:root {\n");
                bundle.rootTokens().forEach((k, v) -> tokens.append("  ").append(k).append(": ").append(v).append(";\n"));
                tokens.append("}\n");
            }
            Files.writeString(cssDir.resolve("tokens.css"), tokens.toString(), StandardCharsets.UTF_8);
            // upgrade.css
            Files.writeString(cssDir.resolve("upgrade.css"), styleUpgrader.generateUpgradeCss(),
                    StandardCharsets.UTF_8);
            // _layout.html 注入 upgrade.css link（幂等）
            Path layout = ctx.workDir().resolve(AiTemplateConstants.FILE_LAYOUT);
            if (Files.isRegularFile(layout)) {
                String content = Files.readString(layout, StandardCharsets.UTF_8);
                if (!content.contains("upgrade.css")) {
                    int headIdx = content.lastIndexOf("</head>");
                    String inject = "<#-- 设计稿转化：utility 体系 -->\n"
                            + "<link rel=\"stylesheet\" href=\"${ctx()}/css/upgrade.css\">\n";
                    content = headIdx >= 0
                            ? new StringBuilder(content).insert(headIdx, inject).toString()
                            : inject + content;
                    Files.writeString(layout, content, StandardCharsets.UTF_8);
                }
            }
            // 占位 SVG 复制进模板静态目录（custom 区块 img 引用 ${ctx()}/images/design/）
            copyDesignAssets(ctx);
        } catch (IOException e) {
            throw new IllegalStateException("CSS 后处理失败: " + e.getMessage(), e);
        }
    }

    /** design/assets/*.svg → static/images/design/（幂等复制） */
    private void copyDesignAssets(ConvertContext ctx) throws IOException {
        Path srcDir = ctx.workDir().resolve("design/assets");
        if (!Files.isDirectory(srcDir)) {
            return;
        }
        Path targetDir = ctx.workDir().resolve("static/images/design");
        Files.createDirectories(targetDir);
        try (var stream = Files.list(srcDir)) {
            for (Path src : stream.filter(Files::isRegularFile).toList()) {
                Path target = targetDir.resolve(src.getFileName());
                if (!Files.exists(target) || Files.size(src) != Files.size(target)) {
                    Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ==================== Step 4 脚本迁移与锚点垫片 ====================

    /**
     * 交互脚本迁移：设计稿 inline script 注入 _layout.html（</body> 前）；
     * 脚本引用锚点在产物缺失（组件替换区块）时注入确定性防御垫片——
     * detached 元素兜底 getElementById，防交互脚本因元素不存在而 TypeError 中断。
     *
     * <p>R2 大脚本外置：超过阈值（{@link #EXTERNAL_SCRIPT_THRESHOLD}）的脚本写
     * {@code static/js/imported.js}，layout 引 {@code <script src="${ctx()}/js/imported.js">}
     * （决议 D2：目录只在有外置脚本时创建）；小脚本仍内联省一次请求，垫片始终内联。</p>
     */
    private void migrateScripts(ConvertContext ctx, DesignBundle bundle, DesignSseSink sse) {
        if (!StringUtils.hasText(bundle.scripts())) {
            return;
        }
        try {
            Path layout = ctx.workDir().resolve(AiTemplateConstants.FILE_LAYOUT);
            if (!Files.isRegularFile(layout)) {
                return;
            }
            String content = Files.readString(layout, StandardCharsets.UTF_8);
            if (content.contains("fastcms-design-scripts")) {
                return; // 幂等
            }
            // 锚点缺失判定：脚本引用 id vs 产物 HTML/JS 文件实际 id
            List<String> missing = new ArrayList<>();
            for (String anchor : bundle.scriptAnchors()) {
                if (!productHasId(ctx, anchor)) {
                    missing.add(anchor);
                }
            }
            StringBuilder inject = new StringBuilder();
            inject.append("<#-- fastcms-design-scripts: 设计稿交互脚本（转化段迁移） -->\n");
            if (!missing.isEmpty()) {
                inject.append("""
                        <script>
                        /* 转化垫片：区块被组件替换后部分锚点缺失，detached 元素兜底防脚本中断 */
                        (function () {
                          if (!window.__fastcmsAnchorShim) {
                            var phantom = document.createElement('div');
                            window.__fastcmsAnchorShim = true;
                            var orig = document.getElementById.bind(document);
                            document.getElementById = function (id) {
                              return orig(id) || phantom;
                            };
                          }
                        })();
                        </script>
                        """);
                sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, "设计稿交互脚本引用的锚点 "
                        + String.join("、", missing) + " 已被组件化替换，已注入防御垫片（相关交互由组件内置行为承担）");
            }
            // R2 阈值分流：大脚本外置 static/js/imported.js（内联大段脚本会让 layout 臃肿难维护），
            // 小脚本内联；imported.js 的注册由 persistProducts 的 extra 清单兜底
            if (bundle.scripts().length() > EXTERNAL_SCRIPT_THRESHOLD) {
                Path jsFile = ctx.workDir().resolve(AiTemplateConstants.DIR_STATIC_JS).resolve("imported.js");
                Files.createDirectories(jsFile.getParent());
                Files.writeString(jsFile, bundle.scripts(), StandardCharsets.UTF_8);
                inject.append("<script src=\"${ctx()}/js/imported.js\"></script>\n");
            } else {
                inject.append("<script>\n").append(bundle.scripts()).append("</script>\n");
            }
            int bodyIdx = content.lastIndexOf("</body>");
            content = bodyIdx >= 0
                    ? new StringBuilder(content).insert(bodyIdx, inject.toString()).toString()
                    : content + inject;
            Files.writeString(layout, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("交互脚本迁移失败（不阻塞转化）: {}", e.getMessage());
        }
    }

    /** 产物 HTML/JS 文件是否含 id 锚点（值匹配 id="x" 形态） */
    private boolean productHasId(ConvertContext ctx, String id) {
        try (var stream = Files.walk(ctx.workDir())) {
            Pattern p = Pattern.compile("id=[\"']" + Pattern.quote(id) + "[\"']");
            return stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".html") || name.endsWith(".ftl");
                    })
                    .anyMatch(f -> {
                        try {
                            return p.matcher(Files.readString(f, StandardCharsets.UTF_8)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 收尾：持久化 / 报告 / 状态 ====================

    /** 产物文件注册进会话文件表 + SSE file 事件 + switch-file（index.html） */
    private void persistProducts(ConvertContext ctx, List<String> writtenFiles, DesignSseSink sse) {
        List<String> ordered = new ArrayList<>(writtenFiles);
        // 后处理改动的文件（tokens.css/upgrade.css/_layout.html/R2 外置脚本 + 复制的 SVG）确保在场
        for (String extra : List.of("static/css/tokens.css", "static/css/upgrade.css",
                AiTemplateConstants.FILE_LAYOUT,
                AiTemplateConstants.DIR_STATIC_JS + "/imported.js")) {
            if (Files.isRegularFile(ctx.workDir().resolve(extra)) && !ordered.contains(extra)) {
                ordered.add(extra);
            }
        }
        try (var stream = Files.walk(ctx.workDir().resolve("static/images/design"))) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = ctx.workDir().relativize(p).normalize().toString().replace('\\', '/');
                if (!ordered.contains(rel)) {
                    ordered.add(rel);
                }
            });
        } catch (IOException ignored) {
            // 目录不存在：无 SVG 需要注册
        }
        boolean switched = false;
        for (String relPath : ordered) {
            try {
                String content = Files.readString(ctx.workDir().resolve(relPath), StandardCharsets.UTF_8);
                fileService.saveOrUpdateFile(ctx.session().getSessionId(), relPath, content,
                        AiTemplateConstants.ACTION_CREATE);
                Map<String, String> fileData = new LinkedHashMap<>();
                fileData.put("path", relPath);
                fileData.put("action", AiTemplateConstants.ACTION_CREATE);
                sse.send(AiTemplateConstants.SSE_EVENT_FILE, JSON_MAPPER.writeValueAsString(fileData));
                if (!switched && "index.html".equals(relPath)) {
                    switched = true;
                    Map<String, String> switchData = new LinkedHashMap<>();
                    switchData.put("path", "index.html");
                    sse.send(AiTemplateConstants.SSE_EVENT_SWITCH_FILE, JSON_MAPPER.writeValueAsString(switchData));
                }
            } catch (Exception e) {
                log.warn("转化产物注册失败: sessionId={}, path={}", ctx.session().getSessionId(), relPath, e);
            }
        }
    }

    private List<PageResult> buildPageResults(DesignBundle bundle, RenderAttempt attempt) {
        List<PageResult> results = new ArrayList<>();
        for (PageDesign page : bundle.pages()) {
            String pageKey = page.plan().fastcmsPageKey();
            if (attempt.failedPages().contains(pageKey)) {
                results.add(new PageResult(pageKey, page.plan().title(), PageOutcome.FAILED,
                        "渲染降级轮耗尽，页面可能异常（设计稿保留，可重新发消息转化）"));
            } else if (attempt.degradedByPage().containsKey(pageKey)) {
                results.add(new PageResult(pageKey, page.plan().title(), PageOutcome.DEGRADED,
                        attempt.degradedByPage().get(pageKey) == 1
                                ? "组件区块已降级为自定义区块" : "整页降级为自定义区块（设计原样保留）"));
            } else {
                results.add(new PageResult(pageKey, page.plan().title(), PageOutcome.OK, ""));
            }
        }
        return results;
    }

    private ConvertStatus classifyStatus(List<PageResult> pageResults) {
        long failed = pageResults.stream().filter(r -> r.outcome() == PageOutcome.FAILED).count();
        if (pageResults.isEmpty() || failed * 2 >= pageResults.size()) {
            return ConvertStatus.MAJOR_FAILURE;
        }
        return pageResults.stream().anyMatch(r -> r.outcome() != PageOutcome.OK)
                ? ConvertStatus.DONE_WITH_DEGRADATION : ConvertStatus.DONE;
    }

    /** 结构一致性报告（§6.2 收尾：区块数/tokens/锚点 设计稿 vs 模板） */
    private List<String> buildReport(ConvertContext ctx, DesignBundle bundle,
                                     Map<String, SectionMapping> mappingByUnit, RenderAttempt attempt) {
        List<String> report = new ArrayList<>();
        int mapped = 0;
        int custom = 0;
        int contentBody = 0;
        for (SectionMapping m : mappingByUnit.values()) {
            if (MAP_CUSTOM.equals(m.component())) {
                custom++;
            } else if (MAP_CONTENT_BODY.equals(m.component())) {
                contentBody++;
            } else {
                mapped++;
            }
        }
        report.add("区块映射：组件化 " + mapped + " / 自定义 " + custom
                + (contentBody > 0 ? " / 正文占位 " + contentBody : 0)
                + "（共 " + mappingByUnit.size() + "）");
        report.add("tokens：设计变量 " + bundle.rootTokens().size()
                + " 个已并入 tokens.css，主色 " + derivePrimaryColor(bundle, ctx.direction()));
        if (bundle.scripts() != null) {
            report.add("交互脚本：已迁移（引用锚点 " + bundle.scriptAnchors().size() + " 个）");
        }
        long ok = attempt.failedPages().isEmpty() ? 1 : 0;
        report.add("渲染校验：" + (attempt.failedPages().isEmpty()
                ? "全部页面通过" : "失败页 " + String.join("、", attempt.failedPages())));
        return report;
    }

    /** R3 收尾扫描：产物 html（含 _layout.html）任一含 menuTag 即视为菜单已接入 CMS */
    private boolean anyProductHasMenuTag(ConvertContext ctx, List<String> writtenFiles) {
        for (String relPath : writtenFiles) {
            if (!relPath.endsWith(".html")) {
                continue;
            }
            try {
                if (Files.readString(ctx.workDir().resolve(relPath), StandardCharsets.UTF_8)
                        .contains("menuTag")) {
                    return true;
                }
            } catch (IOException e) {
                // 单文件读失败不阻断扫描
            }
        }
        return false;
    }

}
