/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 站点信息架构（PageSpec 1.1 新增）：AI 规划的菜单 / 分类 / 单页 / 预览文章
 *
 * <p>用户只输入"创新土鸡官网"这类极简需求时，AI 必须先完成信息架构推导：
 * 主导航有什么栏目、哪些栏目是文章分类（article_list）、哪些是单页（page），
 * 每个栏目带什么 suffix、预览用哪些主题文章——这些是"生成跟用户输入内容相关的
 * 所有页面"的事实源。</p>
 *
 * <p>渲染器按本结构产出：</p>
 * <ul>
 *     <li>{@code _preview_data.json} 的 menus/categories/singlePages/articles/seo 全量段落</li>
 *     <li>每个带 suffix 的条目对应 {@code {type}_{suffix}.html} 页面文件
 *         （spec.pages 未显式编排时克隆基础页骨架）</li>
 * </ul>
 *
 * <p>spec 无 site（1.0 旧数据）时全部回退内置默认，行为不变。</p>
 *
 * @param menus      主导航（最多 8 个顶级，两级；首页之外每个菜单须有 type + suffix）
 * @param categories 文章分类（对应 article_list 菜单与分类列表，最多 10 个）
 * @param singlePages 单页（对应 page 菜单，最多 8 个）
 * @param articles   预览文章（标题 + 摘要，须全部贴合站点主题，8~12 篇）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record SiteContentSpec(
        List<NavItem> menus,
        List<CatalogItem> categories,
        List<CatalogItem> singlePages,
        List<PreviewArticle> articles) {

    public List<NavItem> safeMenus() {
        return menus == null ? List.of() : menus;
    }

    public List<CatalogItem> safeCategories() {
        return categories == null ? List.of() : categories;
    }

    public List<CatalogItem> safeSinglePages() {
        return singlePages == null ? List.of() : singlePages;
    }

    public List<PreviewArticle> safeArticles() {
        return articles == null ? List.of() : articles;
    }

    /**
     * 收集信息架构中全部带 suffix 的页面 key（如 article_list_products / page_about），
     * 渲染器据此保证每个菜单/分类/单页都有对应模板文件
     */
    public List<String> allSuffixedPageKeys() {
        List<String> keys = new ArrayList<>();
        collectMenuKeys(menus, keys);
        for (CatalogItem item : safeCategories()) {
            addKey(keys, "article_list", item.suffix());
        }
        for (CatalogItem item : safeSinglePages()) {
            addKey(keys, "page", item.suffix());
        }
        return keys;
    }

    private void collectMenuKeys(List<NavItem> items, List<String> keys) {
        for (NavItem item : items == null ? List.<NavItem>of() : items) {
            if (!NavItem.TYPE_INDEX.equals(item.safeType())) {
                addKey(keys, item.safeType(), item.suffix());
            }
            collectMenuKeys(item.safeChildren(), keys);
        }
    }

    private void addKey(List<String> keys, String type, String suffix) {
        String key = PageSpec.suffixedPageKey(type, suffix);
        if (key != null && !keys.contains(key)) {
            keys.add(key);
        }
    }

    /**
     * 导航项：顶级菜单或子菜单
     *
     * @param name    菜单名（中文，2~6 字）
     * @param type    index / article_list / article / page（菜单几乎不会直接指向 article）
     * @param suffix  对应模板文件 {type}_{suffix}.html 与预览 URL；type=index 时忽略
     * @param children 二级菜单（仅顶级可带，最多 6 个）
     */
    public record NavItem(String name, String type, String suffix, List<NavItem> children) {

        public static final String TYPE_INDEX = "index";
        public static final String TYPE_ARTICLE_LIST = "article_list";
        public static final String TYPE_ARTICLE = "article";
        public static final String TYPE_PAGE = "page";

        public String safeType() {
            return type == null || type.isBlank() ? TYPE_ARTICLE_LIST : type;
        }

        public List<NavItem> safeChildren() {
            return children == null ? List.of() : children;
        }
    }

    /**
     * 分类 / 单页条目
     *
     * @param title  标题（中文，贴合站点主题）
     * @param suffix 对应模板文件 {type}_{suffix}.html；空表示用该类型默认页
     */
    public record CatalogItem(String title, String suffix) {
    }

    /**
     * 预览文章：标题 + 摘要，全部贴合站点主题（如土鸡站写散养环境/喂养标准/品类，
     * 严禁出现与主题无关的通用技术文章）
     *
     * @param title    文章标题（≤30 字）
     * @param summary  文章摘要（≤80 字，具体有信息量）
     */
    public record PreviewArticle(String title, String summary) {
    }

}