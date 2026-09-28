package com.fastcms.web;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;

/**
 * 一次性探针（不入库测试）：dump Spring 7 earlySendAttempts 元素的精确结构
 */
public class SseStructureProbe {

    public static void main(String[] args) throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.send(SseEmitter.event().id("1").name("reasoning").data("{\"page\":\"a\",\"delta\":\"hi\"}"));
        emitter.send(SseEmitter.event().id("2").name("status").data("并行设计（0/8 完成）：文章页…"));

        Field bufField = emitter.getClass().getSuperclass().getDeclaredField("earlySendAttempts");
        bufField.setAccessible(true);
        Object buf = bufField.get(emitter);
        System.out.println("earlySendAttempts type: " + buf.getClass().getName() + ", size=" + ((java.util.Collection<?>) buf).size());
        for (Object entry : (java.util.Collection<?>) buf) {
            dump("entry", entry, 0);
        }
    }

    private static void dump(String label, Object o, int depth) {
        if (o == null || depth > 3) {
            System.out.println("  ".repeat(depth) + label + " = " + o);
            return;
        }
        if (o instanceof String s) {
            System.out.println("  ".repeat(depth) + label + " : String = [" + s.replace("\n", "\\n") + "]");
            return;
        }
        System.out.println("  ".repeat(depth) + label + " : " + o.getClass().getName());
        if (o instanceof Iterable<?> items) {
            for (Object item : items) {
                dump("item", item, depth + 1);
            }
            return;
        }
        for (Field f : o.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                dump(f.getName(), f.get(o), depth + 1);
            } catch (Exception e) {
                System.out.println("  ".repeat(depth + 1) + f.getName() + " = <" + e.getClass().getSimpleName() + ">");
            }
        }
    }
}
