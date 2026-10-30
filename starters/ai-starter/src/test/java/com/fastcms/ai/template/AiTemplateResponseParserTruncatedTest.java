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
package com.fastcms.ai.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应解析器的「截断抢救」测试（2026-10-29）
 *
 * <p><b>背景</b>：模型输出撞 maxTokens 上限时，JSON 尾部被硬切，整体解析必然失败。
 * 但残缺之前的元素是完整合法的 JSON —— 若连同已完成的元素一起丢弃，
 * 意味着几分钟算力全部白费（实测深度焕新轮：8192 上限下输出 19212 token 被截断，
 * 整轮产出归零）。</p>
 *
 * @author wjun_java@163.com
 */
class AiTemplateResponseParserTruncatedTest {

    private final AiTemplateResponseParser parser = new AiTemplateResponseParser();

    @Test
    @DisplayName("截断时救回已完整写出的 files 元素")
    void salvagesCompleteFileElements() {
        // 模拟：第一个文件完整，第二个文件在 content 中途被截断
        String raw = """
                {"reply":"改造中","files":[
                {"path":"a.html","content":"<html>A</html>","action":"modify"},
                {"path":"b.html","content":"<html>B未完
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.isTruncated()).isTrue();
        assertThat(r.getFiles()).hasSize(1);
        assertThat(r.getFiles().get(0).getPath()).isEqualTo("a.html");
        assertThat(r.getFiles().get(0).getContent()).isEqualTo("<html>A</html>");
    }

    @Test
    @DisplayName("截断时救回多个已完整写出的 files 元素")
    void salvagesMultipleCompleteFileElements() {
        String raw = """
                {"files":[
                {"path":"a.html","content":"AAA"},
                {"path":"b.html","content":"BBB"},
                {"path":"c.html","content":"CCC截断
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.isTruncated()).isTrue();
        assertThat(r.getFiles()).hasSize(2);
        assertThat(r.getFiles()).extracting(AiTemplateFileDto::getPath)
                .containsExactly("a.html", "b.html");
    }

    @Test
    @DisplayName("截断时救回已完整写出的 patches 元素")
    void salvagesCompletePatchElements() {
        String raw = """
                {"patches":[
                {"path":"index.html","search":"<h1>旧</h1>","replace":"<h1>新</h1>"},
                {"path":"index.html","search":"未闭合
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.isTruncated()).isTrue();
        assertThat(r.getPatches()).hasSize(1);
        assertThat(r.getPatches().get(0).getSearch()).isEqualTo("<h1>旧</h1>");
        assertThat(r.getPatches().get(0).getReplace()).isEqualTo("<h1>新</h1>");
    }

    @Test
    @DisplayName("截断时救回 unchanged 字符串数组元素")
    void salvagesUnchangedStringElements() {
        String raw = """
                {"unchanged":["_layout.html","plugins.html","截断中
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.isTruncated()).isTrue();
        assertThat(r.getUnchanged()).containsExactly("_layout.html", "plugins.html");
    }

    @Test
    @DisplayName("content 里的花括号与引号不干扰元素边界识别")
    void handlesBracesAndQuotesInsideContent() {
        // content 含 JSON 敏感字符：花括号、转义引号
        String raw = """
                {"files":[
                {"path":"a.html","content":"<div class=\\"x\\">{a:1}</div>","action":"modify"},
                {"path":"b.html","content":"截断
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.isTruncated()).isTrue();
        assertThat(r.getFiles()).hasSize(1);
        assertThat(r.getFiles().get(0).getContent()).contains("</div>");
    }

    @Test
    @DisplayName("JSON 完整时不误报截断")
    void doesNotFlagTruncatedForCompleteJson() {
        String raw = """
                {"reply":"ok","files":[{"path":"a.html","content":"AAA"}]}
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.isTruncated()).isFalse();
        assertThat(r.getFiles()).hasSize(1);
    }

    @Test
    @DisplayName("截断且一个完整元素都没有时不算抢救成功")
    void reportsNoContentWhenNothingComplete() {
        String raw = """
                {"reply":"改造中","files":[{"path":"a.html","content":"截断
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.getFiles()).isEmpty();
        assertThat(r.getPatches()).isEmpty();
        assertThat(r.getUnchanged()).isEmpty();
        assertThat(r.hasSalvagedContent()).isFalse();
    }

    @Test
    @DisplayName("截断时 path 前缀 ./ 与反斜杠被归一化")
    void normalizesPathsInSalvagedElements() {
        // JSON 文本里的反斜杠需转义为 \\ （Java 文本块中再转义一层）
        String raw = """
                {"files":[
                {"path":"./static\\\\css\\\\base.css","content":"AAA"},
                {"path":"b.html","content":"截断
                """;
        AiTemplateResponseParser.ParseResult r = parser.parseResponse(raw);

        assertThat(r.getFiles()).hasSize(1);
        assertThat(r.getFiles().get(0).getPath()).isEqualTo("static/css/base.css");
    }
}
