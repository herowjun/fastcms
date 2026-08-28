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
 * AI 文章改写请求（编辑器划词操作）
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiArticleRewriteRequest {

    public static final String OP_REWRITE = "rewrite";
    public static final String OP_EXPAND = "expand";
    public static final String OP_POLISH = "polish";
    public static final String OP_TRANSLATE = "translate";

    /**
     * 选中的文本（可为 HTML 片段）
     */
    private String text;

    /**
     * 操作类型: rewrite（改写）/ expand（扩写）/ polish（润色）/ translate（中英互译）
     */
    private String operation;

    /**
     * 补充指令（可选，如"更口语化"、"翻译成英文"）
     */
    private String instruction;

    /**
     * 文章标题（可选，帮助模型理解上下文）
     */
    private String articleTitle;

    /**
     * 选中内容的上下文 JSON（可选，前端传入选中段落前后的正文摘录，
     * 形如 {"before":"...","after":"..."}，帮助模型保持文风与语义衔接）
     */
    private String context;

    /**
     * 文章ID（可选，编辑已保存文章时传入，用于操作历史归属；新建文章为空，保存后前端触发绑定）
     */
    private Long articleId;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }

    public String getArticleTitle() { return articleTitle; }
    public void setArticleTitle(String articleTitle) { this.articleTitle = articleTitle; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
}
