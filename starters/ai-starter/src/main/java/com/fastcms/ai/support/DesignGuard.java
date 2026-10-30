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

/**
 * 设计稿生成的早停守卫（B4 · 早停三件套）
 *
 * <p>现有的「256K 思考保险丝」是<b>事后刹车</b>——等思考已经烧了几万 token 才停。
 * 本类提供三个<b>前置信号</b>，让失控在早期就被识别：</p>
 *
 * <ol>
 *     <li><b>思考预算</b>（{@link #onReasoning}）：累计思考字符数超阈值即中止。
 *         思考 token 与正文 token 同价（实测 48 tok/s decode），
 *         8192 token 的纯思考 ≈ 170 秒纯等待，1 万字符的预算相当于提前 3 分钟刹车。</li>
 *     <li><b>静止计时</b>（{@link #checkStall}）：超过阈值没有任何增量（既没有思考也没有正文）
 *         视为假死——上游挂着不回，最典型的是端点排队或连接半开。</li>
 *     <li><b>重复窗口</b>（{@link #isLooping}）：思考文本尾部出现同一片段反复粘贴，
 *         是推理模型陷入循环的典型特征（实测「重复循环 + 无上限 reasoning 累积」会把本机内存打爆）。</li>
 * </ol>
 *
 * <p>本类<b>不做任何 I/O</b>，只做判定与计数，便于单测覆盖。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public class DesignGuard {

    /**
     * 思考预算（累计思考字符数上限）；≤ 0 表示不限制
     */
    private final long reasoningBudgetChars;

    /**
     * 静止超时（毫秒）；≤ 0 表示不限制
     */
    private final long stallTimeoutMillis;

    /**
     * 重复检测窗口长度（字符）
     */
    private final int loopWindowChars;

    /**
     * 重复次数阈值：窗口片段在尾部缓冲中出现这么多次即判循环
     */
    private final int loopRepeatThreshold;

    /**
     * 尾部滚动缓冲（只保留判定所需的量，不随思考长度线性增长）
     */
    private final StringBuilder tail = new StringBuilder();

    /**
     * 累计思考字符数
     */
    private long reasoningChars;

    /**
     * 最后一次活动时间（毫秒）
     */
    private long lastActivityMillis;

    /**
     * @param reasoningBudgetChars 思考预算（字符）；≤ 0 不限制
     * @param stallTimeoutMillis   静止超时（毫秒）；≤ 0 不限制
     * @param loopWindowChars      重复检测窗口（字符）；≤ 0 关闭循环检测
     * @param loopRepeatThreshold  重复次数阈值；≤ 1 关闭循环检测
     */
    public DesignGuard(long reasoningBudgetChars, long stallTimeoutMillis,
                        int loopWindowChars, int loopRepeatThreshold) {
        this.reasoningBudgetChars = reasoningBudgetChars;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.loopWindowChars = loopWindowChars;
        this.loopRepeatThreshold = loopRepeatThreshold;
        this.lastActivityMillis = System.currentTimeMillis();
    }

    /**
     * 记录一次活动（收到任何增量：思考或正文）——重置静止计时
     */
    public void onActivity() {
        lastActivityMillis = System.currentTimeMillis();
    }

    /**
     * 记录一段思考增量
     *
     * @param delta 增量文本（可空）
     * @throws IllegalStateException 思考预算超限
     */
    public void onReasoning(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        reasoningChars += delta.length();
        appendTail(delta);
        if (reasoningBudgetChars > 0 && reasoningChars > reasoningBudgetChars) {
            throw new IllegalStateException("设计模型思考过程超预算（已输出 "
                    + reasoningChars + " 字符，预算 " + reasoningBudgetChars + "），已中止本轮调用");
        }
        if (isLooping()) {
            throw new IllegalStateException("设计模型思考过程疑似循环重复（尾部片段重复 "
                    + loopRepeatThreshold + " 次以上），已中止本轮调用");
        }
    }

    /**
     * 静止检查：距上次活动超过阈值即判假死
     *
     * @throws IllegalStateException 静止超时
     */
    public void checkStall() {
        if (stallTimeoutMillis <= 0) {
            return;
        }
        long idle = System.currentTimeMillis() - lastActivityMillis;
        if (idle > stallTimeoutMillis) {
            throw new IllegalStateException("设计模型长时间无响应（已 "
                    + (idle / 1000) + " 秒无任何输出，阈值 " + (stallTimeoutMillis / 1000) + " 秒），已中止本轮调用");
        }
    }

    /**
     * 尾部片段是否反复出现（循环判定）
     *
     * @return true = 疑似循环
     */
    public boolean isLooping() {
        if (loopWindowChars <= 0 || loopRepeatThreshold <= 1) {
            return false;
        }
        int need = loopWindowChars * loopRepeatThreshold;
        if (tail.length() < need) {
            return false;
        }
        String suffix = tail.substring(tail.length() - loopWindowChars);
        int count = 0;
        int idx = 0;
        // 非重叠计数：每次跳过整个片段长度
        while ((idx = tail.indexOf(suffix, idx)) >= 0) {
            count++;
            idx += suffix.length();
        }
        return count >= loopRepeatThreshold;
    }

    /**
     * 累计思考字符数
     */
    public long reasoningChars() {
        return reasoningChars;
    }

    /**
     * 距上次活动的空闲毫秒数
     */
    public long idleMillis() {
        return System.currentTimeMillis() - lastActivityMillis;
    }

    /**
     * 追加到尾部缓冲（只保留判定所需的量）
     */
    private void appendTail(String delta) {
        tail.append(delta);
        int keep = loopWindowChars > 0 ? loopWindowChars * (loopRepeatThreshold + 1) : 0;
        if (keep > 0 && tail.length() > keep) {
            tail.delete(0, tail.length() - keep);
        }
    }
}
