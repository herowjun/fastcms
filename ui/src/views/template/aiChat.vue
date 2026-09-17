<template>
	<div class="ai-chat-panel">
		<!-- 会话信息：会话切换/新建（由父组件管理会话数据，本组件只负责展示与转发事件） -->
		<div class="panel-header">
			<!-- modePill 四态：调整会话 / 会话工作目录 / 已应用（仅回看）/ 生成失败 -->
			<span class="mode-pill" :class="modePill.cls">{{ modePill.text }}</span>
			<el-select
				v-if="sessions && sessions.length > 0"
				:model-value="session?.sessionId"
				placeholder="选择会话"
				size="small"
				class="session-select"
				@change="(v: string) => emit('select-session', v)"
			>
			<el-option-group v-if="adjustSessionList.length" label="调整会话">
				<el-option v-for="sess in adjustSessionList" :key="sess.sessionId" :value="sess.sessionId"
					:label="formatSessionLabel(sess)">
					<span>{{ sess.title || sess.templateName || sess.sessionId }}</span>
					<span class="session-option-time">{{ formatSessionTime(sess.created) }}</span>
				</el-option>
			</el-option-group>
			<!-- 生成会话按应用状态分组：未应用=活跃工作集（可续聊/重新生成/应用），已应用=仅回看 -->
			<el-option-group v-if="pendingSessionList.length" label="未应用模板">
				<el-option v-for="sess in pendingSessionList" :key="sess.sessionId" :value="sess.sessionId"
					:label="formatSessionLabel(sess)" :title="sess.requirement">
					<el-tag size="small" type="warning" class="session-option-tag">待应用</el-tag>
					<span>{{ sess.title || sess.templateName || sess.sessionId }}</span>
					<span class="session-option-time">{{ formatSessionTime(sess.created) }}</span>
				</el-option>
			</el-option-group>
			<el-option-group v-if="appliedSessionList.length" label="已应用模板">
				<el-option v-for="sess in appliedSessionList" :key="sess.sessionId" :value="sess.sessionId"
					:label="formatSessionLabel(sess)" :title="sess.requirement">
					<el-tag size="small" type="success" class="session-option-tag">已应用</el-tag>
					<span>{{ sess.title || sess.templateName || sess.sessionId }}</span>
					<span class="session-option-time">{{ formatSessionTime(sess.created) }}</span>
				</el-option>
			</el-option-group>
			</el-select>
			<span v-else class="panel-title">{{ session?.title || (mode === 'adjust' ? 'AI 调整模板' : 'AI 生成模板') }}</span>
			<el-button size="small" text type="primary" :loading="creatingSession"
				@click="emit('new-session')">
				<el-icon><ele-Plus /></el-icon>新建会话
			</el-button>
		</div>

		<!-- 旧模板「样式组件化升级」横幅（面板顶部）：保留网站功能（JS/元素锚点/FreeMarker），组件库 CSS 焕新视觉。
		     升级走 AI 改造管线（分批重写页面 HTML，写盘前校验 JS 锚点存活），进度在对话流实时展示；
		     中断后横幅变为"继续升级"形态（带剩余进度），断点续传；
		     升级完成后横幅变为"深度焕新"形态（重置计划，全部文件含 _layout.html 再改造一轮） -->
		<div v-if="(state.legacyUpgradable || state.legacyRefinable) && !state.chatting && !isApplied" class="legacy-upgrade-bar">
			<span class="legacy-upgrade-tip">
				<template v-if="state.legacyRefinable && !state.legacyUpgradable">
					<template v-if="state.legacyRefreshCount >= 2">
						已深度焕新 {{ state.legacyRefreshCount }} 次。仍不满意？建议直接对话描述具体问题（如「首页 banner 太单调，加强视觉层次」），AI 只改相关页面，更快更准；或再次焕新（将自动换用新的设计方向）
					</template>
					<template v-else>
						样式组件化升级已完成。对效果不满意？智能焕新：AI 评估哪些页面与新方向强耦合，只重做这些页面（必含公共布局），其余保留并自动换肤，速度快
					</template>
				</template>
				<template v-else-if="state.legacyTotal > 0 && state.legacyDone > 0">
					上次升级未完成（已完成 {{ state.legacyDone }}/{{ state.legacyTotal }} 个页面），点击继续将从剩余 {{ state.legacyPending }} 个页面断点续传
				</template>
				<template v-else>
					检测到旧版模板，可升级为组件化样式：保留全部 JS 功能与元素结构锚点，引入组件库 CSS 焕新视觉，原文件自动备份
				</template>
			</span>
			<template v-if="state.legacyRefinable && !state.legacyUpgradable">
				<el-button type="warning" size="small" :loading="state.upgrading" @click="onDeepRefresh">
					<el-icon><ele-MagicStick /></el-icon>{{ state.legacyRefreshCount > 0 ? '再次智能焕新' : '智能焕新' }}
				</el-button>
				<el-button size="small" :loading="state.upgrading" @click="onFullRefresh" title="跳过范围评估，全部页面恢复原始底稿整体重做（适合彻底换风格，耗时数倍）">
					全量焕新
				</el-button>
			</template>
			<el-button v-else type="warning" size="small" :loading="state.upgrading" @click="onUpgradeLegacy">
				<el-icon><ele-MagicStick /></el-icon>{{ state.legacyTotal > 0 && state.legacyDone > 0 ? '继续升级' : '样式组件化升级' }}
			</el-button>
		</div>

		<!-- 对话区域 -->
		<div class="chat-area" ref="chatAreaRef" @scroll="onChatAreaScroll">
			<!-- 渲染窗口：仅渲染最近 N 条（超长升级会话历史几十轮 × 每轮 KB 级 reasoning，
				全量渲染 DOM + v-html 会把渲染进程内存推到 GB 级；点此增量展开更早消息 -->
			<div v-if="renderLimit < state.messages.length" class="load-earlier" @click="renderLimit += 50">
				加载更早的 {{ state.messages.length - renderLimit }} 条消息
			</div>
			<div v-for="(msg, msgIndex) in visibleMessages" :key="msgIndex" class="chat-message" :class="msg.role">
				<div class="message-role">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
				<div class="message-content">
					<!-- 分批流水线进度卡：规划完成后逐文件点亮（后端 progress 事件全量快照） -->
					<div v-if="msg.progress && msg.progress.length" class="progress-box">
						<div class="progress-header">
							<span>文件生成进度（{{ progressDoneCount(msg) }}/{{ msg.progress.length }}）</span>
							<el-button
								v-if="mode === 'generate' && !isApplied && !state.chatting && isLastMessage(msg)
									&& progressDoneCount(msg) < msg.progress.length"
								size="small" text type="primary" style="margin-left: auto" @click="onResumeMissing">
								<el-icon><ele-MagicStick /></el-icon>补齐缺失文件
							</el-button>
						</div>
						<div class="progress-items">
							<div v-for="f in msg.progress" :key="f.path" class="progress-item" :class="f.status">
								<el-icon v-if="f.status === 'done'" class="pi-done"><ele-Check /></el-icon>
								<el-icon v-else-if="f.status === 'current'" class="is-loading pi-current"><ele-Loading /></el-icon>
								<el-icon v-else class="pi-pending"><ele-Clock /></el-icon>
								<span class="pi-path">{{ f.path }}</span>
							</div>
						</div>
						</div>
						<!-- 设计稿先行模式：审计通过后的确认卡片（问题清单 + 预览 + 确认转化/驳回修改）；
						数据来自 confirm_request 事件或刷新恢复（design-status 端点），handled 后只读保留 -->
						<div v-if="msg.confirmCard" class="confirm-box">
						<div class="confirm-header">
							<el-icon class="ci-done"><ele-Check /></el-icon>
							<span>设计稿已完成，请确认</span>
							<el-link v-if="msg.confirmCard.previewUrl" type="primary" :href="msg.confirmCard.previewUrl"
							           target="_blank" class="confirm-preview">
								<el-icon><ele-View /></el-icon>预览设计稿
							</el-link>
						</div>
						<div v-if="msg.confirmCard.issues && msg.confirmCard.issues.length" class="confirm-issues">
							<div class="ci-title">审计提示（{{ msg.confirmCard.issues.length }} 项，可确认后继续或驳回修改）</div>
							<div v-for="(it, i) in msg.confirmCard.issues" :key="i" class="ci-item">
								<el-tag size="small" type="warning" class="ci-code">{{ it.code }}</el-tag>
								<span class="ci-text">{{ it.page }}：{{ it.message }}</span>
							</div>
						</div>
						<div v-if="!msg.confirmCard.handled" class="confirm-actions">
							<el-button type="primary" size="small" :loading="state.chatting" @click="onConfirmApprove(msg)">
								<el-icon><ele-Check /></el-icon>确认设计稿，开始转化为模板
							</el-button>
							<el-button size="small" :disabled="state.chatting" @click="onConfirmReject(msg)">
								<el-icon><ele-RefreshLeft /></el-icon>驳回修改
							</el-button>
							<span v-if="msg.confirmCard.confirmAuto" class="ci-auto-note">已开启自动转化（审计通过即转化）</span>
						</div>
						<div v-else class="confirm-done-note">已处理（确认转化 / 驳回重设计），等待流程推进</div>
						</div>
						<!-- 推理模型思考过程（可折叠，思考中默认展开） -->
					<div v-if="msg.reasoning" class="reasoning-box">
						<div class="reasoning-header" @click="msg.reasoningExpanded = !msg.reasoningExpanded">
							<el-icon class="reasoning-arrow" :class="{ collapsed: !msg.reasoningExpanded }"><ele-ArrowRight /></el-icon>
							<span>{{ reasoningThinking(msg) ? '思考中...' : '已深度思考' }}</span>
						</div>
						<!-- v-if 而非 v-show：收起状态不创建 DOM（KB 级思考文本 → 数万节点/条，
							几十轮历史全量常驻是渲染进程内存飙升的主因之一） -->
						<div
							v-if="msg.reasoningExpanded"
							class="reasoning-text"
							v-html="renderReasoning(msg)"
						></div>
					</div>
					<pre class="message-text" :class="{ failed: isFailMessage(msg) }">{{ msg.content }}<span
						v-if="state.chatting && isLastMessage(msg) && !msg.reasoning"
						class="typing-cursor"
					>▌</span></pre>
					<!-- AI 消息底部元信息条：hover 浮出（token 消耗 + 复制按钮） -->
					<div v-if="msg.role === 'assistant'" class="message-meta">
						<span v-if="msg.totalTokens > 0 || msg.promptTokens > 0 || msg.completionTokens > 0"
							class="meta-tokens"
							:title="`输入 ${msg.promptTokens ?? 0} tokens + 输出 ${msg.completionTokens ?? 0} tokens。\n输入包含系统提示词、对话历史及注入的模板文件内容（并非只有你输入的那句话），故数值通常远大于输出；输出为 AI 本轮生成的回复。跨轮次聚合：含思考与工具调用轮`">
							<el-icon><ele-Coin /></el-icon>
							token {{ formatTokenCount(msg.totalTokens || ((msg.promptTokens || 0) + (msg.completionTokens || 0))) }}
							（输入 {{ formatTokenCount(msg.promptTokens || 0) }} / 输出 {{ formatTokenCount(msg.completionTokens || 0) }}）
						</span>
						<span class="meta-copy" title="复制本条回复内容" @click="copyMessage(msg)">
							<el-icon><ele-CopyDocument /></el-icon>复制
						</span>
					</div>
				</div>
			</div>
			<el-empty v-if="!state.loading && state.messages.length === 0"
				:description="mode === 'adjust' ? '描述你想调整的内容，例如：把首页导航改为深色，banner 换成轮播图' : '开始对话生成模板'" />
		</div>

		<!-- 输入区域 -->
		<div class="chat-input">
			<div v-if="state.chatting && state.statusText" class="status-bar">
				<el-icon class="is-loading"><ele-Loading /></el-icon>
				<span>{{ state.statusText }}</span>
			</div>
			<!-- 点选模式提示条（正上方引导：看完提示顺势在输入框描述需求，随聊天列收缩） -->
			<div v-if="imagePickMode" class="pick-mode-tip">
				<el-icon><ele-InfoFilled /></el-icon>
				<span>换图模式：点击预览页中高亮的图片进行更换（点击「退出换图模式」结束）</span>
			</div>
			<div v-else-if="sectionSelectMode" class="pick-mode-tip">
				<el-icon><ele-InfoFilled /></el-icon>
				<span>选区模式：点击预览页中的区块，锁定后在下方输入框描述修改需求（点击「退出选区模式」结束）</span>
			</div>
			<div v-if="mode === 'generate' && isFailed" class="regen-bar">
				<span class="regen-tip">上次生成失败（详见上方错误信息）。模型配置修复后可重新生成。</span>
				<el-button type="primary" size="small" @click="onRegenerate" :loading="state.chatting">
					<el-icon><ele-RefreshRight /></el-icon>重新生成
				</el-button>
			</div>
			<el-input
				v-model="state.inputText"
				type="textarea"
				:rows="3"
				:placeholder="isApplied ? '该会话已应用到正式模板目录，仅支持回看历史消息与文件'
					: (focusSection ? `AI 已锁定选中区块「${focusSection}」，描述你想把它改成什么样子，例如：标题改成红色大字、换一张配图`
					: (mode === 'adjust'
						? (currentFileName ? `AI 当前聚焦页面：${currentFileName}。描述你想调整的内容，例如：把这里的导航改为深色` : '描述你想调整的内容，例如：把首页导航改为深色、文章列表改为卡片式布局')
						: '描述你的需求，例如：生成一个企业官网模板，蓝色调，响应式设计'))"
				:disabled="state.chatting || isApplied"
			/>
			<!-- 全量注入开启时常驻警示（输入框正下方，随开关显隐，不用弹出框） -->
			<div v-if="mode === 'adjust' && state.fullInject" class="full-inject-tip">
				<el-icon><ele-WarningFilled /></el-icon>
				<span>全量注入已开启：每轮对话会把全部模板文件提交给 AI，token 消耗大幅增加、耗时明显变长，建议仅在 AI 自动检索效果不佳时使用，问题解决后及时关闭</span>
			</div>
			<div class="chat-actions">
				<!-- 点选工具（换图/选区）：与发送按钮同排靠左；模式开关与预览 iframe 钩子注入由父组件处理 -->
				<div v-if="pickToolsVisible" class="chat-tools">
					<el-button size="small" :type="imagePickMode ? 'primary' : ''" :disabled="pickDisabled"
					:title="imagePickMode ? '换图模式已开启：点击预览页中的图片进行更换' : '开启换图模式：点选预览页中的图片进行更换'"
					@click="emit('toggle-image-pick')">
					<el-icon><ele-PictureFilled /></el-icon>{{ imagePickMode ? '退出换图模式' : '换图' }}
				</el-button>
				<el-button size="small" :type="sectionSelectMode ? 'primary' : ''" :disabled="pickDisabled"
					:title="sectionSelectMode ? '选区模式已开启：点击预览页中的区块锁定为 AI 对话目标' : '开启选区模式：点选预览页中的区块，后续 AI 对话只修改该区块'"
					@click="emit('toggle-section-select')">
					<el-icon><ele-Position /></el-icon>{{ sectionSelectMode ? '退出选区模式' : '选区' }}
				</el-button>
				<!-- 全量注入开关（仅调整型会话）：默认关闭走聚焦注入（AI 按需检索，省 token）；
				     开启后每轮把全部模板文件提交给 AI（旧全量行为），token 消耗与耗时大幅增加 -->
				<div v-if="mode === 'adjust'" class="full-inject-toggle"
					:title="state.fullInject
						? '已开启全量注入：每轮对话把全部模板文件提交给 AI，token 消耗大、耗时长，建议问题解决后关闭'
						: '默认 AI 按需检索：只注入当前页面的依赖文件，AI 需要时自行查看其他文件（省 token、更快）。若 AI 找不到相关文件可开启全量注入'">
					<el-switch v-model="state.fullInject" size="small" :disabled="state.chatting" @change="onFullInjectChange" />
					<span class="full-inject-label">全量注入</span>
				</div>
				</div>
				<div class="chat-send">
					<el-button type="primary" @click="onSend" :loading="state.chatting" :disabled="!state.inputText.trim() || isApplied">
						<el-icon><ele-Promotion /></el-icon>{{ state.chatting ? '生成中...' : '发送' }}
					</el-button>
					<el-button v-if="state.chatting" type="danger" :loading="state.stopping" @click="onStop">
						<el-icon><ele-VideoPause /></el-icon>停止
					</el-button>
				</div>
			</div>
		</div>

		<!-- 文件列表区域：对话下方（限高滚动） -->
		<div class="files-area" v-if="state.files.length > 0">
			<div class="files-header">
				<span>{{ mode === 'adjust' ? '本轮 AI 修改的文件（' + state.files.length + '）' : '生成文件（' + state.files.length + '）' }}</span>
				<div>
					<el-button size="small" text @click="onPreviewTemplate">
						<el-icon><ele-View /></el-icon>预览
					</el-button>
				</div>
			</div>
			<!-- 限高 180px 内部滚动，避免长列表把输入区挤出视口 -->
			<el-table :data="state.files" stripe size="small" :max-height="180">
				<el-table-column prop="filePath" label="文件路径" min-width="200" show-overflow-tooltip />
				<el-table-column prop="action" label="操作" width="90">
					<template #default="scope">
						<el-tag size="small" :type="scope.row.action === 'create' ? 'success' : scope.row.action === 'modify' ? 'warning' : 'danger'">
							{{ scope.row.action }}
						</el-tag>
					</template>
				</el-table-column>
				<el-table-column label="操作" width="110">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onViewFile(scope.row)">查看</el-button>
						<!-- 编辑文件桥（仅调整会话）：把 AI 的改动接回手动编辑视图继续打磨 -->
						<el-button v-if="mode === 'adjust'" size="small" text type="primary" @click="emit('edit-file', scope.row.filePath)">编辑</el-button>
					</template>
				</el-table-column>
			</el-table>
		</div>

		<!-- 文件查看对话框 -->
		<el-dialog :title="state.viewingFile?.filePath || '文件内容'" v-model="state.fileDialogVisible" width="80%" top="5vh" append-to-body>
			<el-scrollbar max-height="70vh">
				<pre class="file-content">{{ state.viewingFile?.content }}</pre>
			</el-scrollbar>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="templateAiChat">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { AiTemplateApi } from '/@/api/ai/index';
