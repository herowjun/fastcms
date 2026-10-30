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
package com.fastcms.ai.template.compliance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内联资源分离器（CSS/JS 硬性分离的确定性兜底）
 *
 * <p><b>为什么需要</b>：fastcms 模板规范要求 <b>HTML / CSS / JS 分文件</b>，
 * HTML 只能通过 {@code <link rel="stylesheet">} 与 {@code <script src>} 引入样式与脚本。
 * 但「提示词里写了规范」不等于「AI 会遵守」——实测 AI 经常把大段 CSS 写进
 * {@code <style>} 块、把 JS 写进内联 {@code <script>} 块。</p>
 *
 * <p><b>为什么这不是小事</b>：内联资源直接撑大 HTML 文件 → ①每次对话都要把整份
 * HTML 塞进提示词（prefill 成本）；②改一行 CSS 也要在巨大的 HTML 里定位片段；
 * ③与「补丁协议」相冲突（补丁要在原文里唯一定位，文件越大越脆弱）。</p>
 *
 * <p><b>本组件的职责</b>：把已落盘 HTML 里的内联 CSS/JS <b>确定性地</b>抽成独立文件，
 * 并就地改写 HTML 引用。不依赖 AI 是否听话。</p>
 *
 * <p><b>关键约束</b>：</p>
 * <ol>
 *     <li><b>就地替换</b>：{@code <link>} / {@code <script src>} 放在原内联块的位置
 *         —— CSS 加载顺序与 JS 执行时机都不变（JS 尤其敏感，提前执行会找不到 DOM）。</li>
 *     <li><b>逐块抽取</b>：一个内联块 → 一个文件（{@code inline-<page>-1.js}），
 *         绝不合并多个 JS 块（合并会改变相对执行顺序）。</li>
 *     <li><b>FTL 保护</b>：{@code static/css} 与 {@code static/js} 是静态资源，
 *         <b>不经 FreeMarker 渲染</b>。内联内容里若含 {@code <#...>} 或 {@code ${...}}
 *         指令，抽取出去会变成字面量而失效 —— 这种情况<b>只报问题不抽取</b>，
 *         交回 AI 改写。</li>
 *     <li><b>阈值放行</b>：小于阈值的内联块（少量关键 CSS、DOM 就绪后的初始化）
 *         属于正常写法，不动它 —— 避免误伤既有模板（实测存量模板最大内联块
 *         1333 字符 / 44 行，阈值取 2000 字符 / 60 行对存量零误伤）。</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class InlineAssetExtractor {

    /**
     * 内联块字符数阈值：超过即视为「大量内联」，需要分离
     */
    public static final int INLINE_MAX_CHARS = 2000;

    /**
     * 内联块行数阈值：超过即视为「大量内联」（与字符阈值取或）
     *
     * <p><b>取值依据</b>（实测全量模板内联块分布，见下）：存量模板里最大的内联块是
     * {@code xjd2022/article_pics.html} 的 1333 字符 / <b>44 行</b>（Swiper 第三方集成），
     * 其余全部 ≤ 505 字符 / ≤ 11 行。字符阈值 2000 对存量零误伤，但行数阈值若取 20
     * 就会误判那 44 行的 Swiper 块 → 调整型会话（readOnly）会持续播报假 ERROR。
     * 因此行数阈值取 <b>60</b>：既能盖住 AI 生成的大段内联（实测 AI 内联 CSS 普遍
     * 300~800 行），又不会误伤存量。</p>
     */
    public static final int INLINE_MAX_LINES = 60;

    /**
     * 内联 {@code <style>} 块（捕获组 1 为样式内容）
     */
    private static final Pattern STYLE_BLOCK =
            Pattern.compile("<style([^>]*)>(.*?)</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 内联 {@code <script>} 块：必须<b>没有</b> src 属性
     * （有 src 的是外部引入，本就合规）
     */
    private static final Pattern INLINE_SCRIPT_BLOCK =
            Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * src 属性检测（用于区分外部引入脚本）
     */
    private static final Pattern SRC_ATTR =
            Pattern.compile("\\bsrc\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * FreeMarker 语法标记：抽取到静态资源后会被当字面量，必须拒绝抽取
     */
    private static final Pattern FTL_MARKER =
            Pattern.compile("<#|\\$\\{", Pattern.DOTALL);

    private InlineAssetExtractor() {
    }

    /**
     * 内联违规项
     *
     * @param file    所属 HTML 文件相对路径
     * @param type    违规类型：{@code style} / {@code script}
     * @param chars   内联内容字符数
     * @param lines   内联内容行数
     * @param hasFtl  内联内容是否含 FreeMarker 指令（含则不可抽取）
     * @param reason  不可抽取的原因（可抽取时为 null）
     */
    public record Violation(String file, String type, int chars, int lines, boolean hasFtl, String reason) {

        /**
         * 是否可确定性抽取修复
         */
        public boolean extractable() {
            return !hasFtl;
        }
    }

    /**
     * 抽取结果
     *
     * @param assetFiles   新建的静态资源文件：相对路径 → 内容
     * @param rewrittenHtml 改写后的 HTML 全文（引用就地替换为 link / script src）
     * @param extracted    被抽取的静态资源相对路径列表（供调用方注册文件事件）
     */
    public record Extraction(Map<String, String> assetFiles, String rewrittenHtml, List<String> extracted) {

        public boolean isEmpty() {
            return assetFiles.isEmpty();
        }
    }

    /**
     * 检测 HTML 中的内联 CSS/JS 违规项（只读，不改任何东西）
     *
     * @param html     HTML 全文
     * @param fileName HTML 文件相对路径（用于报告）
     * @return 违规项列表（可能为空）
     */
    public static List<Violation> detect(String html, String fileName) {
        List<Violation> violations = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return violations;
        }
        Matcher styleMatcher = STYLE_BLOCK.matcher(html);
        while (styleMatcher.find()) {
            String content = styleMatcher.group(2);
            collectViolation(violations, fileName, "style", content);
        }
        Matcher scriptMatcher = INLINE_SCRIPT_BLOCK.matcher(html);
        while (scriptMatcher.find()) {
            // 有 src 属性的是外部引入脚本（本来就合规），跳过
            if (SRC_ATTR.matcher(scriptMatcher.group(1)).find()) {
                continue;
            }
            String content = scriptMatcher.group(2);
            collectViolation(violations, fileName, "script", content);
        }
        return violations;
    }

    private static void collectViolation(List<Violation> violations, String fileName,
                                         String type, String content) {
        if (content == null) {
            return;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int chars = trimmed.length();
        int lines = trimmed.split("\r?\n", -1).length;
        if (chars <= INLINE_MAX_CHARS && lines <= INLINE_MAX_LINES) {
            // 阈值内：少量关键 CSS / 初始化脚本，属正常写法，放行
            return;
        }
        boolean hasFtl = FTL_MARKER.matcher(trimmed).find();
        String reason = hasFtl
                ? "内联内容含 FreeMarker 指令（<# 或 ${}），抽到静态资源会失去模板解析能力，需改为独立文件并改写指令"
                : null;
        violations.add(new Violation(fileName, type, chars, lines, hasFtl, reason));
    }

    /**
     * 抽取 HTML 中的内联 CSS/JS，改写为独立文件引用
     *
     * <p>只抽取「可抽取」的违规块（含 FTL 指令的块原样保留，但仍会出现在 detect 结果里，
     * 由调用方播报给 AI 处理）。</p>
     *
     * @param html     HTML 全文
     * @param fileName HTML 文件相对路径（用于生成静态资源文件名，如 {@code index.html} → {@code inline-index}）
     * @return 抽取结果（无可抽取内容时 {@link Extraction#isEmpty()} 为 true）
     */
    public static Extraction extract(String html, String fileName) {
        Map<String, String> assetFiles = new LinkedHashMap<>();
        List<String> extracted = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return new Extraction(assetFiles, html, extracted);
        }
        String base = baseName(fileName);

        // 一次扫描收集所有替换点：(start, end, 替换文本)
        // 从后往前替换，避免前面的区间因长度变化而偏移
        List<int[]> ranges = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        int cssSeq = 0;
        int jsSeq = 0;

        Matcher styleMatcher = STYLE_BLOCK.matcher(html);
        while (styleMatcher.find()) {
            String content = styleMatcher.group(2);
            if (!isViolating(content) || FTL_MARKER.matcher(content.trim()).find()) {
                continue;
            }
            cssSeq++;
            String cssName = cssSeq == 1
                    ? ("inline-" + base + ".css")
                    : ("inline-" + base + "-" + cssSeq + ".css");
            String cssPath = "static/css/" + cssName;
            assetFiles.put(cssPath,
                    "/* 由 " + fileName + " 的内联 <style> 块自动分离 */\n"
                            + content.stripTrailing() + "\n");
            extracted.add(cssPath);
            ranges.add(new int[]{styleMatcher.start(), styleMatcher.end()});
            replacements.add("<link rel=\"stylesheet\" href=\"${ctx()}/css/" + cssName + "\">");
        }

        Matcher scriptMatcher = INLINE_SCRIPT_BLOCK.matcher(html);
        while (scriptMatcher.find()) {
            // 有 src 属性的是外部引入脚本（本就合规），跳过
            if (SRC_ATTR.matcher(scriptMatcher.group(1)).find()) {
                continue;
            }
            String content = scriptMatcher.group(2);
            if (!isViolating(content) || FTL_MARKER.matcher(content.trim()).find()) {
                continue;
            }
            jsSeq++;
            // 逐块一个文件，绝不合并：合并会改变 JS 的相对执行时机
            String jsName = jsSeq == 1
                    ? ("inline-" + base + ".js")
                    : ("inline-" + base + "-" + jsSeq + ".js");
            String jsPath = "static/js/" + jsName;
            assetFiles.put(jsPath,
                    "/* 由 " + fileName + " 的内联 <script> 块自动分离 */\n"
                            + content.stripTrailing() + "\n");
            extracted.add(jsPath);
            ranges.add(new int[]{scriptMatcher.start(), scriptMatcher.end()});
            replacements.add("<script src=\"${ctx()}/js/" + jsName + "\"></script>");
        }

        if (assetFiles.isEmpty()) {
            return new Extraction(assetFiles, html, extracted);
        }
        // 按起点倒序替换，保证前面的区间不受影响
        List<Integer> order = new ArrayList<>(ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(ranges.get(b)[0], ranges.get(a)[0]));
        StringBuilder out = new StringBuilder(html);
        for (int idx : order) {
            int[] range = ranges.get(idx);
            out.replace(range[0], range[1], replacements.get(idx));
        }
        return new Extraction(assetFiles, out.toString(), extracted);
    }

    /**
     * 内容是否达到「大量内联」阈值
     */
    private static boolean isViolating(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int chars = trimmed.length();
        int lines = trimmed.split("\r?\n", -1).length;
        return chars > INLINE_MAX_CHARS || lines > INLINE_MAX_LINES;
    }

    /**
     * 取文件名主干（去扩展名、转小写、非字母数字转下划线）
     */
    private static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "page";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
        return name.isBlank() ? "page" : name;
    }
}
