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
 * AI 文章生成请求
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiArticleGenRequest {

    /**
     * 文章主题/需求描述
     */
    private String topic;

    /**
     * 关键词（可选，逗号分隔）
     */
    private String keywords;

    /**
     * 补充要求（可选，如目标读者、篇幅、语气）
     */
    private String instruction;

    /**
     * 文章ID（可选，编辑已保存文章时传入，用于操作历史归属；新建文章为空，保存后前端触发绑定）
     */
    private Long articleId;

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }

    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
}