import { Local } from '/@/utils/storage';
import { useAiRunStream, FAIL_MSG_PREFIX } from '/@/views/template/composables/useAiRunStream';
import { useLegacyUpgrade } from '/@/views/template/composables/useLegacyUpgrade';
import { useAiChatRender } from '/@/views/template/composables/useAiChatRender';

/**
 * AI 模板对话面板（模板编辑页内嵌组件）
 *
 * 两种模式：
 * - adjust：调整型会话，绑定正式模板，AI 输出直写正式模板目录（后端自动备份），支持回滚
 * - generate：生成型会话，在预览工作目录中生成完整模板，应用后复制到正式模板目录
 *
 * 会话的创建/复用由父组件负责，本组件只负责指定会话内的对话与文件展示。
 */
const props = defineProps<{
	/** 会话对象（含 sessionId、templateName），为空时面板不可用 */
	session: any;
	/** adjust：调整正式模板；generate：生成新模板 */
	mode: 'adjust' | 'generate';
	/** 用户当前正在编辑的文件（含模板目录前缀），发送消息时传给后端，让 AI 聚焦当前页面 */
	currentFile?: string;
	/** 选区锁定：预览页点选的区块 ID，随消息发送让 AI 只修改该区块（组件化会话生效） */
	focusSection?: string;
	/** 选区锁定的元素语义提示（如 标题「散养土鸡蛋」），随消息发送 */
	focusElementHint?: string;
	/** 可切换的会话列表（父组件管理，含调整与生成全部会话，本组件按类型分组展示） */
	sessions?: any[];
	/** 新建会话请求进行中（按钮 loading） */
	creatingSession?: boolean;
	/** 换图模式开启状态（控制按钮高亮与提示条；模式开关与预览 iframe 钩子注入由父组件处理） */
	imagePickMode?: boolean;
	/** 选区模式开启状态（控制按钮高亮与提示条；模式开关与预览 iframe 钩子注入由父组件处理） */
	sectionSelectMode?: boolean;
}>();

