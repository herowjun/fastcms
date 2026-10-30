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
package com.fastcms.ai.template.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点结构提取器测试（B3 上下文分层 · S1）
 *
 * @author wjun_java@163.com
 */
class SiteStructureExtractorTest {

    private static final String SAMPLE_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>云智科技 - 智能制造平台</title>
              <meta name="description" content="云智科技为制造业提供智能工厂解决方案">
              <link rel="stylesheet" href="/static/css/main.css">
              <style>.hero{color:red}</style>
              <script>console.log("tracking");</script>
            </head>
            <body>
              <nav class="navbar">
                <a href="/features">核心能力</a>
                <a href="/solutions">解决方案</a>
                <a href="/news">新闻动态</a>
                <a href="/about">关于我们</a>
                <a href="https://external.com/x">外部链接</a>
              </nav>
              <h1>让制造更智能</h1>
              <h2>核心能力</h2>
              <p>我们为企业提供端到端的智能制造解决方案，覆盖生产、质检与运维全流程。</p>
              <img src="/hero.png" alt="产品主图">
              <script>var app = 1;</script>
            </body>
            </html>
            """;

    @Test
    @DisplayName("抽出标题与描述")
    void extractsMeta() {
        String summary = SiteStructureExtractor.extract(SAMPLE_HTML);

        assertThat(summary).contains("【文档元信息】");
        assertThat(summary).contains("云智科技 - 智能制造平台");
        assertThat(summary).contains("智能工厂解决方案");
    }

    @Test
    @DisplayName("抽出导航菜单项，且排除外链")
    void extractsNavItems() {
        String summary = SiteStructureExtractor.extract(SAMPLE_HTML);

        assertThat(summary).contains("【导航菜单项】");
        assertThat(summary).contains("核心能力");
        assertThat(summary).contains("解决方案");
        assertThat(summary).contains("新闻动态");
        assertThat(summary).contains("关于我们");
        // 外链不是本站栏目，不能进导航菜单项（否则模型会凭空造一个"外部链接"栏目）
        String navSection = summary.substring(summary.indexOf("【导航菜单项】"), summary.indexOf("【页面标题层级】"));
        assertThat(navSection).doesNotContain("外部链接");
    }

    @Test
    @DisplayName("抽出标题层级并保留 h1/h2 缩进")
    void extractsHeadings() {
        String summary = SiteStructureExtractor.extract(SAMPLE_HTML);

        assertThat(summary).contains("h1: 让制造更智能");
        assertThat(summary).contains("h2: 核心能力");
    }

    @Test
    @DisplayName("脚本与样式不进摘要")
    void dropsScriptAndStyle() {
        String summary = SiteStructureExtractor.extract(SAMPLE_HTML);

        assertThat(summary).doesNotContain("console.log");
        assertThat(summary).doesNotContain("tracking");
        assertThat(summary).doesNotContain(".hero{color:red}");
    }

    @Test
    @DisplayName("站内链接清单含路径与锚文本")
    void extractsLinkList() {
        String summary = SiteStructureExtractor.extract(SAMPLE_HTML);

        assertThat(summary).contains("【站内链接清单】");
        assertThat(summary).contains("/features → 核心能力");
    }

    @Test
    @DisplayName("摘要显著短于原文（上下文大幅削减）")
    void summaryMuchShorterThanSource() {
        String html = SAMPLE_HTML + "<p>" + "填充正文内容。".repeat(2000) + "</p>";
        String summary = SiteStructureExtractor.extract(html);

        assertThat(summary.length()).isLessThan(html.length() / 4);
        assertThat(summary.length()).isGreaterThan(SiteStructureExtractor.MIN_USEFUL_LENGTH);
    }

    @Test
    @DisplayName("空输入返回空串")
    void emptyInput() {
        assertThat(SiteStructureExtractor.extract(null)).isEmpty();
        assertThat(SiteStructureExtractor.extract("   ")).isEmpty();
    }

    @Test
    @DisplayName("无导航的极简页面仍能抽出正文节选")
    void minimalPageStillExtracts() {
        String html = "<html><body><h1>简单页面</h1><p>这是一段用于说明站点定位的介绍文字，"
                + "描述了公司主营业务与核心产品方向，帮助模型归纳站点调性。</p></body></html>";

        String summary = SiteStructureExtractor.extract(html);

        assertThat(summary).contains("简单页面");
        assertThat(summary).contains("正文文字节选");
    }

    @Test
    @DisplayName("畸形 HTML 不抛异常")
    void malformedHtmlDoesNotThrow() {
        String html = "<html><nav><a href=/a>未闭合引号</a><nav><div class=menu";

        String summary = SiteStructureExtractor.extract(html);

        assertThat(summary).isNotNull();
    }
}
