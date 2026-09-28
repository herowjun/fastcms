package com.fastcms.web;

import com.fastcms.ai.template.IAiTemplateGenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续跑驱动（非回归测试）：把已有会话的状态机推到终态 DONE。
 *
 * <p>整站链路（多页设计 + 审计修正轮 + 转化）单次执行窗口很长，超过单进程观测窗口时
 * 用本类续跑：读 plan.json 判断状态，任务空闲时发空输入推进；遇 AWAITING_CONFIRM
 * 自动发 APPROVE 进转化。状态本身全在 plan.json 里，中断重入安全。</p>
 *
 * <p>参数：{@code -Dsession.id=... -Dwork.dir=...}</p>
 */
@SpringBootTest
public class AiTemplateLandingResumeRunTest {

    private static final Pattern STATE_PATTERN = Pattern.compile("\"state\"\\s*:\\s*\"([^\"]+)\"");
    private static final Duration MAX_WAIT = Duration.ofMinutes(120);

    /** FAILED 重试上限（单次模型空响应/超时属可恢复） */
    private static final int MAX_FAILED_ATTEMPTS = 8;

    @Autowired
    private IAiTemplateGenService genService;

    @Test
    public void resumeUntilDone() throws Exception {
        String sessionId = System.getProperty("session.id");
        String workDirArg = System.getProperty("work.dir");
        assertTrue(sessionId != null && workDirArg != null, "需要 -Dsession.id 与 -Dwork.dir");
        Path workDir = Paths.get(workDirArg);
        out("续跑会话=" + sessionId);
        out("工作目录=" + workDir);

        Instant deadline = Instant.now().plus(MAX_WAIT);
        String state = null;
        String lastLogged = null;
        int failedAttempts = 0;
        while (Instant.now().isBefore(deadline)) {
            state = planState(workDir);
            boolean running = genService.getRunStatus(sessionId).running();
            String tag = state + "/running=" + running;
            if (!tag.equals(lastLogged)) {
                out("[" + java.time.LocalTime.now().withNano(0) + "] " + tag);
                lastLogged = tag;
            }
            if ("DONE".equals(state)) {
                break;
            }
            if (!running) {
                // FAILED 不作为终态：orchestrator 会按 plan.json 进度归位续跑（mappingCache 断点续传），
                // 转化阶段"映射模型返回空响应"属单次偶发，重试即可；超上限才放弃
                if ("FAILED".equals(state) && ++failedAttempts > MAX_FAILED_ATTEMPTS) {
                    out("连续 FAILED " + failedAttempts + " 次，超出上限，停止");
                    break;
                }
                // AWAITING_CONFIRM 需显式 APPROVE 才进转化；其余状态发空输入推进状态机
                String action = "AWAITING_CONFIRM".equals(state) ? "APPROVE" : null;
                out("→ 触发推进 genService.chatStream(confirmAction=" + action + ")，FAILED 计数=" + failedAttempts);
                genService.chatStream(sessionId, null, null, null, null,
                        false, false, false, false, null, action, new SseEmitter(0L));
            }
            Thread.sleep(5000L);
        }
        state = planState(workDir);
        out("最终 plan.state=" + state);

        printArtifacts(workDir);
        assertTrue("DONE".equals(state), "链路未到达 DONE，实际 plan.state=" + state);
    }

    private void printArtifacts(Path workDir) throws Exception {
        out("--- 产物清单（" + workDir + "）---");
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.filter(Files::isRegularFile)
                    .map(p -> workDir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(files::add);
        }
        for (String f : files) {
            out("  " + f + "  (" + Files.size(workDir.resolve(f)) + " B)");
        }
        out("产物文件总数=" + files.size() + "（其中 design/ 下 " +
                files.stream().filter(f -> f.startsWith("design/")).count() + " 个）");
    }

    private static String planState(Path workDir) {
        Path plan = workDir.resolve("design").resolve("plan.json");
        try {
            if (!Files.isRegularFile(plan)) {
                return null;
            }
            Matcher m = STATE_PATTERN.matcher(Files.readString(plan));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void out(String line) {
        System.out.println("[RESUME] " + line);
    }
}
