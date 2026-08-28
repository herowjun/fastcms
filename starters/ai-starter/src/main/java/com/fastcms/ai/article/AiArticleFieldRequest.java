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
package com.fastcms.ai.article;

/**
 * AI 文章单字段生成请求（标题/摘要/SEO）
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiArticleFieldRequest {

    public static final String FIELD_TITLE = "title";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_SEO_KEYWORDS = "seoKeywords";
    public static final String FIELD_SEO_DESCRIPTION = "seoDescription";

    /**
     * 目标字段: title / summary / seoKeywords / seoDescription
     */
    private String field;

    /**
     * 文章正文（HTML 或纯文本）
     */
    private String content;

    /**
     * 文章标题（生成摘要/SEO 时提供上下文）
     */
    private String title;

    /**
     * 文章ID（可选，编辑已保存文章时传入，用于操作历史归属；新建文章为空，保存后前端触发绑定）
     */
    private Long articleId;

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
}
