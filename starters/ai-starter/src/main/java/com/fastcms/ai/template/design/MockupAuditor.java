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
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template.design;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 设计稿机器审计 A1~A7（设计稿先行模式，见 doc/wiki/ai-template-two-mode-design.md §4.4）
 *
 * <p><b>纯代码解析，零 AI、零 token</b>（D5 决策）：Jsoup 结构遍历 + 正则。审计通过才放行转化；
 * 审计问题作为修正指令回喂设计智能体（审计→修正 loop ≤ max-audit-rounds 轮，由编排器驱动，
 * 仍失败进 AWAITING_CONFIRM 人工兜底）。</p>
 *
 * <table border="1">
 *     <tr><th>#</th><th>检查</th></tr>
 *     <tr><td>A1</td><td>区块可切分性：每页 ≥3 个 body 顶层 {@code <section data-block>}，section 无嵌套</td></tr>
 *     <tr><td>A2</td><td>token 收敛：正文硬编码色值（style 属性 + style 标签，:root 除外）≤ 10 处</td></tr>
 *     <tr><td>A3</td><td>响应式（mobileAdaptive 时）：viewport meta + ≥2 个 @media 断点（768/1024 口径）</td></tr>
 *     <tr><td>A4</td><td>跨页一致性：各页 nav/#footer 结构签名一致（占位页豁免）</td></tr>
 *     <tr><td>A5</td><td>占位图可替换性：所有 img 有非空 data-src-hint（转化段 imageOverrides 依据）</td></tr>
 *     <tr><td>A6</td><td>JS 白名单：仅 Tailwind CDN 外链 + 简单交互内联脚本（黑名单标记 + 体积上限）</td></tr>
 *     <tr><td>A7</td><td>体积护栏：单页 HTML ≤ 60KB</td></tr>
 * </table>
 *
 * <p><b>A6 实现口径说明</b>（与文档字面"5 条契约交互模式正则"的偏差）：
 * AI 书写交互 JS 的自由度高，正向白名单正则误杀率高（修正轮成本 token）；
 * 实现为确定性等价的「黑名单 + 上限」——网络/存储/动态执行/导航/表单提交标记全拒、
 * 内联脚本总量超 4KB 视为复杂交互拒绝、外链脚本仅放行 Tailwind CDN（设计契约明示）。
 * 转化段 Step 4 仅迁移锚点交互，被 A6 放行的简单脚本其余部分照契约丢弃，风险面一致。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class MockupAuditor {

    private MockupAuditor() {
    }

    /**
     * 审计问题（code + 页面 + 修正指令文案）
     *
     * @param code    问题编号（A1~A7）
     * @param page    页面名（design/&lt;page&gt;.html；跨页问题为差异页名集合）
     * @param message 问题描述（拼入审计修正轮提示词，需自带修复指引）
     */
    public record AuditIssue(String code, String page, String message) {

        /**
         * 拼为修正指令行（DesignContractPrompt"上轮机器审计问题"段逐条注入）
         */
        public String toInstruction() {
            return code + "（" + page + "）: " + message;
        }
    }

    /** A2：正文硬编码色值上限 */
    static final int MAX_HARDCODED_COLORS = 10;

    /** A6：单页内联脚本总量上限（超限视为复杂交互） */
    static final int MAX_INLINE_JS_BYTES = 4 * 1024;

    /** A7：单页 HTML 体积上限 */
    static final int MAX_PAGE_BYTES = 60 * 1024;

    /** A2：硬编码色值（hex 3/6/8 位 + rgb/hsl 函数） */
    private static final Pattern COLOR_PATTERN = Pattern.compile(
            "#[0-9a-fA-F]{3}\\b|#[0-9a-fA-F]{6}\\b|#[0-9a-fA-F]{8}\\b|rgba?\\(|hsla?\\(");

    /** A3：@media 断点查询 */
    private static final Pattern MEDIA_QUERY_PATTERN = Pattern.compile("@media\\s*[^{]+\\{");

    /**
     * A6：外链脚本唯一放行源（设计契约明示 Tailwind CDN；其余外链一律拒绝）
     */
    private static final String ALLOWED_SCRIPT_SRC = "cdn.tailwindcss.com";

    /**
     * A6：内联脚本黑名单标记（网络/存储/动态执行/导航/表单/定时器）
     */
    private static final String[] FORBIDDEN_JS_MARKERS = {
            "fetch(", "XMLHttpRequest", "axios", "localStorage", "sessionStorage", "document.cookie",
            "eval(", "new Function", "import(", "WebSocket", "EventSource", "navigator.",
            "window.open", "location.assign", "location.replace", "location.href", "location.reload",
            ".submit(", "new Image(", "setInterval(", "document.write"
    };

    /**
     * 全量审计（A1~A7）
     *
     * @param workDir          会话工作目录（设计稿在 workDir/design/）
     * @param pages            全站页面规划
     * @param placeholderPages 占位页名单（A4 跨页一致性豁免：占位页 nav/footer 为确定性生成，
     *                         与 AI 页不同构是预期而非缺陷）
     * @param mobileAdaptive   是否移动端适配（false 时 A3 整体跳过——桌面端契约不要求断点）
     * @return 问题清单（空 = 审计通过）
     */
    public static List<AuditIssue> audit(Path workDir, List<DesignPagePlanner.PagePlan> pages,
                                         Set<String> placeholderPages, boolean mobileAdaptive) {
        List<AuditIssue> issues = new ArrayList<>();
        Map<String, Document> docs = new LinkedHashMap<>();
        Map<String, String> htmls = new LinkedHashMap<>();

        // 载入全部页面（缺文件按 A1 报——审计对象是落盘产物）
        for (DesignPagePlanner.PagePlan page : pages) {
            Path file = workDir.resolve("design").resolve(page.name() + ".html");
            String html;
            try {
                html = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                issues.add(new AuditIssue("A1", page.name(),
                        "设计稿文件缺失 design/" + page.name() + ".html（" + e.getMessage() + "），请重新输出该页"));
                continue;
            }
            htmls.put(page.name(), html);
            docs.put(page.name(), Jsoup.parse(html));
        }

        for (Map.Entry<String, Document> entry : docs.entrySet()) {
            String pageName = entry.getKey();
            Document doc = entry.getValue();
            auditSlicing(pageName, doc, issues);                        // A1
            auditTokenConvergence(pageName, htmls.get(pageName), doc, issues); // A2
            if (mobileAdaptive) {
                auditResponsive(pageName, htmls.get(pageName), doc, issues); // A3
            }
            auditImageHints(pageName, doc, issues);                     // A5
            auditJsWhitelist(pageName, doc, issues);                    // A6
            auditSizeGuard(pageName, htmls.get(pageName), issues);      // A7
        }
        auditCrossPageConsistency(docs, placeholderPages, issues);      // A4
        return issues;
    }

    // ==================== A1 区块可切分性 ====================

    /**
     * A1：≥3 个 body 顶层 section[data-block]；section 无嵌套（转化段按顶层切块，
     * 嵌套 section 会破坏切块正确性）
     */
    private static void auditSlicing(String pageName, Document doc, List<AuditIssue> issues) {
        int topLevelBlocks = 0;
        List<String> nestedSections = new ArrayList<>();
        for (Element section : doc.select("section")) {
            // 嵌套判定：祖先链上（到 body 为止）存在另一个 section
            Element ancestor = section.parent();
            boolean nested = false;
            while (ancestor != null && !"body".equals(ancestor.tagName())) {
                if ("section".equals(ancestor.tagName())) {
                    nested = true;
                    break;
                }
                ancestor = ancestor.parent();
            }
            if (nested) {
                if (nestedSections.size() < 3) {
                    nestedSections.add(describeElement(section));
                }
                continue;
            }
            if (section.hasAttr("data-block")) {
                topLevelBlocks++;
            }
        }
        if (topLevelBlocks < 3) {
            issues.add(new AuditIssue("A1", pageName,
                    "body 顶层 <section data-block> 区块仅 " + topLevelBlocks
                            + " 个（至少 3 个，如 hero/features/footer），请补齐或把区块提升到 body 顶层"));
        }
        if (!nestedSections.isEmpty()) {
            issues.add(new AuditIssue("A1", pageName,
                    "存在嵌套 section（" + String.join("、", nestedSections)
                            + "，共 " + nestedSections.size() + "+ 处），section 禁止嵌套 section——"
                            + "内层改用 div 并保留视觉结构，或将内容并入外层区块"));
        }
    }

    // ==================== A2 token 收敛 ====================

    /**
     * A2：正文硬编码色值 ≤ {@link #MAX_HARDCODED_COLORS}（:root token 定义除外）。
     * 扫描面：style 属性 + style 标签内容（剥离 :root 块）。
     */
    private static void auditTokenConvergence(String pageName, String html, Document doc, List<AuditIssue> issues) {
        List<String> samples = new ArrayList<>();
        int count = 0;
        for (Element el : doc.select("[style]")) {
            Matcher m = COLOR_PATTERN.matcher(el.attr("style"));
            while (m.find()) {
                count++;
                if (samples.size() < 3) {
                    samples.add(m.group());
                }
            }
        }
        // style 标签内容（剥离 :root 块后扫描）
        for (Element style : doc.select("style")) {
            String css = stripRootBlock(style.data());
            Matcher m = COLOR_PATTERN.matcher(css);
            while (m.find()) {
                count++;
                if (samples.size() < 3) {
                    samples.add(m.group());
                }
            }
        }
        if (count > MAX_HARDCODED_COLORS) {
            issues.add(new AuditIssue("A2", pageName,
                    "正文硬编码色值 " + count + " 处（上限 " + MAX_HARDCODED_COLORS
                            + "，样例：" + String.join("、", samples) + "），色彩必须统一走 :root CSS 变量"
                            + "（var(--c-primary) 等），请将色值收敛为变量引用"));
        }
    }

    // ==================== A3 响应式 ====================

    /**
     * A3：viewport meta 存在 + @media 断点 ≥2（契约口径 768/1024）
     */
    private static void auditResponsive(String pageName, String html, Document doc, List<AuditIssue> issues) {
        if (doc.select("meta[name=viewport]").isEmpty()) {
            issues.add(new AuditIssue("A3", pageName,
                    "缺少 <meta name=\"viewport\">（移动端适配必需），请在 head 补齐"));
        }
        Set<String> queries = new HashSet<>();
        for (Element style : doc.select("style")) {
            Matcher m = MEDIA_QUERY_PATTERN.matcher(style.data());
            while (m.find()) {
                queries.add(m.group().trim());
            }
        }
        if (queries.size() < 2) {
            issues.add(new AuditIssue("A3", pageName,
                    "@media 响应式断点仅 " + queries.size() + " 个（至少 2 个，如 768px 与 1024px），"
                            + "请为移动端/平板补齐断点（含导航折叠为汉堡菜单）"));
        }
    }

    // ==================== A4 跨页一致性 ====================

    /**
     * A4：各页 nav / #footer 结构签名一致（结构 + id + class，不含文本）。
     * 占位页豁免；差异报出全部不一致页名（修正指令指认到页）。
     */
    private static void auditCrossPageConsistency(Map<String, Document> docs, Set<String> placeholderPages,
                                                  List<AuditIssue> issues) {
        if (docs.size() < 2) {
            return;
        }
        // 基准取第一个非占位页
        String refPage = null;
        String refNav = null;
        String refFooter = null;
        Map<String, String> navDiffPages = new LinkedHashMap<>();
        Map<String, String> footerDiffPages = new LinkedHashMap<>();
        for (Map.Entry<String, Document> entry : docs.entrySet()) {
            String pageName = entry.getKey();
            if (placeholderPages != null && placeholderPages.contains(pageName)) {
                continue;
            }
            Document doc = entry.getValue();
            String navSig = signatureOf(doc.selectFirst("nav"));
            String footerSig = signatureOf(doc.selectFirst("#footer"));
            if (navSig == null) {
                navDiffPages.put(pageName, "缺少 nav 元素");
            }
            if (footerSig == null) {
                footerDiffPages.put(pageName, "缺少 #footer 元素");
            }
            if (refPage == null) {
                refPage = pageName;
                refNav = navSig;
                refFooter = footerSig;
                continue;
            }
            if (navSig != null && refNav != null && !refNav.equals(navSig)) {
                navDiffPages.put(pageName, "nav 结构与 " + refPage + " 不一致");
            }
            if (footerSig != null && refFooter != null && !refFooter.equals(footerSig)) {
                footerDiffPages.put(pageName, "footer 结构与 " + refPage + " 不一致");
            }
        }
        if (!navDiffPages.isEmpty()) {
            issues.add(new AuditIssue("A4", String.join("、", navDiffPages.keySet()),
                    "导航结构跨页不一致：" + navDiffPages.values().stream()
                            .map(v -> "[" + v + "]").collect(Collectors.joining())
                            + "（导航与页脚必须在所有页面保持相同结构与 id：#nav-toggle / #footer），"
                            + "请以 " + refPage + " 页为基准统一各页 nav"));
        }
        if (!footerDiffPages.isEmpty()) {
            issues.add(new AuditIssue("A4", String.join("、", footerDiffPages.keySet()),
                    "页脚结构跨页不一致：" + footerDiffPages.values().stream()
                            .map(v -> "[" + v + "]").collect(Collectors.joining())
                            + "（页脚必须为 #footer 且结构跨页一致），"
                            + "请以 " + refPage + " 页为基准统一各页 footer"));
        }
    }

    // ==================== A5 占位图可替换性 ====================

    /**
     * A5：所有 img 有非空 data-src-hint（转化段写入 _preview_data.json imageOverrides 的依据，
     * 缺失则真实图片意图丢失）
     */
    private static void auditImageHints(String pageName, Document doc, List<AuditIssue> issues) {
        List<String> missing = new ArrayList<>();
        for (Element img : doc.select("img")) {
            if (img.attr("data-src-hint").isBlank()) {
                if (missing.size() < 3) {
                    String src = img.attr("src");
                    missing.add(src.isEmpty() ? "<img>（无 src）" : src);
                }
            }
        }
        if (!missing.isEmpty()) {
            issues.add(new AuditIssue("A5", pageName,
                    "存在缺少 data-src-hint 的 <img>（每张图必须写明真实图片意图描述，"
                            + "如 data-src-hint=\"现代化办公室场景\"），请为全部图片补齐该属性"));
        }
    }

    // ==================== A6 JS 白名单 ====================

    /**
     * A6：外链脚本仅放行 Tailwind CDN；内联脚本黑名单标记零容忍 + 总量 ≤ 4KB
     */
    private static void auditJsWhitelist(String pageName, Document doc, List<AuditIssue> issues) {
        for (Element script : doc.select("script[src]")) {
            String src = script.attr("src");
            if (!src.contains(ALLOWED_SCRIPT_SRC)) {
                issues.add(new AuditIssue("A6", pageName,
                        "外链脚本不被允许：" + src + "（仅允许 Tailwind CDN：https://cdn.tailwindcss.com），请移除"));
            }
        }
        int inlineBytes = 0;
        List<String> violations = new ArrayList<>();
        for (Element script : doc.select("script:not([src])")) {
            String js = script.data();
            inlineBytes += js.getBytes(StandardCharsets.UTF_8).length;
            for (String marker : FORBIDDEN_JS_MARKERS) {
                if (js.contains(marker)) {
                    if (violations.size() < 3) {
                        violations.add("含 " + marker.trim() + " 调用");
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            issues.add(new AuditIssue("A6", pageName,
                    "内联脚本含禁止能力：" + String.join("；", violations)
                            + "（交互只允许：汉堡菜单切换 / 锚点平滑滚动 / 简单滚动渐显，"
                            + "网络请求、存储、动态执行、页面导航、表单提交一律禁止），请移除相关脚本"));
        }
        if (inlineBytes > MAX_INLINE_JS_BYTES) {
            issues.add(new AuditIssue("A6", pageName,
                    "内联脚本总量 " + inlineBytes + " 字节（上限 " + MAX_INLINE_JS_BYTES
                            + "），超出简单交互范畴（菜单切换/平滑滚动/滚动渐显的合理实现远小于此），请精简"));
        }
    }

    // ==================== A7 体积护栏 ====================

    private static void auditSizeGuard(String pageName, String html, List<AuditIssue> issues) {
        int bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAGE_BYTES) {
            issues.add(new AuditIssue("A7", pageName,
                    "页面体积 " + String.format(Locale.ROOT, "%.1f", bytes / 1024.0) + "KB（上限 "
                            + (MAX_PAGE_BYTES / 1024) + "KB），请精简（去除重复样式、合并相似区块、"
                            + "精简占位 SVG），避免转化后模板膨胀"));
        }
    }

    // ==================== 工具 ====================

    /**
     * 元素结构签名：tag#id.class…[childCount]{children…}（不含文本——文本跨页本可不同，
     * 契约约束的是结构一致）
     */
    private static String signatureOf(Element el) {
        if (el == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendSignature(el, sb);
        return sb.toString();
    }

    private static void appendSignature(Element el, StringBuilder sb) {
        sb.append(el.tagName());
        if (el.hasAttr("id")) {
            sb.append('#').append(el.attr("id"));
        }
        String classes = el.classNames().stream().sorted().collect(Collectors.joining("."));
        if (!classes.isEmpty()) {
            sb.append('.').append(classes);
        }
        sb.append('[').append(el.children().size()).append(']');
        for (Element child : el.children()) {
            sb.append('{');
            appendSignature(child, sb);
            sb.append('}');
        }
    }

    /**
     * 剥离 CSS 文本中的 :root { ... } 块（token 定义区，A2 不扫）
     */
    private static String stripRootBlock(String css) {
        int rootIdx = css.indexOf(":root");
        if (rootIdx < 0) {
            return css;
        }
        int braceStart = css.indexOf('{', rootIdx);
        if (braceStart < 0) {
            return css;
        }
        int depth = 0;
        for (int i = braceStart; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return css.substring(0, rootIdx) + css.substring(i + 1);
                }
            }
        }
        return css.substring(0, rootIdx);
    }

    /**
     * 元素简述（A1 嵌套报错定位用）：tag#id 或 tag[data-block=名]
     */
    private static String describeElement(Element el) {
        if (el.hasAttr("id")) {
            return "<" + el.tagName() + " id=\"" + el.attr("id") + "\">";
        }
        if (el.hasAttr("data-block")) {
            return "<" + el.tagName() + " data-block=\"" + el.attr("data-block") + "\">";
        }
        return "<" + el.tagName() + ">";
    }
}
