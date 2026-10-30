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
package com.fastcms.ai.support;

import com.fastcms.ai.template.AiTemplatePatchDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件补丁应用器测试（B2 Patch 形态协议）
 *
 * @author wjun_java@163.com
 */
class FilePatchApplierTest {

    private final FilePatchApplier applier = new FilePatchApplier();

    @Test
    @DisplayName("唯一匹配的 search 被替换为 replace")
    void replacesUniqueMatch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<h1>旧标题</h1>\n<p>正文</p>", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("index.html", "<h1>旧标题</h1>", "<h1>新标题</h1>")));

        assertThat(result.fileContents()).containsKey("index.html");
        assertThat(result.fileContents().get("index.html"))
                .isEqualTo("<h1>新标题</h1>\n<p>正文</p>");
        assertThat(result.failures()).isEmpty();
    }

    @Test
    @DisplayName("search 未找到 → 记失败，不产出文件内容")
    void reportsMissingSearch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<h1>标题</h1>", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("index.html", "<h1>不存在</h1>", "<h1>新</h1>")));

        assertThat(result.fileContents()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0)).contains("未找到");
    }

    @Test
    @DisplayName("search 多处匹配 → 判歧义拒绝应用（宁失败不误改）")
    void rejectsAmbiguousSearch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.css"), ".btn { color: red; }\n.x { color: red; }", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("a.css", "color: red;", "color: blue;")));

        assertThat(result.fileContents()).isEmpty();
        assertThat(result.failures().get(0)).contains("匹配多处");
    }

    @Test
    @DisplayName("replace 为空串 → 删除该片段")
    void emptyReplaceDeletesFragment(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.css"), "body{}\n.old{}\n", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("a.css", ".old{}", "")));

        assertThat(result.fileContents().get("a.css")).isEqualTo("body{}\n\n");
    }

    @Test
    @DisplayName("同文件多补丁串行生效（后一个基于前一个结果）")
    void appliesPatchesInOrder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.html"), "AAA BBB CCC", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("a.html", "AAA", "XXX"),
                new AiTemplatePatchDto("a.html", "XXX BBB", "YYY")));

        assertThat(result.fileContents().get("a.html")).isEqualTo("YYY CCC");
    }

    @Test
    @DisplayName("路径越界 → 拒绝")
    void rejectsPathTraversal(@TempDir Path dir) throws Exception {
        // 扩展名合法（html）但路径逃出工作目录 → 由穿越防护拦截
        Files.writeString(dir.getParent().resolve("evil.html"), "x", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("../evil.html", "x", "y")));

        assertThat(result.fileContents()).isEmpty();
        assertThat(result.failures().get(0)).contains("路径越界");
    }

    @Test
    @DisplayName("路径越界且扩展名不合法 → 扩展名白名单先拦截")
    void rejectsTraversalWithBadExtension(@TempDir Path dir) {
        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("../evil.exe", "x", "y")));

        assertThat(result.fileContents()).isEmpty();
        assertThat(result.failures().get(0)).contains("路径非法");
    }

    @Test
    @DisplayName("非白名单扩展名 → 拒绝")
    void rejectsNonTextExtension(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("logo.png"), new byte[]{1, 2, 3});

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("logo.png", "x", "y")));

        assertThat(result.fileContents()).isEmpty();
        assertThat(result.failures().get(0)).contains("路径非法");
    }

    @Test
    @DisplayName("文件不存在 → 拒绝（补丁只改已有文件）")
    void rejectsMissingFile(@TempDir Path dir) {
        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("index.html", "x", "y")));

        assertThat(result.fileContents()).isEmpty();
        assertThat(result.failures().get(0)).contains("文件不存在");
    }

    @Test
    @DisplayName("空补丁列表 → 空结果，无异常")
    void emptyPatches(@TempDir Path dir) {
        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of());

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.hasFailure()).isFalse();
    }

    @Test
    @DisplayName("多文件多补丁：部分失败不影响其他文件成功")
    void partialFailureKeepsSuccess(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.html"), "AAA", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.html"), "BBB", StandardCharsets.UTF_8);

        FilePatchApplier.ApplyResult result = applier.apply(dir, List.of(
                new AiTemplatePatchDto("a.html", "AAA", "XXX"),
                new AiTemplatePatchDto("b.html", "ZZZ", "YYY")));

        assertThat(result.fileContents()).containsOnlyKeys("a.html");
        assertThat(result.failures()).hasSize(1);
    }
}