const emit = defineEmits<{
	/** AI 写盘后通知（父组件刷新文件树/编辑器） */
	(e: 'files-changed'): void;
	/** AI 每写完一个文件的实时通知（SSE file 事件，父组件用于刷新实时预览） */
	(e: 'file-written', path: string): void;
	/** AI 页面自动切换（SSE switch-file 事件，调整对话流式期间识别到目标 HTML 即推送，父组件切换实时预览页面） */
	(e: 'switch-file', path: string): void;
	/** 生成型会话应用模板成功（templateId：应用后的正式模板 ID，父组件据此无缝切换编辑目标） */
	(e: 'applied', templateId: string): void;
	/** 「编辑文件」桥（调整会话文件行）：父组件切到手动编辑视图并打开该文件 */
	(e: 'edit-file', filePath: string): void;
	/** 切换会话（sessionId） */
	(e: 'select-session', sessionId: string): void;
	/** 新建会话 */
	(e: 'new-session'): void;
	/** 切换换图模式（预览页点选图片更换） */
	(e: 'toggle-image-pick'): void;
	/** 切换选区模式（预览页点选区块锁定为 AI 对话目标） */
	(e: 'toggle-section-select'): void;
}>();

const templateApi = AiTemplateApi();
const chatAreaRef = ref();

