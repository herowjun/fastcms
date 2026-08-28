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

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 文章内容生产服务（无状态）
 *
 * <p>与模板场景不同，文章生成/改写是<b>无状态</b>能力：
 * 每次请求携带完整上下文（主题、选中文本、正文），不创建会话、不存消息表，
 * 迭代调整由前端把"当前内容 + 新指令"整体再次传入。
 * 仅通过审计日志（ai_usage_log）记录 token 消耗。</p>
 *
 * <p>SSE 事件协议与模板对话保持一致：message（文本增量）/ reasoning（思考过程增量）/
 * done（完成，携带结构化结果）/ error。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiArticleGenService {

    /**
     * 生成文章草稿（SSE 流式）
     *
     * <p>done 事件携带完整 JSON：{"reply","title","summary","content","seoKeywords","seoDescription","logId"}，
     * content 为 CKEditor 支持的 HTML 片段。前端按字段提供"应用"按钮，logId 用于操作历史归属。</p>
     */
    void generate(AiArticleGenRequest request, Long userId, SseEmitter emitter);

    /**
     * 改写选中文本（SSE 流式）
     *
     * <p>操作类型见 {@link AiArticleRewriteRequest}：rewrite/expand/polish/translate。
     * message 事件直接推送改写后的文本增量（非 JSON），前端在选区原位替换。
     * done 事件携带 {"content","logId"}。</p>
     */
    void rewrite(AiArticleRewriteRequest request, Long userId, SseEmitter emitter);

    /**
     * 生成单字段候选（SSE 流式）：标题/摘要/SEO 关键词/SEO 描述
     *
     * <p>reasoning 事件推送思考过程增量；done 事件携带 JSON：
     * {"candidates":["候选1",...],"logId":123}。</p>
     */
    void generateField(AiArticleFieldRequest request, Long userId, SseEmitter emitter);
}
