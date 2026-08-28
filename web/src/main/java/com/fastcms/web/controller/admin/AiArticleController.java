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
package com.fastcms.web.controller.admin;

import com.fastcms.ai.article.AiArticleFieldRequest;
import com.fastcms.ai.article.AiArticleGenRequest;
import com.fastcms.ai.article.AiArticleRewriteRequest;
import com.fastcms.ai.article.IAiArticleGenService;
import com.fastcms.common.auth.ActionTypes;
import com.fastcms.common.auth.Secured;
import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import com.fastcms.core.auth.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 文章内容生产
 *
 * <p>文章编辑页的三个 AI 能力入口：
 * <ul>
 *     <li>generate：全文草稿生成（SSE 流式）</li>
 *     <li>rewrite：编辑器划词改写/扩写/润色/翻译（SSE 流式）</li>
 *     <li>field：标题/摘要/SEO 候选生成（同步 JSON）</li>
 * </ul>
 * 全部无状态：每次请求携带完整上下文，不创建会话。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@RestController
@RequestMapping(FastcmsConstants.ADMIN_MAPPING + "/ai/article")
public class AiArticleController {

    /**
     * SSE 超时时间：10 分钟（与模板对话保持一致）
     */
    private static final long SSE_TIMEOUT = 10 * 60 * 1000L;

    @Autowired
    private IAiArticleGenService articleGenService;

    @Autowired
    private com.fastcms.service.IAiArticleOpLogService articleOpLogService;

    /**
     * 生成文章草稿（SSE 流式）
     *
     * <p>事件协议：message（reply 文本增量，打字机）/ reasoning（思考过程增量）/
     * done（完整 JSON：title/summary/content/seoKeywords/seoDescription）/ error。
     * 前端使用 fetch 监听流，与模板 AI 对话一致。</p>
     */
    @PostMapping(value = "generate", produces = "text/event-stream;charset=UTF-8")
    @Secured(name = "fastcms.resource.name.ai.article.generate", resource = "ai:article:generate", action = ActionTypes.WRITE)
    public SseEmitter generate(@RequestBody AiArticleGenRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        articleGenService.generate(request, AuthUtils.getUserId(), emitter);
        return emitter;
    }

    /**
     * 改写选中文本（SSE 流式）
     *
     * <p>事件协议：message（改写文本增量）/ reasoning / done（完整改写结果）/ error。</p>
     */
    @PostMapping(value = "rewrite", produces = "text/event-stream;charset=UTF-8")
    @Secured(name = "fastcms.resource.name.ai.article.rewrite", resource = "ai:article:rewrite", action = ActionTypes.WRITE)
    public SseEmitter rewrite(@RequestBody AiArticleRewriteRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        articleGenService.rewrite(request, AuthUtils.getUserId(), emitter);
        return emitter;
    }

    /**
     * 生成单字段候选（标题/摘要/SEO，SSE 流式）
     *
     * <p>事件协议：reasoning（思考过程增量）/ done（JSON：candidates 候选列表 + logId）/ error。</p>
     */
    @PostMapping(value = "field", produces = "text/event-stream;charset=UTF-8")
    @Secured(name = "fastcms.resource.name.ai.article.field", resource = "ai:article:field", action = ActionTypes.WRITE)
    public SseEmitter field(@RequestBody AiArticleFieldRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        articleGenService.generateField(request, AuthUtils.getUserId(), emitter);
        return emitter;
    }

    /**
     * 查询文章的 AI 操作历史（划词改写/扩写/润色/翻译记录，含思考过程，按时间倒序）
     */
    @GetMapping("ops/{articleId}")
    @Secured(name = "fastcms.resource.name.ai.article.ops", resource = "ai:article:ops", action = ActionTypes.READ)
    public RestResult<List<com.fastcms.entity.AiArticleOpLog>> listOps(@org.springframework.web.bind.annotation.PathVariable Long articleId) {
        return RestResultUtils.success(articleOpLogService.listByArticle(articleId, AuthUtils.getUserId()));
    }

    /**
     * 绑定操作记录到文章（新建文章保存成功后，把本次页面会话期间的划词记录归属到新文章）
     */
    @PostMapping("ops/bind")
    @Secured(name = "fastcms.resource.name.ai.article.ops", resource = "ai:article:ops", action = ActionTypes.WRITE)
    public RestResult<Integer> bindOps(@RequestBody BindOpsRequest request) {
        if (request.getArticleId() == null || request.getOpIds() == null || request.getOpIds().isEmpty()) {
            return RestResultUtils.failed("参数不完整");
        }
        return RestResultUtils.success(articleOpLogService.bindToArticle(request.getArticleId(), request.getOpIds(), AuthUtils.getUserId()));
    }

    /**
     * 绑定操作记录请求体
     */
    public static class BindOpsRequest {
        private Long articleId;
        private List<Long> opIds;

        public Long getArticleId() { return articleId; }
        public void setArticleId(Long articleId) { this.articleId = articleId; }

        public List<Long> getOpIds() { return opIds; }
        public void setOpIds(List<Long> opIds) { this.opIds = opIds; }
    }
}
