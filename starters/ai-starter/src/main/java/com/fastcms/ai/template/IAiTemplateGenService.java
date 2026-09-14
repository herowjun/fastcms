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
     * 解析会话的有效工作目录
     *
     * <p>调整型会话按模板 ID 实时解析当前正式模板目录（会话创建时存储的绝对路径
     * 可能因模板目录迁移而失效），路径变化时回写会话记录自愈；
     * 生成型会话直接返回存储的预览工作目录。</p>
     *
     * @return 工作目录（不为 null；会话未记录 workDir 时抛出 IllegalArgumentException）
     */
    java.nio.file.Path resolveEffectiveWorkDir(AiTemplateSession session);

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
     * @param focusSectionId 预览页点选的目标区块 sectionId（可空；组件化会话微调时注入该 section 的
     *                       spec 片段，AI 只修改该区块，其他 section 原样保留）
     * @param focusElementHint 用户点选区块时命中的具体元素描述（可空；元素级语义提示）
     * @param styleUpgrade 样式组件化升级标志（true 且为旧模板时走升级管线，忽略常规对话）
     * @param deepRefresh 深度焕新标志（配合 styleUpgrade：升级已完成时重置计划再改造一轮，
     *                    需存在升级计划；未开始升级的模板等同首次升级；默认智能焕新——
     *                    AI 规划调用判定最小重做范围）
     * @param fullRefresh 全量焕新标志（配合 deepRefresh：跳过范围评估，全部计划文件重做）
     * @param fullInject 全量注入标志（调整型会话：true 时预算内全部模板文件注入提示词，
     *                   不挂按需检索工具；默认 false 走聚焦注入）
     * @param feedback 用户焕新意见（可空；深度焕新时作为定向修正目标注入提示词，
     *                 并落盘计划文件 lastRound.userFeedback 供下一轮回喂）
     * @param confirmAction 设计稿模式确认动作（可空，仅 design 会话生效，管线会话忽略）：
     *                      APPROVE（审计通过稿放行转化）/ REJECT（附 input 作为修改意见重出设计稿），
     *                      见 doc/wiki/ai-template-two-mode-design.md §6.2
     * @param emitter   SSE emitter
     */
    void chatStream(String sessionId, String userInput, String currentFile, String focusSectionId,
                    String focusElementHint, boolean styleUpgrade, boolean deepRefresh,
                    boolean fullRefresh, boolean fullInject, String feedback, String confirmAction,
                    SseEmitter emitter);

    // ==================== 任务运行态（关页后台续跑 + 重开续看） ====================

    /**
     * 会话运行态探测（前端打开会话时调用，决定是否续连 stream 端点）
     *
     * <p>长任务与页面连接解耦后，SSE 断开（关页面/断网）不再取消任务——用户重新打开
     * 会话时先探测：任务仍在跑则续看（思考过程回放 + 实时续接），已结束则走终态恢复。</p>
     */
    RunStatus getRunStatus(String sessionId);

    /**
     * 续看运行中的任务：回放 journal 中 {@code seq > since} 的历史事件（含已发生的思考过程），
     * 再挂为实时订阅者续接。无运行任务时发 run-status 事件（running=false）后结束
     */
    void observeStream(String sessionId, long since, SseEmitter emitter);

    /**
     * 显式停止运行中的任务（用户点击停止；断开连接不触发取消）
     *
     * @return false = 当前无运行中的任务
     */
    boolean stopRun(String sessionId);

    /**
     * 会话运行态
     *
     * @param running   是否有运行中的任务（终态短保留期内为 false）
     * @param lastSeq   最新事件序号（续连 since 基准）
     * @param startedAt 任务开始时间戳（ms；无任务为 0）
     */
    record RunStatus(boolean running, long lastSeq, long startedAt) {
    }

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
     * @return 应用结果（含成功消息与应用后的正式模板 ID，前端据此无缝切换到正式模板编辑）
     */
    ApplyResult applyTemplate(String sessionId);

    /**
     * 模板应用结果
     *
     * @param message    应用结果描述（展示给用户）
     * @param templateId 应用后正式模板的 ID（按模板目录名从模板注册表匹配；匹配不到时为 null）
     */
    record ApplyResult(String message, String templateId) {
    }

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
    /**
     * 旧模板「样式组件化升级」状态查询（前端横幅展示：未开始 / 未完成续传）
     *
     * <p>upgradable = 会话工作目录有 html 页面、无 _pagespec.json（组件化标志物）
     * 且样式升级未完成（_style_upgrade.json 的 pending 不为空）。
     * 已组件化或升级已完成的模板返回 upgradable=false；带 pendingCount/doneCount/totalFiles
     * 进度数据，用于横幅区分"未升级"与"上次升级未完成，可断点续传"。</p>
     */
    com.fastcms.ai.component.LegacyStyleUpgrader.UpgradeStatusInfo getLegacyUpgradeStatus(String sessionId);

    /**
     * 设计稿先行模式状态查询（前端刷新后恢复确认卡片用）
     *
     * <p>plan.json 处于 AWAITING_CONFIRM 时返回确认卡片数据
     * （{state, issues[], previewUrl, confirmAuto}，与 confirm_request SSE 事件同构），
     * 其余状态（含非 design 会话、无 plan）返回 null。</p>
     */
    java.util.Map<String, Object> getDesignConfirmCard(String sessionId);

    /**
     * HTML 导入（zip 站包 / 单 HTML 文件）
     *
     * <p>仅 createMode=import 的新建会话可用。同步完成 ingest：解压（防 slip）→
     * pageKey 推导 → 归一化落盘（design/*.html）→ 资产归位 → plan.json（CONVERTING）；
     * 转化由既有 chatStream 驱动（编排器 CONVERTING 起步，html-import 设计 §5.4）。</p>
     *
     * @param sessionId 会话 ID（import 模式、未绑定正式模板）
     * @param file      上传的 zip 或 HTML 文件
     * @param userId    当前用户 ID（属主校验）
     * @return 导入报告（页数 / 资产数 / 显式标注）
     * @throws IllegalArgumentException 会话不存在 / 非属主 / 非 import 模式 / 文件不合法
     */
    java.util.Map<String, Object> importHtml(String sessionId,
                                             org.springframework.web.multipart.MultipartFile file,
                                             Long userId);

    /**
     * 更新图片槽位（AI 调整页点选图片换图，不经 AI 对话）
     *
     * <p>流程：读 _pagespec.json → 定位 section 槽位 → 替换为附件库图片 URL →
     * 更新 imageAssets 解析记录 → 校验 → 重渲染 → 持久化产物。
     * 调整型会话直写正式模板目录（与对话微调一致）。</p>
     *
     * @param sessionId    会话 ID
     * @param sectionId    section ID（data-ai-section 标记回传）
     * @param slot         槽位名（data-ai-slot 标记回传，须为 media 类型槽位）
     * @param attachmentId 附件库图片 ID（URL 由服务端解析，前端不传地址）
     * @return 本次写出的模板内相对路径清单（前端据此刷新预览）
     */
    java.util.List<String> updateImageSlot(String sessionId, String sectionId, String slot, Long attachmentId);

    /**
     * 更新预览演示图片（预览页点选无槽位标记的 mock 图片换图，不经 AI 对话）
     *
     * <p>与 {@link #updateImageSlot} 的区别：前者改 _pagespec.json（模板资产图，模板正式生效），
     * 本方法改 _preview_data.json 的 imageOverrides 映射（mock 演示图，如文章封面）——
     * 仅预览渲染生效，正式环境的图片由数据库数据决定。</p>
     *
     * <p>流程：附件解析 URL → workDir 下读写 _preview_data.json（保留既有字段）→
     * imageOverrides[imageUrl] = 附件 URL 写回。预览渲染每次现读该文件，无需重渲染。</p>
     *
     * @param sessionId    会话 ID
     * @param imageUrl     原图片 URL（模板渲染输出的原样值，作为映射 key；含内联 SVG data URI）
     * @param attachmentId 附件库图片 ID
     */
    void updatePreviewImage(String sessionId, String imageUrl, Long attachmentId);

    // ==================== 会话工作目录文件编辑（生成型会话，应用前的手工打磨） ====================

    /**
     * 生成型会话工作目录的文件树（复用正式模板的树构建规则，filePath 以模板目录名开头）
     *
     * <p>已应用（applied）会话同样可读——应用后仍可回看文件结构。</p>
     */
    List<com.fastcms.core.template.TemplateService.FileTreeNode> getSessionFileTree(String sessionId);

    /**
     * 读取会话工作目录的文本文件内容
     *
     * @param filePath 文件路径（以模板目录名开头，与文件树返回值一致）
     */
    String getSessionFile(String sessionId, String filePath);

    /**
     * 保存（或新建）会话工作目录的文本文件
     *
     * <p>仅生成型会话且未应用时可写；应用后的会话目录只读（改动应走正式模板编辑）。</p>
     */
    void saveSessionFile(String sessionId, String filePath, String fileContent);

    /**
     * 删除会话工作目录的文件（仅生成型会话且未应用时）
     */
    void deleteSessionFile(String sessionId, String filePath);

    /**
     * 上传文件到会话工作目录指定子目录
     *
     * @param dirName 目标目录（以模板目录名开头，与文件树路径约定一致）
     * @param files   上传的文件
     * @return 写入的文件相对路径清单
     */
    List<String> uploadSessionFiles(String sessionId, String dirName, org.springframework.web.multipart.MultipartFile[] files);

}
