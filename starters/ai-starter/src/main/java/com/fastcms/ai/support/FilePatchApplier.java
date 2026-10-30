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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件补丁应用器（Patch 形态落地）
 *
 * <p>把 AI 输出的 {@link AiTemplatePatchDto} 变更片段在磁盘文件上做 search/replace，
 * 产出<b>修改后的完整文件内容</b>——下游（DB 持久化 / 备份 / SSE 事件 / 渲染校验）
 * 完全不用改，仍然走「整文件」那套成熟链路。</p>
 *
 * <p><b>安全与正确性约束</b>（与组件补丁 {@code applyComponentPatches} 同口径）：</p>
 * <ol>
 *     <li>路径白名单：只允许已知文本扩展名（html/ftl/css/js/json/properties/xml/txt/md），挡掉二进制；</li>
 *     <li>路径穿越防护：规范化后的绝对路径必须仍在工作目录内；</li>
 *     <li>文件必须已存在（补丁只能改已有文件，不能凭空造文件——造文件走 files 通道）；</li>
 *     <li>{@code search} 必须在文件中<b>唯一匹配</b>：多处匹配视为歧义，拒绝应用（宁可失败也不误改）。</li>
 * </ol>
 *
 * <p><b>同文件多补丁</b>：按给定顺序串行应用（后一个补丁的 search 基于前一个的结果）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class FilePatchApplier {

    private static final Logger log = LoggerFactory.getLogger(FilePatchApplier.class);

    /**
     * 允许被补丁修改的文本扩展名（小写，不含点）
     */
    private static final Set<String> PATCHABLE_EXTENSIONS = Set.of(
            "html", "ftl", "css", "js", "json", "properties", "xml", "txt", "md", "svg");

    /**
     * 单个文件允许的补丁数量上限（防御：模型跑偏时输出上百个补丁）
     */
    private static final int MAX_PATCHES_PER_FILE = 50;

    /**
     * 应用结果
     *
     * @param fileContents 成功应用补丁的文件 → 修改后的完整内容（保持补丁出现顺序）
     * @param failures     失败明细（可直接播报给用户）
     */
    public record ApplyResult(Map<String, String> fileContents, List<String> failures) {

        public boolean isEmpty() {
            return fileContents.isEmpty();
        }

        public boolean hasFailure() {
            return !failures.isEmpty();
        }
    }

    /**
     * 应用补丁
     *
     * @param workDir 工作目录（模板根目录）
     * @param patches 补丁列表（可空）
     * @return 应用结果（永不返回 null）
     */
    public ApplyResult apply(Path workDir, List<AiTemplatePatchDto> patches) {
        Map<String, String> fileContents = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        if (workDir == null || patches == null || patches.isEmpty()) {
            return new ApplyResult(fileContents, failures);
        }

        // 同文件补丁串行应用：fileContents 缓存中间结果，后续补丁基于已改内容继续替换
        Map<String, Integer> perFileCount = new LinkedHashMap<>();
        for (AiTemplatePatchDto patch : patches) {
            try {
                String path = patch.getPath() == null ? "" : patch.getPath().trim();
                if (path.isEmpty()) {
                    failures.add("补丁缺少 path");
                    continue;
                }
                if (!isPatchablePath(path)) {
                    failures.add(path + ": 路径非法（只允许修改 " + String.join("/", PATCHABLE_EXTENSIONS) + " 文本文件）");
                    continue;
                }
                Path target = workDir.resolve(path).normalize();
                if (!target.startsWith(workDir)) {
                    failures.add(path + ": 路径越界");
                    continue;
                }
                if (!Files.isRegularFile(target)) {
                    failures.add(path + ": 文件不存在（补丁只能修改已有文件，新建文件请用 files 通道）");
                    continue;
                }
                int count = perFileCount.merge(path, 1, Integer::sum);
                if (count > MAX_PATCHES_PER_FILE) {
                    failures.add(path + ": 单文件补丁数超过上限 " + MAX_PATCHES_PER_FILE + "，已跳过其余补丁");
                    break;
                }

                String content = fileContents.containsKey(path)
                        ? fileContents.get(path)
                        : Files.readString(target, StandardCharsets.UTF_8);
                String search = patch.getSearch();
                if (search == null || search.isEmpty()) {
                    failures.add(path + ": search 片段为空");
                    continue;
                }
                // 行尾符无关匹配：模型输出的 search 几乎总是 LF，而磁盘文件可能是 CRLF
                // （Windows 项目 / 网页导入的 HTML）。逐字匹配会 100% 失败 → 补丁被拒 →
                // 回退整文件回写 → 补丁协议收益归零。详见 PatchTextMatcher。
                PatchTextMatcher.Match match = PatchTextMatcher.find(content, search);
                if (PatchTextMatcher.AMBIGUOUS == match) {
                    failures.add(path + ": search 片段匹配多处（须唯一，请扩大片段范围）");
                    continue;
                }
                if (match.start() < 0) {
                    failures.add(path + ": search 片段在源码中未找到（需与文件内容逐字一致，行尾符差异已自动兼容）");
                    continue;
                }
                if (match.normalized()) {
                    log.debug("补丁匹配走了换行归一化口径: {}", path);
                }
                String replace = patch.getReplace() == null ? "" : patch.getReplace();
                // 替换内容的行尾风格对齐文件（模型给 LF、文件是 CRLF 时避免混合行尾）
                replace = PatchTextMatcher.alignNewlines(replace, content);
                String patched = content.substring(0, match.start()) + replace
                        + content.substring(match.start() + match.length());
                fileContents.put(path, patched);
            } catch (IOException e) {
                log.warn("文件补丁应用失败: {}", patch.getPath(), e);
                failures.add(patch.getPath() + ": " + e.getMessage());
            } catch (Exception e) {
                log.warn("文件补丁应用异常: {}", patch.getPath(), e);
                failures.add(patch.getPath() + ": " + e.getMessage());
            }
        }
        return new ApplyResult(fileContents, failures);
    }

    /**
     * 对<b>内存中的文件内容</b>应用补丁（不落盘）
     *
     * <p>用于「修复轮」场景：锚点修复轮的提示词注入的是 AI <b>刚输出但尚未写盘</b>的新版内容，
     * 补丁的 {@code search} 片段来自那份新版内容——若按 {@link #apply(Path, List)} 从磁盘读，
     * 拿到的是<b>改造前的旧内容</b>，search 必然匹配不上（语义错误）。</p>
     *
     * @param contents 文件路径 → 当前内容（会被本方法内的修改覆盖，不影响入参 Map）
     * @param patches  补丁列表
     * @return 应用结果（产物内容 + 失败明细）
     */
    public ApplyResult applyToContents(Map<String, String> contents, List<AiTemplatePatchDto> patches) {
        Map<String, String> fileContents = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        if (contents == null || patches == null || patches.isEmpty()) {
            return new ApplyResult(fileContents, failures);
        }
        // 以入参内容为基底（复制一份，不污染调用方的 Map）
        Map<String, String> working = new LinkedHashMap<>(contents);

        Map<String, Integer> perFileCount = new LinkedHashMap<>();
        for (AiTemplatePatchDto patch : patches) {
            try {
                String path = patch.getPath() == null ? "" : patch.getPath().trim();
                if (path.isEmpty()) {
                    failures.add("补丁缺少 path");
                    continue;
                }
                if (!isPatchablePath(path)) {
                    failures.add(path + ": 路径非法（只允许修改 " + String.join("/", PATCHABLE_EXTENSIONS) + " 文本文件）");
                    continue;
                }
                String content = working.get(path);
                if (content == null) {
                    failures.add(path + ": 文件不存在于待修复内容中（补丁只能修改已有文件）");
                    continue;
                }
                int count = perFileCount.merge(path, 1, Integer::sum);
                if (count > MAX_PATCHES_PER_FILE) {
                    failures.add(path + ": 单文件补丁数超过上限 " + MAX_PATCHES_PER_FILE + "，已跳过其余补丁");
                    break;
                }
                String search = patch.getSearch();
                if (search == null || search.isEmpty()) {
                    failures.add(path + ": search 片段为空");
                    continue;
                }
                PatchTextMatcher.Match match = PatchTextMatcher.find(content, search);
                if (PatchTextMatcher.AMBIGUOUS == match) {
                    failures.add(path + ": search 片段匹配多处（须唯一，请扩大片段范围）");
                    continue;
                }
                if (match.start() < 0) {
                    failures.add(path + ": search 片段在源码中未找到（行尾符差异已自动兼容）");
                    continue;
                }
                String replace = PatchTextMatcher.alignNewlines(
                        patch.getReplace() == null ? "" : patch.getReplace(), content);
                working.put(path, content.substring(0, match.start()) + replace
                        + content.substring(match.start() + match.length()));
            } catch (Exception e) {
                log.warn("内存内容补丁应用异常: {}", patch.getPath(), e);
                failures.add(patch.getPath() + ": " + e.getMessage());
            }
        }
        // 只返回真正被补丁触及的文件（未改动的不必回传）
        for (Map.Entry<String, String> entry : working.entrySet()) {
            String original = contents.get(entry.getKey());
            if (original == null || !original.equals(entry.getValue())) {
                fileContents.put(entry.getKey(), entry.getValue());
            }
        }
        return new ApplyResult(fileContents, failures);
    }

    /**
     * 路径是否可被补丁修改（扩展名白名单）
     */
    private boolean isPatchablePath(String path) {
        String lower = path.toLowerCase();
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return PATCHABLE_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
