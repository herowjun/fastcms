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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 模板生成响应解析器
 *
 * <p>将 AI 返回的 JSON 字符串解析为 {@link ParseResult}（自然语言回复 + 文件列表）。</p>
 *
 * <p>标准响应格式为 JSON 对象：</p>
 * <pre>{@code
 * {
 *   "reply": "给用户的自然语言回复",
 *   "files": [ { "path": "...", "content": "...", "action": "create" } ]
 * }
 * }</pre>
 *
 * <p>同时兼容旧版纯 JSON 数组格式（视为无 reply、只有 files）。
 * AI 返回的内容理论上是严格 JSON，但实际生产中可能出现：
 * <ul>
 *     <li>被 markdown 代码块包裹（```json ... ```）</li>
 *     <li>前后有解释性文字</li>
 *     <li>JSON 字符串内嵌套了未转义的引号</li>
 * </ul>
 * 本解析器会尽力容错。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class AiTemplateResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AiTemplateResponseParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析结果：reply（自然语言回复，可能为 null）+ files（文件列表，可能为空）
     */
    public static class ParseResult {
        private String reply;
        private List<AiTemplateFileDto> files = Collections.emptyList();

        public String getReply() {
            return reply;
        }

        public void setReply(String reply) {
            this.reply = reply;
        }

        public List<AiTemplateFileDto> getFiles() {
            return files;
        }

        public void setFiles(List<AiTemplateFileDto> files) {
            this.files = files == null ? Collections.emptyList() : files;
        }
    }

    /**
     * 解析 AI 响应为 reply + 文件列表
     *
     * @param raw AI 返回的原始文本
     * @return 解析结果（reply 可能为 null，files 可能为空列表，不会返回 null）
     */
    public ParseResult parseResponse(String raw) {
        ParseResult result = new ParseResult();
        if (raw == null || raw.isBlank()) {
            return result;
        }

        String json = extractJson(raw);
        if (json == null) {
            log.warn("AI 响应中未找到 JSON，原始内容前 200 字符: {}", raw.substring(0, Math.min(200, raw.length())));
            return result;
        }

        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isObject()) {
                // 新格式：{"reply": "...", "files": [...]}
                JsonNode replyNode = root.get("reply");
                if (replyNode != null && replyNode.isTextual()) {
                    result.setReply(replyNode.asString());
                }
                JsonNode filesNode = root.get("files");
                if (filesNode != null && filesNode.isArray()) {
                    result.setFiles(sanitize(MAPPER.convertValue(filesNode, new TypeReference<List<AiTemplateFileDto>>() {
                    })));
                }
            } else if (root.isArray()) {
                // 旧格式兼容：纯文件数组
                result.setFiles(sanitize(MAPPER.convertValue(root, new TypeReference<List<AiTemplateFileDto>>() {
                })));
            } else {
                log.warn("AI 响应 JSON 根节点既不是对象也不是数组");
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 AI JSON 响应失败: {}", e.getMessage());
            // 二次尝试：宽松解析，从文本中提取数组部分逐项解析
            result.setFiles(parseLenient(json));
            return result;
        }
    }

    /**
     * 从原始文本中提取第一个 JSON 对象或数组
     *
     * <p>处理三种情况：
     * <ol>
     *     <li>纯 JSON（直接返回）</li>
     *     <li>被 ```json ... ``` 包裹（提取代码块内容）</li>
     *     <li>前后有非 JSON 文字（截取首个 { 或 [ 到末个 } 或 ] 的部分）</li>
     * </ol>
     */
    String extractJson(String raw) {
        String text = raw.trim();

        // 情况 2：markdown 代码块 ```json ... ``` 或 ``` ... ```
        int codeBlockStart = text.indexOf("```");
        if (codeBlockStart >= 0) {
            int contentStart = text.indexOf('\n', codeBlockStart);
            if (contentStart > 0) {
                int codeBlockEnd = text.indexOf("```", contentStart);
                if (codeBlockEnd > contentStart) {
                    String codeBlock = text.substring(contentStart + 1, codeBlockEnd).trim();
                    if (codeBlock.startsWith("{") || codeBlock.startsWith("[")) {
                        return codeBlock;
                    }
                }
            }
        }

        // 情况 1：纯 JSON
        if (text.startsWith("{") || text.startsWith("[")) {
            return text;
        }

        // 情况 3：前后有文字，截取 JSON 主体（优先对象，其次数组）
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        if (objStart >= 0 && (arrStart < 0 || objStart < arrStart)) {
            int lastBrace = text.lastIndexOf('}');
            if (lastBrace > objStart) {
                return text.substring(objStart, lastBrace + 1);
            }
        }
        if (arrStart >= 0) {
            int lastBracket = text.lastIndexOf(']');
            if (lastBracket > arrStart) {
                return text.substring(arrStart, lastBracket + 1);
            }
        }

        return null;
    }

    /**
     * 清洗解析结果：过滤空 path、补全 action 默认值
     */
    private List<AiTemplateFileDto> sanitize(List<AiTemplateFileDto> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiTemplateFileDto> result = new ArrayList<>(files.size());
        for (AiTemplateFileDto file : files) {
            if (file.getPath() == null || file.getPath().isBlank()) {
                log.warn("跳过 path 为空的文件项");
                continue;
            }
            // path 标准化：去除前导 ./ 和 /
            String path = file.getPath().trim();
            if (path.startsWith("./")) {
                path = path.substring(2);
            } else if (path.startsWith("/")) {
                path = path.substring(1);
            }
            file.setPath(path);

            // action 默认值
            if (file.getAction() == null || file.getAction().isBlank()) {
                file.setAction(AiTemplateConstants.ACTION_CREATE);
            } else {
                file.setAction(file.getAction().trim().toLowerCase());
            }

            result.add(file);
        }
        return result;
    }

    /**
     * 宽松解析：当整体解析失败时，尝试提取数组部分逐项解析，跳过失败项
     *
     * <p>适用于 AI 返回的 JSON 中个别文件 content 含有未正确转义的特殊字符的场景。</p>
     */
    private List<AiTemplateFileDto> parseLenient(String json) {
        try {
            String arrayPart = extractArrayPart(json);
            if (arrayPart == null) {
                return Collections.emptyList();
            }
            List<Object> rawList = MAPPER.readValue(arrayPart, new TypeReference<List<Object>>() {
            });
            List<AiTemplateFileDto> result = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                try {
                    String itemJson = MAPPER.writeValueAsString(item);
                    AiTemplateFileDto file = MAPPER.readValue(itemJson, AiTemplateFileDto.class);
                    result.add(file);
                } catch (Exception ignored) {
                    // 跳过单个解析失败的项
                }
            }
            return sanitize(result);
        } catch (Exception e) {
            log.warn("宽松解析也失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 JSON 文本中截取数组部分（files 字段的值或整个数组文本）
     */
    private String extractArrayPart(String json) {
        int firstBracket = json.indexOf('[');
        int lastBracket = json.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            return json.substring(firstBracket, lastBracket + 1);
        }
        return null;
    }

}
