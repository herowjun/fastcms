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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设计稿页面规划器（设计稿先行模式，见 doc/wiki/ai-template-two-mode-design.md §4.1）
 *
 * <p><b>确定性规则，不走 AI</b>：需求文本 → 页面清单。基础 4 页（index/about/services/contact），
 * 需求含资讯类关键词追加新闻列表页；需求含产品枚举时提取产品名注入 services 页定位
 * （多产品拆分受限于中文产品名无法确定性转英文 slug，统一落在 services 单页内分区呈现，
 * 由设计智能体按定位自行组织分区）。</p>
 *
 * <p>页面规划同时是转化段（S3）的输入：{@link PagePlan#fastcmsPageKey()} 声明该设计页
 * 对应的 fastcms 模板页（index / page_about / article_list），template.properties 的
 * pages 清单由此生成。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class DesignPagePlanner {

    private DesignPagePlanner() {
    }

    /**
     * 页面规划项
     *
     * @param name          设计稿文件名（design/&lt;name&gt;.html，如 index/about/services/contact）
     * @param title         页面中文标题（导航菜单与 template.properties 用）
     * @param description   页面定位一句话（拼入设计提示词，告诉 AI 该页设计什么）
     * @param fastcmsPageKey 转化目标页 key（index / page_about / page_services / page_contact / article_list）
     */
    public record PagePlan(String name, String title, String description, String fastcmsPageKey) {
    }

    /** 资讯类关键词：命中则在基础 4 页外追加新闻列表页 */
    private static final String[] NEWS_KEYWORDS = {"新闻", "资讯", "博客", "动态", "文章", "行业媒体"};

    /** 产品枚举模式：如 "产品包括 A、B、C" / "主要服务：A，B" */
    private static final Pattern PRODUCT_ENUM_PATTERN =
            Pattern.compile("(?:产品|服务)(?:包括|包含|主要有|有|：|:)\\s*([^。；;\\n]+)");

    /**
     * 需求 → 页面规划
     *
     * <p>规则（按序）：</p>
     * <ol>
     *     <li>基础 4 页：index（首页）/ about（关于我们）/ services（服务与产品）/ contact（联系我们）</li>
     *     <li>需求命中资讯类关键词 → 追加 news（新闻动态，article_list 页）</li>
     *     <li>需求含产品枚举 → 提取产品名注入 services 页定位</li>
     * </ol>
     */
    public static List<PagePlan> plan(String requirement) {
        String req = requirement == null ? "" : requirement;

        List<PagePlan> pages = new ArrayList<>();
        pages.add(new PagePlan("index", "首页",
                "站点门面：品牌主张 + 核心价值/亮点 + 行动召唤（CTA），引导访客进入关键页面",
                PageSpec.PAGE_INDEX));
        pages.add(new PagePlan("about", "关于我们",
                "建立信任：公司故事/团队/资质成就，语气真诚，避免营销腔",
                PageSpec.suffixedPageKey(PageSpec.PAGE_PAGE, "about")));
        pages.add(new PagePlan("services", "服务与产品",
                servicesDescription(req),
                PageSpec.suffixedPageKey(PageSpec.PAGE_PAGE, "services")));
        pages.add(new PagePlan("contact", "联系我们",
                "转化收口：联系方式 + 表单/地图占位 + 明确的下一步指引",
                PageSpec.suffixedPageKey(PageSpec.PAGE_PAGE, "contact")));

        if (containsAny(req, NEWS_KEYWORDS)) {
            pages.add(new PagePlan("news", "新闻动态",
                    "资讯列表：文章卡片流（标题/摘要/日期/封面占位），支持分页视觉",
                    PageSpec.PAGE_ARTICLE_LIST));
        }
        return pages;
    }

    /**
     * services 页定位：需求含产品枚举时提取产品名（确定性正则，非 AI），
     * 未命中用通用定位
     */
    private static String servicesDescription(String requirement) {
        List<String> products = extractProducts(requirement);
        if (products.isEmpty()) {
            return "业务呈现：核心服务/产品的价值说明与卖点分区，每个产品一个视觉重点";
        }
        return "业务呈现：重点呈现以下产品/服务（每个产品独立分区：名称 + 一句话价值 + 配图占位）——"
                + String.join("、", products);
    }

    /**
     * 从需求中提取产品/服务枚举（确定性）："产品包括 A、B、C" → [A, B, C]。
     * 单项超长（>12 字，疑似句子而非产品名）或提取数 >6（疑似误切）时放弃提取。
     */
    static List<String> extractProducts(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return List.of();
        }
        Matcher matcher = PRODUCT_ENUM_PATTERN.matcher(requirement);
        while (matcher.find()) {
            String segment = matcher.group(1).trim();
            if (segment.length() < 3) {
                continue;
            }
            String[] parts = segment.split("[、，,/]|和|与");
            List<String> products = new ArrayList<>();
            for (String part : parts) {
                String item = part.trim().replaceAll("[。；;，,、\\s]+$", "").trim();
                if (item.isEmpty() || item.length() > 12) {
                    continue;
                }
                products.add(item);
            }
            if (products.size() >= 2 && products.size() <= 6) {
                return products;
            }
        }
        return List.of();
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
