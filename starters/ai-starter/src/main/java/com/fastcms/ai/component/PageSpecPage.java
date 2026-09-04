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

import java.util.List;

/**
 * PageSpec 中单个页面的描述：该页的 section 有序列表 + 是否使用公共布局
 *
 * <p>渲染时自上而下依次渲染各 section；内容页（article_list / article / page）
 * 的正文主体由渲染引擎内置模板承载。</p>
 *
 * <p>{@code standalone}（1.2 新增）为 true 时该页不使用公共布局 _layout.html，
 * 渲染为包含完整 HTML 骨架与自有 navbar/footer 的独立页面（特殊落地页等场景）；
 * 缺省 null/false = 使用公共布局（navbar/footer 由布局统一提供）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record PageSpecPage(List<SectionSpec> sections, Boolean standalone) {

    public PageSpecPage(List<SectionSpec> sections) {
        this(sections, null);
    }

    public List<SectionSpec> safeSections() {
        return sections == null ? List.of() : sections;
    }

    /**
     * 是否独立页面（不走公共布局，渲染完整 HTML）
     */
    public boolean safeStandalone() {
        return Boolean.TRUE.equals(standalone);
    }

}