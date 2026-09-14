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

import com.fastcms.ai.autoconfigure.FastcmsAiProperties;
import com.fastcms.ai.component.DesignDirectionLibrary;
import com.fastcms.ai.service.IAiTemplateMessageService;
import com.fastcms.ai.template.AiTemplateConstants;
import com.fastcms.entity.AiTemplateSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 设计稿先行模式状态机编排器（见 doc/wiki/ai-template-two-mode-design.md §2.4/§6.1）
 *
 * <p><b>状态落盘 {@code workDir/design/plan.json}（文件即状态，重启/断线可恢复）</b>，状态机：</p>
 * <pre>
 * DESIGNING → AUDITING →（审计通过 ∧ confirmAuto）→ CONVERTING → DONE
 *                  │         └→（审计失败且 &lt; max-audit-rounds）→ DESIGNING（仅重出问题页）
 *                  │         └→（审计失败 ≥ max-audit-rounds）→ AWAITING_CONFIRM（人工兜底）
 *                  └→（confirmAuto=false 且审计通过）→ AWAITING_CONFIRM
 * AWAITING_CONFIRM →（approve）→ CONVERTING → DONE
 * AWAITING_CONFIRM →（reject + input 作修改意见）→ DESIGNING（全页重出）
 * 任意状态 →（FAILED：模型调用级异常）→ FAILED（保留已有文件，重新发消息按进度续传）
 * </pre>
 *
 * <p><b>AWAITING_CONFIRM 不占线程</b>：推送 {@code confirm_request} 事件后本方法直接返回
 * （线程池归还，SseEmitter 生命周期由宿主 chatStream 收口）；下一次 chat 调用从 plan.json
 * 恢复状态推进。宿主在 doChatStream 分流处调用本类（§6.1 允许的修改点），
 * {@link DesignCancelledException} 由宿主翻译为管线取消语义。</p>
 *
 * <p><b>续传语义</b>：DONE 态会话由宿主分流落回统一管线（产物与管线同构，§7.1 不感知设计稿
 * 血统）；FAILED 态按进度归位（有未完成页 → DESIGNING 续出，无 → AUDITING 复审）；
 * DESIGNING/AUDITING/CONVERTING 中断后原位续传（设计稿 done 页跳过、转化映射走 mappingCache）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