/** 已应用的生成型会话：仅回看，禁止继续对话/应用（后端 apply 后 status 置为 applied） */
const isApplied = computed(() => props.session?.status === 'applied');


/** 生成失败态判定：最后一条消息是后端落库的失败标记消息（空壳会话的可靠信号） */
const isFailed = computed(() => {
	if (isApplied.value || state.chatting) return false;
	const msgs = state.messages;
	if (!msgs.length) return false;
	const last = msgs[msgs.length - 1];
	return last?.role === 'assistant' && String(last.content || '').startsWith(FAIL_MSG_PREFIX);
});

/** 单条消息是否为失败消息（红色样式渲染） */
const isFailMessage = (msg: any) => {
	return msg?.role === 'assistant' && String(msg.content || '').startsWith(FAIL_MSG_PREFIX);
};

/**
 * modePill 四态：调整会话（直写正式模板）/ 会话工作目录 / 已应用（仅回看）/ 生成失败。
 * 「失败」是会话期瞬时态（本轮 SSE 失败未落库），刷新后回退为「会话工作目录」
 */
const modePill = computed(() => {
	if (props.mode === 'adjust') return { text: 'AI 调整 · 直写正式模板', cls: 'adjust' };
	if (isApplied.value) return { text: '已应用 · 仅回看', cls: 'applied' };
	if (isFailed.value) return { text: '生成失败', cls: 'failed' };
	return { text: '会话工作目录', cls: 'generate' };
});

/** 会话下拉分组（各组内按创建时间倒序，最近的在前）：调整会话 / 生成会话按应用状态拆两组 */
const sortByCreatedDesc = (a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime();
const adjustSessionList = computed(() => (props.sessions || []).filter((s: any) => s.templateId).sort(sortByCreatedDesc));
const pendingSessionList = computed(() =>
    (props.sessions || []).filter((s: any) => !s.templateId && s.status !== 'applied').sort(sortByCreatedDesc));
const appliedSessionList = computed(() =>
    (props.sessions || []).filter((s: any) => !s.templateId && s.status === 'applied').sort(sortByCreatedDesc));

/** token 数量格式化：原样输出完整数字，不用 w 等缩写 */
const formatTokenCount = (n: any): string => {
	return String(Number(n) || 0);
};

/** 复制 AI 回复内容（所见即所得：复制清洗后的正文，非原始 JSON） */
const copyMessage = async (msg: any) => {
	const text = String(msg?.content || '');
	if (!text) return;
	try {
		await navigator.clipboard.writeText(text);
		ElMessage.success('已复制回复内容');
	} catch {
		ElMessage.error('复制失败，请手动选择文本复制');
	}
};

/**
 * 重新生成：取会话首条用户需求重走流水线。
 * 后端对"plan 为空且无文件"的会话会重新进入分批生成（hasMissingPlanFiles），
 * 失败标记消息不会注入模型上下文
 */
const onRegenerate = () => {
	if (state.chatting) return;
	const firstUser = state.messages.find((m: any) => m.role === 'user');
	if (!firstUser?.content) return;
	state.inputText = firstUser.content;
	onSend();
};

/** 会话创建时间格式化：今天只显示时分，跨天显示月-日 时分 */
const formatSessionTime = (created: any) => {
	if (!created) return '';
	const d = new Date(created);
	if (isNaN(d.getTime())) return '';
	const pad = (n: number) => String(n).padStart(2, '0');
	const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
	const now = new Date();
	return d.toDateString() === now.toDateString() ? hm : `${d.getMonth() + 1}-${pad(d.getDate())} ${hm}`;
};

/** 会话下拉框标签：标题 + 创建时间（后端生成的会话标题相同，靠时间区分） */
const formatSessionLabel = (sess: any) => {
	const base = sess.title || sess.templateName || sess.sessionId;
	const time = formatSessionTime(sess.created);
	return time ? `${base}（${time}）` : base;
};

/** 当前聚焦页面的文件名（去掉模板目录前缀，用于输入框提示） */
const currentFileName = computed(() => {
	const f = props.currentFile || '';
	const idx = f.lastIndexOf('/');
	return idx >= 0 ? f.substring(idx + 1) : f;
});

const state = reactive({
	loading: false,
	messages: [] as any[],
	inputText: '',
	chatting: false,
	// SSE status 事件文本（"正在接收文件内容…"等阶段性状态，done/error/结束时清空）
	statusText: '',
	abortController: null as AbortController | null,
	// 续连游标：最新已收事件 seq（SSE id 字段，RunChannel 单调递增）。断线重连/重开续看时
	// 作为 stream 端点 since 参数增量回放（不重播已收事件）；新一轮任务开始时归零（新 run 重新计数）
	lastSeq: 0,
	// 显式停止请求进行中（停止按钮 loading；停止走 stop 端点，断开连接不再取消任务）
	stopping: false,
	files: [] as any[],
	loadingFiles: false,
	// 旧模板样式组件化升级：探测结果（目录有 html 无 _pagespec.json 且升级未完成）与执行中状态
	legacyUpgradable: false,
	upgrading: false,
	// 升级进度（legacy-status 返回）：用于横幅区分"未升级"与"未完成续传"
	legacyPending: 0,
	legacyDone: 0,
	legacyTotal: 0,
	// 升级已完成且可深度焕新（计划存在 + 无待改造文件）：横幅变为"深度焕新"形态
	legacyRefinable: false,
	// 已完成的深度焕新轮次（后端计划文件记录；用于横幅文案与焕新引导）
	legacyRefreshCount: 0,
	// 上次升级/焕新后的对话修改轮数（后端计划文件记录）：焕新确认框据此提示
	// "N 轮微调将整合进新基线"，让用户放心焕新不会丢掉对话劳动
	legacyAdjustCount: 0,
	// 本轮 chat 请求是否为样式组件化升级（onUpgradeLegacy 设置，onSend 发出后复位）
	pendingStyleUpgrade: false,
	// 本轮 chat 请求是否为深度焕新（配合 styleUpgrade：升级完成后重置计划再改造一轮）
	pendingDeepRefresh: false,
	// 本轮 chat 请求是否为全量焕新（配合 deepRefresh：跳过 AI 范围评估，全部计划文件重做）
	pendingFullRefresh: false,
	// 本轮焕新用户填写的意见（可空；onSend 发出后复位）：对当前样式的具体不满，
	// 后端作为定向修正目标注入提示词 + 命中关键词（暗/密/素/乱）时方向按反馈定向
	pendingFeedback: '',
	// 全量注入开关（调整型会话）：默认 false 走聚焦注入（AI 按需检索），true 时全部模板文件注入提示词。
	// localStorage 持久化（跨会话记忆），发送消息时随请求体传给后端
	fullInject: Local.get('ai-template-full-inject') === true,
	fileDialogVisible: false,
	viewingFile: null as any,
});

/** 点选工具（换图/选区）可见：AI 工作台内预览列常驻，调整会话与生成会话均可点选预览页 */
const pickToolsVisible = computed(() => !!props.session?.sessionId || props.mode === 'adjust');

/** 全量注入开关切换：持久化；开启时的警示常驻输入框下方提示行（不走弹出框） */
const onFullInjectChange = (val: any) => {
	Local.set('ai-template-full-inject', val === true);
};

/** 点选工具禁用：对话进行中（避免与 AI 写盘冲突）/ 已应用会话（仅回看）/ 无会话 */
const pickDisabled = computed(() => state.chatting || isApplied.value || !props.session?.sessionId);
// ==================== AI 对话运行流（SSE 发送/续看/停止，下沉 useAiRunStream） ====================
// loadSessionData/scrollToBottom 以惰性 getter 传入（二者声明在后方，箭头延后求值规避 setup TDZ）；
// observeRunning（续看后台任务）/applyLegacyStatus（升级横幅状态）反向供本组件使用
const { onSend, onStop, observeRunning, applyLegacyStatus } = useAiRunStream({
	state,
	templateApi,
	getProps: () => props,
	emit,
	loadSessionData: () => loadSessionData(),
	scrollToBottom: () => scrollToBottom()
});

/**
 * 清洗历史 assistant 消息内容：旧版本解析失败时曾把原始 JSON 响应全文存库
 * （含 reply/files 等字段，刷新后会整坨 JSON 显示在聊天区）。这里提取 reply 字段
 * 展示；JSON 不完整（被截断）时用正则提取。仅展示层清洗，不改动数据库。
 */
const cleanHistoryContent = (m: any): string => {
	const text = String(m.content || '');
	if (m.role !== 'assistant' || !text.trimStart().startsWith('{') || !text.includes('"reply"')) {
		return text;
	}
	try {
		const parsed = JSON.parse(text);
		if (parsed && typeof parsed.reply === 'string') {
			return parsed.reply + '\n\n（该轮响应解析失败，文件未写盘）';
		}
	} catch (e) {
		/* JSON 不完整（被截断），走正则提取 */
	}
	const match = text.match(/"reply"\s*:\s*"((?:[^"\\]|\\.)*)"/);
	if (match) {
		return (
			match[1].replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\\\\/g, '\\') +
			'\n\n（该轮响应被截断，文件未写盘）'
		);
	}
	return text;
};

