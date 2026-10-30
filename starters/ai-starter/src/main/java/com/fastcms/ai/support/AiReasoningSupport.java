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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.fastcms.ai.support;

import com.fastcms.entity.AiModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 思考档位适配层（把「档位」翻译成各家端点认的请求参数）
 *
 * <p><b>为什么需要这一层</b>：推理模型端点的「思考开关」没有统一标准，同一个
 * {@code reasoning_effort} 在不同端点行为完全不同，甚至直接 400。2026-10-29 对
 * 自建 Qwen 端点（{@code Qwen3.6-27B @ http://…:30120/v1}）实测结论：</p>
 * <ul>
 *   <li>端点前面挂了一层网关，<b>实际只放行 {@code low / medium}</b>：发 {@code xhigh} 会被
 *       网关拒（{@code Input should be 'none', 'low', 'medium' or 'high'}），
 *       发 {@code high} 被 vLLM 拒（{@code Supported types are xhigh (default), medium, and low.}），
 *       {@code none}/{@code minimal} 同样 400；</li>
 *   <li><b>不传参数 = 端点默认最高档</b>（思考量等同 xhigh）——所以「不传」不是「不思考」，
 *       而是「全力思考」，这正是过去 4 条链路裸奔在最高档的原因；</li>
 *   <li><b>真正关闭思考的唯一有效方式</b>是 {@code chat_template_kwargs:
 *       {"enable_thinking": false}}（实测思考量归零）；顶层 {@code enable_thinking=false}
 *       会被静默忽略；{@code /no_think} 软开关对新版 Qwen3.x 已失效。</li>
 *   <li>{@code reasoning_effort=low} 只能<b>压低</b>不能关（实测思考量约为默认的 1/3）。</li>
 *   <li><b>端点不支持单独的思考预算</b>：{@code thinking_budget} / {@code reasoning_budget} /
 *       {@code max_reasoning_tokens} 实测全部无效（思考长度只是随机波动），
 *       {@code max_tokens} 是<b>思考 + 正文共享</b>预算 —— 详见 {@link AiThinkingBudget}。</li>
 * </ul>
 *
 * <p><b>为什么重要</b>：思考 token 与正文 token 同价（都走 decode，实测 48 tok/s），
 * 所以「不传档位」= 每轮多等数分钟；而 fastcms 过去有 4 条链路（站点分析、组件映射、
 * 文章生成/续写/字段、通用对话）完全没传档位，裸奔在端点默认的 xhigh 档。</p>
 *
 * <p><b>使用方式</b>：</p>
 * <pre>
 * OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.optionsBuilder(config,
 *         AiReasoningSupport.SCENE_DETERMINISTIC);   // 确定性场景 → 关思考
 * </pre>
 *
 * <p><b>档位取值</b>（{@code ai_model_config.reasoning_effort} 列，{@code null}/auto =
 * 跟随场景默认）：</p>
 * <ul>
 *   <li>{@code off}——关闭思考。Qwen 系走 {@code chat_template_kwargs}，其他端点退化为
 *       {@code reasoning_effort=minimal}。</li>
 *   <li>{@code low / medium / xhigh}——映射为请求体 {@code reasoning_effort}。</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class AiReasoningSupport {

    private static final Logger log = LoggerFactory.getLogger(AiReasoningSupport.class);

    /** 跟随场景默认档位（配置列留空时的口径） */
    public static final String EFFORT_AUTO = "auto";

    /** 关闭思考 */
    public static final String EFFORT_OFF = "off";

    /** 低思考（默认档） */
    public static final String EFFORT_LOW = "low";

    /** 中等思考 */
    public static final String EFFORT_MEDIUM = "medium";

    /**
     * 高思考
     *
     * <p><b>2026-10-29 实测修正</b>：本自建端点前面挂了一层网关（FastAPI + Pydantic），
     * 其 {@code reasoning_effort} 校验只放行 <b>{@code low / medium}</b>，
     * {@code xhigh} 会被网关层拒绝：</p>
     * <pre>
     * reasoning_effort=xhigh → HTTP 400
     *   "Input should be 'none', 'low', 'medium' or 'high'"
     * </pre>
     * <p>而 vLLM 自己的报错文案又是另一套（{@code high} 被它拒）：</p>
     * <pre>
     * reasoning_effort=high → HTTP 400
     *   "Unexpected reasoning effort high. Supported types are xhigh (default), medium, and low."
     * </pre>
     * <p>两层叠加后的<b>实际可用集 = {low, medium}</b>（{@code xhigh} 网关拒、
     * {@code high} vLLM 拒、{@code none}/{@code minimal} 均 400）。
     * 不传参数 = 端点默认最高档（思考量等同 xhigh），因此本类把「最高思考」
     * 表达为 {@link #EFFORT_MEDIUM}，而不是发一个会被拒绝的枚举值。</p>
     */
    public static final String EFFORT_XHIGH = "xhigh";

    /**
     * 端点实际支持的最高思考档（= medium）
     *
     * <p>用户配置 {@code xhigh} 时归一到此值（发 xhigh 会 400）。</p>
     */
    public static final String EFFORT_MAX_SUPPORTED = EFFORT_MEDIUM;

    /**
     * 场景默认档：需要一定推理的场合（生成、规划、复杂调整）
     */
    public static final String SCENE_REASONING = EFFORT_LOW;

    /**
     * 场景默认档：确定性场合（分类、组件映射、报错修复、连接探活）
     * <p>这类任务答案空间有限，深度思考纯属浪费——端点默认 xhigh 会让单次调用多等数倍时间。</p>
     */
    public static final String SCENE_DETERMINISTIC = EFFORT_OFF;

    private AiReasoningSupport() {
    }

    /**
     * 按档位给 options 装配思考参数（幂等；同一 builder 重复调用以最后一次为准）
     *
     * @param builder      Spring AI options builder（可空，空则直接返回）
     * @param config       模型配置（可空；为空时只按场景默认档处理）
     * @param sceneDefault 场景默认档位（配置未显式指定时使用）
     */
    public static void apply(OpenAiChatOptions.Builder builder, AiModelConfig config, String sceneDefault) {
        if (builder == null) {
            return;
        }
        String configured = config == null ? null : config.getReasoningEffort();
        String effort = resolve(configured, sceneDefault);
        boolean qwen = isQwenFamily(config);
        if (EFFORT_OFF.equals(effort)) {
            if (qwen) {
                // Qwen 系：只有 chat_template_kwargs 能真正关掉思考
                builder.extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
            } else {
                // 其他端点（OpenAI o 系列等）：退化为最低档
                builder.reasoningEffort("minimal");
            }
            log.debug("思考档位: off（{} 系）", qwen ? "Qwen" : "通用");
            return;
        }
        String normalized = normalize(effort, qwen);
        builder.reasoningEffort(normalized);
        log.debug("思考档位: {}", normalized);
    }

    /**
     * 解析生效档位：配置显式值优先（auto/空 → 场景默认）
     */
    public static String resolve(String configured, String sceneDefault) {
        if (StringUtils.hasText(configured)) {
            String v = configured.trim().toLowerCase(Locale.ROOT);
            if (!EFFORT_AUTO.equals(v)) {
                return v;
            }
        }
        if (StringUtils.hasText(sceneDefault)) {
            return sceneDefault.trim().toLowerCase(Locale.ROOT);
        }
        return SCENE_REASONING;
    }

    /**
     * 归一化到端点支持的枚举（避免发非法值导致 400）
     *
     * <p><b>Qwen 系（2026-10-29 实测）</b>：网关只放行 {@code low / medium}，
     * 发 {@code xhigh}/{@code high}/{@code none}/{@code minimal} 均 400。
     * 因此「最高思考」归一到 {@link #EFFORT_MEDIUM}。</p>
     *
     * <p>其他端点（OpenAI o 系列等）认 {@code minimal/low/medium/high}
     * （{@code xhigh} 降级为 {@code high}）。</p>
     */
    static String normalize(String effort, boolean qwen) {
        String v = (effort == null ? "" : effort.trim().toLowerCase(Locale.ROOT));
        if (qwen) {
            return switch (v) {
                case EFFORT_MEDIUM -> EFFORT_MEDIUM;
                // xhigh / high / minimal 都不被端点接受 → 归一到最高可用档 medium
                // （「不传参数」虽然等价最高档，但显式 medium 可控且不会踩 400）
                case EFFORT_XHIGH, "high", "minimal" -> EFFORT_MAX_SUPPORTED;
                default -> EFFORT_LOW;
            };
        }
        return switch (v) {
            case "minimal" -> "minimal";
            case EFFORT_MEDIUM -> EFFORT_MEDIUM;
            case "high" -> "high";
            case EFFORT_XHIGH -> "high";
            default -> EFFORT_LOW;
        };
    }

    /**
     * 是否 Qwen 系端点（provider 或 model 名含 qwen）
     * <p>Qwen 系用 {@code chat_template_kwargs} 关思考；其他端点用
     * {@code reasoning_effort=minimal}。</p>
     */
    public static boolean isQwenFamily(AiModelConfig config) {
        if (config == null) {
            return false;
        }
        String provider = config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase(Locale.ROOT);
        return provider.contains("qwen") || model.contains("qwen") || model.contains("qwq");
    }
}
