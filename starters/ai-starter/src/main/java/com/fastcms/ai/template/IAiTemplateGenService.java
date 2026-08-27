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

import com.fastcms.entity.AiTemplateFile;
import com.fastcms.entity.AiTemplateMessage;
import com.fastcms.entity.AiTemplateSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 模板生成服务
 *
 * <p>核心业务接口，串联会话管理、ChatClient 调用、文件持久化、模板预览与应用。
 *
 * <p>典型流程：
 * <ol>
 *     <li>{@link #createSession} 创建会话，返回 sessionId</li>
 *     <li>{@link #chatStream} 以 SSE 方式与 AI 对话，AI 响应解析为文件并写入预览目录</li>
 *     <li>{@link #listFiles} 查看会话已生成的文件</li>
 *     <li>{@link #applyTemplate} 将预览目录的文件应用到正式模板目录</li>
 *     <li>{@link #deleteSession} 清理会话</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiTemplateGenService {

    /**
     * 创建会话
     *
     * @param request 创建请求
     * @param userId  创建用户 ID
     * @return 会话实体（含 sessionId、workDir）
     */
    AiTemplateSession createSession(AiTemplateSessionRequest request, Long userId);

    /**
     * 获取会话详情
     */
    AiTemplateSession getSession(String sessionId);

    /**
     * 列出用户的所有会话
     */
    List<AiTemplateSession> listSessions(Long userId);

    /**
     * 删除会话（含数据库记录与预览工作目录）
     */
    void deleteSession(String sessionId);

    /**
     * 列出会话所有消息
     */
    List<AiTemplateMessage> listMessages(String sessionId);

    /**
     * 列出会话已生成的所有文件
     */
    List<AiTemplateFile> listFiles(String sessionId);

    /**
     * SSE 流式对话
     *
     * <p>AI 响应会以 SSE 事件形式推送到前端：
     * <ul>
     *     <li>event: message  / data: {增量文本片段}    —— AI 流式输出</li>
     *     <li>event: file     / data: {path, action}     —— 解析出的文件描述</li>
     *     <li>event: done     / data: {summary}          —— 完成</li>
     *     <li>event: error    / data: {message}          —— 出错</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入（微调需求）
     * @param currentFile 用户当前正在编辑/预览的文件（可空；调整型会话注入提示词，让 AI 聚焦当前页面）
     * @param emitter   SSE emitter
     */
    void chatStream(String sessionId, String userInput, String currentFile, SseEmitter emitter);

    /**
     * 将会话工作目录的模板文件应用到 fastcms 正式模板目录
     *
     * <p>应用流程：
     * <ol>
     *     <li>校验 _template.properties 是否存在且合法</li>
     *     <li>复制工作目录文件到 {@code DirUtils.getTemplateDir() + "/" + templateName + "/"}</li>
     *     <li>调用 {@code TemplateService.initialize()} 刷新模板注册</li>
     *     <li>更新会话状态为 applied</li>
     * </ol>
     *
     * <p>调整型会话（绑定正式模板）不支持应用——其 AI 输出已直接写入正式模板目录。</p>
     *
     * @param sessionId 会话 ID
     * @return 应用结果描述
     */
    String applyTemplate(String sessionId);

    /**
     * 回滚最近一轮 AI 修改（仅调整型会话支持）
     *
     * <p>将最近一轮对话修改过的文件恢复到该轮修改前的状态：
     * 修改过的文件恢复旧内容、AI 新建的文件删除、AI 删除的文件重建。
     * 回滚完成后该轮备份记录被清除，再次调用将作用于更早一轮。</p>
     *
     * @param sessionId 会话 ID
     * @return 回滚结果描述（含恢复的文件列表）
     */
    String rollbackLast(String sessionId);

}
