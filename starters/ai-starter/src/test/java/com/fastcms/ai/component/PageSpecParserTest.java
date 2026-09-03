package com.fastcms.ai.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PageSpecParser 解析测试：标准格式 / markdown 剥壳 / 裸 spec 根对象 / 非法输入
 */
class PageSpecParserTest {

    private final PageSpecParser parser = new PageSpecParser();

    private String sampleSpecJson() {
        return """
                {
                  "specVersion": "1.0",
                  "foundation": "tailwind-v4",
                  "templateName": "demo",
                  "siteName": "演示站",
                  "siteType": "corporate-site",
                  "stylePreset": "corporate",
                  "primaryColor": "#2563eb",
                  "pages": {
                    "index": { "sections": [ { "id": "hero", "component": "tw:hero", "variant": "centered", "data": { "title": "你好" } } ] }
                  }
                }
                """;
    }

    @Test
    void shouldParseStandardFormat() {
        String raw = "{\"reply\": \"设计完成\", \"pagespec\": " + sampleSpecJson() + "}";
        PageSpecParser.ParseResult result = parser.parseResponse(raw);
        assertEquals("设计完成", result.reply());
        assertNotNull(result.pagespec());
        assertEquals("演示站", result.pagespec().safeSiteName());
        assertEquals(1, result.pagespec().sectionsOf("index").size());
        assertEquals("tw:hero", result.pagespec().sectionsOf("index").get(0).component());
    }

    @Test
    void shouldStripMarkdownCodeBlock() {
        String raw = "好的，以下是规划：\n```json\n{\"reply\": \"完成\", \"pagespec\": " + sampleSpecJson() + "}\n```\n希望有帮助";
        PageSpecParser.ParseResult result = parser.parseResponse(raw);
        assertEquals("完成", result.reply());
        assertNotNull(result.pagespec());
    }

    @Test
    void shouldParseBareSpecRoot() {
        // 兼容：AI 直接把 PageSpec 作为根对象输出（无 reply 包裹）
        PageSpecParser.ParseResult result = parser.parseResponse(sampleSpecJson());
        assertNull(result.reply());
        assertNotNull(result.pagespec());
        assertEquals("demo", result.pagespec().safeTemplateName());
    }

    @Test
    void shouldReturnNullsOnInvalidInput() {
        assertNull(parser.parseResponse(null).pagespec());
        assertNull(parser.parseResponse("").pagespec());
        assertNull(parser.parseResponse("这不是 JSON").pagespec());
        // JSON 对象但既无 pagespec 也不像 PageSpec
        assertNull(parser.parseResponse("{\"foo\": \"bar\"}").pagespec());
    }

}