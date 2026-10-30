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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站点结构提取器（S1 站点分析的上下文裁剪）
 *
 * <p><b>要解决的问题</b>：站点分析要把上传的首页 HTML 喂给模型归纳信息架构。
 * 首页 HTML 常见 30~90KB，但归纳 IA 真正需要的信息只有三类：
 * ① 文档元信息（标题/描述）② 导航菜单项 ③ 标题层级与链接清单。
 * 正文段落、样式、脚本、图片对「有哪些栏目」几乎没有贡献，
 * 却把上下文撑到 3 万字符——实测这一项就是 prefill 的大头。</p>
 *
 * <p><b>做法</b>：确定性地抽出结构摘要（元信息 + 导航 + 标题层级 + 链接清单 + 正文纯文本节选），
 * 输出通常只有 1~3KB。摘要过短（抽不出东西）时由调用方回退到截断后的全文。</p>
 *
 * <p><b>为什么不用正则解析 HTML</b>：这里只需要「粗粒度结构」，不需要严格 DOM。
 * 站点结构分析本身是容错的（解析失败会降级为「仅标准页」），
 * 用正则避免引入 HTML 解析器依赖，也避免在畸形 HTML 上抛异常。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class SiteStructureExtractor {

    /**
     * 链接清单上限（超出部分只保留数量统计）
     */
    private static final int MAX_LINKS = 120;

    /**
     * 标题层级上限
     */
    private static final int MAX_HEADINGS = 60;

    /**
     * 正文纯文本节选上限（字符）
     */
    private static final int MAX_TEXT_EXCERPT = 2000;

    /**
     * 导航项上限
     */
    private static final int MAX_NAV_ITEMS = 80;

    /**
     * 摘要过短阈值（低于此值认为抽取失败，调用方应回退到全文）
     */
    public static final int MIN_USEFUL_LENGTH = 200;

    /** 整块剔除的标签（脚本、样式、内联 SVG、iframe 等） */
    private static final Pattern[] DROP_BLOCKS = {
            Pattern.compile("<script\\b[^>]*>.*?</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("<style\\b[^>]*>.*?</style\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("<noscript\\b[^>]*>.*?</noscript\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("<svg\\b[^>]*>.*?</svg\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("<iframe\\b[^>]*>.*?</iframe\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("<!--.*?-->", Pattern.DOTALL),
            Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE)
    };

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile(
            "<meta[^>]+name\\s*=\\s*[\"']?description[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern META_CONTENT_PATTERN = Pattern.compile(
            "content\\s*=\\s*[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern NAV_PATTERN =
            Pattern.compile("<(nav|header)\\b[^>]*>(.*?)</\\1\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** class/id 含导航语义的元素块 */
    private static final Pattern NAV_SEMANTIC_PATTERN = Pattern.compile(
            "<(div|ul|ol)\\b[^>]*(?:class|id)\\s*=\\s*[\"'][^\"']*"
                    + "(?:nav|navbar|menu|header|mhead|topbar)[^\"']*[\"'][^>]*>(.*?)</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern HEADING_PATTERN =
            Pattern.compile("<h([1-3])\\b[^>]*>(.*?)</h\\1\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ANCHOR_PATTERN =
            Pattern.compile("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private SiteStructureExtractor() {
    }

    /**
     * 抽取站点结构摘要
     *
     * @param html 原始 HTML（可空）
     * @return 结构摘要文本；输入为空时返回空串
     */
    public static String extract(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String cleaned = stripNoise(html);

        StringBuilder sb = new StringBuilder();

        // ① 文档元信息
        Map<String, String> meta = extractMeta(cleaned);
        if (!meta.isEmpty()) {
            sb.append("【文档元信息】\n");
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
            sb.append('\n');
        }

        // ② 导航菜单项（信息架构的黄金来源）
        List<String> navItems = extractNavItems(cleaned);
        if (!navItems.isEmpty()) {
            sb.append("【导航菜单项】\n");
            for (String item : navItems) {
                sb.append("- ").append(item).append('\n');
            }
            sb.append('\n');
        }

        // ③ 标题层级
        List<String> headings = extractHeadings(cleaned);
        if (!headings.isEmpty()) {
            sb.append("【页面标题层级】\n");
            for (String heading : headings) {
                sb.append(heading).append('\n');
            }
            sb.append('\n');
        }

        // ④ 站内链接清单
        Map<String, String> links = extractLinks(cleaned);
        if (!links.isEmpty()) {
            sb.append("【站内链接清单】（共 ").append(links.size()).append(" 个）\n");
            int i = 0;
            for (Map.Entry<String, String> entry : links.entrySet()) {
                if (i++ >= MAX_LINKS) {
                    sb.append("…（其余 ").append(links.size() - MAX_LINKS).append(" 个已省略）\n");
                    break;
                }
                sb.append(entry.getKey()).append(" → ").append(entry.getValue()).append('\n');
            }
            sb.append('\n');
        }

        // ⑤ 正文纯文本节选（判断栏目页型的内容线索）
        String excerpt = extractTextExcerpt(cleaned);
        if (!excerpt.isBlank()) {
            sb.append("【正文文字节选】\n").append(excerpt).append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 剔除脚本/样式/注释等噪声块
     */
    private static String stripNoise(String html) {
        String result = html;
        for (Pattern pattern : DROP_BLOCKS) {
            result = pattern.matcher(result).replaceAll(" ");
        }
        return result;
    }

    /**
     * 抽取文档元信息（title / description）
     */
    private static Map<String, String> extractMeta(String html) {
        Map<String, String> meta = new LinkedHashMap<>();
        Matcher titleMatcher = TITLE_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            String title = collapse(titleMatcher.group(1));
            if (!title.isBlank()) {
                meta.put("title", title);
            }
        }
        Matcher descMatcher = META_DESCRIPTION_PATTERN.matcher(html);
        if (descMatcher.find()) {
            Matcher contentMatcher = META_CONTENT_PATTERN.matcher(descMatcher.group());
            if (contentMatcher.find()) {
                String description = collapse(contentMatcher.group(1));
                if (!description.isBlank()) {
                    meta.put("description", description);
                }
            }
        }
        return meta;
    }

    /**
     * 抽取导航菜单项
     */
    private static List<String> extractNavItems(String html) {
        Set<String> items = new LinkedHashSet<>();
        List<Matcher> matchers = List.of(NAV_PATTERN.matcher(html), NAV_SEMANTIC_PATTERN.matcher(html));
        for (Matcher matcher : matchers) {
            while (matcher.find() && items.size() < MAX_NAV_ITEMS) {
                String block = matcher.group(2);
                if (block == null) {
                    continue;
                }
                Matcher anchorMatcher = ANCHOR_PATTERN.matcher(block);
                while (anchorMatcher.find() && items.size() < MAX_NAV_ITEMS) {
                    String href = anchorMatcher.group(1) == null ? "" : anchorMatcher.group(1).trim();
                    // 外链与协议链接不是本站栏目，必须排除（否则模型会凭空造一个"外部链接"栏目）
                    if (isExternalHref(href)) {
                        continue;
                    }
                    String text = collapse(TAG_PATTERN.matcher(anchorMatcher.group(2)).replaceAll(" "));
                    if (text.isBlank()) {
                        // 无锚文本的菜单项（如纯图标导航）：退而用 href 末段
                        text = href.isBlank() ? "" : shorten(href);
                    }
                    if (!text.isBlank()) {
                        items.add(href.isBlank() ? text : text + "（" + shorten(href) + "）");
                    }
                }
            }
        }
        return new ArrayList<>(items);
    }

    /**
     * 是否外链 / 非站内链接（对信息架构无意义，且会导致模型凭空造栏目）
     */
    private static boolean isExternalHref(String href) {
        if (href == null || href.isBlank()) {
            return true;
        }
        String lower = href.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("//") || lower.startsWith("mailto:")
                || lower.startsWith("tel:") || lower.startsWith("javascript:")
                || lower.startsWith("#");
    }

    /**
     * 抽取标题层级（保留 h1/h2/h3 的层级缩进）
     */
    private static List<String> extractHeadings(String html) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(html);
        while (matcher.find() && headings.size() < MAX_HEADINGS) {
            int level = matcher.group(1).charAt(0) - '0';
            String text = collapse(TAG_PATTERN.matcher(matcher.group(2)).replaceAll(" "));
            if (!text.isBlank()) {
                headings.add("  ".repeat(Math.max(0, level - 1)) + "h" + level + ": " + text);
            }
        }
        return headings;
    }

    /**
     * 抽取站内链接（按 href 去重，保留首个锚文本）
     */
    private static Map<String, String> extractLinks(String html) {
        Map<String, String> links = new LinkedHashMap<>();
        Matcher matcher = ANCHOR_PATTERN.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (href.isBlank()) {
                continue;
            }
            String lower = href.toLowerCase(Locale.ROOT);
            // 外链与锚点对信息架构无用
            if (lower.startsWith("http://") || lower.startsWith("https://")
                    || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("javascript:")) {
                continue;
            }
            if (!links.containsKey(href)) {
                String text = collapse(TAG_PATTERN.matcher(matcher.group(2)).replaceAll(" "));
                links.put(href, text.isBlank() ? "(无锚文本)" : text);
            }
        }
        return links;
    }

    /**
     * 抽取正文纯文本节选
     */
    private static String extractTextExcerpt(String html) {
        String text = TAG_PATTERN.matcher(html).replaceAll(" ");
        text = collapse(text);
        if (text.length() > MAX_TEXT_EXCERPT) {
            text = text.substring(0, MAX_TEXT_EXCERPT) + "…";
        }
        return text;
    }

    /**
     * 折叠空白为单个空格并去除首尾空白
     */
    private static String collapse(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    /**
     * 缩短链接文本（只保留末段，长路径对 IA 无意义）
     */
    private static String shorten(String href) {
        String value = href;
        int query = value.indexOf('?');
        if (query > 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash > 0) {
            value = value.substring(0, hash);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash < value.length() - 1) {
            value = value.substring(slash + 1);
        }
        return value.length() > 40 ? value.substring(value.length() - 40) : value;
    }
}
