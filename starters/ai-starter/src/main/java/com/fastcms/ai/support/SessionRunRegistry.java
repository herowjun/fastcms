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

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话运行注册表：sessionId → {@link RunChannel}
 *
 * <p>职责：① 任务进行中的会话登记（同会话并发任务防御：已有运行中任务时拒绝新任务）；
 * ② 前端打开会话时的运行态探测（run-status 端点）与续连接入（stream 端点）；
 * ③ 终态短保留——任务结束后不立即移除，保留 {@link #FINISHED_RETENTION_MS}
 * 供迟到的续连回放完整事件（含 done），过期后惰性清理（get 时顺带移除，
 * 不起后台清理线程）。</p>
 *
 * <p>生命周期：内存注册表，随 JVM——重启后任务本就不存在（返回"无运行任务"），
 * 会话靠既有断点续传恢复（plan.json / mappingCache / 文件表），自洽。</p>
 *
 * @author wjun_java@163.com
 * @since 2026-09-14
 */
@Component
public class SessionRunRegistry {

    /** 任务结束后 RunChannel 的保留时长（迟到的续连仍能收到完整事件回放） */
    public static final long FINISHED_RETENTION_MS = 5 * 60 * 1000L;

    private final Map<String, RunChannel> runs = new ConcurrentHashMap<>();

    /**
     * 登记运行任务
     *
     * @return false = 该会话已有任务（含终态未过期），调用方应拒绝新任务
     */
    public boolean tryRegister(String sessionId, RunChannel run) {
        RunChannel existing = runs.putIfAbsent(sessionId, run);
        if (existing == null) {
            return true;
        }
        // 前任已结束且过期：替换登记（惰性清理路径之一）
        return existing.isFinished() && existing.finishedForMs() > FINISHED_RETENTION_MS
                && runs.replace(sessionId, existing, run);
    }

    /**
     * 查询运行通道（含终态未过期的）
     *
     * @return empty = 无任务或终态已过期（过期时顺带清理）
     */
    public Optional<RunChannel> get(String sessionId) {
        RunChannel run = runs.get(sessionId);
        if (run == null) {
            return Optional.empty();
        }
        if (run.isFinished() && run.finishedForMs() > FINISHED_RETENTION_MS) {
            runs.remove(sessionId, run);
            return Optional.empty();
        }
        return Optional.of(run);
    }

    /**
     * 任务结束后移除登记（与 {@link RunChannel#finish()} 配对；保留终态短 TTL 的语义
     * 由 finish 不 remove + get 惰性清理实现，本方法供需要立即让位的场景）
     */
    public void remove(String sessionId, RunChannel expected) {
        if (expected != null) {
            runs.remove(sessionId, expected);
        } else {
            runs.remove(sessionId);
        }
    }
}