/**
 * 加载会话消息与文件列表
 *
 * 注意：必须声明在下方 watch 之前——watch 带 immediate: true 会在 setup 阶段同步执行回调，
 * 若本函数还处于 const 声明的暂时性死区（TDZ）内，将抛出
 * "Cannot access 'loadSessionData' before initialization" 导致组件初始化中断。
 */
const loadSessionData = async () => {
	if (!props.session?.sessionId) return;
	state.loading = true;
	try {
		const [messagesRes, filesRes, legacyRes, designRes] = await Promise.all([
			templateApi.listMessages(props.session.sessionId),
			templateApi.listFiles(props.session.sessionId),
			// 旧模板探测：决定文件区"升级为组件版"按钮显隐；接口异常时静默降级为不显示
			templateApi.legacyStatus(props.session.sessionId).catch(() => null),
			// 设计稿先行模式确认状态：plan 处于 AWAITING_CONFIRM 时返回卡片数据
			// （非 design 会话返回 null，接口异常静默降级为不恢复）
			templateApi.designStatus(props.session.sessionId).catch(() => null),
		]);
		applyLegacyStatus(legacyRes?.data);
		// 历史消息：思考面板默认收起（reasoning 落库后刷新仍可回看）；
		// content 经 cleanHistoryContent 清洗（兜底旧版本存库的原始 JSON 全文）
		if (messagesRes.data) {
			state.messages = messagesRes.data.map((m: any) => ({
				...m,
				content: cleanHistoryContent(m),
				reasoningExpanded: false,
			}));
		}
		if (filesRes.data) state.files = filesRes.data;
		// 切换/加载会话回到自动跟随模式（历史消息加载后滚到底部）
		userScrolledUp.value = false;
		// 进度卡恢复：plan 已持久化（会话对象携带），刷新页面后用 plan + 已生成文件重算全量进度，
		// 挂到最后一条 assistant 消息上，与生成过程中的进度卡视觉一致
		restoreProgressCard();
		// 确认卡片恢复：设计稿等待人工确认时刷新页面，卡片从 design-status 重建
		restoreConfirmCard(designRes?.data);
		scrollToBottom();
		// 任务运行态探测：关页后台续跑的任务在重开/刷新时自动续看——回放已发生的
		// 思考过程（journal 事件）到新 assistant 占位消息，再实时续接。探测失败静默
		// 降级（不续看，任务仍在后台跑，重新打开页面可再次续看）
		try {
			const runRes: any = await templateApi.runStatus(props.session.sessionId);
			if (runRes?.data?.running === true) {
				observeRunning(0);
			}
		} catch (e) {
			/* 运行态探测失败：静默降级 */
		}
	} catch (e) {
		console.error(e);
		ElMessage.error('加载会话数据失败');
	} finally {
		state.loading = false;
	}
};

/**
 * 刷新页面后恢复文件进度卡：
 * 会话的 planFiles（JSON 数组）与文件列表对比，已生成标 done、缺失标 pending
 */
