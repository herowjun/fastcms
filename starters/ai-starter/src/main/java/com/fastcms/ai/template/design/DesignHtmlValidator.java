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
package com.fastcms.ai.template.design;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 设计稿格式校验 V1~V6（设计稿先行模式，见 doc/wiki/ai-template-two-mode-design.md §4.2）
 *
 * <p><b>全确定性</b>（Jsoup 结构解析 + 正则，零 AI、零 token）。校验失败输出的错误文案
 * 即为喂给设计智能体的修正指令（拼入 {@code DesignContractPrompt} 的"上轮输出格式错误"段）。</p>
 *
 * <p>规则与设计契约（{@code DesignContractPrompt.DESIGN_SYSTEM_PROMPT} 可转化性契约 1~5）一一对应：</p>
 * <ul>
 *     <li>V1 文件块齐全：design/&lt;page&gt;.html 存在且含 ≥3 个 {@code <section data-block>} 区块</li>
 *     <li>V2 顶层区块必须是 {@code <section>}（与升级管线"顶层 section"硬约束同口径，
 *     区块级刷新/转化切块的正确性根本保证）</li>
 *     <li>V3 公共 token 变量齐全：--c-primary 等 5 个必备 CSS 变量在 :root 定义</li>
 *     <li>V4 锚点齐全：#nav-toggle / #footer 存在（JS 交互迁移依据）</li>
 *     <li>V5 图片路径合法：仅允许 design/assets/placeholder-*.svg 或 /attachment/ 附件路径，
 *     引用的占位 SVG 文件块必须在场（本轮输出或已落盘）</li>
 *     <li>V6 无 FreeMarker 语法泄漏（&lt;# / ${ ：设计稿是纯 HTML，误写 FTL 会在预览直出时原样暴露）</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class DesignHtmlValidator {

    private DesignHtmlValidator() {
    }

    /**
     * 必备 CSS token（V3）：色彩统一走 :root 变量是转化段提取 tokens.css 的前提
     */
    public static final List<String> REQUIRED_TOKENS = List.of(
            "--c-primary", "--c-accent", "--c-bg", "--c-text", "--c-muted");

    /** 占位图路径模式（V5）：design/assets/placeholder-*.svg */
    private static final Pattern PLACEHOLDER_IMG_PATTERN =
            Pattern.compile("^design/assets/placeholder-[\\w-]+\\.svg$");

    /** 文件块标记（与输出契约一致）：===FILE: path=== */
    static final Pattern FILE_MARKER_PATTERN =
            Pattern.compile("^===\\s*FILE:\\s*(.+?)\\s*===\\s*$", Pattern.MULTILINE);

    /** FTL 语法泄漏（V6） */
    private static final String[] FTL_LEAK_MARKERS = {"<#", "${"};

    /**
     * V1~V6 全量校验
     *
     * @param pageName      页面名（design/&lt;pageName&gt;.html）
     * @param files         本轮 AI 输出的文件块（相对路径 → 内容）
     * @param existingPaths 已落盘文件路径集合（跨页共享的占位 SVG 等；首轮传空集合）
     * @return 错误清单（空 = 通过）；文案即给 AI 的修正指令，逐条带 V 编号
     */
    public static List<String> validate(String pageName, Map<String, String> files, Set<String> existingPaths) {
        List<String> errors = new ArrayList<>();
        String pagePath = "design/" + pageName + ".html";
        String html = files.get(pagePath);

        // V1：页面文件块齐全（修正文案兼容两种输出形态：直出完整文档——系统会自动收编，
        // 或 ===FILE:=== 文件块协议）
        if (html == null || html.isBlank()) {
            errors.add("V1: 缺少页面文件 " + pagePath
                    + "（必须输出该页完整 HTML 文档：<!DOCTYPE html> 起、</html> 止；"
                    + "或以 ===FILE: " + pagePath + "=== 标记输出文件块，不要省略）");
            return errors;
        }

        Document doc = Jsoup.parse(html);

        // V1：≥3 个 section[data-block] 区块
        int blockCount = doc.select("section[data-block]").size();
        if (blockCount < 3) {
            errors.add("V1: 顶层 <section data-block=\"语义名\"> 区块仅 " + blockCount
                    + " 个（至少 3 个，如 hero/features/gallery/footer），请补齐区块");
        }

        // V2：data-block 元素必须是 <section> 且位于 body 顶层
        for (Element el : doc.select("[data-block]")) {
            if (!"section".equals(el.tagName())) {
                errors.add("V2: data-block 区块使用了 <" + el.tagName()
                        + "> 标签，必须改为 <section data-block=\"" + el.attr("data-block") + "\">");
            }
            if (el.parent() != null && !"body".equals(el.parent().tagName())) {
                errors.add("V2: data-block 区块（" + el.attr("data-block") + "）嵌套在 <"
                        + el.parent().tagName() + "> 内，必须移到 body 顶层（顶层区块禁止包裹容器）");
            }
        }

        // V3：必备 token 在 :root 定义（正则扫 :root 块内的变量定义）
        String rootBlock = extractRootBlock(html);
        for (String token : REQUIRED_TOKENS) {
            if (!Pattern.compile(Pattern.quote(token) + "\\s*:[^;]+;").matcher(rootBlock).find()) {
                errors.add("V3: :root 缺少 CSS 变量 " + token + "（色彩必须统一走 :root 变量，禁止区块内硬编码色值）");
            }
        }

        // V4：交互锚点（JS 迁移依据，缺失则转化段无法迁移导航交互）
        if (doc.select("#nav-toggle").isEmpty()) {
            errors.add("V4: 缺少导航锚点 id=\"nav-toggle\"（移动端汉堡菜单按钮必须有该 id）");
        }
        if (doc.select("#footer").isEmpty()) {
            errors.add("V4: 缺少页脚锚点 id=\"footer\"（页脚容器必须有该 id，且各页结构一致）");
        }

        // V5：图片路径合法 + 引用的占位 SVG 在场
        for (Element img : doc.select("img")) {
            String src = img.attr("src").trim();
            if (src.isEmpty()) {
                errors.add("V5: 存在无 src 的 <img>（每张图必须有 src；占位图写 design/assets/placeholder-<语义>.svg 并输出对应 SVG 文件块）");
                continue;
            }
            if (!PLACEHOLDER_IMG_PATTERN.matcher(src).matches() && !src.startsWith("/attachment/")) {
                errors.add("V5: 图片路径非法 " + src + "（只允许 design/assets/placeholder-<语义>.svg 占位图或 /attachment/ 附件路径，"
                        + "禁止外链图床；真实图片意图写在 img 的 data-src-hint 属性里）");
            }
            if (PLACEHOLDER_IMG_PATTERN.matcher(src).matches()
                    && !files.containsKey(src) && !existingPaths.contains(src)) {
                errors.add("V5: 占位图 " + src + " 被引用但未输出对应文件块（每个占位 SVG 必须以 ===FILE: " + src + "=== 输出）");
            }
        }

        // V6：FTL 语法泄漏
        for (String marker : FTL_LEAK_MARKERS) {
            if (html.contains(marker)) {
                errors.add("V6: 检测到 FreeMarker 语法泄漏 \"" + marker
                        + "\"（设计稿是纯 HTML，禁止 <# 指令与 ${} 插值）");
            }
        }

        return errors;
    }

    /**
     * 提取 :root { ... } 块内容（无则空串——V3 会逐 token 报缺失）
     */
    private static String extractRootBlock(String html) {
        int rootIdx = html.indexOf(":root");
        if (rootIdx < 0) {
            return "";
        }
        int braceStart = html.indexOf('{', rootIdx);
        if (braceStart < 0) {
            return "";
        }
        int depth = 0;
        for (int i = braceStart; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(braceStart + 1, i);
                }
            }
        }
        return html.substring(braceStart + 1);
    }
}
