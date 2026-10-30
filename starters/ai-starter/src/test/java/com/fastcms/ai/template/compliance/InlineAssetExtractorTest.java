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

import com.fastcms.ai.template.compliance.InlineAssetExtractor.Extraction;
import com.fastcms.ai.template.compliance.InlineAssetExtractor.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内联资源分离器测试（2026-10-29）
 *
 * <p><b>背景</b>：fastcms 模板规范要求 HTML / CSS / JS 分文件，HTML 只能通过
 * {@code <link rel="stylesheet">} 与 {@code <script src>} 引入样式与脚本。
 * AI 经常违反 → 内联资源撑大 HTML → 每轮对话都要塞整页进提示词，且与补丁协议冲突。
 * 本测试锁定 {@link InlineAssetExtractor} 的行为契约。</p>
 *
 * @author wjun_java@163.com
 */
class InlineAssetExtractorTest {

    /** 构造一个「足够大」的 CSS 内容（超过字符阈值） */
    private static String bigCss() {
        return IntStream.range(0, 200)
                .mapToObj(i -> ".cls-" + i + " { color: #123456; margin: 0 auto; }")
                .collect(Collectors.joining("\n"));
    }

    /** 构造一个「足够大」的 JS 内容（超过字符阈值） */
    private static String bigJs() {
        return IntStream.range(0, 200)
                .mapToObj(i -> "function fn" + i + "() { return " + i + "; }")
                .collect(Collectors.joining("\n"));
    }

    // ==================== 阈值边界 ====================

    @Test
    @DisplayName("恰好 2000 字符的内联 style 在阈值内放行")
    void keepsStyleAtCharThreshold() {
        String css = "a".repeat(InlineAssetExtractor.INLINE_MAX_CHARS);
        String html = "<style>" + css + "</style>";

        assertThat(InlineAssetExtractor.detect(html, "index.html")).isEmpty();
        assertThat(InlineAssetExtractor.extract(html, "index.html").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("超过 2000 字符的内联 style 判定为违规")
    void flagsStyleOverCharThreshold() {
        String css = "a".repeat(InlineAssetExtractor.INLINE_MAX_CHARS + 1);
        String html = "<style>" + css + "</style>";

        List<Violation> violations = InlineAssetExtractor.detect(html, "index.html");

        assertThat(violations).hasSize(1);
        Violation v = violations.get(0);
        assertThat(v.type()).isEqualTo("style");
        assertThat(v.file()).isEqualTo("index.html");
        assertThat(v.chars()).isEqualTo(InlineAssetExtractor.INLINE_MAX_CHARS + 1);
        assertThat(v.extractable()).isTrue();
        assertThat(v.reason()).isNull();
    }

    @Test
    @DisplayName("恰好 60 行的内联 style 在阈值内放行")
    void keepsStyleAtLineThreshold() {
        String css = String.join("\n", IntStream.range(0, 60).mapToObj(i -> "a{}").toList());
        String html = "<style>" + css + "</style>";

        assertThat(InlineAssetExtractor.detect(html, "index.html")).isEmpty();
    }

    @Test
    @DisplayName("超过 60 行的内联 style 判定为违规（字符数未超阈值）")
    void flagsStyleOverLineThreshold() {
        String css = String.join("\n", IntStream.range(0, 61).mapToObj(i -> "a{}").toList());
        String html = "<style>" + css + "</style>";

        List<Violation> violations = InlineAssetExtractor.detect(html, "index.html");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).chars()).isLessThan(InlineAssetExtractor.INLINE_MAX_CHARS);
        assertThat(violations.get(0).lines()).isEqualTo(61);
    }

