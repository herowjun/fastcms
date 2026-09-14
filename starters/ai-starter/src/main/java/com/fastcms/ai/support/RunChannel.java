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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行通道：长任务与页面连接解耦的 SSE 事件中枢
 *
 * <p>设计目标（后台续跑 + 断线续看）：</p>
 * <ul>
 *     <li><b>任务与连接解耦</b>：任务持有本通道，页面只是订阅者。SSE 断开仅摘除订阅者，
 *     任务照常运行（产物照常落盘）；取消只能由显式停止（stop 端点）触发——
 *     取代旧 SseChannel "断开即取消"的语义（用户关页面/断网不应中断任务）</li>
 *     <li><b>事件 journal</b>：任务运行期间所有事件（reasoning/message/status/file…）追加进
 *     append-only 日志并分配单调递增 seq（SSE 标准 id 字段）。页面重开时先回放
 *     {@code seq > since} 的历史事件，再挂订阅者实时续推——"继续写思考过程"</li>
 *     <li><b>多订阅者扇出</b>：同一会话可多标签页同时观看；单订阅者断开互不影响</li>
 *     <li><b>终态短保留</b>：任务结束后保留一段时间（见 SessionRunRegistry），迟到的续连
 *     仍能回放完整事件（含 done 事件）；过期后惰性清理</li>
 * </ul>
 *
 * <p>容量边界：journal 生命周期 = 任务生命周期（分钟级），且管线对 reasoning 累积有
 * 保险丝封顶、事件量有限，正常无需淘汰；硬上限 {@link #MAX_JOURNAL_ENTRIES} 仅防御
 * 异常任务无限追加，超限时丢弃最老条目（重放时早于 since 的本就不再需要）。</p>
 *
 * <p>线程模型：单写者（任务线程 send）+ 偶发读者（HTTP 线程 subscribe/回放），
 * 全部 journal/订阅者操作走方法级 synchronized——回放与实时推送互斥，
 * 不会对同一 emitter 交错写（SseEmitter 非线程安全）。</p>
 *
 * @author wjun_java@163.com
 * @since 2026-09-14
 */
public final class RunChannel {

    private static final Logger log = LoggerFactory.getLogger(RunChannel.class);

    /** journal 硬上限（防御异常任务无限追加；正常任务远达不到——reasoning 有保险丝封顶） */
    private static final int MAX_JOURNAL_ENTRIES = 10_000;

    /** journal 条目（不可变） */
    private record JournalEntry(long seq, String eventName, String data) {
    }

    private final String reasoningEventName;
    private final List<JournalEntry> journal = new ArrayList<>();
    private final List<SseEmitter> subscribers = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong seqGen = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();
    private volatile long finishedAt = 0L;

    /**
     * @param reasoningEventName reasoning 事件名（用于 {@link #reasoningSoFar()} 从 journal 聚合；
     *                           由调用方传常量，本类不依赖上层常量类）
     */
    public RunChannel(String reasoningEventName) {
        this.reasoningEventName = reasoningEventName;
    }

    // ==================== 任务侧（单写者） ====================

    /**
     * 任务侧推送事件：追加 journal + 扇出给所有订阅者
     *
     * <p>单订阅者发送失败仅摘除该订阅者（断开≠取消），不影响任务与其他订阅者。</p>
     *
     * @return 本事件 seq
     */
    public synchronized long send(String eventName, String data) {
        long seq = seqGen.incrementAndGet();
        journal.add(new JournalEntry(seq, eventName, data));
        if (journal.size() > MAX_JOURNAL_ENTRIES) {
            journal.remove(0);
        }
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(seq)).name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(emitter);
                log.debug("SSE 订阅者已断开，已摘除: event={}", eventName);
            }
        }
        return seq;
    }

    /** 显式取消（仅 stop 端点触发；断开连接不再触发取消） */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 任务正常结束：标记终态并 complete 全部订阅者（journal 保留供迟到续连回放） */
    public void finish() {
        finishedAt = System.currentTimeMillis();
        List<SseEmitter> current;
        synchronized (this) {
            current = new ArrayList<>(subscribers);
            subscribers.clear();
        }
        for (SseEmitter emitter : current) {
            completeQuietly(emitter);
        }
    }

    public boolean isFinished() {
        return finishedAt > 0L;
    }

    /** 已处于终态的时长（ms；未结束返回 0） */
    public long finishedForMs() {
        long t = finishedAt;
        return t == 0L ? 0L : System.currentTimeMillis() - t;
    }

    public long startedAt() {
        return startedAt;
    }

    /** 当前最新 seq（续连请求的 since 基准） */
    public synchronized long lastSeq() {
        return seqGen.get();
    }

    /**
     * 从 journal 聚合已发生的思考过程全文（中断落库用——旧实现取消时丢弃 reasoning，
     * 现在停止/失败也能回看本轮思考）
     */
    public synchronized String reasoningSoFar() {
        StringBuilder sb = new StringBuilder();
        for (JournalEntry e : journal) {
            if (reasoningEventName.equals(e.eventName()) && e.data() != null) {
                sb.append(e.data());
            }
        }
        return sb.toString();
    }

    // ==================== 连接侧（HTTP 线程） ====================

    /**
     * 订阅：先回放 {@code seq > since} 的历史事件，再挂为实时订阅者
     *
     * <p>回放的事件携带原 seq 作为 SSE id——前端记录 lastSeq，再次断线重连时
     * 以此为 since 续接（增量续看，不重播）。</p>
     *
     * @return 回放后挂上订阅时的最新 seq
     */
    public synchronized long subscribe(SseEmitter emitter, long since) {
        for (JournalEntry e : journal) {
            if (e.seq() > since) {
                try {
                    emitter.send(SseEmitter.event().id(String.valueOf(e.seq()))
                            .name(e.eventName()).data(e.data()));
                } catch (IOException | IllegalStateException ex) {
                    // 回放即失败：连接已断，不挂订阅者
                    return lastSeq();
                }
            }
        }
        subscribers.add(emitter);
        return lastSeq();
    }

    /** 摘除订阅者（断开/超时回调——不触发任务取消） */
    public synchronized void unsubscribe(SseEmitter emitter) {
        subscribers.remove(emitter);
    }

    /** 仅回放不订阅（任务已结束的迟到续连：回放完整事件后 complete） */
    public synchronized void replayAndComplete(SseEmitter emitter, long since) {
        for (JournalEntry e : journal) {
            if (e.seq() > since) {
                try {
                    emitter.send(SseEmitter.event().id(String.valueOf(e.seq()))
                            .name(e.eventName()).data(e.data()));
                } catch (IOException | IllegalStateException ex) {
                    completeQuietly(emitter);
                    return;
                }
            }
        }
        completeQuietly(emitter);
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
