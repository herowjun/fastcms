package com.fastcms.web;

import com.fastcms.ai.template.AiTemplateSessionRequest;
import com.fastcms.ai.template.IAiTemplateGenService;
import com.fastcms.entity.AiTemplateMessage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实链路驱动（非回归测试）：上传单个 index.html → AI 推导整站 → 产出 fastcms 模板。
 *
 * <p>覆盖会话：design 新建会话 → uploadReference（单文件 ingest 后 create_mode 归一为 import）
 * → chatStream 触发状态机 ANALYZING → DESIGNING → AUDITING → CONVERTING。</p>
 *
 * <p><b>不做断言式回归</b>：依赖真实模型与本地 DB/MySQL，仅用于人工观察产物；
 * 唯一硬断言是链路必须到达终态 DONE。跑完请直接看输出的 workDir 产物清单。</p>
 */
@SpringBootTest
public class AiTemplateLandingFullSiteRunTest {

    /** 待仿写的首页 HTML（单文件上传物） */
    private static final Path SOURCE_HTML =
            Paths.get("F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-landing.html");

    private static final long USER_ID = 1L;
    private static final Duration TIMEOUT = Duration.ofMinutes(150);
    private static final Pattern STATE_PATTERN = Pattern.compile("\"state\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private IAiTemplateGenService genService;

    @Test
    public void runLandingToFullSiteTemplate() throws Exception {
        String templateName = "landing" + (System.currentTimeMillis() % 1000000L);
        AiTemplateSessionRequest request = new AiTemplateSessionRequest();
        request.setTemplateName(templateName);
        request.setTitle("FastCMS landing 整站");
        request.setRequirement("按上传的 FastCMS 官网首页仿写整站模板，其余页面由你从首页推导");
        request.setCreateMode("design");
        request.setMobileAdaptive(true);

        AiTemplateSession session = genService.createSession(request, USER_ID);
        String sessionId = session.getSessionId();
        Path workDir = Paths.get(session.getWorkDir());
        out("会话已创建: sessionId=" + sessionId);
        out("工作目录: " + workDir);

        // 1. 上传单个 index.html（ingest：归一化 + 首页保真落盘 + plan.json）
        byte[] bytes = Files.readAllBytes(SOURCE_HTML);
        MockMultipartFile file = new MockMultipartFile("file", "fastcms-landing.html", "text/html", bytes);
        Map<String, Object> report = genService.uploadReference(sessionId, file, USER_ID);
        out("ingest 报告: " + report);
        out("ingest 后 plan.state=" + planState(workDir));

        // 2. 触发状态机（异步执行，SSE 仅作通道，本测试用文件状态轮询替代）
        genService.chatStream(sessionId, null, null, null, null, false, false, false, false,
                null, null, new SseEmitter(0L));

        // 3. 轮询直到终态
        Instant deadline = Instant.now().plus(TIMEOUT);
        String state = null;
        String last = null;
        while (Instant.now().isBefore(deadline)) {
            state = planState(workDir);
            if (state != null && !state.equals(last)) {
                out("[" + java.time.LocalTime.now().withNano(0) + "] plan.state=" + state);
                last = state;
            }
            if ("DONE".equals(state) || "FAILED".equals(state) || "AWAITING_CONFIRM".equals(state)) {
                break;
            }
            Thread.sleep(3000L);
        }
        out("终态 plan.state=" + state);

        // 4. 观察面：AI 播报（截断）+ site-ia.json + 产物清单
        printMessages(sessionId);
        printFile(workDir.resolve("design").resolve("site-ia.json"), 4000);
        printArtifacts(workDir);

        assertTrue("DONE".equals(state), "链路未到达 DONE，实际 plan.state=" + state);
    }

    private void printMessages(String sessionId) {
        List<AiTemplateMessage> messages = genService.listMessages(sessionId);
        out("会话消息数=" + messages.size());
        for (AiTemplateMessage m : messages) {
            String content = m.getContent() == null ? "" : m.getContent().replace("\n", " ");
            if (content.length() > 600) {
                content = content.substring(0, 600) + "…(截断)";
            }
            out("  [" + m.getRole() + "] " + content);
        }
    }

    private void printFile(Path file, int maxChars) {
        out("--- " + file.getFileName() + " ---");
        try {
            if (!Files.isRegularFile(file)) {
                out("  (不存在)");
                return;
            }
            String text = Files.readString(file);
            out(text.length() > maxChars ? text.substring(0, maxChars) + "…(截断)" : text);
        } catch (Exception e) {
            out("  读取失败: " + e.getMessage());
        }
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
            Path p = workDir.resolve(f);
            out("  " + f + "  (" + Files.size(p) + " B)");
        }
        out("产物文件总数=" + files.size());
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
        System.out.println("[E2E] " + line);
    }
}