@Component
public class MockupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MockupOrchestrator.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** plan.json 版本（结构演进时递增，读取端按版本兼容） */
    private static final int PLAN_VERSION = 1;

    /** 历史条目上限（防病态增长，超出丢最旧） */
    private static final int HISTORY_MAX = 50;

    // ==================== 状态与动作常量（plan.json / confirm_request 协议字段） ====================

    public static final String STATE_DESIGNING = "DESIGNING";
    public static final String STATE_AUDITING = "AUDITING";
    public static final String STATE_AWAITING_CONFIRM = "AWAITING_CONFIRM";
    public static final String STATE_CONVERTING = "CONVERTING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_FAILED = "FAILED";

    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";

    /** 页面状态：待设计 / 完成（含占位页降级另计） */
    private static final String PAGE_PENDING = "pending";
    private static final String PAGE_DONE = "done";
    private static final String PAGE_PLACEHOLDER = "placeholder";

    // ==================== 依赖 ====================

    private final MockupDesignService designService;
    private final MockupConverter converter;
    private final IAiTemplateMessageService messageService;
    private final FastcmsAiProperties aiProperties;
    private final com.fastcms.ai.template.htmlimport.ImportService importService;

    public MockupOrchestrator(MockupDesignService designService, MockupConverter converter,
                              IAiTemplateMessageService messageService, FastcmsAiProperties aiProperties,
                              com.fastcms.ai.template.htmlimport.ImportService importService) {
        this.designService = designService;
        this.converter = converter;
        this.messageService = messageService;
        this.aiProperties = aiProperties;
        this.importService = importService;
    }

    // ==================== plan.json 模型 ====================

    /**
     * 单页状态（§2.4 pages[] 条目）
     *
     * @param name    页面名（design/&lt;name&gt;.html，与 DesignPagePlanner 规划一致）
     * @param title   页面中文标题（播报文案用）
     * @param html    设计稿相对路径（design/&lt;name&gt;.html）
     * @param status  pending / done / placeholder
     * @param pageKey fastcms 页面 key（index / article_list / page_about…；
     *                import 会话的转化页面来源——plan.json 自包含，不依赖 requirement 规划；
     *                design 会话从 DesignPagePlanner 快照，旧 plan.json 缺字段时为 null 不影响主流程）
     */
    public record PageState(String name, String title, String html, String status, String pageKey) {
        PageState withStatus(String newStatus) {
            return new PageState(name, title, html, newStatus, pageKey);
        }
    }

    /**
     * 设计会话计划（§2.4 plan.json 结构）
     *
     * @param version       结构版本
     * @param state         当前状态机状态
     * @param direction     设计方向 key（会话创建时快照；资产解析按 key 实时查，缺失按 AI 自选）
     * @param pages         逐页状态（规划顺序）
     * @param mappingCache  转化映射缓存（CONVERTING 断点续传：已映射区块不重问 AI）
     * @param pendingIssues 上轮机器审计问题指令（审计修正轮注入设计提示词；审计通过清空）
     * @param userComment   用户否决意见（REJECT 携带；注入设计提示词，进入 AUDITING 后清空）
     * @param auditRounds   本轮设计迭代已消耗的审计失败次数（REJECT 重出时归零）
     * @param history       关键事件账本（AUDIT_FAIL/CONFIRM/CONVERT…，人读 + 诊断用）
     */
    public record DesignPlan(int version, String state, String direction,
                             List<PageState> pages, List<MockupConverter.SectionMapping> mappingCache,
                             List<String> pendingIssues, String userComment,
                             int auditRounds, List<String> history) {

        DesignPlan withState(String newState) {
            return new DesignPlan(version, newState, direction, pages, mappingCache,
                    pendingIssues, userComment, auditRounds, history);
        }

        DesignPlan withPages(List<PageState> newPages) {
            return new DesignPlan(version, state, direction, newPages, mappingCache,
                    pendingIssues, userComment, auditRounds, history);
        }

        DesignPlan withMappingCache(List<MockupConverter.SectionMapping> cache) {
            return new DesignPlan(version, state, direction, pages, cache,
                    pendingIssues, userComment, auditRounds, history);
        }

        DesignPlan withPendingIssues(List<String> issues) {
            return new DesignPlan(version, state, direction, pages, mappingCache,
                    issues, userComment, auditRounds, history);
        }

        DesignPlan withUserComment(String comment) {
            return new DesignPlan(version, state, direction, pages, mappingCache,
                    pendingIssues, comment, auditRounds, history);
        }

        DesignPlan withAuditRounds(int rounds) {
            return new DesignPlan(version, state, direction, pages, mappingCache,
                    pendingIssues, userComment, rounds, history);
        }

        DesignPlan appendHistory(String entry) {
            List<String> next = new ArrayList<>(history == null ? List.of() : history);
            next.add(entry);
            while (next.size() > HISTORY_MAX) {
                next.remove(0);
            }
            return new DesignPlan(version, state, direction, pages, mappingCache,
                    pendingIssues, userComment, auditRounds, List.copyOf(next));
        }
    }

    // ==================== 宿主分流辅助 ====================

    /**
     * 读取当前设计计划状态（宿主 doChatStream 分流判定用：DONE 态落回统一管线微调路径，§7.1）
     *
     * @return 状态名；无 plan.json / 解析失败返回 null（视作未完成，进编排器）
     */
    public static String currentState(Path workDir) {
        DesignPlan plan = readPlan(workDir);
        return plan == null ? null : plan.state();
    }

    /**
     * 设计页进度统计（宿主停止中断消息播报用）："已完成/总数" 页数
     *
     * @return 如 "3/5"；无 plan.json / 解析失败返回 null（调用方回退通用文案）
     */
    public static String describePageProgress(Path workDir) {
        try {
            DesignPlan plan = readPlan(workDir);
            if (plan == null || plan.pages().isEmpty()) {
                return null;
            }
            long done = plan.pages().stream()
                    .filter(p -> PAGE_DONE.equals(p.status())).count();
            return done + "/" + plan.pages().size();
        } catch (Exception e) {
            log.warn("设计页进度统计失败: workDir={}", workDir, e);
            return null;
        }
    }

    // ==================== 主入口 ====================

    /**
     * 推进设计会话状态机（每次 chat 调用进入一次）
     *
     * <p>返回 = 本轮推进结束（AWAITING_CONFIRM 等待确认 / DONE 转化完成 / 入参非法提示）；
     * 模型调用级异常向上抛（宿主按失败口径落库 MSG_FAIL_PREFIX 消息，本类已先把状态持久化为
     * FAILED）；客户端断开抛 {@link DesignCancelledException}。</p>
     *
     * @param session       会话实体（design 模式、生成型）
     * @param workDir       会话工作目录（宿主 resolveEffectiveWorkDir 解析后传入）
     * @param userInput     用户输入（REJECT 时为修改意见；AWAITING_CONFIRM 态普通输入按否决意见处理）
     * @param confirmAction 确认动作 APPROVE / REJECT / null（普通消息）
     * @param sse           SSE 通道（事件名与管线同构）
     */
    public void run(AiTemplateSession session, Path workDir, String userInput, String confirmAction, DesignSseSink sse) {
        String sessionId = session.getSessionId();
        DesignPlan plan = readPlan(workDir);

        // 1. 确认动作合法性（无效动作不动状态机，明确提示）
        String action = normalizeAction(confirmAction);
        if (action == null && StringUtils.hasText(confirmAction)) {
            sendError(sse, "无效的确认动作: " + confirmAction + "（仅支持 APPROVE/REJECT）");
            return;
        }
        if (action != null && plan == null) {
            sendError(sse, "会话尚无设计稿，无需确认");
            return;
        }

        // 2. DONE 防御：宿主已分流（DONE 落回管线），此处兜底提示（并发窗口/手工调用）
        if (plan != null && STATE_DONE.equals(plan.state())) {
            sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, "设计稿已转化完成，请直接继续对话微调模板。\n");
            return;
        }

        // 3. 用户输入/确认动作落消息表（会话历史可追溯；空输入的 APPROVE/REJECT 落动作标记）
        persistUserAction(sessionId, userInput, action);

        // 4. 状态归位
        boolean importMode = AiTemplateConstants.isImportMode(session);
        if (plan == null) {
            if (importMode) {
                // 导入会话的 plan.json 由 ingest 生成；缺失 = 导入产物丢失，
                // 设计稿重出不适用于导入源（重新导入文件即可恢复，明确提示）
                sendError(sse, "会话缺少导入内容（plan.json 丢失），请重新上传 HTML 文件导入");
                return;
            }
            plan = createInitialPlan(session);
        } else if (STATE_AWAITING_CONFIRM.equals(plan.state())) {
            if (ACTION_APPROVE.equals(action)) {
                plan = plan.appendHistory("CONFIRM: approve").withState(STATE_CONVERTING);
            } else {
                // REJECT（或 AWAITING_CONFIRM 态的普通输入——旧客户端/直接 API 调用没有确认按钮，
                // 自然语言修改意见按否决回流，与升级管线 feedback 同口径）
                String comment = StringUtils.hasText(userInput) ? userInput.trim() : null;
                String marker = ACTION_REJECT.equals(action) ? "CONFIRM: reject" : "CONFIRM: reject(input)";
                plan = resetForRedesign(plan, comment).appendHistory(marker);
            }
        } else if (STATE_FAILED.equals(plan.state())) {
            // FAILED 续传归位：导入会话回 CONVERTING（mappingCache 续传重试转化）；
            // 设计会话有未完成页 → 续设计，无 → 复审（审计纯代码，重跑无成本）
            plan = plan.withState(importMode ? STATE_CONVERTING
                            : (hasPendingPage(plan) ? STATE_DESIGNING : STATE_AUDITING))
                    .appendHistory("RESUME: from FAILED");
        }
        persistPlan(workDir, plan);

        // 5. 状态机主循环（DESIGNING → AUDITING →（修正/确认/转化）…）；
        // 导入会话的页面来自 plan.json（ingest 推导，pageKey 自包含，不依赖 requirement 规划）
        List<DesignPagePlanner.PagePlan> allPages = importMode
                ? importPagePlans(plan) : DesignPagePlanner.plan(session.getRequirement());
        try {
            while (true) {
                if (sse.isCancelled()) {
                    throw new DesignCancelledException();
                }
                switch (plan.state()) {
                    case STATE_DESIGNING -> plan = runDesigning(session, workDir, plan, allPages, sse);
                    case STATE_AUDITING -> {
                        plan = runAuditing(session, workDir, plan, allPages, sse);
                        if (plan == null) {
                            // 已进入 AWAITING_CONFIRM 并完成播报，本轮结束
                            return;
                        }
                    }
                    case STATE_CONVERTING -> {
                        runConverting(session, workDir, plan, allPages, sse);
                        return;
                    }
                    default -> throw new IllegalStateException("设计计划状态非法: " + plan.state());
                }
            }
        } catch (DesignCancelledException ce) {
            throw ce;
        } catch (Exception e) {
            // FAILED：保留已有文件与 mappingCache（断点续传），重新发消息按进度继续
            persistPlan(workDir, plan.withState(STATE_FAILED)
                    .appendHistory("FAILED: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            throw e;
        }
    }

    // ==================== 状态处理 ====================

    /**
     * DESIGNING：设计未完成页（done 页跳过 = 断点续传；placeholder 页重试 = 降级页有机会翻盘）
     *
     * @return 推进后的计划（状态已置 AUDITING 并落盘）
     */
    private DesignPlan runDesigning(AiTemplateSession session, Path workDir, DesignPlan plan,
                                    List<DesignPagePlanner.PagePlan> allPages, DesignSseSink sse) {
        // lambda 引用终态副本（plan 在方法内会被重新赋值）
        final DesignPlan before = plan;
        List<DesignPagePlanner.PagePlan> pendingPages = allPages.stream()
                .filter(p -> !PAGE_DONE.equals(pageStatus(before, p.name())))
                .toList();
        if (!pendingPages.isEmpty()) {
            // 页级进度即时持久化：单页 DONE 落盘即写回 plan.json（中断重入不重做该页）。
            // planRef 持有可变引用——lambda 内更新，设计结束后以最新副本为基应用 outcome
            final DesignPlan[] planRef = {plan};
            MockupDesignService.DesignContext ctx = new MockupDesignService.DesignContext(
                    session, workDir, session.getRequirement(), directionAsset(plan),
                    isMobileAdaptive(session), allPages,
                    plan.pendingIssues(), plan.userComment(),
                    pageName -> {
                        planRef[0] = markPageDone(planRef[0], pageName);
                        persistPlan(workDir, planRef[0]);
                    });
            MockupDesignService.DesignOutcome outcome = designService.designPages(ctx, pendingPages, sse);
            plan = applyDesignOutcome(planRef[0], outcome);
        }
        // userComment 单轮消费（已在设计提示词注入），pendingIssues 保留至审计重判
        plan = plan.withUserComment(null).withState(STATE_AUDITING);
        persistPlan(workDir, plan);
        return plan;
    }

    /**
     * AUDITING：A1~A7 机器审计 → 通过（自动/人工确认）/ 修正轮重设计 / 轮次耗尽人工兜底
     *
     * @return 推进后的计划（CONVERTING 或 DESIGNING）；null = 已进入 AWAITING_CONFIRM（本轮结束）
     */
    private DesignPlan runAuditing(AiTemplateSession session, Path workDir, DesignPlan plan,
                                   List<DesignPagePlanner.PagePlan> allPages, DesignSseSink sse) {
        Set<String> placeholders = new LinkedHashSet<>();
        for (PageState p : plan.pages()) {
            if (PAGE_PLACEHOLDER.equals(p.status())) {
                placeholders.add(p.name());
            }
        }
        List<MockupAuditor.AuditIssue> issues = MockupAuditor.audit(
                workDir, allPages, placeholders, isMobileAdaptive(session));

        if (issues.isEmpty()) {
            boolean confirmAuto = Boolean.TRUE.equals(session.getConfirmAuto());
            plan = plan.withPendingIssues(List.of()).appendHistory("AUDIT_PASS");
            if (confirmAuto) {
                plan = plan.withState(STATE_CONVERTING);
                persistPlan(workDir, plan);
                return plan;
            }
            plan = plan.withState(STATE_AWAITING_CONFIRM);
            persistPlan(workDir, plan);
            enterAwaitingConfirm(session, plan, List.of(), sse);
            return null;
        }

        List<String> instructions = issues.stream().map(MockupAuditor.AuditIssue::toInstruction).toList();
        int nextRound = plan.auditRounds() + 1;
        String codes = issues.stream().map(MockupAuditor.AuditIssue::code)
                .distinct().reduce((a, b) -> a + "/" + b).orElse("?");
        if (nextRound < Math.max(1, aiProperties.getTemplate().getDesign().getMaxAuditRounds()) + 1
                && nextRound <= Math.max(1, aiProperties.getTemplate().getDesign().getMaxAuditRounds())) {
            // 修正轮：仅重出问题页（含 A4 跨页差异页），已通过页不重设计（省 token 且防已过项回归）
            sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                    "审计发现 " + issues.size() + " 个问题（" + codes + "），正在修正…\n");
            plan = plan.withAuditRounds(nextRound).withPendingIssues(instructions)
                    .withPages(resetIssuePages(plan, issues))
                    .withState(STATE_DESIGNING)
                    .appendHistory("AUDIT_FAIL(" + nextRound + "): " + codes);
            persistPlan(workDir, plan);
            return plan;
        }
        // 轮次耗尽：人工兜底（问题清单随 confirm_request 展示，用户可否决重出或确认放行）
        plan = plan.withPendingIssues(instructions).withState(STATE_AWAITING_CONFIRM)
                .appendHistory("AUDIT_FAIL(exhausted): " + codes);
        persistPlan(workDir, plan);
        enterAwaitingConfirm(session, plan, instructions, sse);
        return null;
    }

    /**
     * CONVERTING：五步转化（mappingCache 断点续传）→ DONE 收尾 / MAJOR_FAILURE 人工兜底
     */
    private void runConverting(AiTemplateSession session, Path workDir, DesignPlan plan,
                               List<DesignPagePlanner.PagePlan> allPages, DesignSseSink sse) {
        // 批次级进度即时持久化：每批映射完成即写回 plan.json（中断重入已映射批次不重问 AI）
        final DesignPlan[] planRef = {plan};
        MockupConverter.ConvertContext ctx = new MockupConverter.ConvertContext(
                session, workDir, session.getRequirement(), directionAsset(plan),
                isMobileAdaptive(session), allPages, plan.mappingCache(),
                mappings -> {
                    planRef[0] = planRef[0].withMappingCache(mappings);
                    persistPlan(workDir, planRef[0]);
                });
        MockupConverter.ConvertOutcome outcome = converter.convert(ctx, sse);
        plan = planRef[0].withMappingCache(outcome.mappings());

        if (outcome.status() == MockupConverter.ConvertStatus.MAJOR_FAILURE) {
            List<String> failures = outcome.pageResults().stream()
                    .filter(r -> r.outcome() == MockupConverter.PageOutcome.FAILED)
                    .map(r -> r.pageKey() + ": " + (r.note() == null ? "渲染失败" : r.note()))
                    .toList();
            plan = plan.withPendingIssues(failures).withState(STATE_AWAITING_CONFIRM)
                    .appendHistory("CONVERT: MAJOR_FAILURE");
            persistPlan(workDir, plan);
            enterAwaitingConfirm(session, plan, failures, sse);
            return;
        }

        plan = plan.withState(STATE_DONE).appendHistory("CONVERT: " + outcome.status());
        persistPlan(workDir, plan);
        // 导入会话收尾接线（外部 CSS 注入 layout head + 资产文件注册；
        // 先于 DONE 播报，file 事件全部送达后才发 done，前端文件树完整）
        if (AiTemplateConstants.isImportMode(session)) {
            importService.postConvertWiring(session, workDir, sse);
        }
        String summary = buildDoneSummary(outcome);
        sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, summary);
        sendDone(sse, summary);
        saveAssistant(session.getSessionId(), summary);
        log.info("设计稿转化完成: sessionId={}, status={}, pages={}",
                session.getSessionId(), outcome.status(), outcome.pageResults().size());
    }

    // ==================== AWAITING_CONFIRM 播报 ====================

    /**
     * 进入等待人工确认：推送 confirm_request 事件（data 结构 §7.4）+ 助手消息落库，随后返回
     *
     * <p>不占线程：调用链逐层 return，SSE 生命周期由宿主 chatStream 收口；
     * 下一次 chat 调用（confirmAction 或普通输入）从 plan.json 恢复推进。</p>
     */
    private void enterAwaitingConfirm(AiTemplateSession session, DesignPlan plan,
                                      List<String> issues, DesignSseSink sse) {
        sse.send(AiTemplateConstants.SSE_EVENT_CONFIRM_REQUEST,
                toJson(buildConfirmCardData(session, issues, designPreviewUrl(session))));

        String previewUrl = designPreviewUrl(session);
        StringBuilder msg = new StringBuilder("设计稿已就绪，等待人工确认（预览：").append(previewUrl).append("）");
        if (!issues.isEmpty()) {
            msg.append("\n待处理问题：");
            for (String issue : issues) {
                msg.append("\n- ").append(issue);
            }
        }
        saveAssistant(session.getSessionId(), msg.toString());
        log.info("设计稿等待人工确认: sessionId={}, issues={}", session.getSessionId(), issues.size());
    }

    /** DONE 收尾播报文案（页面结果 + 结构一致性报告 + 降级说明） */
    private String buildDoneSummary(MockupConverter.ConvertOutcome outcome) {
        StringBuilder sb = new StringBuilder("设计稿已转化为模板。\n页面结果：");
        for (MockupConverter.PageResult r : outcome.pageResults()) {
            sb.append("\n- ").append(r.title()).append("（").append(r.pageKey()).append("）：")
                    .append(r.outcome());
            if (StringUtils.hasText(r.note())) {
                sb.append("——").append(r.note());
            }
        }
        if (outcome.report() != null && !outcome.report().isEmpty()) {
            sb.append("\n结构一致性报告：");
            for (String line : outcome.report()) {
                sb.append("\n").append(line);
            }
        }
        if (outcome.status() == MockupConverter.ConvertStatus.DONE_WITH_DEGRADATION) {
            sb.append("\n（部分区块已降级为 custom 自定义宏，功能不受影响，可在后续对话中继续微调）");
        }
        return sb.toString();
    }

    /**
     * 构造确认卡片数据（confirm_request SSE 事件与 design-status 端点共用同一结构，§7.4）
     *
     * <p>issues 与 {@code plan.pendingIssues} 同步（三个进入路径均先 withPendingIssues 再播报），
     * 故刷新后经 {@link #awaitingConfirmCard} 恢复的卡片与实时播报内容一致。</p>
     */
    private static Map<String, Object> buildConfirmCardData(AiTemplateSession session,
                                                            List<String> issues, String previewUrl) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", STATE_AWAITING_CONFIRM);
        data.put("issues", issues == null ? List.of() : issues);
        data.put("previewUrl", previewUrl);
        data.put("confirmAuto", Boolean.TRUE.equals(session.getConfirmAuto()));
        return data;
    }

    /**
     * 设计稿预览地址（会话内设计目录入口页，前端确认卡片"查看设计稿"链接）
     */
    private static String designPreviewUrl(AiTemplateSession session) {
        return "/ai/template/preview/" + session.getSessionId() + "/"
                + session.getTemplateName() + "/design/index.html";
    }

    /**
     * 刷新恢复查询：plan 处于 AWAITING_CONFIRM 时返回确认卡片数据，其余状态（含无 plan）返回 null
     *
     * <p>供 design-status 端点使用——前端加载会话历史后据此重建确认卡片
     * （S2 验收：confirmAuto=false 时刷新后确认状态不丢）。</p>
     */
    public static Map<String, Object> awaitingConfirmCard(AiTemplateSession session, Path workDir) {
        DesignPlan plan = readPlan(workDir);
        if (plan == null || !STATE_AWAITING_CONFIRM.equals(plan.state())) {
            return null;
        }
        return buildConfirmCardData(session, plan.pendingIssues(), designPreviewUrl(session));
    }

    // ==================== plan 演进辅助 ====================

    /** 全新计划：页面规划落 pending，状态 DESIGNING */
    private DesignPlan createInitialPlan(AiTemplateSession session) {
        List<PageState> pages = DesignPagePlanner.plan(session.getRequirement()).stream()
                .map(p -> new PageState(p.name(), p.title(), "design/" + p.name() + ".html",
                        PAGE_PENDING, p.fastcmsPageKey()))
                .toList();
        return new DesignPlan(PLAN_VERSION, STATE_DESIGNING, session.getDesignDirection(),
                pages, List.of(), List.of(), null, 0, List.of());
    }

    /**
     * REJECT 重出：全页 pending + mappingCache/pendingIssues/auditRounds 清零 + 携带用户意见
     * （否决针对整体设计而非单页，全量重出语义最直观；done 页设计稿保留在盘但不再信任）
     */
    private DesignPlan resetForRedesign(DesignPlan plan, String comment) {
        List<PageState> pages = plan.pages().stream()
                .map(p -> p.withStatus(PAGE_PENDING)).toList();
        return plan.withPages(pages).withMappingCache(List.of()).withPendingIssues(List.of())
                .withAuditRounds(0).withUserComment(comment).withState(STATE_DESIGNING);
    }

    /**
     * 页级进度回写（pageDoneSink 的实现体）：单页 DONE 即时标记并落盘——
     * 中断（停止/失败）后重入 pendingPages 筛选即跳过该页，不重做。
     * 与 {@link #applyDesignOutcome} 幂等兼容（DONE 再应用 DONE 无副作用）
     */
    private DesignPlan markPageDone(DesignPlan plan, String pageName) {
        List<PageState> pages = plan.pages().stream()
                .map(p -> p.name().equals(pageName) ? p.withStatus(PAGE_DONE) : p)
                .toList();
        return plan.withPages(pages);
    }

    /** 设计结果回写页面状态（done / placeholder） */
    private DesignPlan applyDesignOutcome(DesignPlan plan, MockupDesignService.DesignOutcome outcome) {
        if (outcome.pages().isEmpty()) {
            return plan;
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (MockupDesignService.PageResult r : outcome.pages()) {
            byName.put(r.pageName(), r.status() == MockupDesignService.PageStatus.DONE ? PAGE_DONE : PAGE_PLACEHOLDER);
        }
        List<PageState> pages = plan.pages().stream()
                .map(p -> byName.containsKey(p.name()) ? p.withStatus(byName.get(p.name())) : p)
                .toList();
        return plan.withPages(pages);
    }

    /** 审计修正轮：问题页（含 A4 跨页差异页，page 字段以「、」连接）重置为 pending */
    private List<PageState> resetIssuePages(DesignPlan plan, List<MockupAuditor.AuditIssue> issues) {
        Set<String> issuePages = new LinkedHashSet<>();
        for (MockupAuditor.AuditIssue issue : issues) {
            for (String name : issue.page().split("、")) {
                if (StringUtils.hasText(name)) {
                    issuePages.add(name.trim());
                }
            }
        }
        return plan.pages().stream()
                .map(p -> issuePages.contains(p.name()) ? p.withStatus(PAGE_PENDING) : p)
                .toList();
    }

    private boolean hasPendingPage(DesignPlan plan) {
        return plan.pages().stream().anyMatch(p -> !PAGE_DONE.equals(p.status()));
    }

    private String pageStatus(DesignPlan plan, String pageName) {
        return plan.pages().stream()
                .filter(p -> p.name().equals(pageName))
                .map(PageState::status)
                .findFirst().orElse(PAGE_PENDING);
    }

    /** 方向资产：plan.direction 为创建时快照，资产缺失（如库裁剪）按 AI 自选处理 */
    private DesignDirectionLibrary.DesignDirectionAsset directionAsset(DesignPlan plan) {
        return StringUtils.hasText(plan.direction())
                ? DesignDirectionLibrary.get(plan.direction().trim()) : null;
    }

    /**
     * 导入会话页面规划：从 plan.json pages 恢复（ingest 由 PageKeyResolver 推导 pageKey，
     * plan.json 自包含；description 无对应来源，传 null 供转化段只作切分/装配依据）
     */
    private static List<DesignPagePlanner.PagePlan> importPagePlans(DesignPlan plan) {
        return plan.pages().stream()
                .map(p -> new DesignPagePlanner.PagePlan(p.name(), p.title(), null, p.pageKey()))
                .toList();
    }

    /** 移动端适配（与会话口径一致：null 视为 true） */
    private boolean isMobileAdaptive(AiTemplateSession session) {
        return session.getMobileAdaptive() == null || session.getMobileAdaptive();
    }

    // ==================== 消息与 SSE ====================

    /** 用户输入/确认动作落消息表（空输入的 APPROVE/REJECT 落动作标记，历史可追溯） */
    private void persistUserAction(String sessionId, String userInput, String action) {
        if (ACTION_APPROVE.equals(action)) {
            saveUser(sessionId, StringUtils.hasText(userInput) ? userInput.trim() : "（已确认设计稿，开始转化）");
        } else if (ACTION_REJECT.equals(action)) {
            saveUser(sessionId, StringUtils.hasText(userInput) ? userInput.trim() : "（要求修改设计稿）");
        } else if (StringUtils.hasText(userInput)) {
            saveUser(sessionId, userInput);
        }
    }

    private void saveUser(String sessionId, String content) {
        try {
            messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_USER, content);
        } catch (Exception e) {
            log.warn("设计会话用户消息落库失败: sessionId={}", sessionId, e);
        }
    }

    private void saveAssistant(String sessionId, String content) {
        try {
            messageService.saveMessage(sessionId, AiTemplateConstants.ROLE_ASSISTANT, content);
        } catch (Exception e) {
            log.warn("设计会话助手消息落库失败: sessionId={}", sessionId, e);
        }
    }

    private void sendError(DesignSseSink sse, String message) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("message", message);
        sse.send(AiTemplateConstants.SSE_EVENT_ERROR, toJson(data));
    }

    private void sendDone(DesignSseSink sse, String summary) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("summary", summary);
        sse.send(AiTemplateConstants.SSE_EVENT_DONE, toJson(data));
    }

    private String toJson(Object value) {
        return JSON_MAPPER.writeValueAsString(value);
    }

    // ==================== plan.json 读写 ====================

    private static Path planFile(Path workDir) {
        return workDir.resolve("design").resolve("plan.json");
    }

    private void persistPlan(Path workDir, DesignPlan plan) {
        try {
            Path file = planFile(workDir);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON_MAPPER.writeValueAsString(plan), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 状态落盘失败不中断主流程（内存态继续推进），但必须可见：断点续传精度受损
            log.error("设计计划落盘失败: workDir={}", workDir, e);
        }
    }

    private static DesignPlan readPlan(Path workDir) {
        Path file = planFile(workDir);
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            DesignPlan plan = JSON_MAPPER.readValue(raw, DesignPlan.class);
            return plan.version() == PLAN_VERSION ? plan : null;
        } catch (Exception e) {
            // 损坏/版本不识别：按无计划处理（重新设计全量页面，已落盘设计稿被 V5 视作在场）
            log.warn("设计计划读取失败（按全新计划处理）: {}", file, e);
            return null;
        }
    }

    // ==================== 其他 ====================

    /** 确认动作归一化：null/空白 → null；非法值原样返回 null 由调用方提示——此处返回大写规范值 */
    private String normalizeAction(String confirmAction) {
        if (!StringUtils.hasText(confirmAction)) {
            return null;
        }
        String normalized = confirmAction.trim().toUpperCase(Locale.ROOT);
        return ACTION_APPROVE.equals(normalized) || ACTION_REJECT.equals(normalized) ? normalized : null;
    }
}
