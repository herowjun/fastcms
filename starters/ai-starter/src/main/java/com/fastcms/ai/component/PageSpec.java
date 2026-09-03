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
 * 值为该页的 section 有序列表。文章详情/列表/单页三类内容页当前由渲染引擎内置模板承载，
 * spec 中可为其提供 navbar/footer 等外围 section。</p>
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
        Map<String, PageSpecPage> pages) {

    public static final String SPEC_VERSION = "1.0";

    public static final String PAGE_INDEX = "index";
    public static final String PAGE_ARTICLE_LIST = "article_list";
    public static final String PAGE_ARTICLE = "article";
    public static final String PAGE_PAGE = "page";

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

    public java.util.List<SectionSpec> sectionsOf(String page) {
        PageSpecPage p = pages == null ? null : pages.get(page);
        if (p == null || p.sections() == null) {
            return java.util.List.of();
        }
        return p.sections();
    }

}