const restoreProgressCard = () => {
	let plan: string[] = [];
	try {
		plan = props.session?.planFiles ? JSON.parse(props.session.planFiles) : [];
	} catch (e) {
		plan = [];
	}
	if (!plan || plan.length === 0) return;
	const doneSet = new Set(state.files.map((f: any) => f.filePath));
	const progress = plan.map((p: string) => ({
		path: p,
		status: doneSet.has(p) ? 'done' : 'pending',
	}));
	// 挂到最后一条 assistant 消息（汇总消息所在位置）
	for (let i = state.messages.length - 1; i >= 0; i--) {
		if (state.messages[i].role === 'assistant') {
			state.messages[i].progress = progress;
			break;
		}
	}
};

/**
 * 刷新页面后恢复设计稿确认卡片：
 * design-status 返回 AWAITING_CONFIRM 卡片数据时，挂到最后一条 assistant 消息
 * （与实时 confirm_request 事件挂载位置一致），approve/reject 按钮可用——
 * 状态以 plan.json 落盘为单一事实源，刷新不丢确认上下文
 */
const restoreConfirmCard = (data: any) => {
	if (!data || data.state !== 'AWAITING_CONFIRM') return;
	for (let i = state.messages.length - 1; i >= 0; i--) {
		if (state.messages[i].role === 'assistant') {
			state.messages[i].confirmCard = {
				issues: Array.isArray(data.issues) ? data.issues : [],
				previewUrl: data.previewUrl || '',
				confirmAuto: data.confirmAuto === true,
			};
			break;
		}
	}
};

// 切换会话时加载消息与文件（断开连接不取消任务：后台任务照常运行，切回可续看）
watch(() => props.session, (val) => {
	if (state.abortController) {
		state.abortController.abort();
		state.abortController = null;
	}
	state.chatting = false;
	state.lastSeq = 0;
	state.stopping = false;
	state.messages = [];
	state.files = [];
	state.inputText = '';
	if (val?.sessionId) {
		loadSessionData();
	}
}, { immediate: true });

onBeforeUnmount(() => {
	if (state.abortController) {
		state.abortController.abort();
		state.abortController = null;
	}
});

/**
 * 对外暴露：自动发送首条消息（父组件创建生成型会话后传入需求描述）
 *
 * 等待会话历史加载完成后再发送，避免 loadSessionData 的异步结果
 * 覆盖掉本轮刚推入的用户消息与 AI 占位消息
 */
const autoSend = async (input: string) => {
	if (!input || !props.session?.sessionId || state.chatting) return;
	// 等待历史加载结束（最多等 10 秒，防止异常情况下永久阻塞）
	for (let i = 0; i < 200 && state.loading; i++) {
		await new Promise((r) => setTimeout(r, 50));
	}
	state.inputText = input;
	onSend();
};

defineExpose({ autoSend, isChatting: () => state.chatting, loadSessionData, isFailed: () => isFailed.value });

// ==================== 旧模板样式组件化升级 / 焕新动作（下沉 useLegacyUpgrade） ====================
// 三个动作均走标准 chat 流：确认对话框收集参数后预填输入框 + 置 pending* 标志，委托 onSend 发出
const { onUpgradeLegacy, onDeepRefresh, onFullRefresh } = useLegacyUpgrade({
	state,
	getProps: () => props,
	onSend
});

/**
 * 补齐缺失文件：后端检测到 plan 未完成时会走断点续传流水线，只生成缺失部分。
 * 发送的文本内容不限，后端以会话原始需求为生成依据。
 */
const onResumeMissing = () => {
	if (state.chatting || !props.session?.sessionId) return;
	state.inputText = '请补齐缺失的文件';
	onSend();
};

// ==================== 设计稿先行模式：确认卡片动作 ====================

/**
 * 清除待确认卡片（按钮点击后立即置 handled 防重复提交；重开卡片由后端
 * 下一次 confirm_request 事件或刷新恢复重建，历史卡片只读展示）
 */
const markConfirmHandled = (msg: any) => {
	if (msg?.confirmCard) {
		msg.confirmCard.handled = true;
	}
};

/**
 * 确认转化：APPROVE 空输入（后端从 plan.json 恢复 CONVERTING 推进），
 * 转化过程仍走 SSE 流水（进度/降级/结果播报与设计段一致）
 */
const onConfirmApprove = (msg: any) => {
	if (state.chatting || isApplied.value) return;
	markConfirmHandled(msg);
	onSend({ confirmAction: 'APPROVE', input: '', displayText: '确认设计稿，开始转化为模板' });
};

/**
 * 驳回修改：弹窗收集修改意见（REJECT + input=意见文本，后端注入下一轮设计提示词
 * "用户具体不满"段），取消弹窗则卡片保留不动
 */
const onConfirmReject = async (msg: any) => {
	if (state.chatting || isApplied.value) return;
	let input = '';
	try {
		const { value } = await ElMessageBox.prompt(
			'请描述对设计稿的修改意见，AI 将按意见重新设计（留空则直接重新设计）',
			'驳回设计稿',
			{
				confirmButtonText: '重新设计',
				cancelButtonText: '取 消',
				inputType: 'textarea',
				inputPlaceholder: '例如：主色换成暖色调，首页 banner 改成轮播图，导航改为深色底',
			}
		);
		input = (value || '').trim();
	} catch {
		return;
	}
	markConfirmHandled(msg);
	onSend({
		confirmAction: 'REJECT',
		input,
		displayText: input ? `驳回设计稿：${input}` : '驳回设计稿，重新设计',
	});
};

const onPreviewTemplate = () => {
	if (!props.session?.templateName) return;
	// 调整型会话工作目录即正式模板目录，直接预览首页；
	// 生成型会话从文件列表解析入口页
	let entry = 'index.html';
	if (props.mode === 'generate') {
		const htmlFiles: string[] = state.files
			.map((f: any) => f.filePath)
			.filter((p: any) => {
				if (!p || !p.toLowerCase().endsWith('.html')) return false;
				return !p.split('/').pop()!.startsWith('_');
			});
		if (htmlFiles.length === 0) {
			ElMessage.warning('当前会话没有可预览的 HTML 页面文件');
			return;
		}
		entry = htmlFiles.includes('index.html') ? 'index.html' : htmlFiles[0];
	}
	const url = templateApi.previewUrl(props.session.sessionId, props.session.templateName, entry);
	window.open(url, '_blank');
};

const onViewFile = (file: any) => {
	state.viewingFile = file;
	state.fileDialogVisible = true;
};

const scrollToBottom = () => {
	nextTick(() => {
		const area = chatAreaRef.value;
		if (!area) return;
		// 用户主动上滚查看历史时暂停自动滚底，滚回底部（距底 <40px）自动恢复
		if (!userScrolledUp.value) {
			area.scrollTop = area.scrollHeight;
		}
		// 思考面板内部滚动条跟随流式输出：reasoning 增量追加在面板底部，
		// 若不跟随，新内容始终在可视区下方，看起来像"过程卡住了"
		const thinkingEl = area.querySelector('.chat-message:last-child .reasoning-text');
		if (thinkingEl) {
			thinkingEl.scrollTop = thinkingEl.scrollHeight;
		}
	});
};

