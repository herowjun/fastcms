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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 导入页文件名 → fastcms 页面 key 推导（纯确定性，零 AI，见 doc/wiki/html-import-to-template-design.md §5.1）
 *
 * <p>规则（按序）：</p>
 * <ol>
 *     <li>文件名（去扩展名、小写、slug 化）为 {@code index/home/default} → {@code index} 首页</li>
 *     <li>含 {@code news/blog/article/post/list} 关键词 → {@code article_list}（首个）；
 *     后续列表页用 {@code article_list_<slug>} 区分（保住 CMS 文章流页型）</li>
 *     <li>其余 → {@code page_<slug>} 单页</li>
 * </ol>
 *
 * <p><b>冲突规则</b>：同 pageKey 多个文件 → 后者追加 {@code -2/-3} 序号并报告；
 * 首页缺失（zip 无 index）→ 取字典序第一页为 index 并<b>报告标注</b>（不静默）。</p>
 *
 * <p>pageKey 决定 {@code HtmlNormalizer} 的页型策略（§4.2 页型闭环：
 * 内容页主体区块 → content-body，CMS 文章才流得进去），故本类是 M1 必做件。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class PageKeyResolver {

    private PageKeyResolver() {
    }

    /**
     * 解析结果
     *
     * @param name          设计稿页名（design/&lt;name&gt;.html，slug 化且全站唯一）
     * @param sourceRelPath zip 内相对路径（原始文件，诊断用）
     * @param pageKey       fastcms 页面 key（index / article_list / page_about …）
     */
    public record ResolvedPage(String name, String sourceRelPath, String pageKey) {
    }

    /**
     * 解析产物
     *
     * @param pages 按字典序排列的页面（首页 index 排首位由调用方决定，这里保持文件序）
     * @param notes 需要显式报告的标注（冲突/首页缺失等，绝不静默）
     */
    public record Resolution(List<ResolvedPage> pages, List<String> notes) {
    }

    /** 首页文件名（去扩展名后命中即视为首页） */
    private static final Set<String> INDEX_NAMES = Set.of("index", "home", "default");

    /** 文章列表关键词（含任一即视为列表页） */
    private static final String[] LIST_KEYWORDS = {"news", "blog", "article", "post", "list"};

    /**
     * zip 内 HTML 相对路径清单 → 页面规划
     *
     * @param htmlRelPaths HTML 文件相对路径（zip 根为基准，/ 分隔）
     */
    public static Resolution resolve(List<String> htmlRelPaths) {
        List<String> notes = new ArrayList<>();
        List<ResolvedPage> pages = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        Set<String> usedPageKeys = new LinkedHashSet<>();
        boolean indexAssigned = false;

        List<String> sorted = new ArrayList<>(htmlRelPaths);
        java.util.Collections.sort(sorted);

        for (String relPath : sorted) {
            String fileName = fileNameOf(relPath);
            String slug = slugify(fileName);
            if (slug.isEmpty()) {
                slug = "page";
            }
            // 页名唯一（design/<name>.html 同名会互相覆盖）
            String name = uniqueName(slug, usedNames, notes, relPath);

            String pageKey;
            if (INDEX_NAMES.contains(slug) && !indexAssigned) {
                pageKey = "index";
                indexAssigned = true;
            } else if (INDEX_NAMES.contains(slug)) {
                // 第二个 index 文件（zip 多目录都有 index.html）：降级单页并报告
                pageKey = uniquePageKey("page_" + slug, usedPageKeys);
                notes.add("压缩包内存在多个首页文件，" + relPath + " 已按单页处理（pageKey=" + pageKey + "）");
            } else if (containsAny(slug, LIST_KEYWORDS)) {
                pageKey = usedPageKeys.contains("article_list")
                        ? uniquePageKey("article_list_" + slug, usedPageKeys)
                        : uniquePageKey("article_list", usedPageKeys);
            } else {
                pageKey = uniquePageKey("page_" + slug, usedPageKeys);
            }
            pages.add(new ResolvedPage(name, relPath, pageKey));
        }

        // 首页缺失：取字典序第一页为 index 并显式报告
        if (!indexAssigned && !pages.isEmpty()) {
            ResolvedPage first = pages.get(0);
            pages.set(0, new ResolvedPage(first.name(), first.sourceRelPath(), "index"));
            notes.add("压缩包内无 index 首页，已按文件序取 " + first.sourceRelPath() + " 作为首页（可重新导入包含 index.html 的包纠正）");
        }
        return new Resolution(pages, notes);
    }

    private static String uniquePageKey(String preferred, Set<String> used) {
        if (used.add(preferred)) {
            return preferred;
        }
        for (int i = 2; ; i++) {
            String candidate = preferred + "-" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private static String uniqueName(String slug, Set<String> used, List<String> notes, String relPath) {
        if (used.add(slug)) {
            return slug;
        }
        for (int i = 2; ; i++) {
            String candidate = slug + "-" + i;
            if (used.add(candidate)) {
                notes.add("文件名冲突：" + relPath + " 与其他页面重名，页名已调整为 " + candidate);
                return candidate;
            }
        }
    }

    /** 取路径最后一段文件名（去扩展名基准） */
    static String fileNameOf(String relPath) {
        String normalized = relPath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** slug 化：小写，保留字母数字下划线中划线，其余折叠为中划线 */
    static String slugify(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean lastDash = true; // 开头不产生中划线
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash) {
                sb.append('-');
                lastDash = true;
            }
        }
        String result = sb.toString();
        // 结尾去中划线
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
