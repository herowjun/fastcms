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
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template.design;

import com.fastcms.ai.agent.AgentChatExecutor;
import com.fastcms.ai.agent.AgentProfile;
import com.fastcms.ai.agent.BuiltinAgents;
import com.fastcms.ai.autoconfigure.FastcmsAiProperties;
import com.fastcms.ai.component.DesignDirectionLibrary;
import com.fastcms.ai.service.IAiTemplateFileService;
import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.ai.support.ReasoningStreamAccumulator;
import com.fastcms.ai.template.AiTemplateConstants;
import com.fastcms.entity.AiTemplateSession;
import com.fastcms.service.IAiUsageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 设计段编排（设计稿先行模式，见 doc/wiki/ai-template-two-mode-design.md §4.1/§4.2）
 *
 * <p>轮次 loop：对每个待设计页面，拼装设计契约提示词 → 调用 builtin.template-designer
 * （流式，reasoning 增量 + 文件块阶段状态推送）→ 解析 {@code ===FILE: path===} 文件块 →
 * {@link DesignHtmlValidator} V1~V6 校验 → 通过则落盘 {@code workDir/design/}；未通过携带
 * 修正指令重试（上限 {@code max-format-rounds}）；耗尽降级为<b>确定性占位页</b>（满足 V1~V6，
 * 全站流程可继续），并 SSE 播报。单页失败不阻塞其余页面。</p>
 *
 * <p><b>与管线模式的隔离</b>：不引用 AiTemplateGenServiceImpl 任何私有成员；模型调用走
 * {@link AgentChatExecutor}（article-writer 同款流式消费模式），SSE 经 {@link DesignSseSink}
 * 适配（事件名与管线同构：status/message/reasoning/file/switch-file）。文件块解析为本类自有
 * 实现（管线的 JSON files 契约与设计稿的 ===FILE=== 文本契约不同构，无从复用）。</p>
 *
 * <p><b>调用方</b>：设计态编排器（状态机持有者）负责 plan.json 状态推进与本服务的调用时机
 * （首轮全量设计 / 审计修正轮仅重出问题页 / REJECT 后携带用户意见重出）；本类无状态机知识，
 * 同一调用即可服务三种场景（{@code prevAuditIssues}/{@code userComment} 由上下文注入）。</p>
 *
 * <p><b>用量审计</b>：本类每次 {@link #designPages} 调用（可能含多页多轮模型调用）聚合
 * token 用量统一记一次——agentId={@code builtin.template-designer}、scene=
 * {@link IAiUsageLogService.Scene#TEMPLATE_DESIGN}（§8.2：设计段+转化段统一记此场景）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
@Component
public class MockupDesignService {

    private static final Logger log = LoggerFactory.getLogger(MockupDesignService.class);

    /**
     * 思考过程失控保险丝（与文章生成/管线同口径：推理模型偶发循环输出，超限中止防 OOM）
     */
    private static final long REASONING_RUNAWAY_MAX_CHARS = 256L * 1024;

    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER = new tools.jackson.databind.ObjectMapper();

    private final AgentChatExecutor agentChatExecutor;
    private final IAiTemplateFileService fileService;
    private final com.fastcms.ai.audit.AiUsageRecorder usageRecorder;
    private final FastcmsAiProperties aiProperties;
    private final SkillRegistry skillRegistry;

    public MockupDesignService(AgentChatExecutor agentChatExecutor, IAiTemplateFileService fileService,
                               com.fastcms.ai.audit.AiUsageRecorder usageRecorder, FastcmsAiProperties aiProperties,
                               SkillRegistry skillRegistry) {
        this.agentChatExecutor = agentChatExecutor;
        this.fileService = fileService;
        this.usageRecorder = usageRecorder;
        this.aiProperties = aiProperties;
        this.skillRegistry = skillRegistry;
    }

    // ==================== 输入/输出模型 ====================

    /**
     * 设计段上下文（一次 {@link #designPages} 调用的共享要素）
     *
     * @param session         会话实体（userId / sessionId；workDir 用 {@link #workDir()} 独立传递，
     *                        避免本类依赖宿主的 resolveEffectiveWorkDir 私有逻辑）
     * @param workDir         会话工作目录（设计稿落 {@code workDir/design/} 子目录）
     * @param requirement     站点需求（会话 requirement）
     * @param direction       设计方向资产（可空 = AI 自由发挥；来自 DesignDirectionLibrary）
     * @param mobileAdaptive  是否适配移动端（true=响应式契约）
     * @param allPages        全站页面规划（跨页一致性提示 + sitePageContext 拼装用；
     *                        待设计页面是它的子集，由调用方按 plan.json 状态筛选）
     * @param prevAuditIssues 上轮机器审计问题（可空；审计修正轮注入，仅拼入每页第 1 轮提示词）
     * @param userComment     用户否决意见（可空；REJECT 确认携带，与升级管线 feedback 同口径）
     * @param pageDoneSink    页级进度回调（可空）：单页 DONE 落盘后回调页名——编排器即时写回
     *                        plan.json（中断重入不重做该页；旧实现整段结束才写回，中途停止
     *                        已完成的页会被重新设计）。仅 DONE 页回调（PLACEHOLDER 页不标——
     *                        重入重设计，与"降级页有机会翻盘"语义一致）
     */
    public record DesignContext(
            AiTemplateSession session,
            Path workDir,
            String requirement,
            DesignDirectionLibrary.DesignDirectionAsset direction,
            boolean mobileAdaptive,
            List<DesignPagePlanner.PagePlan> allPages,
            List<String> prevAuditIssues,
            String userComment,
            java.util.function.Consumer<String> pageDoneSink) {
    }

    /**
     * 单页设计结果状态
     *
     * <ul>
     *     <li>{@link #DONE}：格式校验通过，设计稿已落盘</li>
     *     <li>{@link #PLACEHOLDER}：多轮未收敛，降级为确定性占位页（流程可继续；
     *         A4 跨页一致性审计对占位页豁免——调用方从本结果感知状态并写 plan.json）</li>
     * </ul>
     */
    public enum PageStatus { DONE, PLACEHOLDER }

    /**
     * 单页设计结果
     *
     * @param pageName   页面名（design/&lt;pageName&gt;.html）
     * @param title      页面中文标题（播报文案用）
     * @param status     {@link PageStatus}
     * @param roundsUsed 实际消耗的格式校验轮数（1~maxFormatRounds）
     * @param lastErrors 降级时的最后一轮校验错误（DONE 时为空；审计/诊断用）
     */
    public record PageResult(String pageName, String title, PageStatus status, int roundsUsed, List<String> lastErrors) {
    }

    /**
     * 设计段整体结果
     *
     * @param pages            逐页结果（与入参 pendingPages 同序）
     * @param allPassed        全部页面 DONE（存在占位页为 false——调用方决定是否阻塞后续转化）
     * @param promptTokens     本次调用聚合的输入 token（审计已在内部落库，此处仅供调用方统计）
     * @param completionTokens 本次调用聚合的输出 token
     * @param totalTokens      本次调用聚合的总 token
     */
    public record DesignOutcome(List<PageResult> pages, boolean allPassed,
                                long promptTokens, long completionTokens, long totalTokens) {
    }

    // ==================== 主入口 ====================

    /**
     * 设计 loop：逐页「生成 → V1~V6 校验 → 修正重试/降级」
     *
     * <p>模型调用级异常（网络断/配额超/思考失控）直接抛出（调用方按 FAILED 状态处理，
     * 已落盘文件保留断点续传）；客户端断开抛 {@link DesignCancelledException}；
     * 单页格式不收敛<b>不抛异常</b>——降级占位页继续后续页面。</p>
     *
     * @param ctx          设计段上下文
     * @param pendingPages 待设计页面（调用方按 plan.json 筛选；空列表直接返回空结果）
     * @param sse          SSE 通道（事件名与管线同构）
     * @return 逐页结果
     * @throws DesignCancelledException 客户端断开/停止
     * @throws IllegalArgumentException 智能体/模型配置缺失（prepare 失败，无模型调用发生）
     * @throws com.fastcms.ai.audit.AiQuotaExceededException 配额超限（同上，无模型调用发生）
     */
    public DesignOutcome designPages(DesignContext ctx, List<DesignPagePlanner.PagePlan> pendingPages, DesignSseSink sse) {
        long startTime = System.currentTimeMillis();
        // 聚合本次全部模型调用的 token 用量（多页×多轮）
        long[] usageAgg = {0L, 0L, 0L};
        // prepare 置于 try 外：装配失败（智能体停用/模型缺失/配额超限）无模型调用发生，
        // 不产生审计记录（与管线 doChatStream 同口径）。
        // injectSkills=false：design-brief 经全文直注系统提示词（见 doDesignPages），不走
        // L1 清单 + load_skill 两段式——设计轮技能必用，工具往返徒增思考流重启与时延
        AgentChatExecutor.Prepared prepared = agentChatExecutor.prepare(
                BuiltinAgents.TEMPLATE_DESIGNER_ID, ctx.session().getUserId(), false);

        boolean succeeded = false;
        String errorMessage = null;
        try {
            DesignOutcome outcome = doDesignPages(ctx, pendingPages, sse, usageAgg, prepared);
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
                // 审计失败不影响主流程（与管线同口径）
                log.warn("设计段用量审计落库失败: sessionId={}", ctx.session().getSessionId(), auditEx);
            }
        }
    }

    private DesignOutcome doDesignPages(DesignContext ctx, List<DesignPagePlanner.PagePlan> pendingPages,
                                        DesignSseSink sse, long[] usageAgg, AgentChatExecutor.Prepared prepared) {
        if (pendingPages == null || pendingPages.isEmpty()) {
            return new DesignOutcome(List.of(), true, 0, 0, 0);
        }
        OpenAiChatOptions designOptions = buildDesignOptions(prepared);
        // 系统提示词 = 设计契约基底 + design-brief 技能全文直注（技能未安装时仅契约基底，
        // 措辞已条件化不做虚假声明）。一次设计任务装配一次，多页多轮复用
        String designSystemPrompt = DesignContractPrompt.buildInjectedSystemPrompt(
                prepared.getBaseSystemPrompt(),
                skillRegistry.loadContent(BuiltinAgents.DESIGN_BRIEF_SKILL_ID));
        String sitePageContext = buildSitePageContext(ctx.allPages());
        // 断点续传：已落盘的设计稿文件（跨页共享的占位 SVG 等）视作在场，V5 不再要求重出
        Set<String> existingPaths = scanExistingDesignFiles(ctx.workDir());

        // 设计段启动播报（§6.2：方向名让用户知道本轮设计基调）
        String directionName = ctx.direction() == null ? null : ctx.direction().name();
        sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, directionName != null
                ? "正在设计设计稿（方向：" + directionName + "）…"
                : "正在设计设计稿（AI 自选方向）…");

        List<PageResult> results = new ArrayList<>(pendingPages.size());
        for (DesignPagePlanner.PagePlan page : pendingPages) {
            if (sse.isCancelled()) {
                throw new DesignCancelledException();
            }
            PageResult result = designPage(ctx, prepared, designOptions, designSystemPrompt, page,
                    sitePageContext, existingPaths, sse, usageAgg);
            results.add(result);
            // 页级进度即时持久化：DONE 页落盘后立即回调（编排器写回 plan.json——中断重入不重做该页）。
            // PLACEHOLDER 页不回调：重入重设计（降级页有机会翻盘，与 pendingPages 筛选语义一致）
            if (ctx.pageDoneSink() != null && result.status() == PageStatus.DONE) {
                ctx.pageDoneSink().accept(page.name());
            }
        }
        // 清空状态条（与管线同口径：空 status 前端隐藏）
        sse.send(AiTemplateConstants.SSE_EVENT_STATUS, "");
        boolean allPassed = results.stream().noneMatch(r -> r.status() == PageStatus.PLACEHOLDER);
        return new DesignOutcome(List.copyOf(results), allPassed, usageAgg[0], usageAgg[1], usageAgg[2]);
    }

    /**
     * 单页设计 loop（生成 → 校验 → 修正重试 → 降级占位页）
     */
    private PageResult designPage(DesignContext ctx, AgentChatExecutor.Prepared prepared,
                                  OpenAiChatOptions designOptions, String designSystemPrompt,
                                  DesignPagePlanner.PagePlan page,
                                  String sitePageContext, Set<String> existingPaths,
                                  DesignSseSink sse, long[] usageAgg) {
        String title = page.title();
        sse.send(AiTemplateConstants.SSE_EVENT_STATUS,
                "正在设计「" + title + "」（design/" + page.name() + ".html）…");

        int maxRounds = Math.max(1, aiProperties.getTemplate().getDesign().getMaxFormatRounds());
        List<String> lastErrors = List.of();
        Map<String, String> accepted = null;
        int roundsUsed = 0;

        for (int round = 1; round <= maxRounds; round++) {
            if (sse.isCancelled()) {
                throw new DesignCancelledException();
            }
            roundsUsed = round;
            // 提示词场景段：审计问题仅第 1 轮注入（修正目标）；格式错误仅第 2 轮起注入（上轮修正指令）
            String userPrompt = DesignContractPrompt.build(ctx.requirement(), ctx.direction(), page,
                    sitePageContext, ctx.mobileAdaptive(),
                    round == 1 ? ctx.prevAuditIssues() : null,
                    round == 1 ? null : String.join("\n", lastErrors),
                    ctx.userComment());
            String raw = callDesignModel(prepared, designOptions, designSystemPrompt, userPrompt, sse, usageAgg);
            Map<String, String> files = parseFileBlocks(raw);
            List<String> errors = new ArrayList<>(checkPathSafety(files));
            if (errors.isEmpty()) {
                errors.addAll(DesignHtmlValidator.validate(page.name(), files, existingPaths));
            }
            if (errors.isEmpty()) {
                accepted = files;
                break;
            }
            lastErrors = List.copyOf(errors);
            if (round < maxRounds) {
                sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, "「" + title + "」设计稿第 " + round
                        + " 轮未通过格式校验（" + errors.size() + " 处），正在修正…");
            }
        }

        if (accepted != null) {
            persistFiles(ctx, accepted, page, existingPaths, sse, true);
            log.info("设计稿就绪: sessionId={}, page={}, rounds={}",
                    ctx.session().getSessionId(), page.name(), roundsUsed);
            return new PageResult(page.name(), title, PageStatus.DONE, roundsUsed, List.of());
        }

        // 降级：确定性占位页（满足 V1~V6 + A3，全站流程可继续；A4 跨页一致性按 PLACEHOLDER 状态豁免）
        sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE, "「" + title + "」连续 " + maxRounds
                + " 轮未通过格式校验，已降级为占位页（重新发送消息可要求重新设计该页）");
        Map<String, String> placeholder = new LinkedHashMap<>();
        placeholder.put("design/" + page.name() + ".html", buildPlaceholderPage(page, ctx.direction()));
        persistFiles(ctx, placeholder, page, existingPaths, sse, true);
        log.warn("设计稿降级为占位页: sessionId={}, page={}, lastErrors={}",
                ctx.session().getSessionId(), page.name(), lastErrors);
        return new PageResult(page.name(), title, PageStatus.PLACEHOLDER, roundsUsed, lastErrors);
    }

    // ==================== 模型调用（流式） ====================

    /**
     * 单轮流式调用：reasoning 增量 + 文件块阶段状态推送，聚合完整响应文本返回
     *
     * <p>思考流归一（累积/增量/重复帧/重启链四种透传形态）统一交由
     * {@link ReasoningStreamAccumulator}，本方法只推送其产出的真实增量；
     * 正文不推 message 事件（正文是文件块原文，前端以 status 阶段文案呈现，
     * 与管线"文件内容不进对话流"同口径）；断开检测在每个 chunk 头部。</p>
     */
    private String callDesignModel(AgentChatExecutor.Prepared prepared, OpenAiChatOptions designOptions,
                                   String designSystemPrompt, String userPrompt, DesignSseSink sse, long[] usageAgg) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(designSystemPrompt), new UserMessage(userPrompt)), designOptions);

        StringBuilder textBuf = new StringBuilder();
        // 思考流归一累积器（单轮私有：识别重启链防缓冲平方级膨胀，见类注释）
        ReasoningStreamAccumulator reasoningAcc = new ReasoningStreamAccumulator();
        // 本轮真实累计思考字符数（缓冲封顶后推送仍在继续，仅此计数反映失控规模）
        long[] reasoningTotal = {0L};
        // 文件块标记扫描游标（增量扫描，见 pushMarkerStatus）
        int[] markerScanPos = {0};
        int[] markerCount = {0};

        prepared.getChatClient().prompt(prompt)
                .stream()
                .chatResponse()
                .doOnNext(resp -> {
                    // 客户端已断开：抛出取消信号中断本轮流式调用
                    if (sse.isCancelled()) {
                        throw new DesignCancelledException();
                    }
                    // token 用量（OpenAI 兼容流式仅最后 chunk 携带 usage，累加聚合）
                    if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                        Usage u = resp.getMetadata().getUsage();
                        if (u.getTotalTokens() != null || u.getPromptTokens() != null || u.getCompletionTokens() != null) {
                            usageAgg[0] += u.getPromptTokens() != null ? u.getPromptTokens() : 0;
                            usageAgg[1] += u.getCompletionTokens() != null ? u.getCompletionTokens() : 0;
                            usageAgg[2] += u.getTotalTokens() != null ? u.getTotalTokens() : 0;
                        }
                    }
                    if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                        return;
                    }
                    AssistantMessage output = resp.getResult().getOutput();
                    // 推理模型思考过程（累积器归一后推送真实增量；失控保险丝按累计量判定）
                    Object reasoning = output.getMetadata() == null
                            ? null : output.getMetadata().get("reasoningContent");
                    if (reasoning != null && StringUtils.hasText(String.valueOf(reasoning))) {
                        String delta = reasoningAcc.feed(String.valueOf(reasoning));
                        if (delta != null && StringUtils.hasText(delta)) {
                            sse.send(AiTemplateConstants.SSE_EVENT_REASONING, delta);
                            reasoningTotal[0] += delta.length();
                        }
                        if (reasoningTotal[0] > REASONING_RUNAWAY_MAX_CHARS) {
                            throw new IllegalStateException("设计模型思考过程失控（已输出 "
                                    + reasoningTotal[0] + " 字符），已中止本轮调用");
                        }
                    }
                    // 正文增量聚合（不推 message：文件块原文不进对话流）
                    String content = output.getText();
                    if (StringUtils.hasText(content)) {
                        textBuf.append(content);
                        pushMarkerStatus(textBuf, markerScanPos, markerCount, sse);
                    }
                })
                // 双超时兜底：OkHttp callTimeout（baseOptionsBuilder 已设 10 分钟）在流死时
                // 会以错误信号终止 blockLast，无需在此重复设超时（与 article-writer 同口径）
                .blockLast();

        if (textBuf.length() == 0) {
            throw new IllegalStateException("设计模型返回空响应（思考过程可能耗尽了输出上限）");
        }
        return textBuf.toString();
    }

    /**
     * 文件块标记阶段状态推送：聚合缓冲中新出现 ===FILE: path=== 标记时推送"正在生成 path…"
     *
     * <p>增量扫描：游标回退到上一个换行后的行首（标记的 ^ 锚定真实行首，避免行中误配）；
     * 计数单调递增（重扫区域只会重复命中已计数标记），无新增即静默。</p>
     */
    private static void pushMarkerStatus(StringBuilder textBuf, int[] scanPosRef, int[] countRef, DesignSseSink sse) {
        int from = scanPosRef[0];
        int nl = textBuf.lastIndexOf("\n", from - 1);
        if (nl >= 0) {
            from = nl + 1;
        } else {
            from = 0;
        }
        Matcher m = DesignHtmlValidator.FILE_MARKER_PATTERN.matcher(textBuf);
        m.region(from, textBuf.length());
        int found = 0;
        String lastPath = null;
        while (m.find()) {
            found++;
            lastPath = m.group(1).trim();
        }
        if (found > countRef[0] && lastPath != null) {
            countRef[0] = found;
            scanPosRef[0] = textBuf.length();
            sse.send(AiTemplateConstants.SSE_EVENT_STATUS, "正在生成 " + lastPath + "…");
        }
    }

    // ==================== 文件块解析 ====================

    /**
     * 解析 ===FILE: path=== 文件块（相对路径 → 内容）
     *
     * <p>内容到下一个标记行首或文本末尾；单块/整体被 markdown 围栏包裹时剥栅栏。
     * 无任何标记返回空 Map（V1 校验将报"缺少页面文件"形成修正指令）。</p>
     */
    static Map<String, String> parseFileBlocks(String raw) {
        Map<String, String> files = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return files;
        }
        String text = raw;
        Matcher probe = DesignHtmlValidator.FILE_MARKER_PATTERN.matcher(text);
        if (!probe.find()) {
            // 整体剥一层围栏再扫（模型偶尔给整个输出套 ```html）
            String unfenced = stripGlobalFence(text);
            if (unfenced == null) {
                return files;
            }
            text = unfenced;
            probe = DesignHtmlValidator.FILE_MARKER_PATTERN.matcher(text);
            if (!probe.find()) {
                return files;
            }
        }
        List<String> paths = new ArrayList<>();
        List<Integer> markerStarts = new ArrayList<>();
        List<Integer> contentStarts = new ArrayList<>();
        Matcher m = DesignHtmlValidator.FILE_MARKER_PATTERN.matcher(text);
        while (m.find()) {
            paths.add(m.group(1).trim());
            markerStarts.add(m.start());
            contentStarts.add(m.end());
        }
        for (int i = 0; i < paths.size(); i++) {
            int from = contentStarts.get(i);
            int to = (i + 1 < paths.size()) ? markerStarts.get(i + 1) : text.length();
            String content = stripBlockFence(text.substring(from, to)).trim();
            if (!content.isEmpty()) {
                files.put(paths.get(i), content);
            }
        }
        return files;
    }

    /**
     * 剥整体 markdown 围栏（```html ... ```）；非围栏包裹返回 null
     */
    private static String stripGlobalFence(String text) {
        String t = text.trim();
        if (!t.startsWith("```")) {
            return null;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) {
            return null;
        }
        String body = t.substring(firstNl + 1);
        int fenceEnd = body.lastIndexOf("```");
        return fenceEnd < 0 ? body : body.substring(0, fenceEnd);
    }

    /**
     * 剥单块内容的 markdown 围栏
     */
    private static String stripBlockFence(String content) {
        String c = content.trim();
        if (!c.startsWith("```")) {
            return c;
        }
        int firstNl = c.indexOf('\n');
        if (firstNl < 0) {
            return c;
        }
        String body = c.substring(firstNl + 1);
        int fenceEnd = body.lastIndexOf("```");
        if (fenceEnd >= 0) {
            body = body.substring(0, fenceEnd);
        }
        return body.trim();
    }

    /**
     * 文件块路径安全校验（V5 只管 img src，这里管文件块自身路径）：
     * 只允许 design/ 前缀的相对路径，禁止绝对路径/..（路径穿越）。
     * 错误文案进入修正指令（与 V1~V6 同通道）。
     */
    static List<String> checkPathSafety(Map<String, String> files) {
        List<String> errors = new ArrayList<>();
        for (String path : files.keySet()) {
            String normalized = path.replace('\\', '/');
            if (normalized.startsWith("/") || normalized.contains("..") || !normalized.startsWith("design/")) {
                errors.add("文件路径非法 " + path
                        + "（文件块路径只允许 design/ 下的相对路径，禁止绝对路径与 ..）");
            }
        }
        return errors;
    }

    // ==================== 落盘与注册 ====================

    /**
     * 设计稿文件落盘 + 会话文件表注册 + SSE file/switch-file 事件
     *
     * <p>成功页首个 HTML 落盘时推送 switch-file（前端预览切到该页）；
     * 全部文件（含占位 SVG）逐一推 file 事件（与管线产物事件同构）。
     * 落盘路径加入 existingPaths（后续页面 V5 复用判定）。</p>
     */
    private void persistFiles(DesignContext ctx, Map<String, String> files, DesignPagePlanner.PagePlan page,
                              Set<String> existingPaths, DesignSseSink sse, boolean switchPreview) {
        String pagePath = "design/" + page.name() + ".html";
        boolean switched = false;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = ctx.workDir().resolve(entry.getKey()).normalize();
            // checkPathSafety 已挡非法前缀；防御式再验不越界（双保险，安全代码不省）
            if (!target.startsWith(ctx.workDir().normalize())) {
                throw new IllegalStateException("文件路径越界: " + entry.getKey());
            }
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("设计稿落盘失败: " + entry.getKey() + ": " + e.getMessage(), e);
            }
            fileService.saveOrUpdateFile(ctx.session().getSessionId(), entry.getKey(),
                    entry.getValue(), AiTemplateConstants.ACTION_CREATE);
            existingPaths.add(entry.getKey());

            Map<String, String> fileData = new LinkedHashMap<>();
            fileData.put("path", entry.getKey());
            fileData.put("action", AiTemplateConstants.ACTION_CREATE);
            sse.send(AiTemplateConstants.SSE_EVENT_FILE, JSON_MAPPER.writeValueAsString(fileData));
            if (switchPreview && !switched && pagePath.equals(entry.getKey())) {
                switched = true;
                Map<String, String> switchData = new LinkedHashMap<>();
                switchData.put("path", pagePath);
                sse.send(AiTemplateConstants.SSE_EVENT_SWITCH_FILE, JSON_MAPPER.writeValueAsString(switchData));
            }
        }
    }

    /**
     * 扫描已落盘的设计稿文件（断点续传：上一轮已产出的占位 SVG 等视作在场）
     */
    private static Set<String> scanExistingDesignFiles(Path workDir) {
        Set<String> paths = new HashSet<>();
        Path designRoot = workDir.resolve("design");
        if (!Files.isDirectory(designRoot)) {
            return paths;
        }
        try (Stream<Path> stream = Files.walk(designRoot)) {
            stream.filter(Files::isRegularFile).forEach(p ->
                    paths.add(workDir.relativize(p).normalize().toString().replace('\\', '/')));
        } catch (IOException e) {
            // 扫描失败按空集处理：V5 将要求重出占位 SVG（安全侧失败，不阻塞设计）
            log.warn("已落盘设计稿扫描失败（按空集处理）: {}", e.getMessage());
        }
        return paths;
    }

    // ==================== 选项与文案 ====================

    /**
     * 设计段调用 options：从配置基底出发（apiKey/baseUrl/timeout 必须在场——runtime options
     * 不会自动合并默认 options，漏设会 404/断流），叠 design-max-tokens 输出上限。
     * qwen3 压低思考预算与管线 buildPipelineOptions 同口径（大输出场景防预算挤占）。
     */
    private OpenAiChatOptions buildDesignOptions(AgentChatExecutor.Prepared prepared) {
        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.baseOptionsBuilder(prepared.getModelConfig());
        builder.maxTokens(Math.max(1024, aiProperties.getTemplate().getDesign().getDesignMaxTokens()));
        AgentProfile profile = prepared.getProfile();
        if (profile.getTemperature() != null) {
            builder.temperature(profile.getTemperature());
        }
        String model = prepared.getModelConfig().getModel();
        if (model != null && model.toLowerCase(Locale.ROOT).contains("qwen3")) {
            builder.reasoningEffort("low");
        }
        return builder.build();
    }

    /**
     * 全站页面清单摘要（跨页 nav/footer 一致性提示）：index（首页）/ about（关于我们）/ …
     */
    private static String buildSitePageContext(List<DesignPagePlanner.PagePlan> pages) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }
        return pages.stream()
                .map(p -> p.name() + "（" + p.title() + "）")
                .collect(Collectors.joining(" / "));
    }

    // ==================== 占位页 ====================

    /**
     * 确定性占位页（单页多轮格式不收敛的降级产物）
     *
     * <p>满足 V1~V6 与 A3（viewport + 768/1024 双断点）、A7（远小于 60KB）；
     * 无图片（规避 V5 占位 SVG 要求）、无脚本（A6 白名单天然通过）。
     * nav/footer 结构跨页一致（占位页之间），A4 审计对 PLACEHOLDER 页豁免由调用方处理。</p>
     *
     * @param page      页面规划（标题入页面文案）
     * @param direction 设计方向（主色沿用方向资产；空用默认蓝）
     */
    static String buildPlaceholderPage(DesignPagePlanner.PagePlan page,
                                       DesignDirectionLibrary.DesignDirectionAsset direction) {
        String primary = direction != null && StringUtils.hasText(direction.primaryColor())
                ? direction.primaryColor().trim() : "#2563eb";
        String title = page.title();
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>%s</title>
                  <style>
                    :root { --c-primary: %s; --c-accent: #f59e0b; --c-bg: #f8fafc; --c-text: #0f172a; --c-muted: #64748b; }
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: system-ui, sans-serif; background: var(--c-bg); color: var(--c-text); }
                    nav { display: flex; justify-content: space-between; align-items: center; padding: 16px 32px; background: #ffffff; }
                    #nav-toggle { display: none; background: none; border: 1px solid var(--c-muted); border-radius: 6px; padding: 6px 10px; font-size: 16px; color: var(--c-text); cursor: pointer; }
                    section { padding: 64px 32px; }
                    .ph-hero { text-align: center; }
                    .ph-hero h1 { font-size: 32px; margin-bottom: 12px; color: var(--c-primary); }
                    .ph-hero p { color: var(--c-muted); }
                    .ph-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; }
                    .ph-card { background: #ffffff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 24px; }
                    .ph-card h3 { margin-bottom: 8px; }
                    .ph-card p { color: var(--c-muted); font-size: 14px; }
                    #footer { background: var(--c-text); color: #ffffff; text-align: center; padding: 32px; }
                    @media (max-width: 1024px) { .ph-grid { grid-template-columns: repeat(2, 1fr); } }
                    @media (max-width: 768px) { #nav-toggle { display: block; } .ph-grid { grid-template-columns: 1fr; } section { padding: 40px 16px; } }
                  </style>
                </head>
                <body>
                  <nav>
                    <span>%s</span>
                    <button id="nav-toggle" aria-label="菜单">☰</button>
                  </nav>
                  <section data-block="hero" class="ph-hero">
                    <h1>%s</h1>
                    <p>该页面设计稿多轮未通过格式校验，已降级为占位页。重新发送消息可要求重新设计。</p>
                  </section>
                  <section data-block="features">
                    <div class="ph-grid">
                      <div class="ph-card"><h3>占位区块一</h3><p>等待重新设计</p></div>
                      <div class="ph-card"><h3>占位区块二</h3><p>等待重新设计</p></div>
                      <div class="ph-card"><h3>占位区块三</h3><p>等待重新设计</p></div>
                    </div>
                  </section>
                  <section data-block="footer" id="footer">
                    <p>© 占位页脚</p>
                  </section>
                </body>
                </html>
                """.formatted(title, primary, title, title);
    }
}