/** 用户滚动状态：距底部超过 40px 视为"正在查看历史"，暂停自动跟随 */
const userScrolledUp = ref(false);
const onChatAreaScroll = () => {
	const el = chatAreaRef.value;
	if (!el) return;
	userScrolledUp.value = el.scrollHeight - el.scrollTop - el.clientHeight > 40;
};

// ===== 渲染窗口与渲染缓存（渲染进程内存治理） =====
// 仅渲染最近 N 条消息：超长升级会话历史几十轮，全量渲染 DOM 会把内存推到 GB 级
const MESSAGE_RENDER_LIMIT = 30;
const renderLimit = ref(MESSAGE_RENDER_LIMIT);
const visibleMessages = computed(() => state.messages.slice(-renderLimit.value));
// "最后一条消息"用引用比较（配合 visibleMessages 的局部下标偏移，index 比较会错位）
const isLastMessage = (msg: any) => msg === state.messages[state.messages.length - 1];

// 思考过程渲染（消息级 WeakMap 缓存）与进度统计下沉 useAiChatRender：
// 流式期间 150ms 节流更新若不缓存，全部历史消息每次都全量重算 HTML（KB 级思考文本
// ×几十条 × 6.6 次/秒 = 分配速率爆炸）；缓存键 = (消息对象, reasoning 全文, thinking 态)
const { renderReasoning, progressDoneCount, reasoningThinking } = useAiChatRender({
	isChatting: () => state.chatting,
	isLastMessage
});
</script>

<style scoped lang="scss">
.ai-chat-panel {
	display: flex;
	flex-direction: column;
	height: 100%;
	overflow: hidden;
}

.panel-header {
	display: flex;
	align-items: center;
	gap: 8px;
	margin-bottom: 10px;

	// modePill 四态：圆点 + 状态文案（adjust 橙 / generate 蓝 / applied 绿 / failed 红）
	.mode-pill {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		flex-shrink: 0;
		padding: 3px 10px;
		border-radius: 999px;
		font-size: 12px;
		line-height: 1;
		white-space: nowrap;

		&::before {
			content: '';
			width: 6px;
			height: 6px;
			border-radius: 50%;
			background: currentColor;
		}

		&.adjust {
			color: var(--el-color-warning);
			background: var(--el-color-warning-light-9);
		}

		&.generate {
			color: var(--el-color-primary);
			background: var(--el-color-primary-light-9);
		}

		&.applied {
			color: var(--el-color-success);
			background: var(--el-color-success-light-9);
		}

		&.failed {
			color: var(--el-color-danger);
			background: var(--el-color-danger-light-9);
		}
	}

	.session-select {
		flex: 1;
		min-width: 0;
	}

	.panel-title {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		font-size: 14px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
}

.chat-area {
	flex: 1;
	overflow-y: auto;
	padding: 8px;
	background: var(--el-fill-color-lighter);
	/* 渲染窗口：点击加载更早消息的入口条 */
	.load-earlier {
		text-align: center;
		margin: 4px 0 10px;
		font-size: 12px;
		color: var(--el-color-primary);
		cursor: pointer;
		user-select: none;
		&:hover { text-decoration: underline; }
	}
	border-radius: 6px;
	margin-bottom: 12px;
	min-height: 200px;
}

.chat-message {
	margin-bottom: 12px;

	.message-role {
		font-size: 12px;
		color: var(--el-text-color-secondary);
		margin-bottom: 4px;
	}

	&.user {
		.message-content {
			background: var(--el-color-primary-light-9);
		}
	}

	&.assistant {
		.message-content {
			background: var(--el-bg-color);
			border: 1px solid var(--el-border-color-lighter);
		}
	}

	.message-content {
		padding: 8px 12px;
		border-radius: 6px;
	}

	// 分批流水线进度卡
	.progress-box {
		margin-bottom: 8px;
		border: 1px solid var(--el-border-color-lighter);
		border-radius: 6px;
		overflow: hidden;
	}

	// 设计稿先行模式：审计通过后的确认卡片（问题清单 + 预览链接 + 确认/驳回）
	.confirm-box {
		margin-bottom: 8px;
		border: 1px solid var(--el-color-success-light-5);
		border-left: 3px solid var(--el-color-success);
		border-radius: 6px;
		background: var(--el-color-success-light-9);
		padding: 10px 12px;

		.confirm-header {
			display: flex;
			align-items: center;
			gap: 6px;
			font-size: 13px;
			font-weight: 600;
			color: var(--el-text-color-primary);

			.ci-done {
				color: var(--el-color-success);
			}

			.confirm-preview {
				margin-left: auto;
				font-size: 12px;
				font-weight: 400;
			}
		}

		.confirm-issues {
			margin: 8px 0 0;
			padding: 8px 10px;
			border-radius: 4px;
			background: var(--el-bg-color);
			border: 1px solid var(--el-border-color-lighter);
		}

		.ci-title {
			font-size: 12px;
			font-weight: 600;
			color: var(--el-text-color-regular);
			margin-bottom: 6px;
		}

		.ci-item {
			display: flex;
			align-items: baseline;
			gap: 8px;
			padding: 2px 0;
			font-size: 12px;
			color: var(--el-text-color-regular);
			line-height: 1.6;

			.ci-code {
				flex-shrink: 0;
			}
		}

		.confirm-actions {
			display: flex;
			align-items: center;
			gap: 8px;
			margin-top: 10px;

			.ci-auto-note {
				font-size: 12px;
				color: var(--el-text-color-secondary);
			}
		}

		.confirm-done-note {
			margin-top: 10px;
			font-size: 12px;
			color: var(--el-text-color-secondary);
		}
	}

	.progress-header {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 5px 10px;
		font-size: 12px;
		font-weight: 600;
		color: var(--el-text-color-regular);
		background: var(--el-fill-color-light);
	}

	.progress-items {
		padding: 4px 10px 6px;
		max-height: 200px;
		overflow-y: auto;
	}

	.progress-item {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 2px 0;
		font-size: 12px;
		font-family: 'Consolas', 'Monaco', monospace;
		color: var(--el-text-color-secondary);

		.pi-done {
			color: var(--el-color-success);
		}

		.pi-current {
			color: var(--el-color-primary);
		}

		.pi-pending {
			color: var(--el-text-color-placeholder);
		}

		.pi-path {
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}

		&.done .pi-path {
			color: var(--el-text-color-primary);
		}

		&.current .pi-path {
			color: var(--el-color-primary);
			font-weight: 600;
		}
	}

	.message-text {
		margin: 0;
		font-family: 'Consolas', 'Monaco', monospace;
		font-size: 13px;
		white-space: pre-wrap;
		word-break: break-all;
		line-height: 1.5;

		// 失败消息（后端落库的"生成失败："标记消息）红色醒目渲染
		&.failed {
			color: var(--el-color-danger);
		}
	}

	// AI 消息底部元信息条（token 消耗 + 复制按钮）：hover 该条消息时浮出
	.message-meta {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-top: 6px;
		font-size: 12px;
		color: var(--el-text-color-secondary);
		opacity: 0;
		transition: opacity 0.15s ease;
		user-select: none;

		.meta-tokens {
			display: inline-flex;
			align-items: center;
			gap: 3px;
		}

		.meta-copy {
			display: inline-flex;
			align-items: center;
			gap: 3px;
			margin-left: auto;
			cursor: pointer;
			color: var(--el-text-color-secondary);
			transition: color 0.15s ease;

			&:hover {
				color: var(--el-color-primary);
			}
		}
	}

	// hover 规则必须用 & 引用父级 .chat-message：直接写 .chat-message:hover 会被 SCSS
	// 拼接成 ".chat-message .chat-message:hover .message-meta"（永不匹配，元信息条永远不显示）
	&:hover .message-meta {
		opacity: 1;
	}

	// 生成过程中的阶段性状态条（输入框上方：正在接收文件内容等）
	.status-bar {
		display: flex;
		align-items: center;
		gap: 6px;
		margin-bottom: 8px;
		padding: 6px 10px;
		background: var(--el-color-primary-light-9);
		border-left: 3px solid var(--el-color-primary);
		border-radius: 4px;
		font-size: 12px;
		color: var(--el-color-primary);
	}

	// 失败会话的重新生成提示条（输入框上方）
	.regen-bar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		margin-bottom: 8px;
		padding: 6px 10px;
		background: var(--el-color-danger-light-9);
		border-left: 3px solid var(--el-color-danger);
		border-radius: 4px;

		.regen-tip {
			font-size: 12px;
			color: var(--el-color-danger);
		}
	}

	.typing-cursor {
		color: var(--el-color-primary);
		animation: cursor-blink 1s step-end infinite;
	}

	// 推理模型思考过程面板
	.reasoning-box {
		margin-bottom: 8px;
		border-left: 3px solid var(--el-color-info-light-5);
		background: var(--el-fill-color-light);
		border-radius: 4px;
	}

	.reasoning-header {
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 4px 8px;
		font-size: 12px;
		color: var(--el-text-color-secondary);
		cursor: pointer;
		user-select: none;

		&:hover {
			color: var(--el-text-color-primary);
		}
	}

	.reasoning-arrow {
		transition: transform 0.2s;

		&.collapsed {
			transform: rotate(0deg);
		}

		&:not(.collapsed) {
			transform: rotate(90deg);
		}
	}

	.reasoning-text {
		margin: 0;
		padding: 4px 10px 6px;
		font-size: 12px;
		color: var(--el-text-color-secondary);
		word-break: break-word;
		line-height: 1.6;
		max-height: 220px;
		overflow-y: auto;

		// v-html 注入内容不带 scoped 属性，须用 :deep()
		:deep(.ri-heading) {
			display: inline-block;
			font-weight: 600;
			color: var(--el-text-color-regular);
			margin: 4px 0 2px;
		}

		:deep(.ri-list-item) {
			display: inline-block;
			padding-left: 16px;
			text-indent: -16px;

			.ri-marker {
				color: var(--el-color-primary);
			}
		}

		:deep(code) {
			padding: 0 3px;
			font-family: 'Consolas', 'Monaco', monospace;
			font-size: 11px;
			background: var(--el-fill-color);
			border-radius: 3px;
		}

		:deep(strong) {
			font-weight: 600;
			color: var(--el-text-color-regular);
		}

		:deep(.typing-cursor) {
			color: var(--el-color-primary);
			animation: cursor-blink 1s step-end infinite;
		}
	}

	@keyframes cursor-blink {
		0%,
		100% {
			opacity: 1;
		}
		50% {
			opacity: 0;
		}
	}
}

