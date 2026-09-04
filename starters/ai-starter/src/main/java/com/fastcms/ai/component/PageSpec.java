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
package com.fastcms.ai.component;

import java.util.Map;

/**
 * PageSpec：组件化模板生成的唯一事实源（AI 规划输出 / 渲染引擎输入 / 微调 patch 对象）
 *
 * <p>落盘为模板目录下的 {@code _pagespec.json}。网站 = PageSpec（结构）+ 组件库当前版本，
 * 组件升级后对存量 PageSpec 重渲染即继承新组件——这是"弹药持续补充、网站越做越好"的机制基础。</p>
 *
 * <p>{@code pages} 的 key 为 fastcms 页面类型（index / article_list / article / page），
 * 或带 suffix 的专属页面（article_list_products / page_about，见 {@link #suffixedPageKey}）。
 * 值为该页的 section 有序列表。文章详情/列表/单页三类内容页当前由渲染引擎内置模板承载，
 * spec 中可为其提供 navbar/footer 等外围 section。</p>
 *
 * <p>{@code site}（1.1 新增）为站点信息架构：菜单/分类/单页/预览文章。
 * AI 规划时必须先推导信息架构再编排页面，渲染器据此产出全量 _preview_data.json
 * 与每个菜单的专属页面文件。null（1.0 旧数据）时回退内置默认演示数据。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record PageSpec(
        String specVersion,
        String foundation,
        String templateName,
        String siteName,
        String siteType,
        String stylePreset,
        String primaryColor,
        SiteContentSpec site,
        Map<String, PageSpecPage> pages) {

    public static final String SPEC_VERSION = "1.1";

    public static final String PAGE_INDEX = "index";
    public static final String PAGE_ARTICLE_LIST = "article_list";
    public static final String PAGE_ARTICLE = "article";
    public static final String PAGE_PAGE = "page";

    /**
     * 正文占位组件（虚拟组件，不在组件包清单中）：AI 编排内容页时用它标记正文位置，
     * 渲染时替换为该页真实正文骨架（文章列表/文章详情/单页正文）。
     * 使 AI 能为内容页设计"横幅 → 正文 → 转化区"等完整结构。
     */
    public static final String CONTENT_BODY_SECTION = "tw:content-body";

    /**
     * 首选 specVersion（向后兼容旧 spec 时按版本分支）
     */
    public String safeSpecVersion() {
        return specVersion == null || specVersion.isBlank() ? SPEC_VERSION : specVersion;
    }

    public String safeTemplateName() {
        return templateName == null || templateName.isBlank() ? "ai-component-site" : templateName;
    }

    public String safeSiteName() {
        return siteName == null || siteName.isBlank() ? safeTemplateName() : siteName;
    }

    public SiteContentSpec safeSite() {
        return site;
    }

    /**
     * 构造带 suffix 的页面 key：type=article_list/suffix=products → "article_list_products"。
     * type 必须是四类基础页之一，suffix 为空或非法（非字母数字下划线中划线）返回 null。
     */
    public static String suffixedPageKey(String type, String suffix) {
        if (type == null || suffix == null || suffix.isBlank()) {
            return null;
        }
        String t = type.trim();
        String s = suffix.trim();
        if (!t.equals(PAGE_INDEX) && !t.equals(PAGE_ARTICLE_LIST)
                && !t.equals(PAGE_ARTICLE) && !t.equals(PAGE_PAGE)) {
            return null;
        }
        if (!s.matches("[a-zA-Z0-9_-]+")) {
            return null;
        }
        return t + "_" + s;
    }

    /**
     * 页面 key 的基础页类型：index → index；article_list_products → article_list；未知 → null
     */
    public static String basePageKeyOf(String pageKey) {
        if (pageKey == null || pageKey.isBlank()) {
            return null;
        }
        if (pageKey.equals(PAGE_INDEX) || pageKey.equals(PAGE_ARTICLE_LIST)
                || pageKey.equals(PAGE_ARTICLE) || pageKey.equals(PAGE_PAGE)) {
            return pageKey;
        }
        if (pageKey.startsWith(PAGE_ARTICLE_LIST + "_")) {
            return PAGE_ARTICLE_LIST;
        }
        if (pageKey.startsWith(PAGE_ARTICLE + "_")) {
            return PAGE_ARTICLE;
        }
        if (pageKey.startsWith(PAGE_PAGE + "_")) {
            return PAGE_PAGE;
        }
        return null;
    }

    public java.util.List<SectionSpec> sectionsOf(String page) {
        PageSpecPage p = pages == null ? null : pages.get(page);
        if (p == null || p.sections() == null) {
            return java.util.List.of();
        }
        return p.sections();
    }

}
