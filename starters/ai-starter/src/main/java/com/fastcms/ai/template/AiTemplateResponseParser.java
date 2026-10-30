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
 * <p>同时支持 Patch 形态（调整轮/修复轮的推荐形态，只输出变更片段）：</p>
 * <pre>{@code
 * {
 *   "reply": "给用户的自然语言回复",
 *   "patches": [ { "path": "...", "search": "原文片段", "replace": "新内容" } ]
 * }
 * }</pre>
 *
 * <p>两种形态可同时出现，此时 <b>patches 优先</b>（补丁应用失败的文件由服务层回退到 files）。</p>
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
     * + switchTo（页面切换声明，可能为 null：用户要求"切换/查看某页面"的纯导航轮次）
     * + patches（补丁列表，可能为空：Patch 形态输出协议，优先于 files）
     */
    public static class ParseResult {
        private String reply;
        private String switchTo;
        private List<AiTemplateFileDto> files = Collections.emptyList();
        private List<AiTemplatePatchDto> patches = Collections.emptyList();
        private List<String> unchanged = Collections.emptyList();
        /**
         * 本次解析是否来自「截断抢救」（模型输出被 maxTokens 截断，JSON 不完整）
         */
        private boolean truncated;

        public String getReply() {
            return reply;
        }

        public void setReply(String reply) {
            this.reply = reply;
        }

        /**
         * 是否为截断抢救的结果
         *
         * <p>{@code true} 表示模型输出被 maxTokens 截断，解析器从残缺 JSON 中救回了
         * <b>已完整写出的元素</b>——内容可用，但本轮可能不完整（服务层据此提示用户）。</p>
         */
        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean truncated) {
            this.truncated = truncated;
        }

        /**
         * 是否抢救到了任何可用内容
         */
        public boolean hasSalvagedContent() {
            return !files.isEmpty() || !patches.isEmpty() || !unchanged.isEmpty();
        }

        public String getSwitchTo() {
            return switchTo;
        }

        public void setSwitchTo(String switchTo) {
            this.switchTo = switchTo;
        }

        public List<AiTemplateFileDto> getFiles() {
            return files;
        }

        public void setFiles(List<AiTemplateFileDto> files) {
            this.files = files == null ? Collections.emptyList() : files;
        }

        /**
         * 补丁列表（Patch 形态输出协议）
         *
         * <p>非空表示模型选择了"只输出变更片段"模式：此时 patches 优先于 files，
         * 服务层应用补丁后得到的完整文件内容才是最终产物。</p>
         */
        public List<AiTemplatePatchDto> getPatches() {
            return patches;
        }

        public void setPatches(List<AiTemplatePatchDto> patches) {
            this.patches = patches == null ? Collections.emptyList() : patches;
        }

        /**
         * 无需改动的文件清单（模型显式声明）
         *
         * <p>用于「按文件推进进度」的链路（深度焕新轮）：模型对本轮完全不需要改动的文件
         * 不必重复输出全文，只需把路径列进 {@code unchanged}，进度队列照常推进。</p>
         *
         * <p>这是补丁协议的必要配套——没有它，AI 面对「无需改动」的文件只能选择
         * 「原样回吐整个文件」（整文件输出成本的根源）或「不输出」（进度卡死）。</p>
         */
        public List<String> getUnchanged() {
            return unchanged;
        }

        public void setUnchanged(List<String> unchanged) {
            this.unchanged = unchanged == null ? Collections.emptyList() : unchanged;
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
                // 新格式：{"reply": "...", "files": [...], "switchTo": "..."}
                JsonNode replyNode = root.get("reply");
                if (replyNode != null && replyNode.isTextual()) {
                    result.setReply(replyNode.asString());
                }
                JsonNode switchNode = root.get("switchTo");
                if (switchNode != null && switchNode.isTextual() && !switchNode.asString().isBlank()) {
                    result.setSwitchTo(switchNode.asString().trim());
                }
                JsonNode filesNode = root.get("files");
                if (filesNode != null && filesNode.isArray()) {
                    result.setFiles(sanitize(MAPPER.convertValue(filesNode, new TypeReference<List<AiTemplateFileDto>>() {
                    })));
                }
                // Patch 形态：优先读 patches，兼容 filePatches（组件管线同名字段）
                JsonNode patchesNode = root.get("patches");
                if (patchesNode == null || !patchesNode.isArray()) {
                    patchesNode = root.get("filePatches");
                }
                if (patchesNode != null && patchesNode.isArray()) {
                    result.setPatches(sanitizePatches(MAPPER.convertValue(patchesNode,
                            new TypeReference<List<AiTemplatePatchDto>>() {
                            })));
                }
                // unchanged：模型显式声明「这些文件本轮无需改动」。
                // 深度焕新轮等「按文件推进进度」的链路需要它——否则 AI 对无需改动的文件
                // 不输出任何内容，进度队列会卡死（原先靠「原样完整回吐整个文件」来占位，
                // 那是整文件输出成本的根源）。
                JsonNode unchangedNode = root.get("unchanged");
                if (unchangedNode != null && unchangedNode.isArray()) {
                    result.setUnchanged(sanitizePaths(unchangedNode));
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
            // 截断抢救（2026-10-29）：模型输出被 maxTokens 截断时，JSON 尾部残缺，
            // 整体 readTree 与宽松解析双双失败 → 该轮产出全部丢失（白等几分钟）。
            // 但数组中「已完整写出的元素」本身是合法 JSON，可以逐个救回。
            ParseResult salvaged = salvageTruncated(json);
            if (salvaged.hasSalvagedContent()) {
                log.warn("已从截断响应中抢救出内容: files={} patches={} unchanged={}",
                        salvaged.getFiles().size(), salvaged.getPatches().size(),
                        salvaged.getUnchanged().size());
                salvaged.setTruncated(true);
                return salvaged;
            }
            // 二次尝试：宽松解析，从文本中提取数组部分逐项解析
            result.setFiles(parseLenient(json));
            return result;
        }
    }

    /**
     * 从被截断的 JSON 中抢救已完整写出的元素
     *
     * <p><b>为什么需要</b>：模型输出撞上 maxTokens 上限时，JSON 尾部被硬切（实测深度焕新轮
     * 输出 19212 token 撞 8192 上限 → JSON 在字符串中途断裂 → 整体解析失败）。
     * 此时该轮<b>已经消耗了几分钟算力</b>，若因为最后一个元素残缺就把整轮产出全部丢弃，
     * 代价极高。而残缺之前的元素是完整合法的 JSON，完全可以救回来。</p>
     *
     * <p><b>实现</b>：不做整体解析，改为<b>逐元素扫描</b>——在目标数组内跟踪字符串边界与
     * 括号深度，每凑齐一个完整元素就单独解析并收下；遇到残缺元素（深度未归零）立即停止。
     * 已救回的元素逐个用 Jackson 单独解析，个别失败只丢该条。</p>
     *
     * @param json 截断后的 JSON 文本
     * @return 抢救结果（可能为空）
     */
    private ParseResult salvageTruncated(String json) {
        ParseResult result = new ParseResult();
        if (json == null || json.isBlank()) {
            return result;
        }
        // 1. 抢救 files 数组
        List<String> fileChunks = scanCompleteArrayElements(json, "files");
        if (!fileChunks.isEmpty()) {
            List<AiTemplateFileDto> files = new ArrayList<>(fileChunks.size());
            for (String chunk : fileChunks) {
                try {
                    files.add(MAPPER.readValue(chunk, AiTemplateFileDto.class));
                } catch (Exception ignored) {
                    // 单个元素解析失败只丢该条，不影响其他元素
                }
            }
            result.setFiles(sanitize(files));
        }
        // 2. 抢救 patches 数组（兼容 filePatches 键名）
        List<String> patchChunks = scanCompleteArrayElements(json, "patches");
        if (patchChunks.isEmpty()) {
            patchChunks = scanCompleteArrayElements(json, "filePatches");
        }
        if (!patchChunks.isEmpty()) {
            List<AiTemplatePatchDto> patches = new ArrayList<>(patchChunks.size());
            for (String chunk : patchChunks) {
                try {
                    patches.add(MAPPER.readValue(chunk, AiTemplatePatchDto.class));
                } catch (Exception ignored) {
                    // 同上
                }
            }
            result.setPatches(sanitizePatches(patches));
        }
        // 3. 抢救 unchanged 数组（纯字符串数组，逐元素扫描同样适用）
        List<String> unchangedChunks = scanCompleteArrayElements(json, "unchanged");
        if (!unchangedChunks.isEmpty()) {
            List<String> paths = new ArrayList<>(unchangedChunks.size());
            for (String chunk : unchangedChunks) {
                try {
                    // chunk 是含引号的 JSON 字符串原文（含转义），用 Jackson 解码还原真实路径
                    String path = MAPPER.readValue(chunk, String.class);
                    if (path != null && !path.isBlank()) {
                        paths.add(path);
                    }
                } catch (Exception ignored) {
                    // 同上
                }
            }
            result.setUnchanged(sanitizeStringList(paths));
        }
        return result;
    }

    /**
     * 在 JSON 文本中扫描指定键的数组，逐个取出<b>完整闭合</b>的元素原文
     *
     * <p>扫描规则（手写状态机，处理字符串内的花括号/转义引号）：</p>
     * <ol>
     *     <li>定位 {@code "key"} 后紧跟的 {@code [}；</li>
     *     <li>在数组内逐字符扫描，跟踪「是否在字符串内」「是否转义中」「花括号深度」；</li>
     *     <li>深度从 1 归零的瞬间即一个完整元素 → 记录其原文并继续；</li>
     *     <li>遇到残缺元素（扫描到末尾深度未归零）→ <b>立即停止</b>，丢弃该残缺元素
     *         （它是被截断的部分，绝不能当完整内容用）。</li>
     * </ol>
     *
     * @param json JSON 文本
     * @param key  数组键名（files / patches / unchanged）
     * @return 完整元素的 JSON 原文列表（可能为空）
     */
    private List<String> scanCompleteArrayElements(String json, String key) {
        List<String> elements = new ArrayList<>();
        int keyIdx = json.indexOf('"' + key + '"');
        if (keyIdx < 0) {
            return elements;
        }
        int bracket = json.indexOf('[', keyIdx + key.length() + 2);
        if (bracket < 0) {
            return elements;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int elementStart = -1;
        for (int i = bracket + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    // 字符串元素结束（depth 仍为 0 → 这是数组里的纯字符串元素，如 unchanged）
                    if (depth == 0 && elementStart >= 0) {
                        elements.add(json.substring(elementStart, i + 1));
                        elementStart = -1;
                    }
                }
                continue;
            }
            if (c == '"') {
                if (depth == 0) {
                    elementStart = i;
                }
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    elementStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && elementStart >= 0) {
                    elements.add(json.substring(elementStart, i + 1));
                    elementStart = -1;
                } else if (depth < 0) {
                    // 数组提前闭合，停止
                    break;
                }
            } else if (c == ']' && depth == 0) {
                // 数组正常结束
                break;
            }
        }
        return elements;
    }

    /**
     * 清洗字符串列表（去空白、去前导 ./ 与 /、反斜杠归一、空串丢弃）
     */
    private List<String> sanitizeStringList(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(paths.size());
        for (String raw : paths) {
            if (raw == null) {
                continue;
            }
            String path = raw.trim().replace('\\', '/');
            if (path.startsWith("./")) {
                path = path.substring(2);
            } else if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (!path.isEmpty()) {
                result.add(path);
            }
        }
        return result;
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
            // path 标准化：反斜杠归一为正斜杠，去除前导 ./ 和 /
            String path = file.getPath().trim().replace('\\', '/');
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
     * 清洗补丁列表：过滤缺字段的条目、标准化 path
     *
     * <p>单条补丁不合法只丢弃该条（不让整轮解析失败）——补丁协议是可选通道，
     * 丢弃后若模型同时给了 files 仍可走全文回写兜底。</p>
     */
    private List<AiTemplatePatchDto> sanitizePatches(List<AiTemplatePatchDto> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiTemplatePatchDto> result = new ArrayList<>(patches.size());
        for (AiTemplatePatchDto patch : patches) {
            if (patch == null) {
                continue;
            }
            if (patch.getPath() == null || patch.getPath().isBlank()) {
                log.warn("跳过 path 为空的补丁项");
                continue;
            }
            if (patch.getSearch() == null || patch.getSearch().isEmpty()) {
                log.warn("跳过 search 为空的补丁项: {}", patch.getPath());
                continue;
            }
            String path = patch.getPath().trim().replace('\\', '/');
            if (path.startsWith("./")) {
                path = path.substring(2);
            } else if (path.startsWith("/")) {
                path = path.substring(1);
            }
            patch.setPath(path);
            // replace 允许为空串（= 删除 search 片段），但不允许为 null（null 会变成 "null" 字面量）
            if (patch.getReplace() == null) {
                patch.setReplace("");
            }
            result.add(patch);
        }
        return result;
    }

    /**
     * 清洗 unchanged 文件路径列表
     *
     * <p>去空白、去 {@code ./} 与 {@code /} 前缀、反斜杠归一为正斜杠、空串丢弃。</p>
     */
    private List<String> sanitizePaths(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            if (node == null || !node.isTextual()) {
                continue;
            }
            String path = node.asString().trim().replace('\\', '/');
            if (path.startsWith("./")) {
                path = path.substring(2);
            } else if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (!path.isEmpty()) {
                result.add(path);
            }
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
