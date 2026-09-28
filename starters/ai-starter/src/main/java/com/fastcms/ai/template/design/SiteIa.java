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

import com.fastcms.ai.component.PageSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 站点信息架构（AI 读上传 HTML 梳理出的整站栏目清单）
 *
 * <p>这是导入型会话（{@code createMode=import}）页面来源的第二条通道，与
 * {@link DesignPagePlanner}（需求文本规则规划）并列：上传物只有一个首页 {@code index.html} 时，
 * 其余页面不由需求文本推出，而由 AI 读懂这个站推出来——<b>第一步提取顶部导航栏</b>，
 * 再结合页面正文判断每个栏目在 fastcms 里应建成什么页型。</p>
 *
 * <p><b>页型只映射到 fastcms 的两类内容页</b>（对齐 {@link PageSpec} 的页面 key 体系）：</p>
 * <ul>
 *     <li>{@link #PAGE_TYPE_ARTICLE_LIST}：资讯/新闻/博客/公告/案例 这类会持续新增文章的栏目
 *     —— 全站复用<b>唯一一份</b> {@code article_list} 模板（不新增设计页），栏目差异由菜单指向体现</li>
 *     <li>{@link #PAGE_TYPE_PAGE}：关于我们/服务与产品/解决方案/联系 这类静态栏目
 *     —— 各自一份 {@code page_<slug>} 模板（新增设计页，由 AI 按首页设计语言推导内容）</li>
 * </ul>
 *
 * <p><b>首页数据区</b>：上传的首页是保真搬运的静态 HTML，后台发布内容不会出现在首页。
 * 故本结构额外携带 {@link #homeSectionHeading()}/{@link #homeSectionIntro()}——转化链路的
 * 确定性插桩会据此在首页插入一个 {@code data-block="article-list"} 数据区，
 * 由 {@code MockupConverter} 的语义映射接成 {@code tw:article-list} 组件（标签驱动）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record SiteIa(String siteName, String summary,
                     String homeSectionHeading, String homeSectionIntro,
                     List<SitePage> pages) {

    /** 页型：静态栏目（每个栏目一份独立模板） */
    public static final String PAGE_TYPE_PAGE = "page";

    /** 页型：文章列表栏目（全站复用一份 article_list 模板） */
    public static final String PAGE_TYPE_ARTICLE_LIST = "article_list";

    /** 栏目页数量上限（防模型失控；超出截断并报告，不静默） */
    public static final int MAX_COLUMN_PAGES = 12;

    /** 首页数据区默认标题（AI 未给或解析失败时兜底） */
    public static final String DEFAULT_HOME_SECTION_HEADING = "最新文章";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 栏目页（一个导航栏目 → 一条页面规划）
     *
     * @param title       栏目中文名（导航菜单文案，也是设计页标题）
     * @param pageType    页型（{@link #PAGE_TYPE_ARTICLE_LIST} / {@link #PAGE_TYPE_PAGE}）
     * @param slug        页面 slug（ASCII：模板文件名 page_&lt;slug&gt;.html 与菜单 URL 用）
     * @param description 该栏目要呈现什么内容（拼入设计提示词，指导 AI 推导页面内容；可空）
     */
    public record SitePage(String title, String pageType, String slug, String description) {
        public boolean isArticleList() {
            return PAGE_TYPE_ARTICLE_LIST.equals(pageType);
        }
    }

    /** 需要独立设计稿/模板的栏目：静态栏目。列表型栏目复用全站唯一的 article_list 模板，不产生设计页 */
    public List<SitePage> templatePages() {
        if (pages == null) {
            return List.of();
        }
        return pages.stream().filter(p -> !p.isArticleList()).toList();
    }

    /** 导航菜单里的栏目清单（含列表型：菜单指向 article_list 模板，故菜单项比设计页多） */
    public List<SitePage> menuItems() {
        return pages == null ? List.of() : List.copyOf(pages);
    }

    /** 首页数据区标题（AI 未给时兜底 {@link #DEFAULT_HOME_SECTION_HEADING}） */
    public String safeHomeSectionHeading() {
        return homeSectionHeading == null || homeSectionHeading.isBlank()
                ? DEFAULT_HOME_SECTION_HEADING : homeSectionHeading.trim();
    }

    // ==================== 解析（纯函数，可单测） ====================

    /**
     * 解析 AI 输出为站点 IA
     *
     * <p><b>健壮性</b>：容忍 markdown 围栏与前后解释文字（截取首个 {@code {} 到末个 {@code }}）；
     * 页型非法一律归 {@link #PAGE_TYPE_PAGE}；slug 非法/缺失由序号退化 {@code nav-N}；
     * slug 与保留名（index/article_list/article/page）或彼此冲突时追加 {@code -2/-3}；
     * 栏目数超 {@link #MAX_COLUMN_PAGES} 截断。任一必需字段缺失视为解析失败返回 {@code null}
     * （调用方降级为"仅标准页"，不阻断导入）。</p>
     *
     * @param raw           模型原始输出
     * @param reservedSlugs 已被占用的设计页名（标准页 index/article_list/article/page），避免重名覆盖
     * @return 站点 IA；解析失败返回 null
     */
    public static SiteIa parse(String raw, Set<String> reservedSlugs) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode pagesNode = root.get("pages");
        if (pagesNode == null || !pagesNode.isArray()) {
            return null;
        }

        Set<String> used = new LinkedHashSet<>(reservedSlugs == null ? Set.of() : reservedSlugs);
        List<SitePage> pages = new ArrayList<>();
        int seq = 0;
        for (JsonNode item : pagesNode) {
            if (pages.size() >= MAX_COLUMN_PAGES) {
                break;
            }
            if (item == null || !item.isObject()) {
                continue;
            }
            String title = text(item, "title");
            if (title == null) {
                continue;
            }
            seq++;
            String pageType = PAGE_TYPE_ARTICLE_LIST.equals(text(item, "pageType"))
                    ? PAGE_TYPE_ARTICLE_LIST : PAGE_TYPE_PAGE;
            String slug = uniqueSlug(slugify(text(item, "slug")), used, seq);
            pages.add(new SitePage(title, pageType, slug, text(item, "description")));
        }
        if (pages.isEmpty()) {
            return null;
        }
        return new SiteIa(text(root, "siteName"), text(root, "summary"),
                text(root, "homeSectionHeading"), text(root, "homeSectionIntro"), List.copyOf(pages));
    }

    // ==================== 持久化（design/site-ia.json，转化段消费） ====================

    /** 序列化为 JSON（字段与 AI 输出契约一致，便于人读与二次解析） */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        if (siteName != null) {
            root.put("siteName", siteName);
        }
        if (summary != null) {
            root.put("summary", summary);
        }
        if (homeSectionHeading != null) {
            root.put("homeSectionHeading", homeSectionHeading);
        }
        if (homeSectionIntro != null) {
            root.put("homeSectionIntro", homeSectionIntro);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (SitePage p : menuItems()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", p.title());
            item.put("pageType", p.pageType());
            item.put("slug", p.slug());
            if (p.description() != null) {
                item.put("description", p.description());
            }
            items.add(item);
        }
        root.put("pages", items);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 从 site-ia.json 读回
     *
     * <p>走与 {@link #parse} 同一条解析路径（格式同构），slug 已净化过故为幂等；
     * 保留名传空集——读回时不做二次重命名，避免文件与内存态漂移。</p>
     */
    public static SiteIa fromJson(String json) {
        return parse(json, Set.of());
    }

    // ==================== 内部工具 ====================

    /** 截取首个 { 到末个 }（容忍 markdown 围栏与前后解释文字） */
    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String s = value.asString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * slug 化：小写，保留字母数字下划线中划线，其余折叠为中划线
     *
     * <p>与 {@code PageSpecValidator} 的 SUFFIX_PATTERN（{@code [a-zA-Z0-9_-]+}）同口径——
     * 中文栏目的 slug 由 AI 给英文/拼音，拿不到英文时退化为 {@code nav-N}，
     * 中文<b>不能</b>直接当文件名（非法 suffix 会让该页被校验器丢弃）。</p>
     */
    static String slugify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean lastDash = true;
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash) {
                sb.append('-');
                lastDash = true;
            }
        }
        while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** slug 唯一化：非法/空 → {@code nav-N}；与已用名冲突 → 追加 -2/-3 */
    private static String uniqueSlug(String base, Set<String> used, int seq) {
        String candidate = base.isEmpty() ? "nav-" + seq : base;
        if (used.add(candidate)) {
            return candidate;
        }
        for (int i = 2; ; i++) {
            String next = candidate + "-" + i;
            if (used.add(next)) {
                return next;
            }
        }
    }
}
