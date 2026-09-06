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
package com.fastcms.ai.component;

import java.util.List;

/**
 * 图片资产解析记录（PageSpec 1.2 新增）：media 槽位 {@code search:} 引用的解析结果
 *
 * <p>media 槽位协议（AI 规划提示词约定）：</p>
 * <ul>
 *     <li>{@code "search:关键词"}——AI 表达图片意图（如 {@code "search:散养土鸡 生态农场"}），
 *         渲染前由 {@code AttachmentImageSearcher} 从附件库搜索匹配图片，命中则替换为附件引用；
 *         未命中时回退演示图。AI 不自行编造图片 URL</li>
 *     <li>普通 URL（{@code http(s)://} 或 {@code /} 开头）——直接使用（微调沿用已解析图片时的形态）</li>
 *     <li>空/缺省——组件模板自带的占位兜底（渐变块/图标）</li>
 * </ul>
 *
 * <p>本记录由系统在解析后回写到 {@code PageSpec.imageAssets} 并落盘 {@code _pagespec.json}，
 * 用于：①图片来源追溯（附件库 or 演示图）；②微调往返中沿用旧图时保留附件关联；
 * ③AI 调整页面（S4）展示图片可执行的操作（换图/修图）。AI 不产出本结构。</p>
 *
 * @param search        搜索关键词（media 槽位值去掉 {@code search:} 前缀后的部分）
 * @param resolved      解析结果图片引用（附件访问路径或演示图路径）
 * @param source        来源：{@link #SOURCE_ATTACHMENT}（附件库命中）/ {@link #SOURCE_DEMO}（演示图兜底）
 * @param attachmentId  附件库来源时的附件 ID（演示图为 null）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record ImageAssetSpec(String search, String resolved, String source, Long attachmentId) {

    /**
     * media 槽位的搜索引用前缀：值为 "search:关键词" 时触发附件库搜图
     */
    public static final String SEARCH_PREFIX = "search:";

    public static final String SOURCE_ATTACHMENT = "attachment";
    public static final String SOURCE_DEMO = "demo";

    /**
     * 是否为搜索引用（{@code search:} 前缀）
     */
    public static boolean isSearchRef(String value) {
        return value != null && value.startsWith(SEARCH_PREFIX);
    }

    /**
     * 提取搜索关键词：{@code "search:产品主图 科技感"} → {@code "产品主图 科技感"}；非搜索引用返回 null
     */
    public static String searchKeyword(String value) {
        if (!isSearchRef(value)) {
            return null;
        }
        String keyword = value.substring(SEARCH_PREFIX.length()).trim();
        return keyword.isEmpty() ? null : keyword;
    }

    /**
     * media 槽位值是否为可直接使用的引用（URL 形态：http(s):// 或站内绝对路径 / 开头）
     */
    public static boolean isDirectRef(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("/"));
    }

    public static List<ImageAssetSpec> safeList(List<ImageAssetSpec> list) {
        return list == null ? List.of() : list;
    }

}
