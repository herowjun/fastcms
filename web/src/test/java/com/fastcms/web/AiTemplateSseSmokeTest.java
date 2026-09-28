package com.fastcms.web;

import com.fastcms.ai.template.AiTemplateSessionRequest;
import com.fastcms.ai.template.IAiTemplateGenService;
import com.fastcms.entity.AiTemplateSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 事件流冒烟（非回归测试）：实证并行设计段的事件协议
 *
 * <p>覆盖会话：design 新建 → uploadReference → chatStream 触发 → observeStream 挂捕获
 * emitter 续看。验证三项（2026-09-26 页级并行前端适配的事件契约）：</p>
 * <ol>
 *     <li>reasoning 事件为 JSON {"page":"name/title","delta":"…"}（分节思考流，前端按页分桶）</li>
 *     <li>status 事件为聚合板文案「并行设计（k/N 完成）：页名…」</li>
 *     <li>并行段不再出现逐页「正在设计「…」与逐文件「正在生成 …」的覆盖式 status（闪烁源已抑制）</li>
 * </ol>
 *
 * <p>捕获方式：测试环境无真实 HTTP handler，ResponseBodyEmitter 会把所有 send 的数据
 * 暂存进 earlySendAttempts 缓冲（SseEmitter.send(SseEventBuilder) 内部循环调
 * super.send(entry)——注意它绕过 send(Object, MediaType) 覆写，覆写方案实测捕获不到）。
 * 反射读取该缓冲解析 SSE 行前缀（id:/event:/data:）还原 (event, data) 对，读后清空防内存增长。
 * 捕获到足够样本即 stopRun 干净收尾，不等整站跑完（冒烟目标只是事件协议，非产物）。</p>
 */
@SpringBootTest
public class AiTemplateSseSmokeTest {

    private static final Path SOURCE_HTML =
            Paths.get("F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-landing.html");

    private static final Duration TIMEOUT = Duration.ofMinutes(14);

    /**
     * 事件捕获 emitter：不覆写任何 send（SseEventBuilder 形式会绕过覆写），
     * 定期反射读取父类 earlySendAttempts 缓冲解析事件
     */
    static final class CapturingEmitter extends SseEmitter {
        final List<String[]> captured = new CopyOnWriteArrayList<>();

        CapturingEmitter() {
            super(0L);
        }

        /** 拉取并解析 earlySendAttempts，解析后清空缓冲。
         *  Spring 7 结构（探针实证）：ResponseBodyEmitter.earlySendAttempts 为
         *  LinkedHashSet&lt;DataWithMediaType&gt;——每次 send(SseEventBuilder) 依次追加三个元素：
         *  ①"id:1\nevent:reasoning\ndata:"（前缀串）②载荷本体 ③"\n"。并发防护用父类
         *  writeLock（ReentrantLock，send 同锁），非 synchronized。 */
        void drainEarlySends() {
            try {
                Class<?> baseClass = SseEmitter.class.getSuperclass(); // ResponseBodyEmitter
                java.lang.reflect.Field bufField = baseClass.getDeclaredField("earlySendAttempts");
                bufField.setAccessible(true);
                java.lang.reflect.Field lockField = baseClass.getDeclaredField("writeLock");
                lockField.setAccessible(true);
                java.util.concurrent.locks.ReentrantLock lock =
                        (java.util.concurrent.locks.ReentrantLock) lockField.get(this);
                lock.lock();
                try {
                    java.util.Collection<?> buffer = (java.util.Collection<?>) bufField.get(this);
                    if (buffer == null || buffer.isEmpty()) {
                        return;
                    }
                    String currentEvent = "message";
                    for (Object item : buffer) {
                        Object payload = readField(item, "data");
                        if (!(payload instanceof String s)) {
                            continue; // 非字符串载荷（本链路 data 均为 String）
                        }
                        String trimmed = s.trim();
                        if (trimmed.startsWith("id:")) {
                            // 前缀串：提取事件名（"id:1\nevent:reasoning\ndata:"）
                            int e = trimmed.indexOf("event:");
                            if (e >= 0) {
                                int nl = trimmed.indexOf('\n', e);
                                if (nl > e) {
                                    currentEvent = trimmed.substring(e + "event:".length(), nl).trim();
                                }
                            }
                            continue;
                        }
                        if (trimmed.isEmpty()) {
                            continue; // 换行/空白元素
                        }
                        captured.add(new String[]{currentEvent, s});
                    }
                    buffer.clear();
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                // 反射失败静默（缓冲字段名变更等场景）：captured 为空会让断言失败，测试不会假绿
            }
        }

        private static Object readField(Object target, String name) throws Exception {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        }

        List<String[]> data(String event) {
            return captured.stream().filter(e -> e[0].equals(event)).toList();
        }
    }

