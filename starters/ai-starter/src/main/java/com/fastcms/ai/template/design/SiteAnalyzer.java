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
import com.fastcms.ai.agent.AgentProfile;
import com.fastcms.ai.agent.BuiltinAgents;
import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.entity.AiTemplateSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 站点结构分析器（AI 驱动，设计稿链路的<b>第一个 AI 调用点</b>）
 *
 * <p>解决的问题：上传物通常只有一个首页 {@code index.html}，而 fastcms 要的是<b>整站</b>模板
 * （首页 + 各栏目页 + 文章列表/详情 + 单页）。栏目叫什么、有几层、每页该呈现什么，
 * 这些信息全部藏在用户上传的那份 HTML 里——确定性规则读不出来（文件名无关、锚文本任意），
 * 只能让模型读懂它。</p>
 *
 * <p><b>分析管线</b>：读归一化后的首页 HTML → 模型按契约输出结构化 JSON（站点定位 + 顶部导航
 * 栏目清单 + 每栏目页型与内容定位 + 首页数据区文案）→ {@link SiteIa#parse} 解析净化 →
 * 落盘 {@code design/site-ia.json}（编排器负责），供后续两步消费：
 * ① 编排器据此追加栏目页设计任务；② 转化段据此生成 CMS 菜单 + 首页数据区。</p>
 *
 * <p><b>降级契约</b>：本能力失败（模型未配/超时/输出不可解析）<b>不阻断导入</b>——
 * 返回 {@link AnalysisResult#degraded(String)}，编排器按"仅标准页（首页 + 列表/详情/单页）"
 * 继续，并在对话流播报原因。站点分析是增强而非必需环节。</p>
 *
 * <p><b>不流式</b>：与设计段（流式 + 思考增量 + 文件块状态）不同，本阶段输出是一段短 JSON，
 * 流式推送只会在对话流里刷屏无意义文本；故走非流式 {@code call()}，前后由编排器播报状态。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class SiteAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SiteAnalyzer.class);

    /**
     * 喂给模型的首页 HTML 字符上限
     *
     * <p>首页 HTML 常见 30~90KB，全量喂入会吃掉上下文并抬高时延。导航栏在文档头部，
     * 截断尾部（保留头部结构与大部分正文）不会丢栏目信息；prompt 中已声明可能截断。</p>
     */
    private static final int MAX_HTML_CHARS = 30_000;

    /** 站点的信息架构契约（系统提示词） */
    static final String SYSTEM_PROMPT = """
            你是网站信息架构分析师。用户上传了一个网站的首页 HTML——它是这个站点的**样式与结构基准**，
            其余页面将由你按 fastcms 模板规范推导创建。你的任务是把这份 HTML 读透，产出一份整站页面清单。

            【分析步骤】
            1. 先读懂站点：什么行业/什么产品、面向谁、核心主张与调性
            2. **重点提取顶部导航栏**：在 <nav>/<header> 里找到主导航，逐项读出菜单文案与链接
            3. 剔除不属于栏目的项：品牌 logo、语言切换、登录/注册/购物车、以及纯 CTA 按钮（如"免费试用"）
            4. 结合页面正文内容，判断每个栏目在 fastcms 里应建成什么页型
            5. 为每个栏目写清楚"这一页应该呈现什么内容"

            【页型定义】（只能二选一，别造第三种）
            - article_list：资讯/新闻/博客/公告/案例/活动这类**会持续新增文章**的栏目。
              全站共用一份文章列表模板，栏目之间的差异由菜单指向体现，所以它**不需要**独立页面设计。
            - page：关于我们/服务与产品/解决方案/联系我们/团队/招聘这类**静态内容**栏目。
              每个这样的栏目都会得到一份独立模板，你稍后要为它单独设计页面。

            【slug 规则】每个栏目给小写英文（或拼音）slug，只含 a-z 0-9 - _，长度 2~24，全站唯一。
            例：核心能力→features、插件市场→market、关于我们→about、新闻动态→news。

            【首页数据区】首页本身是保真搬运的静态页，后台发布内容不会自动显示。所以首页需要一个
            "文章流数据区"（后台发文后首页即时可见）。请给出这个区块的标题与一句副标题，措辞贴合站点调性。

            【输出契约】只输出一个 JSON 对象——不要 markdown 围栏、不要任何解释文字：
            {
              "siteName": "站点名",
              "summary": "一句话概括这个站点",
              "homeSectionHeading": "最新动态",
              "homeSectionIntro": "一句话副标题",
              "pages": [
                {"title":"核心能力","pageType":"page","slug":"features","description":"该页要呈现什么内容"},
                {"title":"新闻动态","pageType":"article_list","slug":"news","description":"文章列表"}
              ]
            }
            pages 按导航栏里的原始顺序排列；**不要包含首页本身**；**不要编造导航栏里没有的栏目**。
            若导航栏确实读不出来，就从页面正文归纳出 3~5 个最合理的栏目并说明依据。
            """;

    private final AgentChatExecutor agentChatExecutor;

    public SiteAnalyzer(AgentChatExecutor agentChatExecutor) {
        this.agentChatExecutor = agentChatExecutor;
    }

    /**
     * 分析结果
     *
     * @param siteIa         站点信息架构（解析失败为 null）
     * @param degradedReason 降级原因（成功为 null）；非空时编排器应播报并退回"仅标准页"
     */
    public record AnalysisResult(SiteIa siteIa, String degradedReason) {
        public static AnalysisResult ok(SiteIa siteIa) {
            return new AnalysisResult(siteIa, null);
        }

        public static AnalysisResult degraded(String reason) {
            return new AnalysisResult(null, reason);
        }

        public boolean available() {
            return siteIa != null;
        }
    }

    /**
     * 读首页 HTML 推导整站信息架构
     *
     * @param session       会话（取 userId 装配智能体）
     * @param homeHtml      归一化后的首页 HTML（由调用方读 {@code design/index.html} 提供）
     * @param sourceName    上传源名（播报用）
     * @param reservedSlugs 标准页已占用的设计页名，避免栏目 slug 与之重名覆盖
     * @param sse           SSE 通道（仅用于取消检测；播报由编排器做）
     * @return 分析结果（失败不抛异常，返回降级结果）
     * @throws DesignCancelledException 客户端已断开
     */
    public AnalysisResult analyze(AiTemplateSession session, String homeHtml, String sourceName,
                                  Set<String> reservedSlugs, DesignSseSink sse) {
        if (homeHtml == null || homeHtml.isBlank()) {
            return AnalysisResult.degraded("无可分析的首页 HTML（导入内容缺失）");
        }
        AgentChatExecutor.Prepared prepared;
        try {
            // injectSkills=false：本阶段输出是受契约约束的短 JSON，注入技能清单会诱导模型
            // 额外调 load_skill 读长文档（多一轮工具往返 + 输出风格被带偏），确定性文本变换
            // 场景一律关闭（与划词改写/字段候选同口径）
            prepared = agentChatExecutor.prepare(BuiltinAgents.TEMPLATE_DESIGNER_ID,
                    session.getUserId(), false);
        } catch (Exception e) {
            log.warn("站点分析装配失败（降级为仅标准页）: sessionId={}", session.getSessionId(), e);
            return AnalysisResult.degraded("模型装配失败：" + e.getMessage());
        }

        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(buildUserPrompt(sourceName, homeHtml))),
                    buildOptions(prepared));
            var response = prepared.getChatClient().prompt(prompt).call().chatResponse();
            if (sse != null && sse.isCancelled()) {
                throw new DesignCancelledException();
            }
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return AnalysisResult.degraded("模型返回空响应");
            }
            String raw = response.getResult().getOutput().getText();
            SiteIa siteIa = SiteIa.parse(raw, reservedSlugs);
            if (siteIa == null) {
                log.warn("站点分析输出不可解析: sessionId={}, rawHead={}", session.getSessionId(),
                        raw == null ? "null" : raw.substring(0, Math.min(200, raw.length())));
                return AnalysisResult.degraded("模型输出无法解析为站点结构");
            }
            return AnalysisResult.ok(siteIa);
        } catch (DesignCancelledException ce) {
            throw ce;
        } catch (Exception e) {
            log.warn("站点分析调用失败（降级为仅标准页）: sessionId={}", session.getSessionId(), e);
            return AnalysisResult.degraded("站点分析调用失败：" + e.getMessage());
        }
    }

    /** 用户提示词：上传源 + 首页 HTML 全文（超限截断并显式声明，避免模型误判被截断处为文档结尾） */
    private static String buildUserPrompt(String sourceName, String homeHtml) {
        String html = homeHtml;
        boolean truncated = html.length() > MAX_HTML_CHARS;
        if (truncated) {
            html = html.substring(0, MAX_HTML_CHARS);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【上传来源】").append(sourceName == null ? "（未命名）" : sourceName).append('\n');
        sb.append("【首页 HTML").append(truncated ? "（内容较长，已截断尾部）" : "").append("】\n");
        sb.append("```html\n").append(html).append("\n```\n");
        sb.append("\n请按分析步骤产出 JSON。");
        return sb.toString();
    }

    /**
     * 分析调用选项：低温（结构抽取是确定性任务，高温易生造栏目）、沿用智能体绑定的模型
     *
     * <p>不覆盖 maxTokens——沿用基底配置，避免设定值超出模型上限导致请求被拒。</p>
     */
    private static OpenAiChatOptions buildOptions(AgentChatExecutor.Prepared prepared) {
        OpenAiChatOptions.Builder builder =
                AiModelConfigServiceImpl.baseOptionsBuilder(prepared.getModelConfig());
        builder.temperature(0.2);
        AgentProfile profile = prepared.getProfile();
        if (profile != null && profile.getTemperature() != null) {
            // 智能体显式配了温度时尊重配置（用户可自行调高以放宽归纳自由度）
            builder.temperature(profile.getTemperature());
        }
        return builder.build();
    }
}
