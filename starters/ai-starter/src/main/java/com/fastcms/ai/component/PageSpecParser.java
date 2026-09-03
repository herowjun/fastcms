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
 * AI 组件化生成响应解析器：AI 返回文本 → reply + PageSpec
 *
 * <p>标准响应格式（与模板生成管线的 reply 流式契约一致，前端打字机效果复用）：</p>
 * <pre>{@code
 * {
 *   "reply": "给用户的自然语言回复",
 *   "pagespec": { "specVersion": "1.0", "foundation": "...", "pages": { ... } }
 * }
 * }</pre>
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
     * 解析结果：reply（自然语言，可能为 null）+ pagespec（可能为 null）
     */
    public record ParseResult(String reply, PageSpec pagespec) {
    }

    /**
     * 解析 AI 响应为 reply + PageSpec
     *
     * @return 解析结果（两字段均可能为 null，不返回 null 本身）
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

                JsonNode specNode = root.get("pagespec");
                if (specNode == null || !specNode.isObject()) {
                    // 兼容：PageSpec 直接作为根对象（无 reply 包裹）
                    return new ParseResult(reply,
                            looksLikePageSpec(root) ? MAPPER.convertValue(root, PageSpec.class) : null);
                }
                return new ParseResult(reply, MAPPER.convertValue(specNode, PageSpec.class));
            }
            return new ParseResult(null, null);
        } catch (Exception e) {
            log.warn("解析 PageSpec JSON 响应失败: {}", e.getMessage());
            return new ParseResult(null, null);
        }
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