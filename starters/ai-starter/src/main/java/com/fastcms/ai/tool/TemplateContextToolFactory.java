/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * http://www.xjd2020.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 模板上下文按需检索工具工厂（聚焦注入架构的 L2 层）
 *
 * <p>调整轮采用聚焦注入（L0 当前页依赖闭包 + L1 全站文件清单常驻，见
 * AiTemplateGenServiceImpl#buildFocusedTemplateFileSection）后，模型需要"按需查看"
 * 未注入文件的能力。本工厂为每个请求构造两个闭包工具：</p>
 * <ul>
 *     <li>{@code read_template_file(path)}：读取模板内任意文本文件全文</li>
 *     <li>{@code search_template_files(keyword)}：全站文本搜索，返回 文件:行号:上下文</li>
 * </ul>
 *
 * <p><b>为何不走全局 @AiTool 注册表</b>：注册表是单例 bean，无法感知当前会话的工作
 * 目录与 SSE 通道。本工厂由服务层每请求调用 {@link #createCallbacks(Path, BiConsumer)}，
 * 闭包捕获 workDir 与事件推送，天然会话隔离。</p>
 *
 * <p><b>防护</b>：路径穿越校验（resolve+normalize+startsWith）、文本扩展名白名单、
 * 单轮调用次数上限、累计返回量上限——防模型失控读全站撑爆上下文。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class TemplateContextToolFactory {

    public static final String TOOL_READ_FILE = "read_template_file";
    public static final String TOOL_SEARCH_FILES = "search_template_files";

    /**
     * 单轮工具调用总次数上限（两个工具合计）：失控的连环读取无助于修改质量，只会拖长耗时
     */
    private static final int MAX_CALLS_PER_ROUND = 12;

    /**
     * 工具累计返回字符上限：读入的内容全部进入模型上下文，必须有总量闸门
     */
    private static final int MAX_TOTAL_RETURN_CHARS = 96 * 1024;

    /**
     * 单文件读取字符上限（超过截断，正常模板文本文件远小于此值）
     */
    private static final int MAX_FILE_READ_CHARS = 128 * 1024;

    /**
     * 搜索命中条数上限（每文件不限、总量限，返回按文件分组）
     */
    private static final int MAX_SEARCH_HITS = 60;

    /**
     * 参与搜索/读取的文本扩展名（与调整轮注入白名单一致；图片字体等二进制无检索意义）
     */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "html", "css", "js", "properties", "txt", "json", "xml", "md", "ftl", "svg", "scss", "less");

    /**
     * 超大文件跳过读取的下限（如编译产物 pack.css 数百 KB，读入只会挤爆上下文）
     */
    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 构造本轮会话的模板上下文工具（闭包捕获工作目录与 SSE 事件推送）
     *
     * @param workDir      模板工作目录（正式模板目录或生成预览目录）
     * @param eventSender  SSE 事件推送（eventName, data），用于把检索过程实时告知前端；
     *                     传 null 时静默执行（无 SSE 通道的调用场景）
     */
    public ToolCallback[] createCallbacks(Path workDir, BiConsumer<String, String> eventSender) {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger returnedChars = new AtomicInteger();
        return new ToolCallback[]{
                buildReadTool(workDir, eventSender, callCount, returnedChars),
                buildSearchTool(workDir, eventSender, callCount, returnedChars)
        };
    }

    /**
     * read_template_file：读取模板内文本文件全文（未注入/被截断的文件，修改前必须先读）
     */
    private ToolCallback buildReadTool(Path workDir, BiConsumer<String, String> eventSender,
                                       AtomicInteger callCount, AtomicInteger returnedChars) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(TOOL_READ_FILE)
                .description("读取当前模板目录中指定文本文件的完整内容。"
                        + "当文件未注入上下文（不在依赖清单中或被截断）而你需要基于其内容修改时调用；"
                        + "path 为文件清单中的相对路径（如 static/js/pay.js、_layout.html）")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"模板内相对路径\"}},\"required\":[\"path\"]}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                String path = extractStringParam(toolInput, "path");
                if (path == null || path.isBlank()) {
                    return "参数错误：path 不能为空（取值见文件清单中的相对路径）";
                }
                String guard = commonGuard(workDir, callCount, returnedChars);
                if (guard != null) {
                    return guard;
                }
                String rel = normalizeRelPath(path);
                Path target = resolveSafely(workDir, rel);
                if (target == null) {
                    return "文件不存在或不可读: " + path + "（请对照文件清单中的路径）";
                }
                pushEvent(eventSender, "🔍 AI 正在查看 " + rel + " …");
                try {
                    String content = Files.readString(target, StandardCharsets.UTF_8);
                    if (content.length() > MAX_FILE_READ_CHARS) {
                        content = content.substring(0, MAX_FILE_READ_CHARS) + "\n…（文件超长已截断）";
                    }
                    returnedChars.addAndGet(content.length());
                    return "===== " + rel + " =====\n" + content;
                } catch (IOException e) {
                    return "文件读取失败: " + rel + "（" + e.getMessage() + "）";
                }
            }
        };
    }

    /**
     * search_template_files：全站文本搜索（找已有实现/样式/脚本定义位置）
     */
    private ToolCallback buildSearchTool(Path workDir, BiConsumer<String, String> eventSender,
                                         AtomicInteger callCount, AtomicInteger returnedChars) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(TOOL_SEARCH_FILES)
                .description("在当前模板的全站文本文件中搜索关键词（如 wxpay、付费下载、carousel），"
                        + "返回 文件:行号:行内容。用于：实现新功能前查找站内是否已有同类实现"
                        + "（如其他页面的支付/下载/轮播），有则参考保持一致；"
                        + "定位某样式类/脚本函数/FreeMarker 宏定义在哪个文件")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\","
                        + "\"description\":\"搜索关键词（大小写不敏感）\"},"
                        + "\"isRegex\":{\"type\":\"boolean\",\"description\":\"是否按正则表达式搜索，默认 false\"}},"
                        + "\"required\":[\"keyword\"]}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                String keyword = extractStringParam(toolInput, "keyword");
                boolean isRegex = Boolean.parseBoolean(extractStringParam(toolInput, "isRegex"));
                if (keyword == null || keyword.isBlank()) {
                    return "参数错误：keyword 不能为空";
                }
                String guard = commonGuard(workDir, callCount, returnedChars);
                if (guard != null) {
                    return guard;
                }
                pushEvent(eventSender, "🔍 AI 正在全站搜索「" + keyword + "」…");
                Pattern pattern;
                try {
                    pattern = isRegex
                            ? Pattern.compile(keyword, Pattern.CASE_INSENSITIVE)
                            : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
                } catch (Exception e) {
                    return "正则表达式无效: " + keyword;
                }
                List<String> hits = new ArrayList<>();
                int truncatedFiles = 0;
                try (Stream<Path> stream = Files.walk(workDir)) {
                    List<Path> files = stream
                            .filter(Files::isRegularFile)
                            .filter(p -> isTextFile(p.getFileName().toString()))
                            .collect(java.util.stream.Collectors.toList());
                    for (Path p : files) {
                        if (hits.size() >= MAX_SEARCH_HITS) {
                            break;
                        }
                        String rel = workDir.relativize(p).toString().replaceAll("\\\\", "/");
                        int before = hits.size();
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (int i = 0; i < lines.size() && hits.size() < MAX_SEARCH_HITS; i++) {
                                String line = lines.get(i);
                                if (pattern.matcher(line).find()) {
                                    String ctx = line.trim();
                                    if (ctx.length() > 160) {
                                        ctx = ctx.substring(0, 160) + "…";
                                    }
                                    hits.add(rel + ":" + (i + 1) + ": " + ctx);
                                }
                            }
                        } catch (IOException ignored) {
                            // 非 UTF-8 文件跳过
                        }
                        if (hits.size() > before && hits.size() < MAX_SEARCH_HITS
                                && hits.size() - before > 20) {
                            truncatedFiles++;
                        }
                    }
                } catch (IOException e) {
                    return "搜索失败: " + e.getMessage();
                }
                if (hits.isEmpty()) {
                    return "未找到匹配「" + keyword + "」的内容。可尝试其他关键词（如英文标识、函数名、CSS 类名）";
                }
                StringBuilder sb = new StringBuilder("搜索「").append(keyword).append("」命中 ")
                        .append(hits.size()).append(" 处（文件:行号: 内容）:\n");
                for (String hit : hits) {
                    sb.append(hit).append("\n");
                }
                if (truncatedFiles > 0) {
                    sb.append("…（部分文件命中过多已限量）\n");
                }
                sb.append("\n需要完整内容时用 read_template_file 读取对应文件");
                String result = sb.toString();
                returnedChars.addAndGet(result.length());
                return result;
            }
        };
    }

    /**
     * 公共守卫：调用次数与返回量上限；超限时返回提示文案（非 null 表示拦截）
     */
    private String commonGuard(Path workDir, AtomicInteger callCount, AtomicInteger returnedChars) {
        if (callCount.incrementAndGet() > MAX_CALLS_PER_ROUND) {
            return "本轮工具调用已达上限（" + MAX_CALLS_PER_ROUND + " 次），请基于已获取的信息继续完成任务";
        }
        if (returnedChars.get() >= MAX_TOTAL_RETURN_CHARS) {
            return "本轮工具返回内容已达上限，请基于已获取的信息继续完成任务";
        }
        return null;
    }

    /**
     * 从工具入参 JSON 中提取字符串字段（兼容 null / 非对象输入）
     */
    private String extractStringParam(String toolInput, String field) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode node = root.get(field);
            return node != null && node.isTextual() ? node.asString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 归一化路径：去 ./ 与反斜杠，拒绝绝对路径
     */
    private String normalizeRelPath(String raw) {
        String p = raw.trim().replace("\\", "/");
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/")) {
            return null;
        }
        return p;
    }

    /**
     * 安全解析：resolve + normalize + 必须落在 workDir 内（防路径穿越），
     * 且为白名单文本扩展名的常规文件、大小在可读范围内
     */
    private Path resolveSafely(Path workDir, String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        try {
            Path target = workDir.resolve(rel).normalize();
            if (!target.startsWith(workDir)) {
                return null;
            }
            if (!Files.isRegularFile(target) || Files.size(target) > MAX_FILE_SIZE_BYTES) {
                return null;
            }
            if (!isTextFile(target.getFileName().toString())) {
                return null;
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isTextFile(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return TEXT_EXTENSIONS.contains(ext);
    }

    /**
     * 推送工具执行过程到前端对话流（无通道时静默）
     */
    private void pushEvent(BiConsumer<String, String> eventSender, String text) {
        if (eventSender != null) {
            try {
                eventSender.accept("message", "\n" + text + "\n");
            } catch (Exception ignored) {
                // 推送失败不影响工具执行
            }
        }
    }

    /**
     * 引用解析模式（供服务层依赖闭包构建器复用，保持两处解析规则一致）：
     * FreeMarker include/import、script src、link href、CSS @import
     */
    public static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "<#include\\s+[\"']([^\"']+)[\"']"
                    + "|<#import\\s+[\"']([^\"']+)[\"']"
                    + "|<script[^>]+src=[\"']([^\"']+)[\"']"
                    + "|<link[^>]+href=[\"']([^\"']+)[\"']"
                    + "|@import\\s+(?:url\\()?\\s*[\"']([^\"')]+)[\"']");

    /**
     * 从引用匹配组中取第一个非空值
     */
    public static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            String v = m.group(i);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
