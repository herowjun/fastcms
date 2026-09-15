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
package com.fastcms.ai.template.htmlimport;

import com.fastcms.ai.component.PageSpec;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部 HTML → 设计稿契约归一化（确定性，零 AI，见 doc/wiki/html-import-to-template-design.md §4/§5.2/§5.3）
 *
 * <p><b>二维判定（§4.1）</b>：结构语义 × 样式体系 → a/b/c 三形态：</p>
 * <ul>
 *     <li><b>a</b> 契约齐（body 顶层 {@code section[data-block]} + :root 5 个必备 token）→ 零转换直通</li>
 *     <li><b>b</b> Tailwind 系 + 有语义（顶层 nav/footer 或顶层 section）→ AI 映射（漂移小、收益大）</li>
 *     <li><b>c</b> 其余（自定义 CSS 系为主）→ 确定性映射保真（§4.2 页型闭环）</li>
 * </ul>
 *
 * <p><b>结构归一化（三形态统一执行，对契约已符合的 HTML 幂等）</b>：
 * 首个非页脚 nav 提升为 body 顶层（converter 的 nav 公共块口径）；顶层 footer 包一层
 * {@code section[data-block=footer]}；其余顶层可视化元素（div/header/main 等）包一层
 * {@code section[data-block=...]}——不改原标签、不重排（除 nav 提升），CSS 选择器不受影响。</p>
 *
 * <p><b>token 别名共存（§5.3）</b>：:root <b>追加</b>缺失的 5 个必备 {@code --c-*}，值按语义启发
 * 映射自原变量（brand/primary→primary 等）；原变量全部保留（别名共存，区块内 var() 引用不断裂）。
 * b 形态组件渲染靠它；c 形态映射出的 navbar/footer 组件也靠它取站点主色（保真一致）。</p>
 *
 * <p><b>资产接线（§5.2）</b>：href/src/srcset 中指向 zip 内已知资源的相对路径，重写为
 * {@code ${ctx()}/import/...}（custom 区块经 FreeMarker 渲染时解析，与既有 design/assets
 * 机制同口径）；外部 js 仅归位不注入。</p>
 *
 * <p><b>c 形态页型闭环（§4.2）</b>：内容页（article_list/article/page）主体区块确定性映射
 * content-body（CMS 文章数据流入口）；nav→navbar、footer→footer 组件；其余 custom 保真。
 * 主体识别失败 → 全 custom + <b>显式报告</b>（降级不失败，不静默）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class HtmlNormalizer {

    private HtmlNormalizer() {
    }

    /** 归一化形态（§4.1 判定矩阵产物） */
    public enum Form {A, B, C}

    /**
     * 归一化结果
     *
     * @param form           判定形态
     * @param html           归一化后的完整 HTML（写 design/&lt;name&gt;.html）
     * @param title          页面标题（&lt;title&gt; 提取，模板元数据/播报用）
     * @param notes          报告标注（冲突/降级/过大块/CMS 未接入等，显式不静默）
     * @param navPresent     是否存在 nav 公共块（converter idx=-1）
     * @param footerPresent  是否存在 footer 公共块（converter idx=-2）
     * @param contentBodyIdx 内容页主体区块序号（c 形态确定性 content-body 映射依据；null=未识别）
     * @param sectionCount   顶层内容区块数（nav/footer 之外）
     */
    public record NormalizeResult(Form form, String html, String title, List<String> notes,
                                 boolean navPresent, boolean footerPresent,
                                 Integer contentBodyIdx, int sectionCount) {
    }

    /** 引用重写器：入参原始 href/src 值，出参重写后的值（未知目标原样返回） */
    public interface AssetUrlRewriter extends Function<String, String> {
    }

    // ==================== 判定所需常量 ====================

    /** 5 个必备 token（DesignHtmlValidator V3 同口径） */
    private static final List<String> REQUIRED_TOKENS = List.of(
            "--c-primary", "--c-accent", "--c-bg", "--c-text", "--c-muted");

    /** :root 变量（--x: value） */
    private static final Pattern ROOT_VAR_PATTERN = Pattern.compile(
            "(-{2}[A-Za-z][\\w-]*)\\s*:\\s*([^;{}]+)");

    /** Tailwind class 识别：响应式前缀 / 通用原子类 / 常用前缀工具类 */
    private static final Pattern TW_CLASS_PATTERN = Pattern.compile(
            "(^|[\\s])((sm|md|lg|xl|2xl):[a-z][a-z0-9-]*|(flex|grid|hidden|container|inline-flex|inline-block|contents)([\\s]|$)"
                    + "|(bg|text|font|rounded|border|shadow|transition|object|overflow|items|justify|content|self|place|gap"
                    + "|space-x|space-y|grid-cols|col-span|row-span|w|h|min-w|min-h|max-w|max-h|p|px|py|pt|pb|pl|pr|m|mx|my|mt|mb|ml|mr)-)");

    /** 结构归一化时跳过的 body 顶层元素（无可视内容） */
    private static final List<String> SKIP_TAGS = List.of(
            "script", "style", "link", "meta", "noscript", "template", "br", "#comment");

    /** token 语义启发映射：必备 token → 原变量名候选关键词（按序匹配） */
    private static final Map<String, List<String>> TOKEN_HINTS = Map.of(
            "--c-primary", List.of("brand", "primary", "main"),
            "--c-accent", List.of("accent", "secondary"),
            "--c-bg", List.of("background", "bg", "surface"),
            "--c-text", List.of("text", "ink", "fg", "foreground"),
            "--c-muted", List.of("muted", "gray", "grey"));

    /** token 映射不到时的兜底色板（与组件库默认视觉接近） */
    private static final Map<String, String> TOKEN_FALLBACKS = Map.of(
            "--c-primary", "#2563eb",
            "--c-accent", "#7c3aed",
            "--c-bg", "#ffffff",
            "--c-text", "#1e293b",
            "--c-muted", "#64748b");

    // ==================== 主入口 ====================

    /**
     * 归一化单页
     *
     * @param pageKey  fastcms 页面 key（决定 c 形态页型策略）
     * @param html     原始 HTML 文本
     * @param rewriter 资产引用重写器（null=不重写）
     */
    public static NormalizeResult normalize(String pageKey, String html, AssetUrlRewriter rewriter) {
        List<String> notes = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Form form = detectForm(doc);
        switch (form) {
            case A -> notes.add("契约齐（data-block 区块 + 5 个必备 token），零转换直通");
            case B -> notes.add("Tailwind 系 + 语义结构，走 AI 组件映射");
            case C -> notes.add("自定义样式体系，确定性映射保真（nav/footer→组件，正文 custom）");
        }

        restructureBody(doc, notes);
        ensureRequiredTokens(doc, notes);
        if (rewriter != null) {
            rewriteAssetRefs(doc, rewriter);
        }

        boolean navPresent = doc.body().children().stream().anyMatch(c -> "nav".equals(c.tagName()));
        boolean footerPresent = doc.body().children().stream()
                .anyMatch(c -> "section".equals(c.tagName()) && c.hasAttr("data-block")
                        && (c.attr("data-block").toLowerCase(Locale.ROOT).contains("footer")
                        || "footer".equals(c.id())));
        List<Element> sections = contentSections(doc);

        Integer contentBodyIdx = null;
        if (form == Form.C) {
            contentBodyIdx = identifyContentBody(pageKey, sections, notes);
        }
        annotateLargeBlocks(sections, notes);

        String title = doc.title() == null ? "" : doc.title().trim();
        return new NormalizeResult(form, doc.outerHtml(), title, notes,
                navPresent, footerPresent, contentBodyIdx, sections.size());
    }

    // ==================== 二维判定（§4.1） ====================

    static Form detectForm(Document doc) {
        // 结构语义列
        boolean contract = hasTopLevelDataBlockSection(doc) && hasAllRequiredTokens(doc);
        if (contract) {
            return Form.A;
        }
        boolean tailwind = isTailwind(doc);
        boolean semantic = hasTopLevelNavFooter(doc) || countTopLevelSections(doc) >= 1;
        if (tailwind && semantic) {
            return Form.B;
        }
        return Form.C;
    }

    private static boolean hasTopLevelDataBlockSection(Document doc) {
        for (Element child : doc.body().children()) {
            if ("section".equals(child.tagName()) && child.hasAttr("data-block")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAllRequiredTokens(Document doc) {
        Map<String, String> vars = firstRootVars(doc);
        if (vars == null || vars.isEmpty()) {
            return false;
        }
        for (String token : REQUIRED_TOKENS) {
            if (!vars.containsKey(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasTopLevelNavFooter(Document doc) {
        for (Element child : doc.body().children()) {
            if ("nav".equals(child.tagName()) || "footer".equals(child.tagName())) {
                return true;
            }
        }
        return false;
    }

    private static int countTopLevelSections(Document doc) {
        int count = 0;
        for (Element child : doc.body().children()) {
            if ("section".equals(child.tagName())) {
                count++;
            }
        }
        return count;
    }

    /** 样式体系：Tailwind class / CDN 引用 → Tailwind 系 */
    static boolean isTailwind(Document doc) {
        for (Element script : doc.select("script[src]")) {
            if (script.attr("src").toLowerCase(Locale.ROOT).contains("tailwind")) {
                return true;
            }
        }
        for (Element link : doc.select("link[href]")) {
            if (link.attr("href").toLowerCase(Locale.ROOT).contains("tailwind")) {
                return true;
            }
        }
        for (Element style : doc.select("style[type=text/tailwindcss]")) {
            return true;
        }
        for (Element el : doc.select("[class]")) {
            String cls = " " + el.attr("class") + " ";
            if (cls.contains("tailwind") || TW_CLASS_PATTERN.matcher(cls).find()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 结构归一化 ====================

    /**
     * body 顶层结构整理（converter 契约：顶层 nav + section[data-block]… + footer section）
     *
     * <p>规则：</p>
     * <ul>
     *     <li>首个<b>非页脚内</b> nav 提升为 body 顶层（插到其顶层祖先之前）——converter 的
     *     selectFirst("nav") 兜底会把嵌套 nav 误当公共块造成双渲染，提升后顶层优先命中</li>
     *     <li>页脚内的 nav 改标签为 div（防 selectFirst 误命中导致全站导航重复；nav 标签选择器
     *     在页脚场景极少见，风险受控并显式报告）</li>
     *     <li>顶层 {@code <footer>} 包一层 {@code section[data-block=footer]}（converter 只认
     *     section 形态的 footer 公共块，原 footer 元素完整保留在内部）</li>
     *     <li>其余顶层可视化元素包一层 {@code section[data-block=...]}；已有 data-block 的
     *     section 原样保留</li>
     *     <li>第二个及以后的顶层 nav 同样包裹（converter 只取首个 nav 为公共块）</li>
     * </ul>
     */
    private static void restructureBody(Document doc, List<String> notes) {
        hoistPrimaryNav(doc, notes);
        renameFooterNavs(doc, notes);

        int navSeen = 0;
        int blockSeq = 0;
        List<Element> children = new ArrayList<>(doc.body().children());
        for (Element child : children) {
            String tag = child.tagName();
            if (SKIP_TAGS.contains(tag)) {
                continue;
            }
            if ("nav".equals(tag)) {
                navSeen++;
                if (navSeen == 1) {
                    continue; // 首个顶层 nav：公共块本体，不包
                }
                wrapSection(child, "nav-" + (navSeen - 1));
                continue;
            }
            if ("section".equals(tag) && child.hasAttr("data-block")) {
                continue; // 契约区块原样保留
            }
            if ("footer".equals(tag)) {
                wrapSection(child, "footer");
                continue;
            }
            if ("section".equals(tag)) {
                child.attr("data-block", deriveBlockName(child, ++blockSeq));
                continue;
            }
            wrapSection(child, deriveBlockName(child, ++blockSeq));
        }
    }

    /** 首个非页脚内 nav 提升为 body 顶层（防 selectFirst 兜底双渲染）；祖先变空壳则移除 */
    private static void hoistPrimaryNav(Document doc, List<String> notes) {
        Element target = null;
        Element topLevelAncestor = null;
        for (Element nav : doc.body().select("nav")) {
            if (insideFooter(nav)) {
                continue;
            }
            if (nav.parent() == doc.body()) {
                return; // 已有顶层 nav，无需提升
            }
            Element p = nav;
            while (p.parent() != null && p.parent() != doc.body()) {
                p = p.parent();
            }
            if (p.parent() != doc.body()) {
                continue;
            }
            target = nav;
            topLevelAncestor = p;
            break;
        }
        if (target == null) {
            return;
        }
        target.remove();
        topLevelAncestor.before(target);
        notes.add("导航位于嵌套容器内，已提升为页面顶层结构（converter 公共块口径）");
        if (isBlankContent(topLevelAncestor)) {
            topLevelAncestor.remove();
        }
    }

    /** 页脚内 nav 改标签为 div（防被 selectFirst("nav") 兜底命中造成全站导航重复渲染） */
    private static void renameFooterNavs(Document doc, List<String> notes) {
        boolean renamed = false;
        for (Element nav : doc.body().select("nav")) {
            if (insideFooter(nav)) {
                nav.tagName("div");
                renamed = true;
            }
        }
        if (renamed) {
            notes.add("页脚内嵌导航已按普通内容处理（不识别为站点导航，避免重复渲染）");
        }
    }

    private static boolean insideFooter(Element el) {
        for (Element p = el.parent(); p != null; p = p.parent()) {
            if ("footer".equals(p.tagName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlankContent(Element el) {
        return el.children().isEmpty() && (el.text() == null || el.text().isBlank());
    }

    /** 包一层 section[data-block=name]（原元素不动，CSS 选择器不受影响） */
    private static void wrapSection(Element child, String name) {
        child.wrap("<section data-block=\"" + name + "\"></section>");
    }

    /** 区块名：id > 首个 class > 标签名+序号 */
    private static String deriveBlockName(Element el, int seq) {
        if (el.hasAttr("id") && !el.attr("id").isBlank()) {
            String id = PageKeyResolver.slugify(el.attr("id"));
            if (!id.isEmpty()) {
                return id;
            }
        }
        if (el.hasClass("") || el.hasAttr("class")) {
            String cls = el.attr("class");
            if (cls != null && !cls.isBlank()) {
                String first = PageKeyResolver.slugify(cls.trim().split("\\s+")[0]);
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return el.tagName() + "-" + seq;
    }

    /** body 顶层内容区块（section[data-block]，footer 区块除外——与 converter 切分同口径） */
    private static List<Element> contentSections(Document doc) {
        List<Element> sections = new ArrayList<>();
        for (Element child : doc.body().children()) {
            if (!"section".equals(child.tagName()) || !child.hasAttr("data-block")) {
                continue;
            }
            if (child.attr("data-block").toLowerCase(Locale.ROOT).contains("footer") || "footer".equals(child.id())) {
                continue;
            }
            sections.add(child);
        }
        return sections;
    }

    // ==================== token 别名共存（§5.3） ====================

    /**
     * :root 追加缺失的 5 个必备 --c-*（别名共存：原变量不动，区块内 var(--brand) 引用不断裂）。
     * b 形态 AI 映射出的组件靠它渲染；c 形态 navbar/footer 组件靠 --c-primary 取站点主色。
     */
    private static void ensureRequiredTokens(Document doc, List<String> notes) {
        Map<String, String> vars = firstRootVars(doc);
        List<String> missing = new ArrayList<>();
        for (String token : REQUIRED_TOKENS) {
            if (vars == null || !vars.containsKey(token)) {
                missing.add(token);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        String fallbackValue = vars != null && !vars.isEmpty()
                ? vars.values().iterator().next().trim() : null;
        StringBuilder append = new StringBuilder();
        for (String token : missing) {
            String value = matchOriginalVar(token, vars);
            if (value == null && fallbackValue != null) {
                value = fallbackValue;
                notes.add(token + " 无语义相近的原变量，已取 :root 首个变量值兜底");
            }
            if (value == null) {
                value = TOKEN_FALLBACKS.get(token);
                notes.add(token + " 无可映射原变量，已用默认色板兜底");
            }
            append.append("  ").append(token).append(": ").append(value).append(";\n");
        }
        if (!injectIntoRoot(doc, append.toString())) {
            Element style = doc.head().appendElement("style");
            style.appendChild(new DataNode(":root {\n" + append + "}"));
        }
    }

    /** 语义启发匹配原变量（token 关键词命中原变量名，首个命中者胜出） */
    private static String matchOriginalVar(String token, Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) {
            return null;
        }
        List<String> hints = TOKEN_HINTS.get(token);
        for (String hint : hints) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (name.equals(token)) {
                    continue; // 理论不会进（missing 才处理）
                }
                // 精确词匹配（防 text 误配 text-muted 之外的弱关联：优先整词包含）
                if (name.contains(hint)) {
                    return entry.getValue().trim();
                }
            }
        }
        return null;
    }

    /** 往首个含 :root 的 style 块内追加变量；找不到返回 false */
    private static boolean injectIntoRoot(Document doc, String declarations) {
        for (Element style : doc.select("style")) {
            String css = style.data();
            int rootIdx = css.indexOf(":root");
            if (rootIdx < 0) {
                continue;
            }
            int braceEnd = css.indexOf('}', rootIdx);
            if (braceEnd < 0) {
                continue;
            }
            String newCss = css.substring(0, braceEnd) + declarations + css.substring(braceEnd);
            style.empty();
            style.appendChild(new DataNode(newCss));
            return true;
        }
        return false;
    }

    /** 首个含 :root 的 style 的全部变量（找不到返回 null） */
    private static Map<String, String> firstRootVars(Document doc) {
        for (Element style : doc.select("style")) {
            String css = style.data();
            int rootIdx = css.indexOf(":root");
            if (rootIdx < 0) {
                continue;
            }
            int braceEnd = css.indexOf('}', rootIdx);
            if (braceEnd < 0) {
                continue;
            }
            java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
            Matcher m = ROOT_VAR_PATTERN.matcher(css.substring(rootIdx, braceEnd));
            while (m.find()) {
                vars.put(m.group(1).trim(), m.group(2).trim());
            }
            if (!vars.isEmpty()) {
                return vars;
            }
        }
        return null;
    }

    // ==================== 资产接线（§5.2） ====================

    /** href/src/srcset 指向 zip 内已知资源的相对路径 → ${ctx()}/import/... */
    private static void rewriteAssetRefs(Document doc, AssetUrlRewriter rewriter) {
        for (Element el : doc.select("[href]")) {
            rewriteAttr(el, "href", rewriter);
        }
        for (Element el : doc.select("[src]")) {
            rewriteAttr(el, "src", rewriter);
        }
        for (Element el : doc.select("[srcset]")) {
            String srcset = el.attr("srcset");
            String[] parts = srcset.split(",");
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            for (String part : parts) {
                String trimmed = part.trim();
                int space = trimmed.indexOf(' ');
                String url = space > 0 ? trimmed.substring(0, space) : trimmed;
                String desc = space > 0 ? trimmed.substring(space) : "";
                String rewritten = rewriter.apply(url);
                if (!rewritten.equals(url)) {
                    changed = true;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(rewritten).append(desc);
            }
            if (changed) {
                el.attr("srcset", sb.toString());
            }
        }
    }

    private static void rewriteAttr(Element el, String attr, AssetUrlRewriter rewriter) {
        String value = el.attr(attr);
        if (value == null || value.isBlank()) {
            return;
        }
        String rewritten = rewriter.apply(value.trim());
        if (!rewritten.equals(value)) {
            el.attr(attr, rewritten);
        }
    }

    // ==================== c 形态页型闭环（§4.2） ====================

    /**
     * 内容页主体区块识别（纯代码）：article_list 取列表卡片结构（li 最多，≥3 张卡片）；
     * article/page 取文本量最大区块。识别失败 → null + 显式报告（CMS 文章未接入）。
     */
    static Integer identifyContentBody(String pageKey, List<Element> sections, List<String> notes) {
        String base = PageSpec.basePageKeyOf(pageKey);
        if (base == null || PageSpec.PAGE_INDEX.equals(base) || sections.isEmpty()) {
            return null;
        }
        boolean listPage = PageSpec.PAGE_ARTICLE_LIST.equals(base);
        int bestIdx = -1;
        long bestScore = -1;
        int bestCards = 0;
        for (int i = 0; i < sections.size(); i++) {
            Element section = sections.get(i);
            int cards = section.select("li").size();
            long textLen = section.text() == null ? 0 : section.text().length();
            long score = listPage ? cards * 100L + textLen : textLen;
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
                bestCards = cards;
            }
        }
        if (bestIdx < 0) {
            return null;
        }
        Element best = sections.get(bestIdx);
        boolean identified = listPage
                ? (bestCards >= 3 || (best.text() != null && best.text().length() >= 200))
                : (best.text() != null && best.text().length() >= 100);
        if (!identified) {
            notes.add("未识别内容主体（区块过小或无列表卡片结构），CMS 文章未接入该页，已整页保真");
            return null;
        }
        return bestIdx;
    }

    /** 过大块标注（§4.3 原则 1：不猜语义不强拆，只报告建议拆分） */
    private static void annotateLargeBlocks(List<Element> sections, List<String> notes) {
        if (sections.size() < 2) {
            return;
        }
        long total = 0;
        long[] sizes = new long[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            sizes[i] = sections.get(i).outerHtml().length();
            total += sizes[i];
        }
        if (total <= 0) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            if (sizes[i] > total * 0.3 + 200) {
                notes.add("区块 " + sections.get(i).attr("data-block")
                        + " 占页面比例过大（建议后续人工拆分为多个区块）");
            }
        }
    }
}
