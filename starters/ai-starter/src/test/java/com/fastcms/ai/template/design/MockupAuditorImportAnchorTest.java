package com.fastcms.ai.template.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入会话审计锚点适配测试（A2 继承调色板 / A4 锚点基准签名）
 *
 * <p>背景：单文件导入会话中 AI 子页的视觉基准来自保真首页。修正前 A2 对子页
 * 继承原站色彩照计硬编码（必然超限、修正轮永不收敛），A4 让子页互相比 nav/footer
 * （基准漂移）。修正后以保真首页为锚点：命中调色板不计、基准签名取首页。</p>
 */
class MockupAuditorImportAnchorTest {

    private static final String PAGE_HEAD = """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width">
            <style>@media (max-width:768px){.x{}}@media (min-width:1024px){.x{}}</style>
            </head><body>
            """;

    /** 合规子页骨架：3 个顶层 section + viewport + 2 断点 + nav/footer 同构首页 */
    private static String childPage(String navHtml, String footerHtml) {
        return PAGE_HEAD + navHtml + """
                <section data-block="hero"><h1>A</h1></section>
                <section data-block="features"><h2>B</h2></section>
                <section data-block="cta"><h2>C</h2></section>
                """ + footerHtml + "</body></html>";
    }

    private static void writePage(Path designDir, String name, String html) throws IOException {
        Files.createDirectories(designDir);
        Files.writeString(designDir.resolve(name + ".html"), html, StandardCharsets.UTF_8);
    }

    private static List<DesignPagePlanner.PagePlan> plans(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> new DesignPagePlanner.PagePlan(n, n, "desc", n))
                .toList();
    }

    private static String nav() {
        return "<nav id=\"nav\"><a href=\"#\">首页</a><a href=\"#\">栏目</a></nav>";
    }

    private static String footerNoId() {
        return "<footer id=\"docs\"><p>© 2026</p></footer>";
    }

    @Test
    @DisplayName("A2：子页继承首页色值不计入硬编码（调色板命中），新发色值照计")
    void a2InheritedPaletteExempt(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        // 保真首页：深色主题 5 处色值（style 属性）
        String anchor = PAGE_HEAD + nav()
                + "<section data-block=\"hero\" style=\"background:#0a0f1e;color:#e2e8f0\">"
                + "<h1 style=\"color:#94a3b8\">H</h1></section>"
                + "<section data-block=\"f\"><h2>B</h2></section>"
                + "<section data-block=\"c\"><h2>C</h2></section>"
                + footerNoId() + "</body></html>";
        writePage(designDir, "home", anchor);
        // 子页：继承 5 处同款色值（不计）+ 1 处新发明色值（计）
        String child = childPage(nav(),
                "<footer id=\"docs\"><p>© 2026</p></footer>")
                .replace("<section data-block=\"hero\">",
                        "<section data-block=\"hero\" style=\"background:#0a0f1e;color:#e2e8f0\">")
                .replace("<section data-block=\"features\">",
                        "<section data-block=\"features\" style=\"color:#94a3b8\">")
                .replace("<section data-block=\"cta\">",
                        "<section data-block=\"cta\" style=\"color:#ff00ff\">");
        writePage(designDir, "features", child);

        List<MockupAuditor.AuditIssue> issues = MockupAuditor.audit(tmp,
                plans("home", "features"), Set.of(), Set.of("home"), true,
                designDir.resolve("home.html"));

        // 新发色值仅 1 处（#ff00ff），未超上限 10 → 无 A2 问题
        assertTrue(issues.stream().noneMatch(i -> "A2".equals(i.code())),
                "继承首页色值不应计入 A2: " + issues);
    }

    @Test
    @DisplayName("A2：无锚点（自由设计会话）维持原口径——继承色值照计")
    void a2WithoutAnchorCountsAll(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        StringBuilder styleAttrs = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            styleAttrs.append("<section data-block=\"b").append(i)
                    .append("\" style=\"color:#abcde").append(i % 10).append("\"><p>x</p></section>\n");
        }
        writePage(designDir, "features",
                PAGE_HEAD + nav() + styleAttrs + footerNoId() + "</body></html>");

        List<MockupAuditor.AuditIssue> issues = MockupAuditor.audit(tmp,
                plans("features"), Set.of(), Set.of(), true, null);

        assertTrue(issues.stream().anyMatch(i -> "A2".equals(i.code())),
                "自由设计会话 15 处色值应报 A2");
    }

    @Test
    @DisplayName("A4：导入会话以首页 nav/footer 为基准；首页 footer 无 #footer 时不强求子页")
    void a4AnchorBaselineAndFooterTolerance(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        String anchor = PAGE_HEAD + nav()
                + "<section data-block=\"a\"><h1>A</h1></section>"
                + "<section data-block=\"b\"><h2>B</h2></section>"
                + "<section data-block=\"c\"><h2>C</h2></section>"
                + footerNoId() + "</body></html>";
        writePage(designDir, "home", anchor);
        // 子页完全复制首页 nav + footer（无 id=footer）——修正前子页会被判"缺少 #footer 元素"
        writePage(designDir, "features", childPage(nav(), footerNoId()));
        writePage(designDir, "market", childPage(nav(), footerNoId()));

        List<MockupAuditor.AuditIssue> issues = MockupAuditor.audit(tmp,
                plans("home", "features", "market"), Set.of(), Set.of("home"), true,
                designDir.resolve("home.html"));

        assertTrue(issues.stream().noneMatch(i -> "A4".equals(i.code())),
                "子页同构于保真首页（含无 id 的 footer）不应报 A4: " + issues);
    }

    @Test
    @DisplayName("A4：导入会话子页 nav 与首页不同构仍要报（基准不能失效）")
    void a4StillReportsRealDrift(@TempDir Path tmp) throws IOException {
        Path designDir = tmp.resolve("design");
        String anchor = PAGE_HEAD + nav()
                + "<section data-block=\"a\"><h1>A</h1></section>"
                + "<section data-block=\"b\"><h2>B</h2></section>"
                + "<section data-block=\"c\"><h2>C</h2></section>"
                + footerNoId() + "</body></html>";
        writePage(designDir, "home", anchor);
        // 子页 nav 结构不同（多一层 div / class 不同）
        writePage(designDir, "features", childPage(
                "<nav id=\"main-nav\" class=\"nav2\"><div><a href=\"#\">首页</a></div></nav>",
                footerNoId()));

        List<MockupAuditor.AuditIssue> issues = MockupAuditor.audit(tmp,
                plans("home", "features"), Set.of(), Set.of("home"), true,
                designDir.resolve("home.html"));

        assertTrue(issues.stream().anyMatch(i -> "A4".equals(i.code())),
                "子页 nav 与首页不同构应报 A4");
        // 错误信息指明基准是导入首页
        MockupAuditor.AuditIssue a4 = issues.stream()
                .filter(i -> "A4".equals(i.code())).findFirst().orElseThrow();
        assertTrue(a4.message().contains("导入首页"), "错误信息应注明基准为导入首页: " + a4.message());
    }

    @Test
    @DisplayName("PagePlan 四参构造兼容（测试自用）")
    void plansRecordShape() {
        assertEquals(2, plans("home", "features").size());
    }
}
