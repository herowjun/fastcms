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
package com.fastcms.ai.template;

/**
 * AI 模板生成相关常量
 *
 * <p>集中管理 fastcms 模板规范的关键常量，供提示词构建器、响应解析器、Service 复用。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class AiTemplateConstants {

    private AiTemplateConstants() {
    }

    /**
     * 会话状态
     */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_CLOSED = "closed";

    /**
     * 消息角色
     */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    /**
     * 失败消息统一前缀：模型调用失败时落库的 assistant 消息以此开头。
     * 前端据此展示失败态与"重新生成"入口；后端构建对话历史时跳过此类消息
     * （错误文本对模型是无意义上下文）
     */
    public static final String MSG_FAIL_PREFIX = "生成失败：";

    /**
     * 文件操作类型
     */
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_MODIFY = "modify";
    public static final String ACTION_DELETE = "delete";

    /**
     * fastcms 模板必备文件
     */
    public static final String FILE_TEMPLATE_PROPERTIES = "_template.properties";
    public static final String FILE_LAYOUT = "_layout.html";
    public static final String FILE_INDEX = "index.html";
    public static final String FILE_ARTICLE = "article.html";
    public static final String FILE_ARTICLE_LIST = "article_list.html";
    public static final String FILE_PAGE = "page.html";

    /**
     * 静态资源目录
     */
    public static final String DIR_STATIC = "static";
    public static final String DIR_STATIC_CSS = "static/css";
    public static final String DIR_STATIC_JS = "static/js";
    public static final String DIR_STATIC_IMAGES = "static/images";

    /**
     * 预览会话目录名前缀（避免与正式模板目录冲突）
     */
    public static final String PREVIEW_DIR_PREFIX = "_preview_";

    /**
     * SSE 事件类型
     */
    public static final String SSE_EVENT_MESSAGE = "message";
    /**
     * 推理模型思考过程增量（Spring AI 透传 reasoning_content，仅推理模型返回）
     */
    public static final String SSE_EVENT_REASONING = "reasoning";
    public static final String SSE_EVENT_FILE = "file";
    /**
     * 分批流水线进度快照（全量推送文件清单及各自状态：done/current/pending）
     */
    public static final String SSE_EVENT_PROGRESS = "progress";
    public static final String SSE_EVENT_DONE = "done";
    public static final String SSE_EVENT_ERROR = "error";

}
