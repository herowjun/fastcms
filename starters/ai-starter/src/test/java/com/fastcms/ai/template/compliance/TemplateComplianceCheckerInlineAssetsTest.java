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
package com.fastcms.ai.template.compliance;

import com.fastcms.ai.component.PageSpecRenderer;
import com.fastcms.ai.template.compliance.TemplateComplianceChecker.ComplianceIssue;
import com.fastcms.ai.template.compliance.TemplateComplianceChecker.Link;
import com.fastcms.ai.template.compliance.TemplateComplianceChecker.Report;
import com.fastcms.ai.template.compliance.TemplateComplianceChecker.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合规校验器「内联 CSS/JS 分离」检查项测试（2026-10-29）
 *
 * <p>校验检查项 10：HTML 不得内联大段 CSS/JS。
 * 可抽取的（不含 FreeMarker 指令）→ 确定性抽取修复；含 FTL 的 → 只报 ERROR。</p>
 *
 * @author wjun_java@163.com
 */
class TemplateComplianceCheckerInlineAssetsTest {

    /**
     * 校验器只用到 PageSpecRenderer 的「正文骨架」能力（补基础页时），
     * 本测试的场景里基础页齐全 → 传空依赖即可，避免拉起整个组件容器
     */
    private final TemplateComplianceChecker checker = new TemplateComplianceChecker(new PageSpecRenderer(null, null));

    private static String bigCss() {
        return IntStream.range(0, 200)
                .mapToObj(i -> ".cls-" + i + " { color: #123456; margin: 0 auto; }")
                .collect(Collectors.joining("\n"));
    }

    private static String bigJs() {
        return IntStream.range(0, 200)
                .mapToObj(i -> "function fn" + i + "() { return " + i + "; }")
                .collect(Collectors.joining("\n"));
    }

    /**
     * 造一个「除内联资源外基本合规」的模板目录
     */
    private void prepareTemplate(Path workDir, String indexHtml) throws IOException {
        Files.createDirectories(workDir.resolve("static/css"));
        write(workDir.resolve("index.html"), indexHtml);
        for (String page : List.of("article.html", "article_list.html", "page.html")) {
            write(workDir.resolve(page), "<#-- page -->\n<html><body>${x}</body></html>\n");
        }
        write(workDir.resolve("_layout.html"), "<#macro page><html><body><#nested></body></html></#macro>\n");
        write(workDir.resolve("_template.properties"), """
                template.id=t1
                template.name=t1
                template.path=/t1/
                template.version=0.0.1
                template.i18n=t1
                template.provider=ai
                template.description=desc
                """);
        write(workDir.resolve("_preview_data.json"), """
                {"menus":[{"name":"首页","type":"index","children":[]}]}
                """);
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static List<ComplianceIssue> inlineIssues(Report report) {
        return report.issues().stream().filter(i -> "inline-assets".equals(i.code())).toList();
    }

    // ==================== readOnly=false：确定性抽取 ====================

    @Test
    @DisplayName("readOnly=false：自动抽取内联 CSS/JS 并改写 HTML")
    void extractsInlineAssetsWhenNotReadOnly() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline");
        prepareTemplate(workDir, "<html><head><style>" + bigCss() + "</style></head>"
                + "<body><script>" + bigJs() + "</script></body></html>");

        Report report = checker.check(workDir, Link.BATCH_HTML, false, null);

        // 静态资源文件已生成
        assertThat(workDir.resolve("static/css/inline-index.css")).exists();
        assertThat(workDir.resolve("static/js/inline-index.js")).exists();
        assertThat(Files.readString(workDir.resolve("static/css/inline-index.css"))).contains(".cls-199");
        assertThat(Files.readString(workDir.resolve("static/js/inline-index.js"))).contains("function fn199");

        // HTML 已就地改写为引用
        String html = Files.readString(workDir.resolve("index.html"));
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"${ctx()}/css/inline-index.css\">");
        assertThat(html).contains("<script src=\"${ctx()}/js/inline-index.js\"></script>");
        assertThat(html).doesNotContain("<style>");

