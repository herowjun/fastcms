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
package com.fastcms.ai.support;

import com.fastcms.entity.AiModelConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 思考预算控制器（解决「思考吃满输出预算 → 正文 0 token」）
 *
 * <p><b>问题根源（2026-10-29 端点实测）</b>：Qwen 系端点的 {@code max_tokens} 是
 * <b>思考 + 正文共享</b>的总预算，且端点<b>不支持</b>任何单独的思考预算参数
 * （{@code thinking_budget} / {@code reasoning_budget} / {@code max_reasoning_tokens}
 * 实测全部无效——思考长度只是随机波动）。于是：</p>
 * <ul>
 *   <li>{@code xhigh} 档 + 2048 预算 → 思考写了 7935 字符 → <b>正文 0 字符</b>；</li>
 *   <li>{@code low} 档 + 512 预算 → 思考写了 913 字符 → <b>正文 0 字符</b>；</li>
 *   <li>{@code low} 档 + 16384 预算 → 思考 968 字符 + 正文 16232 字符（正常）。</li>
 * </ul>
 *
 * <p><b>更糟的是：预算越大，模型思考越长</b>（拿到大预算就"想得更细"）。
 * 所以「检测到思考吃满 → 翻倍 maxTokens 重试」是<b>错的解法</b>——
 * 翻倍只会给思考更多空间，正文照样是 0。</p>
 *
 * <p><b>本组件的三层解法</b>：</p>
 * <ol>
 *   <li><b>预算分离（预防）</b>：{@link #reserveOutputBudget} 给思考留出固定余量，
 *       正文预算 = 需求 + 思考预留，避免思考挤掉正文空间。</li>
 *   <li><b>提示词约束（降本）</b>：{@link #thinkingBudgetHint} 在提示词里限制思考长度。
 *       实测同一问题加此约束：思考量 968 → 268 字符（<b>-72%</b>），
 *       completion 6862 → 696 token（<b>-90%</b>）。</li>
 *   <li><b>降档重试（兜底）</b>：{@link #degradeEffort} 检测到「思考吃满」时把档位降一级
 *       （{@code low → off}，即 {@code enable_thinking=false}）重试，
 *       而<b>不是</b>翻倍上限。实测 {@code off} 档思考恒为 0，正文必定产出。</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class AiThinkingBudget {

    /**
     * 思考安全垫 token（低档默认）：正文预算之外额外留出的少量空间
     *
     * <p><b>为什么垫子这么小（这是端点实测逼出来的）</b>：端点上
     * <b>预算越大，模型思考越长</b>——拿到的额度越大就"想得更细"。
     * 实测 xhigh 档 2048 预算 → 思考写了 7935 字符；给到 16384 反而思考只有 968 字符
     * （正文 16232 字符一次写完）。也就是说：</p>
     * <ul>
     *   <li>垫子给大了 → 思考有空间施展 → 正文被挤掉 → 思考吃满；</li>
     *   <li>垫子给小了 → 思考被压在几百 token → 正文拿满预算。</li>
     * </ul>
     * <p>所以本常量不是"给思考多少预算"，而是"<b>正文之外的安全余量</b>"——
     * 够模型写完思考的收尾（几百 token）即可，绝不能当成思考配额来放大。</p>
     */
    public static final int THINKING_RESERVE_TOKENS = 1024;

    /**
     * 思考安全垫 token（中等及以上档）：档位越高思考越长，垫子略大
     *
     * <p>即便如此也不超过 2048——理由同 {@link #THINKING_RESERVE_TOKENS}：
     * 垫子放大换来的思考膨胀远大于它保护的正文。</p>
     */
    public static final int THINKING_RESERVE_TOKENS_HIGH = 2048;

    /**
     * 请求级强制关闭思考的标记（用于降档重试）
     */
    public static final String FORCE_OFF_MARKER = "__force_thinking_off__";

    /**
     * 思考预算约束的标记串（幂等检测用）
     *
     * <p>多轮对话里同一份 messages 可能被反复复用（重试、修复轮重建上下文），
     * 追加约束前先查标记，避免重复追加导致提示词膨胀。</p>
     */
    public static final String HINT_MARKER = "【思考预算】";

    /**
     * usage 解析用的 JSON mapper（提取 {@code completion_tokens_details.reasoning_tokens}）
     */
    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER =
            new tools.jackson.databind.ObjectMapper();

    private AiThinkingBudget() {
    }

    /**
     * 为「正文需求」预留思考空间后的总预算
     *
     * <p>用法：调用方算出正文需要多少 token（通常是输入量的倍数或文件体积估算），
     * 交给本方法得到应当设置的 {@code max_tokens}。</p>
     *
     * <p><b>注意语义</b>：这不是"给思考多少预算"，而是"正文之外留一点安全余量"。
     * 端点上思考与正文共享同一个池子，且<b>预算越大思考越长</b>，
     * 所以余量给大了反而会诱发思考膨胀、挤掉正文。真正的思考控制手段是
     * {@link #thinkingBudgetHint}（提示词约束）与 {@link #degradeEffort}（降档重试）。</p>
     *
     * @param outputNeeded 正文所需的 token 数（非正数表示不限）
     * @param effort       生效档位（{@code off} 时不预留）
     * @return 总预算 token
     */
    public static int reserveOutputBudget(int outputNeeded, String effort) {
        if (outputNeeded <= 0) {
            return 0;
        }
        return outputNeeded + reserveFor(effort);
    }

    /**
     * 在既有输出上限上叠加思考安全垫
     *
     * <p>用于「正文需求已估算」的链路（如整页重做轮）：正文需求之外再留一点余量，
     * 避免思考收尾时把正文的最后几个 token 挤掉。</p>
     *
     * @param budget 既有上限（可空 / 非正数表示"未设上限"）
     * @param effort 生效档位
     * @return 叠加后的上限（输入为空时原样返回）
     */
    public static Integer applyReserve(Integer budget, String effort) {
        if (budget == null || budget <= 0) {
            return budget;
        }
        return budget + reserveFor(effort);
    }

    /**
     * 按档位计算思考预留 token
     *
     * @param effort 生效档位
     * @return 预留 token（{@code off} 为 0）
     */
    public static int reserveFor(String effort) {
        String v = effort == null ? "" : effort.trim().toLowerCase(Locale.ROOT);
        if (AiReasoningSupport.EFFORT_OFF.equals(v)) {
            return 0;
        }
        // medium 及以上（不限 Qwen 端点实际支持哪几档）给更多余量
        return AiReasoningSupport.EFFORT_MEDIUM.equals(v) || "high".equals(v)
                || AiReasoningSupport.EFFORT_XHIGH.equals(v)
                ? THINKING_RESERVE_TOKENS_HIGH : THINKING_RESERVE_TOKENS;
    }

    /**
     * 提示词里的思考预算约束（降低思考量的最有效手段）
     *
     * <p>实测（同一问题、同一档位、同一预算）：加此约束后思考量 968 → 268 字符（-72%），
     * completion 6862 → 696 token（-90%），正文质量无明显损失。</p>
     *
     * @param effort 生效档位（{@code off} 时无需约束）
     * @return 可直接拼接到提示词末尾的约束文本；不需要时返回空串
     */
    public static String thinkingBudgetHint(String effort) {
        String v = effort == null ? "" : effort.trim().toLowerCase(Locale.ROOT);
        if (AiReasoningSupport.EFFORT_OFF.equals(v)) {
            return "";
        }
        return "\n\n【思考预算】请在 500 字以内完成思考，确认方案后立即输出结果，不要展开长篇分析或复述需求。"
                + "（思考与输出共用同一份 token 预算，思考过长会导致正文被截断为空。）\n";
    }

    /**
     * 判定本次请求是否<b>已关闭思考</b>
     *
     * <p>两种关闭形态都要认：</p>
     * <ul>
     *   <li>Qwen 系：{@code extraBody.chat_template_kwargs.enable_thinking == false}；</li>
     *   <li>其他端点：{@code reasoning_effort == minimal}。</li>
     * </ul>
     *
     * <p>已关思考的请求<b>不需要</b>再拼思考预算约束（没有思考可约束）。</p>
     *
     * @param options 本次请求的 options（可空）
     * @return 是否已关闭思考
     */
    public static boolean isThinkingOff(OpenAiChatOptions options) {
        if (options == null) {
            return false;
        }
        Map<String, Object> body = options.getExtraBody();
        if (body != null) {
            Object ctk = body.get("chat_template_kwargs");
            if (ctk instanceof Map<?, ?> map) {
                if (Boolean.FALSE.equals(map.get("enable_thinking"))) {
                    return true;
                }
            }
        }
        return "minimal".equalsIgnoreCase(options.getReasoningEffort());
    }

    /**
     * 「思考吃满预算」的判定
     *
     * <p>三个信号同时成立才判定（避免误杀正常的短输出）：</p>
     * <ol>
     *   <li>输出撞上限（{@code finish_reason == "length"}）；</li>
     *   <li>正文为空（长度 0）；</li>
     *   <li>确实产生了思考（有思考内容或思考 token 计数 &gt; 0）。</li>
     * </ol>
     *
     * @param finishReason      端点返回的 finish_reason（可空）
     * @param contentLength     正文长度
     * @param reasoningLength   思考内容长度
     * @param reasoningTokens   思考 token 计数（可空；端点不一定上报）
     * @return 是否判定为「思考吃满预算」
     */
    public static boolean isThinkingFlooded(String finishReason, int contentLength,
                                             int reasoningLength, Integer reasoningTokens) {
        if (!"length".equalsIgnoreCase(finishReason == null ? "" : finishReason.trim())) {
            return false;
        }
        if (contentLength > 0) {
            return false;
        }
        boolean hasReasoning = reasoningLength > 0
                || (reasoningTokens != null && reasoningTokens > 0);
        return hasReasoning;
    }

    /**
     * 思考吃满后的降档：{@code low → off}，{@code medium → low}，{@code off → off}（不再降）
     *
     * <p>降 {@code off} 的效果：{@code enable_thinking=false} → 思考恒为 0 →
     * 全部预算归正文，正文必定产出（实测端点行为稳定）。</p>
     *
     * @param effort 当前档位
     * @return 降档后的档位
     */
    public static String degradeEffort(String effort) {
        String v = effort == null ? "" : effort.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case AiReasoningSupport.EFFORT_MEDIUM -> AiReasoningSupport.EFFORT_LOW;
            case AiReasoningSupport.EFFORT_LOW -> AiReasoningSupport.EFFORT_OFF;
            default -> v;
        };
    }

    /**
     * 强制在本次请求中关闭思考（忽略配置档位），用于「思考吃满 → 降档重试」
     *
     * <p><b>按端点族分两种形态</b>（与 {@link AiReasoningSupport} 的 off 分支保持一致）：</p>
     * <ul>
     *   <li><b>Qwen 系</b>：{@code chat_template_kwargs: {"enable_thinking": false}}
     *       —— 实测这是该端点唯一可靠的关思考手段（顶层 {@code enable_thinking} 被忽略、
     *       {@code /no_think} 已失效）；</li>
     *   <li><b>其他端点</b>（OpenAI o 系列等）：{@code reasoning_effort=minimal}
     *       —— 发 {@code chat_template_kwargs} 对它们无效，
     *       反而会多出一个被忽略的请求体字段。</li>
     * </ul>
     *
     * @param builder options builder（可空）
     * @param config  模型配置（可空；为空时按 Qwen 系处理）
     */
    public static void forceOff(OpenAiChatOptions.Builder builder, AiModelConfig config) {
        if (builder == null) {
            return;
        }
        if (config != null && !AiReasoningSupport.isQwenFamily(config)) {
            // 非 Qwen 系：最低档即为关闭思考
            builder.reasoningEffort("minimal");
            return;
        }
        // 复用适配层的 off 形态：Qwen → chat_template_kwargs；其他端点 → reasoning_effort=minimal
        Map<String, Object> existing = builder.build().getExtraBody();
        Map<String, Object> body = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        builder.extraBody(body);
        builder.reasoningEffort(null);
    }

    /**
     * 强制关闭思考（不区分端点族，一律按 Qwen 形态处理）
     *
     * <p>仅供「配置未知」的兜底场景使用；已知模型配置时请用
     * {@link #forceOff(OpenAiChatOptions.Builder, AiModelConfig)}。</p>
     *
     * @param builder options builder（可空）
     */
    public static void forceOff(OpenAiChatOptions.Builder builder) {
        forceOff(builder, null);
    }

    /**
     * 判定模型配置是否为 Qwen 系（供思考预算策略分端点处理）
     */
    public static boolean isQwen(AiModelConfig config) {
        return AiReasoningSupport.isQwenFamily(config);
    }

    /**
     * 是否已显式指定思考预算标记（用于日志与调试）
     */
    public static boolean hasForceOffMarker(Map<String, Object> extraBody) {
        return extraBody != null && extraBody.containsKey(FORCE_OFF_MARKER);
    }

    /**
     * 从端点原始 usage 中提取思考 token 数
     *
     * <p>OpenAI 兼容协议里是 {@code completion_tokens_details.reasoning_tokens}。
     * 部分端点不上报该字段（返回 null）——此时判定「思考吃满」退化为用
     * 「思考字符数 &gt; 0」这一个信号。</p>
     *
     * <p>放在这里而非各服务内部，是为了让所有链路共用同一套提取口径
     * （管线 {@code AiTemplateGenServiceImpl}、文章 {@code AiArticleGenServiceImpl}、
     * 设计稿 {@code MockupDesignService} 三处各写一份迟早会漂移）。</p>
     *
     * @param nativeUsage 端点原始 usage 对象（可空）
     * @return 思考 token 数；端点未上报或解析失败时返回 null
     */
    public static Integer extractReasoningTokens(Object nativeUsage) {
        if (nativeUsage == null) {
            return null;
        }
        try {
            tools.jackson.databind.JsonNode node = JSON_MAPPER.valueToTree(nativeUsage);
            if (node == null) {
                return null;
            }
            tools.jackson.databind.JsonNode details = node.get("completion_tokens_details");
            if (details == null) {
                return null;
            }
            tools.jackson.databind.JsonNode reasoning = details.get("reasoning_tokens");
            if (reasoning == null || reasoning.isNull() || !reasoning.isNumber()) {
                return null;
            }
            return reasoning.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 空值安全的档位文本（供日志）
     */
    public static String describe(String effort) {
        return StringUtils.hasText(effort) ? effort : "(未指定)";
    }

    /**
     * 单轮流式调用的诊断信息（识别「思考吃满预算 → 正文 0 token」）
     *
     * <p>2026-10-29 端点实测：{@code max_tokens} 是思考 + 正文共享预算，
     * 思考写满即正文 0。此时端点返回 {@code finish_reason=length} 且正文长度为 0，
     * 而思考长度 &gt; 0 —— 三者同时成立即可判定「思考吃满」，据此降档重试。</p>
     *
     * <p>放在这里而非各服务内部，是为了让<b>所有</b>链路共用同一套判定口径
     * （模板生成 S4~S8、文章生成 S9、设计稿生成 S2 都会用到）。</p>
     */
    public static final class RoundDiagnostics {
        /** 端点返回的 finish_reason（流式最后一个 chunk 携带；端点返回大写 LENGTH） */
        public String finishReason;
        /** 本轮累计思考字符数（必须用差分后的真实增量，见 ReasoningStreamAccumulator） */
        public int reasoningChars;
        /** 端点上报的思考 token 数（部分端点不上报，为 null） */
        public Integer reasoningTokens;
        /**
         * 本轮正文长度（字符）
         *
         * <p><b>必须显式赋值</b>：三信号判定要求「正文长度 == 0」，
         * 若一律按 0 处理，则「正文被截断但已产出内容」会被误判成「思考吃满」，
         * 从而错误地触发关思考重试（丢掉已经写出来的正文）。</p>
         *
         * <p>调用点若已在上层确认「本轮无可用正文」（如解析失败、正文为空），
         * 可沿用默认值 0。</p>
         */
        public int contentLength;

        /**
         * 是否判定为「思考吃满预算、正文 0 token」
         */
        public boolean thinkingFlooded() {
            return AiThinkingBudget.isThinkingFlooded(finishReason, contentLength, reasoningChars, reasoningTokens);
        }
    }

    /**
     * 给提示词追加「思考预算约束」（降低思考占满输出预算的概率）
     *
     * <p><b>效果（实测）</b>：同一问题、同一档位、同一预算，
     * 思考量 968 → 268 字符（<b>-72%</b>），completion 6862 → 696 token（<b>-90%</b>）。
     * 思考与正文同价（48 tok/s），省下的思考时间直接变成正文产出的等待时间。</p>
     *
     * <p><b>跳过条件</b>：</p>
     * <ul>
     *   <li>本次请求已关闭思考（{@code enable_thinking=false} / {@code reasoning_effort=minimal}）
     *       → 没有思考可约束，不追加；</li>
     *   <li>最后一条用户消息已含约束标记 → 不重复追加（多轮/重试会复用 messages）。</li>
     * </ul>
     *
     * @param messages 本轮消息列表（可空；不可变列表也能安全处理——返回新列表）
     * @param options  本次请求的 options（用于判断是否已关思考；可空）
     * @return 追加约束后的消息列表（无需追加时原样返回）
     */
    public static List<Message> applyBudgetHint(List<Message> messages, OpenAiChatOptions options) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        if (isThinkingOff(options)) {
            return messages;
        }
        String hint = thinkingBudgetHint(AiReasoningSupport.EFFORT_LOW);
        if (!StringUtils.hasText(hint)) {
            return messages;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof UserMessage um) {
                String text = um.getText();
                if (text != null && text.contains(HINT_MARKER)) {
                    return messages;
                }
                List<Message> copy = new ArrayList<>(messages);
                copy.set(i, new UserMessage((text == null ? "" : text) + hint));
                return copy;
            }
        }
        return messages;
    }
}
