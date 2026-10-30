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
 * 响应解析器的补丁协议解析测试（B2）
 *
 * @author wjun_java@163.com
 */
class AiTemplateResponseParserPatchTest {

    private final AiTemplateResponseParser parser = new AiTemplateResponseParser();

    @Test
    @DisplayName("解析 patches 数组")
    void parsesPatches() {
        String raw = """
                {
                  "reply": "已把标题改成蓝色",
                  "patches": [
                    {"path": "index.html", "search": "color: red;", "replace": "color: blue;"}
                  ]
                }
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getReply()).isEqualTo("已把标题改成蓝色");
        assertThat(result.getPatches()).hasSize(1);
        AiTemplatePatchDto patch = result.getPatches().get(0);
        assertThat(patch.getPath()).isEqualTo("index.html");
        assertThat(patch.getSearch()).isEqualTo("color: red;");
        assertThat(patch.getReplace()).isEqualTo("color: blue;");
    }

    @Test
    @DisplayName("兼容 filePatches 键名（组件管线同名字段）")
    void parsesFilePatchesAlias() {
        String raw = """
                {"reply": "改好了", "filePatches": [
                  {"path": "_components/tw__hero__centered.ftl", "search": "旧", "replace": "新"}
                ]}
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches()).hasSize(1);
        assertThat(result.getPatches().get(0).getPath()).isEqualTo("_components/tw__hero__centered.ftl");
    }

    @Test
    @DisplayName("patch 的 path 去掉前导 ./ 与 /")
    void normalizesPatchPath() {
        String raw = """
                {"patches": [{"path": "./static/css/base.css", "search": "a", "replace": "b"},
                             {"path": "/index.html", "search": "a", "replace": "b"}]}
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches().get(0).getPath()).isEqualTo("static/css/base.css");
        assertThat(result.getPatches().get(1).getPath()).isEqualTo("index.html");
    }

    @Test
    @DisplayName("缺 search / 缺 path 的补丁被丢弃，其余保留")
    void skipsInvalidPatches() {
        String raw = """
                {"patches": [
                  {"path": "", "search": "a", "replace": "b"},
                  {"path": "index.html", "search": "", "replace": "b"},
                  {"path": "ok.html", "search": "a", "replace": "b"}
                ]}
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches()).hasSize(1);
        assertThat(result.getPatches().get(0).getPath()).isEqualTo("ok.html");
    }

    @Test
    @DisplayName("replace 缺省 → 视为空串（删除片段），不变成 null 字面量")
    void nullReplaceBecomesEmpty() {
        String raw = """
                {"patches": [{"path": "index.html", "search": "a"}]}
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches().get(0).getReplace()).isEmpty();
    }

    @Test
    @DisplayName("patches 与 files 同时存在 → 两者都解析出来（由服务层决定优先级）")
    void parsesBothPatchesAndFiles() {
        String raw = """
                {
                  "reply": "改好了",
                  "patches": [{"path": "a.html", "search": "x", "replace": "y"}],
                  "files": [{"path": "b.html", "content": "zzz", "action": "modify"}]
                }
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches()).hasSize(1);
        assertThat(result.getFiles()).hasSize(1);
        assertThat(result.getFiles().get(0).getPath()).isEqualTo("b.html");
    }

    @Test
    @DisplayName("无 patches 字段 → 空列表（不影响 files 老协议）")
    void noPatchesYieldsEmptyList() {
        String raw = """
                {"reply": "生成完成", "files": [{"path": "index.html", "content": "x", "action": "create"}]}
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches()).isEmpty();
        assertThat(result.getFiles()).hasSize(1);
    }

    @Test
    @DisplayName("markdown 围栏包裹的补丁 JSON 也能解析")
    void parsesPatchesInMarkdownFence() {
        String raw = """
                ```json
                {"reply": "改好了", "patches": [{"path": "index.html", "search": "a", "replace": "b"}]}
                ```
                """;

        AiTemplateResponseParser.ParseResult result = parser.parseResponse(raw);

        assertThat(result.getPatches()).hasSize(1);
        assertThat(result.getPatches().get(0).getSearch()).isEqualTo("a");
    }
}