    @Test
    @DisplayName("存量模板同款内联块（1290 字符 / 44 行）不误判为违规")
    void doesNotFalsePositiveOnExistingTemplateBlock() {
        // 实测 xjd2022/article_pics.html 的内联 Swiper 集成块：1290 字符 / 44 行
        // 字符数未过 2000、行数未过 60 → 必须在阈值内放行，否则调整型会话会持续播报假 ERROR
        String js = String.join("\n", IntStream.range(0, 44)
                .mapToObj(i -> "  var opt" + i + " = { loop: true };").toList());
        String html = "<script>" + js + "</script>";

        assertThat(js.length()).isBetween(1200, 1400);
        assertThat(InlineAssetExtractor.detect(html, "index.html")).isEmpty();
    }

    @Test
    @DisplayName("空 style / 空 script 不算违规")
    void ignoresEmptyBlocks() {
        String html = "<style></style><script>   \n  </script>";

        assertThat(InlineAssetExtractor.detect(html, "index.html")).isEmpty();
    }

    // ==================== 外部引入不算违规 ====================

    @Test
    @DisplayName("带 src 的 script 是外部引入，不算违规")
    void ignoresExternalScript() {
        String html = "<script src=\"${ctx()}/js/main.js\"></script>";

        assertThat(InlineAssetExtractor.detect(html, "index.html")).isEmpty();
        assertThat(InlineAssetExtractor.extract(html, "index.html").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("带 src 的超大 script 仍是外部引入，不算违规")
    void ignoresExternalScriptEvenWhenHuge() {
        String html = "<script src=\"${ctx()}/js/main.js\">" + bigJs() + "</script>";

        assertThat(InlineAssetExtractor.detect(html, "index.html")).isEmpty();
    }

    // ==================== 抽取与就地替换 ====================

    @Test
    @DisplayName("抽取后 HTML 就地替换为 link / script src，位置不变")
    void rewritesInPlace() {
        String html = "<html><head>"
                + "<style>" + bigCss() + "</style>"
                + "</head><body>"
                + "<div>内容</div>"
                + "<script>" + bigJs() + "</script>"
                + "</body></html>";

        Extraction extraction = InlineAssetExtractor.extract(html, "index.html");

        assertThat(extraction.extracted())
                .containsExactly("static/css/inline-index.css", "static/js/inline-index.js");
        assertThat(extraction.assetFiles()).containsKeys(
                "static/css/inline-index.css", "static/js/inline-index.js");
        // 引用就地在原位置替换
        assertThat(extraction.rewrittenHtml())
                .contains("<link rel=\"stylesheet\" href=\"${ctx()}/css/inline-index.css\">")
                .contains("<script src=\"${ctx()}/js/inline-index.js\"></script>")
                .doesNotContain("<style>")
                .doesNotContain("<script>" + bigJs());
        // 位置校验：link 仍在 head 内、script 仍在 body 末尾
        String rewritten = extraction.rewrittenHtml();
        assertThat(rewritten.indexOf("<link")).isLessThan(rewritten.indexOf("</head>"));
        assertThat(rewritten.indexOf("<script src")).isGreaterThan(rewritten.indexOf("</head>"));
        assertThat(rewritten.indexOf("<script src")).isLessThan(rewritten.indexOf("</body>"));
        // 抽取出的文件内容保留原始 CSS/JS 正文
        assertThat(extraction.assetFiles().get("static/css/inline-index.css")).contains(".cls-199");
        assertThat(extraction.assetFiles().get("static/js/inline-index.js")).contains("function fn199");
    }

    @Test
    @DisplayName("多个 CSS 块拆成多个文件，不合并")
    void splitsMultipleStyleBlocks() {
        String html = "<style>" + bigCss() + "</style>"
                + "<style>" + bigCss() + "</style>";

        Extraction extraction = InlineAssetExtractor.extract(html, "index.html");

        assertThat(extraction.extracted()).containsExactly(
                "static/css/inline-index.css", "static/css/inline-index-2.css");
        assertThat(extraction.rewrittenHtml()).contains(
                        "<link rel=\"stylesheet\" href=\"${ctx()}/css/inline-index.css\">")
                .contains("<link rel=\"stylesheet\" href=\"${ctx()}/css/inline-index-2.css\">");
    }

    @Test
    @DisplayName("多个 JS 块拆成多个文件，绝不合并（合并会改变执行时机）")
    void splitsMultipleScriptBlocks() {
        String html = "<script>" + bigJs() + "</script>"
                + "<script>" + bigJs() + "</script>";

        Extraction extraction = InlineAssetExtractor.extract(html, "index.html");

        assertThat(extraction.extracted()).containsExactly(
                "static/js/inline-index.js", "static/js/inline-index-2.js");
        assertThat(extraction.rewrittenHtml()).contains(
                        "<script src=\"${ctx()}/js/inline-index.js\"></script>")
                .contains("<script src=\"${ctx()}/js/inline-index-2.js\"></script>");
    }

    @Test
    @DisplayName("阈值内的小内联块原样保留，不被抽取")
    void keepsSmallInlineBlocks() {
        String html = "<style>.a{color:red}</style><script>console.log(1)</script>";

        Extraction extraction = InlineAssetExtractor.extract(html, "index.html");

        assertThat(extraction.isEmpty()).isTrue();
        assertThat(extraction.rewrittenHtml()).isEqualTo(html);
    }

    // ==================== FTL 保护 ====================

    @Test
    @DisplayName("含 ${} 的内联块标记为不可抽取")
    void flagsFtlDollarBlock() {
        String css = bigCss() + "\n.title{content:\"${seoTag('website_title')}\"}";
        String html = "<style>" + css + "</style>";

        List<Violation> violations = InlineAssetExtractor.detect(html, "index.html");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).hasFtl()).isTrue();
        assertThat(violations.get(0).extractable()).isFalse();
        assertThat(violations.get(0).reason()).contains("FreeMarker");
    }