.chat-input {
	// 点选模式提示条（输入框正上方）：模式开启时引导用户去预览页点选
	.pick-mode-tip {
		display: flex;
		align-items: center;
		gap: 4px;
		margin-bottom: 6px;
		padding: 4px 8px;
		font-size: 12px;
		color: var(--el-color-primary);
		background: var(--el-color-primary-light-9);
		border-radius: 4px;
	}

	// 全量注入开启时常驻警示（输入框正下方，警示色与点选提示的引导色区分）
	.full-inject-tip {
		display: flex;
		align-items: center;
		gap: 4px;
		margin-top: 6px;
		padding: 4px 8px;
		font-size: 12px;
		color: var(--el-color-warning);
		background: var(--el-color-warning-light-9);
		border-radius: 4px;
	}

	.chat-actions {
		margin-top: 8px;
		display: flex;
		align-items: center;

		.chat-tools {
			display: flex;
			align-items: center;
			gap: 8px;
		}

		// 全量注入开关（选区按钮右侧）：开关 + 标签，warning 色提示"昂贵模式"
		.full-inject-toggle {
			display: flex;
			align-items: center;
			gap: 4px;
			cursor: default;

			.full-inject-label {
				font-size: 12px;
				color: #909399;
				user-select: none;
			}

			&:has(.el-switch.is-checked) .full-inject-label {
				color: #e6a23c;
			}
		}

		// 工具按钮不存在时也保持发送按钮靠右
		.chat-send {
			margin-left: auto;
		}
	}
}

// 旧模板升级横幅（面板顶部，panel-header 之下）：不依赖文件列表存在，新建调整会话即可见
.legacy-upgrade-bar {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	margin-bottom: 8px;
	padding: 8px 10px;
	background: var(--el-color-warning-light-9);
	border-left: 3px solid var(--el-color-warning);
	border-radius: 4px;

	.legacy-upgrade-tip {
		font-size: 12px;
		color: var(--el-color-warning-dark-2);
	}
}

.files-area {
	margin-top: 12px;

	.files-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		flex-wrap: wrap;
		gap: 6px;
		margin-bottom: 8px;
		font-weight: 500;
	}
}

.file-content {
	margin: 0;
	padding: 12px;
	font-family: 'Consolas', 'Monaco', monospace;
	font-size: 13px;
	white-space: pre-wrap;
	word-break: break-all;
	line-height: 1.5;
	background: var(--el-fill-color-lighter);
	border-radius: 4px;
}
</style>

<style lang="scss">
// 会话下拉选项：标题在左，创建时间浅色靠右（下拉 popper teleport 到 body，需全局选择器）
.el-select-dropdown .session-option-time {
	float: right;
	color: var(--el-text-color-secondary);
	font-size: 12px;
	margin-left: 16px;
}
// 分组选项状态徽章（待应用/已应用）：紧跟标题左侧
.el-select-dropdown .session-option-tag {
	margin-right: 6px;
}
</style>
