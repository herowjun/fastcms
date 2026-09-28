package com.fastcms.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 捕获机制自检（无 Spring 上下文，秒级）：直接向 CapturingEmitter 发事件，
 * 验证反射读 earlySendAttempts + SSE 行解析的正确性——真冒烟前的机制前置校验
 */
class AiTemplateSseCaptureSelfTest {

    @Test
    void capturesEventNameAndData() {
        AiTemplateSseSmokeTest.CapturingEmitter emitter = new AiTemplateSseSmokeTest.CapturingEmitter();
        try {
            emitter.send(SseEmitter.event().id("1").name("reasoning")
                    .data("{\"page\":\"article/文章详情\",\"delta\":\"正在构思\"}"));
            emitter.send(SseEmitter.event().id("2").name("status").data("并行设计（0/8 完成）：文章页…"));
            emitter.send(SseEmitter.event().id("3").name("message").data("设计稿切分完成"));
        } catch (Exception e) {
            throw new IllegalStateException("send 不应抛异常（无 handler 时应进缓冲）", e);
        }
        emitter.drainEarlySends();

        assertEquals(3, emitter.captured.size(), "应捕获 3 个事件，实际: " + emitter.captured);
        assertEquals("reasoning", emitter.captured.get(0)[0]);
        assertTrue(emitter.captured.get(0)[1].startsWith("{\"page\":\"article/文章详情\""),
                "reasoning 载荷应含页标识 JSON: " + emitter.captured.get(0)[1]);
        assertEquals("status", emitter.captured.get(1)[0]);
        assertEquals("并行设计（0/8 完成）：文章页…", emitter.captured.get(1)[1]);
        assertEquals("message", emitter.captured.get(2)[0]);
        assertEquals("设计稿切分完成", emitter.captured.get(2)[1]);
    }

    @Test
    void drainIsIncremental() {
        AiTemplateSseSmokeTest.CapturingEmitter emitter = new AiTemplateSseSmokeTest.CapturingEmitter();
        try {
            emitter.send(SseEmitter.event().id("1").name("status").data("第一次"));
            emitter.drainEarlySends();
            emitter.send(SseEmitter.event().id("2").name("status").data("第二次"));
            emitter.drainEarlySends();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(2, emitter.captured.size());
        assertEquals("第二次", emitter.captured.get(1)[1]);
    }
}
