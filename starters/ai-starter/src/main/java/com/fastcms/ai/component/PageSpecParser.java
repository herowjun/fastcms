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
package com.fastcms.ai.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 组件化生成响应解析器：AI 返回文本 → reply + PageSpec + 组件源码补丁
 *
 * <p>标准响应格式（与模板生成管线的 reply 流式契约一致，前端打字机效果复用）：</p>
 * <pre>{@code
 * {
 *   "reply": "给用户的自然语言回复",
 *   "pagespec": { "specVersion": "1.0", "foundation": "...", "pages": { ... } },
 *   "filePatches": [
 *     {"path": "_components/tw__navbar__sticky.ftl", "search": "原文精确片段", "replace": "替换后片段"}
 *   ]
 * }
 * }</pre>
 *
 * <p>filePatches 为可选补充：spec 表达不了的组件源码级调整（如导航选中态颜色、
 * hover 样式、圆角间距等组件源码内写死的样式）由模型输出精准 search/replace 补丁，
 * 由调用方校验后写入组件覆盖目录。</p>
 *
 * <p>容错策略与 {@link com.fastcms.ai.template.AiTemplateResponseParser} 一致：
 * markdown 代码块包裹、前后解释文字均可剥壳；额外兼容 pagespec 直接作为根对象的输出。
 * PageSpec 体量小（几 KB），被截断的概率远低于整套 HTML 模板，截断时由调用方触发重试。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class PageSpecParser {

    private static final Logger log = LoggerFactory.getLogger(PageSpecParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 组件源码补丁：对工作目录中已有组件 FTL 文件的精准文本替换
     *
     * @param path    组件文件相对路径（_components/xxx.ftl）
     * @param search  当前文件中的原文精确片段（要求全文唯一匹配）
     * @param replace 替换后的片段
     */
    public record FilePatch(String path, String search, String replace) {
    }

    /**
     * 解析结果：reply（自然语言，可能为 null）+ pagespec（可能为 null）
     * + filePatches（组件源码补丁，可能为空列表）
     */
    public record ParseResult(String reply, PageSpec pagespec, java.util.List<FilePatch> filePatches) {

        public ParseResult(String reply, PageSpec pagespec) {
            this(reply, pagespec, java.util.List.of());
        }
    }

    /**
     * 解析 AI 响应为 reply + PageSpec + filePatches
     *
     * @return 解析结果（各字段均可能为 null/空，不返回 null 本身）
     */
    public ParseResult parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(null, null);
        }
        String json = extractJson(raw);
        if (json == null) {
            log.warn("AI 响应中未找到 JSON，原始内容前 200 字符: {}", raw.substring(0, Math.min(200, raw.length())));
            return new ParseResult(null, null);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isObject()) {
                JsonNode replyNode = root.get("reply");
                String reply = replyNode != null && replyNode.isTextual() ? replyNode.asString() : null;

                java.util.List<FilePatch> patches = parseFilePatches(root.get("filePatches"));

                JsonNode specNode = root.get("pagespec");
                if (specNode == null || !specNode.isObject()) {
                    // 兼容：PageSpec 直接作为根对象（无 reply 包裹）
                    return new ParseResult(reply,
                            looksLikePageSpec(root) ? MAPPER.convertValue(root, PageSpec.class) : null, patches);
                }
                return new ParseResult(reply, MAPPER.convertValue(specNode, PageSpec.class), patches);
            }
            return new ParseResult(null, null);
        } catch (Exception e) {
            log.warn("解析 PageSpec JSON 响应失败: {}", e.getMessage());
            return new ParseResult(null, null);
        }
    }

    /**
     * 解析 filePatches 数组（字段非法的条目跳过，不整体失败）
     */
    private java.util.List<FilePatch> parseFilePatches(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<FilePatch> patches = new java.util.ArrayList<>();
        for (JsonNode elem : node) {
            if (elem == null || !elem.isObject()) {
                continue;
            }
            JsonNode pathNode = elem.get("path");
            JsonNode searchNode = elem.get("search");
            JsonNode replaceNode = elem.get("replace");
            if (pathNode == null || !pathNode.isTextual()
                    || searchNode == null || !searchNode.isTextual()
                    || replaceNode == null || !replaceNode.isTextual()) {
                log.warn("filePatch 条目字段缺失或类型非法，跳过: {}", elem);
                continue;
            }
            patches.add(new FilePatch(pathNode.asString().trim(), searchNode.asString(), replaceNode.asString()));
        }
        return patches;
    }

    /**
     * 粗判根对象是否像 PageSpec（含 pages 键且值为对象）
     */
    private boolean looksLikePageSpec(JsonNode node) {
        JsonNode pages = node.get("pages");
        return pages != null && pages.isObject();
    }

    /**
     * 从原始文本中提取 JSON 主体（markdown 代码块 / 前后文字剥壳）
     */
    String extractJson(String raw) {
        String text = raw.trim();

        // markdown 代码块 ```json ... ```
        int codeBlockStart = text.indexOf("```");
        if (codeBlockStart >= 0) {
            int contentStart = text.indexOf((char) 10, codeBlockStart);
            if (contentStart > 0) {
                int codeBlockEnd = text.indexOf("```", contentStart);
                if (codeBlockEnd > contentStart) {
                    String codeBlock = text.substring(contentStart + 1, codeBlockEnd).trim();
                    if (codeBlock.startsWith("{")) {
                        return codeBlock;
                    }
                }
            }
        }

        if (text.startsWith("{")) {
            return text;
        }

        // 前后有文字：截取首个 { 到末个 }
        int objStart = text.indexOf((char) 123);
        if (objStart >= 0) {
            int lastBrace = text.lastIndexOf((char) 125);
            if (lastBrace > objStart) {
                return text.substring(objStart, lastBrace + 1);
            }
        }
        return null;
    }

}