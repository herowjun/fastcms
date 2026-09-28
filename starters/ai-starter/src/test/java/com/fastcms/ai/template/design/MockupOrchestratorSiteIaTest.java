package com.fastcms.ai.template.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首页数据区插桩 + 站点 IA 读取测试
 *
 * <p>插桩是<b>对保真首页的唯一写入动作</b>，故两点必须钉死：① 只插入、不改写原有内容与结构
 * （上传站视觉 100% 保留）；② 幂等（重入/续传不重复插入）。</p>
 */
class MockupOrchestratorSiteIaTest {

    private static SiteIa siteIa(String heading, String intro) {
        return new SiteIa("X", "S", heading, intro,
                List.of(new SiteIa.SitePage("核心能力", SiteIa.PAGE_TYPE_PAGE, "features", "D")));
    }

    private static Path writeIndex(Path designDir, String html) throws IOException {
        Files.createDirectories(designDir);
        Path file = designDir.resolve("index.html");
        Files.writeString(file, html, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("数据区插在页脚之前，且首页原有内容一字未改")
    void injectsBeforeFooterKeepingOriginalIntact(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        String original = """
                <!DOCTYPE html>
                <html><head><title>演示站</title></head>
                <body>
                <nav><a href="#features">核心能力</a></nav>
                <section data-block="hero"><h1>原来的首屏文案</h1></section>
                <footer id="footer"><p>版权所有</p></footer>
                </body></html>
                """;
        Path indexFile = writeIndex(designDir, original);

        assertTrue(MockupOrchestrator.injectHomeDataSection(designDir, siteIa("最新动态", "看看最近在做什么")));

        String out = Files.readString(indexFile, StandardCharsets.UTF_8);
        // 数据区就位且带受控语义名（转化段据此接 articleListTag 标签）
        assertTrue(out.contains("data-block=\"article-list\""), "应插入 CMS 数据区语义标记");
        assertTrue(out.contains("data-fastcms-home-data"));
        assertTrue(out.contains("最新动态"));
        assertTrue(out.contains("看看最近在做什么"));
        // 原有内容与结构保留
        assertTrue(out.contains("原来的首屏文案"));
        assertTrue(out.contains("<nav>"));
        assertTrue(out.contains("版权所有"));
        // 位置：在 footer 之前
        assertTrue(out.indexOf("data-fastcms-home-data") < out.indexOf("<footer"),
                "数据区应位于页脚之前");
    }

    @Test
    @DisplayName("无页脚时追加到 body 末尾（不丢页面）")
    void appendsToBodyWhenNoFooter(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        Path indexFile = writeIndex(designDir,
                "<!DOCTYPE html><html><body><section data-block=\"hero\">A</section></body></html>");

        assertTrue(MockupOrchestrator.injectHomeDataSection(designDir, siteIa("最新文章", null)));

        String out = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertTrue(out.contains("data-fastcms-home-data"));
        assertTrue(out.contains("最新文章"));
        // Jsoup 序列化会对块级元素做 pretty-print（"&gt;A&lt;" 会被拆成多行），故按结构+内容分开断言
        assertTrue(out.contains("data-block=\"hero\""), "原有区块结构应保留");
        assertTrue(out.contains("A"), "原有区块内容应保留");
        assertTrue(out.indexOf("data-block=\"hero\"") < out.indexOf("data-fastcms-home-data"),
                "数据区应追加在原有内容之后");
    }

    @Test
    @DisplayName("footer 被 section[data-block=footer] 包装时，数据区插到包装器之前（不被归入 footer 区块）")
    void injectsBeforeFooterWrapperNotInsideIt(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        // 真实踩坑形态：footer 元素被 body 顶层 <section data-block="footer"> 裹住
        String original = """
                <!DOCTYPE html>
                <html><head><title>演示站</title></head>
                <body>
                <nav><a href="#features">核心能力</a></nav>
                <section data-block="hero"><h1>首屏</h1></section>
                <section data-block="footer">
                <footer id="docs"><p>版权所有</p></footer>
                </section>
                </body></html>
                """;
        Path indexFile = writeIndex(designDir, original);

        assertTrue(MockupOrchestrator.injectHomeDataSection(designDir, siteIa("产品动态", null)));

        String out = Files.readString(indexFile, StandardCharsets.UTF_8);
        // 数据区必须在 footer 包装 section 之前（body 顶层），否则 pickFooterSection 会把它
        // 连同 footer 一起归入 footer 区块，语义映射失效、退化成静态 HTML
        int injectIdx = out.indexOf("data-fastcms-home-data");
        int wrapperIdx = out.indexOf("data-block=\"footer\"");
        int footerIdx = out.indexOf("<footer");
        assertTrue(injectIdx >= 0 && wrapperIdx > injectIdx,
                "数据区应插在 footer 包装 section 之前");
        assertTrue(wrapperIdx < footerIdx, "footer 元素仍在包装 section 内");
    }

    @Test
    @DisplayName("幂等：重复调用不产生第二个数据区")
    void idempotentOnSecondCall(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        Path indexFile = writeIndex(designDir, "<html><body><footer>F</footer></body></html>");

        assertTrue(MockupOrchestrator.injectHomeDataSection(designDir, siteIa("最新动态", null)));
        assertTrue(MockupOrchestrator.injectHomeDataSection(designDir, siteIa("最新动态", null)));

        String out = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(out, "data-fastcms-home-data"), "数据区应只存在一个");
    }

    @Test
    @DisplayName("首页不存在时不报错，返回 false（不阻断其他页面）")
    void toleratesMissingIndex(@TempDir Path tmp) {
        assertFalse(MockupOrchestrator.injectHomeDataSection(tmp.resolve("design"), siteIa("X", null)));
    }

    @Test
    @DisplayName("AI 文案转义：标题里的尖括号引号不破坏 HTML 结构")
    void escapesAiText(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        Path indexFile = writeIndex(designDir, "<html><body><footer>F</footer></body></html>");

        assertTrue(MockupOrchestrator.injectHomeDataSection(designDir,
                siteIa("<script>alert(1)</script>", "\"引号\"")));

        String out = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertFalse(out.contains("<script>alert(1)</script>"), "AI 文案必须转义后才进 HTML");
        assertTrue(out.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("site-ia.json 读回栏目内容定位（设计提示词用它指导栏目页内容）")
    void readsDescriptionsFromSiteIa(@TempDir Path tmp) throws IOException {
        Path workDir = tmp;
        Path designDir = workDir.resolve("design");
        Files.createDirectories(designDir);
        Files.writeString(designDir.resolve("site-ia.json"),
                "{\"pages\":[{\"title\":\"核心能力\",\"pageType\":\"page\",\"slug\":\"features\","
                        + "\"description\":\"展示三大核心能力\"}]}",
                StandardCharsets.UTF_8);

        Map<String, String> descs = MockupOrchestrator.siteIaDescriptions(workDir);

        assertEquals(1, descs.size());
        assertEquals("展示三大核心能力", descs.get("features"));
    }

    @Test
    @DisplayName("无 site-ia.json / 内容损坏时返回空表，不抛异常")
    void toleratesMissingOrBrokenSiteIa(@TempDir Path tmp) throws IOException {
        assertTrue(MockupOrchestrator.siteIaDescriptions(tmp).isEmpty());

        Path designDir = tmp.resolve("design");
        Files.createDirectories(designDir);
        Files.writeString(designDir.resolve("site-ia.json"), "{不是合法 JSON", StandardCharsets.UTF_8);
        assertTrue(MockupOrchestrator.siteIaDescriptions(tmp).isEmpty());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