    @Test
    @DisplayName("含 <#...> 的内联块标记为不可抽取")
    void flagsFtlDirectiveBlock() {
        String js = bigJs() + "\n<#if x??>console.log(x);</#if>";
        String html = "<script>" + js + "</script>";

        List<Violation> violations = InlineAssetExtractor.detect(html, "index.html");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).hasFtl()).isTrue();
        assertThat(violations.get(0).extractable()).isFalse();
    }

    @Test
    @DisplayName("含 FTL 的块不抽取，HTML 原样返回")
    void doesNotExtractFtlBlocks() {
        String css = bigCss() + "\n.title{content:\"${x}\"}";
        String html = "<style>" + css + "</style>";

        Extraction extraction = InlineAssetExtractor.extract(html, "index.html");

        assertThat(extraction.isEmpty()).isTrue();
        assertThat(extraction.rewrittenHtml()).isEqualTo(html);
    }

    @Test
    @DisplayName("同页中可抽取块与 FTL 块共存：只抽可抽取的，FTL 块保留")
    void extractsExtractableButKeepsFtl() {
        String html = "<style>" + bigCss() + "</style>"
                + "<script>" + bigJs() + "\n<#if x??>f();</#if>" + "</script>";

        Extraction extraction = InlineAssetExtractor.extract(html, "index.html");

        assertThat(extraction.extracted()).containsExactly("static/css/inline-index.css");
        // CSS 被抽走（<style> 消失），含 FTL 的 JS 块原样保留
        assertThat(extraction.rewrittenHtml())
                .doesNotContain("<style>")
                .contains("<script>")
                .contains("<#if x??>f();</#if>");
    }

    // ==================== 文件名推导 ====================

    @Test
    @DisplayName("静态资源文件名由 HTML 文件名推导")
    void derivesAssetFileName() {
        String html = "<style>" + bigCss() + "</style>";

        assertThat(InlineAssetExtractor.extract(html, "index.html").extracted())
                .containsExactly("static/css/inline-index.css");
        assertThat(InlineAssetExtractor.extract(html, "sub/dir/About Us.html").extracted())
                .containsExactly("static/css/inline-about-us.css");
        assertThat(InlineAssetExtractor.extract(html, "index.html").assetFiles().keySet())
                .allMatch(p -> p.startsWith("static/css/"));
    }
}