        // 报告：已自动修复、无剩余 inline-assets 问题
        assertThat(inlineIssues(report)).isEmpty();
        assertThat(report.fixed()).contains("static/css/inline-index.css", "static/js/inline-index.js", "index.html");
    }

    @Test
    @DisplayName("readOnly=false：静态资源目录不存在时自动创建")
    void createsStaticDirsWhenMissing() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-nodir");
        // 只放 index.html（连 static/css 都不建）
        write(workDir.resolve("index.html"), "<html><head><style>" + bigCss() + "</style></head><body></body></html>");

        checker.check(workDir, Link.BATCH_HTML, false, null);

        assertThat(workDir.resolve("static/css/inline-index.css")).exists();
    }

    // ==================== readOnly=true：只播报不改内容 ====================

    @Test
    @DisplayName("readOnly=true：只提示（WARNING），不改正式模板内容、不触发 AI 重写")
    void onlyReportsWhenReadOnly() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-ro");
        String original = "<html><head><style>" + bigCss() + "</style></head>"
                + "<body><script>" + bigJs() + "</script></body></html>";
        prepareTemplate(workDir, original);

        Report report = checker.check(workDir, Link.BATCH_HTML, true, null);

        // 内容未变
        assertThat(Files.readString(workDir.resolve("index.html"))).isEqualTo(original);
        assertThat(workDir.resolve("static/css/inline-index.css")).doesNotExist();
        assertThat(workDir.resolve("static/js/inline-index.js")).doesNotExist();

        // 报了两条 WARNING（style + script）——人工写少量 CSS/JS 是合法写法，不能判 ERROR
        List<ComplianceIssue> issues = inlineIssues(report);
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(i -> i.severity() == Severity.WARNING);
        assertThat(issues).allMatch(i -> i.file().equals("index.html"));
        assertThat(issues).allMatch(i -> i.detail().contains("建议拆到 static/"));
        // 关键：inline-assets 不得有 ERROR —— ERROR 会触发 AI 重写，把人工写的代码抹掉
        assertThat(issues).noneMatch(i -> i.severity() == Severity.ERROR);
    }

    // ==================== FTL 保护 ====================

    @Test
    @DisplayName("含 FreeMarker 指令的内联块：只报 ERROR，不抽取")
    void reportsButDoesNotExtractFtlBlocks() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-ftl");
        String html = "<html><head><style>" + bigCss()
                + "\n.title{content:\"${seoTag('website_title')}\"}</style></head><body></body></html>";
        prepareTemplate(workDir, html);

        Report report = checker.check(workDir, Link.BATCH_HTML, false, null);

        assertThat(workDir.resolve("static/css/inline-index.css")).doesNotExist();
        // 内容保持原样（含 FTL 指令，不会被抽走失效）
        assertThat(Files.readString(workDir.resolve("index.html"))).isEqualTo(html);

        List<ComplianceIssue> issues = inlineIssues(report);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(issues.get(0).fixable()).isFalse();
        assertThat(issues.get(0).detail()).contains("FreeMarker");
    }

    @Test
    @DisplayName("同页内可抽取块与 FTL 块共存：抽前者，FTL 块报 ERROR")
    void extractsExtractableAndReportsFtl() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-mixed");
        String html = "<html><head><style>" + bigCss() + "</style></head>"
                + "<body><script>" + bigJs() + "\n<#if x??>f();</#if>" + "</script></body></html>";
        prepareTemplate(workDir, html);

        Report report = checker.check(workDir, Link.BATCH_HTML, false, null);

        assertThat(workDir.resolve("static/css/inline-index.css")).exists();
        assertThat(workDir.resolve("static/js/inline-index.js")).doesNotExist();

        List<ComplianceIssue> issues = inlineIssues(report);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).detail()).contains("script");
    }

    // ==================== 无违规 ====================

    @Test
    @DisplayName("已分文件的模板：无 inline-assets 问题")
    void noIssueWhenAssetsSeparated() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-clean");
        prepareTemplate(workDir, "<html><head>"
                + "<link rel=\"stylesheet\" href=\"${ctx()}/css/base.css\">"
                + "</head><body><script src=\"${ctx()}/js/main.js\"></script></body></html>");

        Report report = checker.check(workDir, Link.BATCH_HTML, false, null);

        assertThat(inlineIssues(report)).isEmpty();
    }

    @Test
    @DisplayName("人工写的少量内联（阈值内）：完全放行，连提示都不给")
    void allowsSmallManualInline() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-small");
        // 人工快速调整模板时的典型写法：几行关键样式 + 一小段初始化脚本
        String html = "<html><head><style>\n"
                + ".hero { padding: 40px 0; }\n"
                + ".btn-primary { background: #185FA5; color: #fff; }\n"
                + "</style></head><body>\n"
                + "<script>\n"
                + "document.addEventListener('DOMContentLoaded', function () {\n"
                + "  document.getElementById('nav-toggle')?.classList.add('ready');\n"
                + "});\n"
                + "</script></body></html>";
        prepareTemplate(workDir, html);

        // readOnly 与生成型两种模式都不该报
        Report roReport = checker.check(workDir, Link.BATCH_HTML, true, null);
        assertThat(inlineIssues(roReport)).isEmpty();

        Report genReport = checker.check(workDir, Link.BATCH_HTML, false, null);
        assertThat(inlineIssues(genReport)).isEmpty();
        // 内容原样保留，不被抽取
        assertThat(Files.readString(workDir.resolve("index.html"))).isEqualTo(html);
        assertThat(workDir.resolve("static/css/inline-index.css")).doesNotExist();
    }

    // ==================== design/ 中间态目录必须排除 ====================

    @Test
    @DisplayName("design/ 中间态目录被排除：设计稿的内联 <style> 不被抽走")
    void excludesDesignIntermediateDir() throws IOException {
        Path workDir = Files.createTempDirectory("compliance-inline-design");
        prepareTemplate(workDir, "<html><head>"
                + "<link rel=\"stylesheet\" href=\"${ctx()}/css/base.css\">"
                + "</head><body></body></html>");
        // 设计稿：本身就是「HTML + 大段内联 <style>」形态（V1~V6 校验与转换器依赖它）
        Path designDir = workDir.resolve("design");
        Files.createDirectories(designDir);
        String mockup = "<!DOCTYPE html>\n<html><head><style>\n" + bigCss()
                + "\n</style></head><body>设计稿</body></html>";
        write(designDir.resolve("index.html"), mockup);

        Report report = checker.check(workDir, Link.BATCH_HTML, false, null);

        // 设计稿内容未被改动，也没有被抽出静态资源
        assertThat(Files.readString(designDir.resolve("index.html"))).isEqualTo(mockup);
        assertThat(workDir.resolve("static/css/inline-index.css")).doesNotExist();
        assertThat(inlineIssues(report)).isEmpty();
    }
}