    @Autowired
    private IAiTemplateGenService genService;

    @Test
    public void captureParallelDesignEventProtocol() throws Exception {
        AiTemplateSessionRequest request = new AiTemplateSessionRequest();
        request.setTemplateName("smoke" + (System.currentTimeMillis() % 1000000L));
        request.setTitle("SSE 事件协议冒烟");
        request.setRequirement("按上传的 FastCMS 官网首页仿写整站模板，其余页面由你从首页推导");
        request.setCreateMode("design");
        request.setMobileAdaptive(true);

        AiTemplateSession session = genService.createSession(request, 1L);
        String sessionId = session.getSessionId();
        out("会话已创建: sessionId=" + sessionId);

        byte[] bytes = Files.readAllBytes(SOURCE_HTML);
        MockMultipartFile file = new MockMultipartFile("file", "fastcms-landing.html", "text/html", bytes);
        genService.uploadReference(sessionId, file, 1L);
        out("ingest 完成，触发状态机");

        // 触发（首订阅者为哑 emitter：RunChannel 内部缓冲，任务照常跑）
        genService.chatStream(sessionId, null, null, null, null, false, false, false, false,
                null, null, new SseEmitter(0L));

        // 挂捕获 emitter 续看（journal 回放 + 实时续接）
        CapturingEmitter capture = new CapturingEmitter();
        genService.observeStream(sessionId, 0, capture);
        out("捕获 emitter 已挂载，等待并行设计事件…");

        Instant deadline = Instant.now().plus(TIMEOUT);
        boolean jsonReasoningSeen = false;
        while (Instant.now().isBefore(deadline)) {
            capture.drainEarlySends();
            List<String[]> reasoning = capture.data("reasoning");
            long jsonCount = reasoning.stream().filter(e -> e[1].startsWith("{\"page\":")).count();
            boolean aggregatedStatus = capture.data("status").stream()
                    .anyMatch(e -> e[1].matches("并行设计（\\d+/\\d+ 完成）：.*"));
            if (jsonCount >= 3 && aggregatedStatus) {
                jsonReasoningSeen = true;
                break;
            }
            Thread.sleep(3000L);
        }
        capture.drainEarlySends();

        // ===== 样本输出（证据面）=====
        List<String[]> reasoning = capture.data("reasoning");
        out("--- reasoning 事件总数=" + reasoning.size()
                + "，其中 JSON 分节格式=" + reasoning.stream().filter(e -> e[1].startsWith("{\"page\":")).count());
        reasoning.stream().filter(e -> e[1].startsWith("{\"page\":")).limit(2)
                .forEach(e -> out("  [reasoning/JSON] " + truncate(e[1], 120)));
        reasoning.stream().filter(e -> !e[1].startsWith("{\"page\":")).limit(2)
                .forEach(e -> out("  [reasoning/纯文本] " + truncate(e[1], 80)));
        out("--- status 事件样本：");
        capture.data("status").stream().limit(12).forEach(e -> out("  [status] " + truncate(e[1], 90)));

        // ===== 断言 =====
        assertTrue(jsonReasoningSeen, "未捕获到 ≥3 个 JSON 分节 reasoning 事件（并行思考流协议未生效）");
        long aggregated = capture.data("status").stream()
                .filter(e -> e[1].matches("并行设计（\\d+/\\d+ 完成）：.*")).count();
        assertTrue(aggregated >= 1, "未捕获到聚合状态条文案（ParallelDesignStatusBoard 未生效）");
        // 抑制断言：并行设计期间的逐页/逐文件覆盖式 status 不应再出现（分析/转化段的其他 status 不受影响）
        assertTrue(capture.data("status").stream().noneMatch(e -> e[1].startsWith("正在设计「")),
                "并行段仍在推送逐页「正在设计」status（应被聚合板取代）");
        assertTrue(capture.data("status").stream().noneMatch(e -> e[1].startsWith("正在生成 design/")),
                "并行段仍在推送逐文件「正在生成 design/…」status（应被聚合板取代）");

        // 干净收尾：停止后台任务（冒烟只验事件协议，不等整站）
        boolean stopped = genService.stopRun(sessionId);
        out("stopRun=" + stopped + "，冒烟结束");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void out(String line) {
        System.out.println("[SMOKE] " + line);
    }
}
