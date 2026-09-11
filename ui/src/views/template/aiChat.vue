<template>
	<div class="ai-chat-panel">
		<!-- 会话信息：会话切换/新建（由父组件管理会话数据，本组件只负责展示与转发事件） -->
		<div class="panel-header">
			<el-select
				v-if="sessions && sessions.length > 0"
				:model-value="session?.sessionId"
				placeholder="选择会话"
				size="small"
				class="session-select"
				@change="(v: string) => emit('select-session', v)"
			>
				<el-option v-for="sess in sessions" :key="sess.sessionId" :value="sess.sessionId"
					:label="formatSessionLabel(sess)">
					<span>{{ sess.title || sess.templateName || sess.sessionId }}</span>
					<span class="session-option-time">{{ formatSessionTime(sess.created) }}</span>
				</el-option>
			</el-select>
			<span v-else class="panel-title">{{ session?.title || (mode === 'adjust' ? 'AI 调整模板' : 'AI 生成模板') }}</span>
			<el-button v-if="mode === 'adjust'" size="small" text type="primary" :loading="creatingSession"
				@click="emit('new-session')">
				<el-icon><ele-Plus /></el-icon>新建会话
			</el-button>
			<el-tag v-if="mode === 'adjust'" size="small" type="warning">直接修改正式模板</el-tag>
		<el-tag v-if="mode === 'generate' && isApplied" size="small" type="success">已应用（仅回看）</el-tag>
			<el-tag v-if="mode === 'generate' && isFailed" size="small" type="danger">生成失败</el-tag>
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
			<div v-for="(msg, msgIndex) in state.messages" :key="msgIndex" class="chat-message" :class="msg.role">
				<div class="message-role">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
				<div class="message-content">
					<!-- 分批流水线进度卡：规划完成后逐文件点亮（后端 progress 事件全量快照） -->
					<div v-if="msg.progress && msg.progress.length" class="progress-box">
						<div class="progress-header">
							<span>文件生成进度（{{ progressDoneCount(msg) }}/{{ msg.progress.length }}）</span>
							<el-button
							v-if="mode === 'generate' && !isApplied && !state.chatting && msgIndex === state.messages.length - 1
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
					<!-- 推理模型思考过程（可折叠，思考中默认展开） -->
					<div v-if="msg.reasoning" class="reasoning-box">
						<div class="reasoning-header" @click="msg.reasoningExpanded = !msg.reasoningExpanded">
							<el-icon class="reasoning-arrow" :class="{ collapsed: !msg.reasoningExpanded }"><ele-ArrowRight /></el-icon>
							<span>{{ reasoningThinking(msg, msgIndex) ? '思考中...' : '已深度思考' }}</span>
						</div>
						<div
							v-show="msg.reasoningExpanded"
							class="reasoning-text"
							v-html="renderReasoning(msg.reasoning, reasoningThinking(msg, msgIndex))"
						></div>
					</div>
					<pre class="message-text" :class="{ failed: isFailMessage(msg) }">{{ msg.content }}<span
						v-if="state.chatting && msgIndex === state.messages.length - 1 && !msg.reasoning"
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
					<el-button v-if="state.chatting" type="danger" @click="onStop">
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
					<el-button v-if="mode === 'adjust'" size="small" text type="danger" @click="onRollback" :loading="state.rollingBack">
						<el-icon><ele-RefreshLeft /></el-icon>回滚最近一次修改
					</el-button>
						<el-button size="small" text @click="onPreviewTemplate">
						<el-icon><ele-View /></el-icon>预览
					</el-button>
					<el-button v-if="mode === 'generate' && !isApplied && !sessionActive && state.files.length > 0" size="small" text type="primary" @click="emit('edit-files')">
						<el-icon><ele-Edit /></el-icon>编辑文件
					</el-button>
					<el-button v-if="mode === 'generate' && !isApplied" type="success" size="small" @click="onApplyTemplate" :loading="state.applying">
						<el-icon><ele-Check /></el-icon>应用模板
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
				<el-table-column label="操作" width="70">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onViewFile(scope.row)">查看</el-button>
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
import { Local, Session } from '/@/utils/storage';

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
	/** 可切换的会话列表（父组件管理，为空时面板头部只显示标题） */
	sessions?: any[];
	/** 新建会话请求进行中（按钮 loading） */
	creatingSession?: boolean;
	/** 父组件已处于会话编辑模式：隐藏「编辑文件」入口（已在编辑，点击反而会清空当前编辑文件） */
	sessionActive?: boolean;
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
	/** 进入会话编辑模式（父组件把文件树/编辑器/预览切到会话工作目录） */
	(e: 'edit-files'): void;
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

/** 失败消息统一前缀（与后端 AiTemplateConstants.MSG_FAIL_PREFIX 对齐） */
const FAIL_MSG_PREFIX = '生成失败：';

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
	files: [] as any[],
	loadingFiles: false,
	applying: false,
	rollingBack: false,
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
	// 本轮 chat 请求是否为样式组件化升级（onUpgradeLegacy 设置，onSend 发出后复位）
	pendingStyleUpgrade: false,
	// 本轮 chat 请求是否为深度焕新（配合 styleUpgrade：升级完成后重置计划再改造一轮）
	pendingDeepRefresh: false,
	// 本轮 chat 请求是否为全量焕新（配合 deepRefresh：跳过 AI 范围评估，全部计划文件重做）
	pendingFullRefresh: false,
	// 全量注入开关（调整型会话）：默认 false 走聚焦注入（AI 按需检索），true 时全部模板文件注入提示词。
	// localStorage 持久化（跨会话记忆），发送消息时随请求体传给后端
	fullInject: Local.get('ai-template-full-inject') === true,
	fileDialogVisible: false,
	viewingFile: null as any,
});

/** 点选工具（换图/选区）可见：仅预览列存在的模式（adjust / 会话编辑视图）下才有可点选的预览页 */
const pickToolsVisible = computed(() => props.mode === 'adjust' || !!props.sessionActive);

/** 全量注入开关切换：持久化；开启时的警示常驻输入框下方提示行（不走弹出框） */
const onFullInjectChange = (val: any) => {
	Local.set('ai-template-full-inject', val === true);
};

/** 点选工具禁用：对话进行中（避免与 AI 写盘冲突）/ 已应用会话（仅回看）/ 无会话 */
const pickDisabled = computed(() => state.chatting || isApplied.value || !props.session?.sessionId);

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
 * 应用升级状态查询结果（legacy-status 返回 {upgradable, pendingCount, doneCount, totalFiles}）
 *
 * 兼容旧版布尔返回（升级完成横幅消失语义一致）；进度数据驱动横幅
 * 区分"未升级"与"上次升级未完成，可断点续传"两种文案形态。
 */
const applyLegacyStatus = (data: any) => {
	if (data && typeof data === 'object') {
		state.legacyUpgradable = data.upgradable === true;
		state.legacyRefinable = data.refinable === true;
		state.legacyPending = data.pendingCount || 0;
		state.legacyDone = data.doneCount || 0;
		state.legacyTotal = data.totalFiles || 0;
		state.legacyRefreshCount = data.refreshCount || 0;
	} else {
		// 旧版布尔返回或异常空值：仅控制横幅显隐
		state.legacyUpgradable = data === true;
		state.legacyRefinable = false;
		state.legacyPending = 0;
		state.legacyDone = 0;
		state.legacyTotal = 0;
		state.legacyRefreshCount = 0;
	}
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
		const [messagesRes, filesRes, legacyRes] = await Promise.all([
			templateApi.listMessages(props.session.sessionId),
			templateApi.listFiles(props.session.sessionId),
			// 旧模板探测：决定文件区"升级为组件版"按钮显隐；接口异常时静默降级为不显示
			templateApi.legacyStatus(props.session.sessionId).catch(() => null),
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
		scrollToBottom();
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

// 切换会话时加载消息与文件
watch(() => props.session, (val) => {
	if (state.abortController) {
		state.abortController.abort();
		state.abortController = null;
	}
	state.chatting = false;
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

defineExpose({ autoSend });

const onSend = async () => {
	if (!state.inputText.trim() || !props.session?.sessionId) return;

	// 新一轮对话回到自动跟随模式
	userScrolledUp.value = false;

	// 先把用户输入加入消息列表（UI 即时反馈）
	state.messages.push({
		role: 'user',
		content: state.inputText,
		created: new Date().toISOString(),
	});

	const userInput = state.inputText;
		const styleUpgrade = state.pendingStyleUpgrade;
		const deepRefresh = state.pendingDeepRefresh;
		const fullRefresh = state.pendingFullRefresh;
	state.inputText = '';
	state.chatting = true;

	if (state.abortController) {
		state.abortController.abort();
	}
	const controller = new AbortController();
	state.abortController = controller;

	// 累积 AI 响应文本（后端流式推送 message 事件为高频小增量，逐段拼接即打字机效果）
	let assistantContent = '';
	let reasoningContent = '';
	state.messages.push({
		role: 'assistant',
		content: '',
		reasoning: '',
		reasoningExpanded: true,
		progress: [] as any[],
		created: new Date().toISOString(),
	});
	const assistantIndex = state.messages.length - 1;

	// ===== 流式渲染节流 =====
	// SSE chunk 高频到达（推理模型长思考期间每秒可达上百个），若每个 chunk 都把
	// 全量 reasoning/content 写入响应式状态，会触发 renderReasoning 全量重算 +
	// v-html 整棵 DOM 子树销毁重建，分配速率远超 GC 回收（实测长思考轮次
	// Chrome 内存飙升 4GB+ 直至回复结束）。双管齐下：
	// - 攒批降频：STREAM_FLUSH_MS 内的增量合并为一次渲染
	// - 渲染窗口：流式期间 reasoning 仅渲染尾部窗口（思考面板滚动本就跟随尾部），
	//   单次渲染成本恒定；结束（done/error/停止/异常）时 flushStream 全量展示
	const STREAM_FLUSH_MS = 150;
	const REASONING_RENDER_WINDOW = 6000;
	let flushTimer: ReturnType<typeof setTimeout> | null = null;
	let lastFlushAt = 0;

	const applyStream = (full: boolean) => {
		const msg = state.messages[assistantIndex];
		if (!msg) return;
		msg.content = assistantContent;
		msg.reasoning = !full && reasoningContent.length > REASONING_RENDER_WINDOW
			? '…（思考过长，流式期间仅显示尾部，完成后可查看全文）\n' + reasoningContent.slice(-REASONING_RENDER_WINDOW)
			: reasoningContent;
		scrollToBottom();
	};

	const scheduleFlush = () => {
		if (flushTimer !== null) return;
		const wait = Math.max(0, STREAM_FLUSH_MS - (Date.now() - lastFlushAt));
		flushTimer = setTimeout(() => {
			flushTimer = null;
			lastFlushAt = Date.now();
			applyStream(false);
		}, wait);
	};

	const flushStream = () => {
		if (flushTimer !== null) {
			clearTimeout(flushTimer);
			flushTimer = null;
		}
		lastFlushAt = 0;
		applyStream(true);
	};

	const finish = () => {
		// 结束路径统一收口：清掉挂起的节流定时器并把完整内容一次落库展示
		flushStream();
		state.abortController = null;
		state.chatting = false;
		state.statusText = '';
		state.upgrading = false;
	};

	const refreshFiles = () => {
		templateApi.listFiles(props.session.sessionId).then((res: any) => {
			if (res.data) state.files = res.data;
		}).catch(() => {});
	};

	/**
	 * SSE file 事件实时更新文件列表（done 后 refreshFiles 会用服务器数据覆盖，
	 * 此处只需保证流式过程中列表即时可见）
	 */
	const upsertFile = (path: string, action: string) => {
		const idx = state.files.findIndex((f: any) => f.filePath === path);
		if (idx >= 0) {
			state.files.splice(idx, 1, { ...state.files[idx], action });
		} else {
			state.files.push({ filePath: path, action, content: '' });
		}
	};

	const handleDone = () => {
		const last = state.messages[assistantIndex];
		if (last && last.reasoning) {
			last.reasoningExpanded = false;
		}
		finish();
		refreshFiles();
		// 升级轮结束后刷新升级状态（升级完成则横幅消失；中断续传则横幅显示剩余进度）
		if (styleUpgrade && props.session?.sessionId) {
			templateApi.legacyStatus(props.session.sessionId).then((res: any) => {
				applyLegacyStatus(res?.data);
			}).catch(() => {});
		}
		// AI 已写盘，通知父组件刷新文件树/编辑器
		emit('files-changed');
	};

	const handleError = (e: any) => {
		let msg = '生成失败';
		if (e.data) {
			try {
				const data = JSON.parse(e.data);
				msg = data.message || msg;
			} catch (err) {
				/* ignore */
			}
		}
		// 与后端落库逻辑一致：失败写入当前 assistant 占位消息，
		// 即时触发失败态 UI（红色消息 + 重新生成入口），无需刷新页面
		const last = state.messages[assistantIndex];
		if (last && !last.content) {
			last.content = FAIL_MSG_PREFIX + msg;
		}
		ElMessage.error(msg);
		finish();
		// 升级轮失败（如某文件改造解析失败中断）：刷新升级状态，
		// 横幅转为"继续升级"形态展示剩余进度（用户可一键续传）
		if (styleUpgrade && props.session?.sessionId) {
			templateApi.legacyStatus(props.session.sessionId).then((res: any) => {
				applyLegacyStatus(res?.data);
			}).catch(() => {});
		}
	};

	try {
		const token = Local.get('token') as string | undefined;
		const resp = await fetch(templateApi.chatUrl(props.session.sessionId), {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				...(token ? { Authorization: 'Bearer ' + token } : {})
			},
			body: JSON.stringify({
				input: userInput,
				currentFile: props.currentFile || '',
				focusSectionId: props.focusSection || '',
				focusElementHint: props.focusElementHint || '',
			styleUpgrade,
			deepRefresh,
			fullRefresh,
			fullInject: state.fullInject === true
		}),
		signal: controller.signal
	});
		// 请求已发出，升级标志复位（下一轮普通对话不带该标志）
		state.pendingStyleUpgrade = false;
		state.pendingDeepRefresh = false;
		state.pendingFullRefresh = false;

		if (!resp.ok || !resp.body) {
			let msg = '请求失败（' + resp.status + '）';
			try {
				const errRes = await resp.json();
				if (errRes && errRes.msg) msg = errRes.msg;
			} catch (err) {
				/* ignore */
			}
			// HTTP 层失败同样写入占位消息触发失败态 UI
			const last = state.messages[assistantIndex];
			if (last && !last.content) {
				last.content = FAIL_MSG_PREFIX + msg;
			}
			// 401 = 登录过期/已在别处登录：AI 对话走原生 fetch，axios 的 401 拦截器不生效，
			// 此处清缓存 + 弹窗提示 + 跳转管理后台入口（/fastcms → SPA 检测无 token 自动进登录页）
			if (resp.status === 401) {
				Session.clear();
				Local.clear();
				finish();
				ElMessageBox.alert('你已被登出，请重新登录', '提示', {})
					.then(() => { window.location.href = '/fastcms'; })
					.catch(() => {});
				return;
			}
			ElMessage.error(msg);
			finish();
			return;
		}

		const reader = resp.body.getReader();
		const decoder = new TextDecoder('utf-8');
		let buf = '';
		let currentEvent = 'message';
		let currentData: string[] = [];
		let sawEvent = false;

		const dispatch = () => {
			if (currentData.length === 0) {
				currentEvent = 'message';
				return;
			}
			const data = currentData.join('\n');
			switch (currentEvent) {
				case 'message':
					assistantContent += data;
					// 节流渲染：不直接写响应式状态（每 chunk 全量重渲会打爆内存），攒批 150ms
					scheduleFlush();
					break;
				case 'reasoning':
					reasoningContent += data;
					scheduleFlush();
					break;
				case 'file':
					// AI 每写完一个文件推送一次：实时更新文件列表 + 通知父组件（刷新实时预览）。
					// 阶段状态条不在此清除：由后端状态链负责（新 status 覆盖旧文案，结束时发空 status 清除）
					try {
						const info = JSON.parse(data);
						if (info.path) {
							upsertFile(info.path, info.action || 'modify');
							emit('file-written', info.path);
						}
					} catch (err) {
						/* 忽略格式异常的 file 事件 */
					}
					break;
				case 'switch-file':
					// 调整/升级对话流式期间，后端识别到 AI 正在处理的首个可路由 HTML 即推送：
					// 通知父组件把实时预览切到该页面（先看旧版本，写盘后经 file 事件刷新重载新内容）
					try {
						const info = JSON.parse(data);
						if (info.path) {
							emit('switch-file', info.path);
						}
					} catch (err) {
						/* 忽略格式异常的 switch-file 事件 */
					}
					break;
			case 'progress':
				// 分批流水线进度快照（全量文件清单及状态），更新 AI 消息内的进度卡
				try {
					const info = JSON.parse(data);
					if (info.files) {
						state.messages[assistantIndex].progress = info.files;
						scrollToBottom();
					}
				} catch (err) {
					/* 忽略格式异常的 progress 事件 */
				}
				break;
			case 'status':
					// 阶段性状态提示（如 reply 已流完、files 内容仍在传输），
					// 显示在输入区上方状态行，消除"回复已结束却长时间转圈"的假死观感
					if (data) {
						state.statusText = data;
					}
					break;
				case 'usage':
					// 本轮 token 用量（done 之后到达）：挂到本条 assistant 消息，hover 底部展示
					try {
						const u = JSON.parse(data);
						state.messages[assistantIndex].promptTokens = u.promptTokens ?? 0;
						state.messages[assistantIndex].completionTokens = u.completionTokens ?? 0;
						state.messages[assistantIndex].totalTokens = u.totalTokens ?? 0;
					} catch (err) {
						/* 忽略格式异常的 usage 事件 */
					}
					break;
				case 'done':
					handleDone({ data });
					break;
				case 'error':
					handleError({ data });
					break;
				default:
					break;
			}
			currentEvent = 'message';
			currentData = [];
		};

		// 逐块读取 SSE 流，按行解析（兼容 \r\n / \n）
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			buf += decoder.decode(value, { stream: true });
			let idx: number;
			while ((idx = buf.indexOf('\n')) >= 0) {
				const raw = buf.slice(0, idx);
				buf = buf.slice(idx + 1);
				const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;

				if (line === '') {
					// 空行 = 事件结束
					if (sawEvent) dispatch();
					currentEvent = 'message';
					currentData = [];
					sawEvent = false;
				} else if (line.startsWith('event:')) {
					const name = line.slice(6).trim();
					if (name) {
						currentEvent = name;
						sawEvent = true;
					}
				} else if (line.startsWith('data:')) {
					currentData.push(line.slice(5).replace(/^ /, ''));
					sawEvent = true;
				}
				// 其余字段（id:/retry:/注释）忽略
			}
		}
		// 流结束但后端未发 done/error 时收尾
		if (state.chatting) {
			finish();
			refreshFiles();
			emit('files-changed');
		}
	} catch (e: any) {
		if (e?.name === 'AbortError') {
			// 用户主动停止，不提示错误
			finish();
		} else {
			console.error(e);
			const msg = e?.message || '网络错误';
			// 网络异常同样写入占位消息触发失败态 UI
			const last = state.messages[assistantIndex];
			if (last && !last.content) {
				last.content = FAIL_MSG_PREFIX + msg;
			}
			ElMessage.error('生成失败：' + msg);
			finish();
		}
	}
};

const onStop = () => {
	if (state.abortController) {
		state.abortController.abort();
		state.abortController = null;
	}
	state.chatting = false;
};

/**
 * 补齐缺失文件：后端检测到 plan 未完成时会走断点续传流水线，只生成缺失部分。
 * 发送的文本内容不限，后端以会话原始需求为生成依据。
 */
const onResumeMissing = () => {
	if (state.chatting || !props.session?.sessionId) return;
	state.inputText = '请补齐缺失的文件';
	onSend();
};

const onRollback = () => {
	if (!props.session?.sessionId) return;
	ElMessageBox.confirm(
		'将把最近一轮 AI 修改的文件恢复到该轮修改前的状态（此后各轮对这些文件的改动也会一并撤销），是否继续？',
		'回滚最近一次修改',
		{ confirmButtonText: '回 滚', cancelButtonText: '取 消', type: 'warning' }
	).then(async () => {
		state.rollingBack = true;
		try {
			const res = await templateApi.rollbackLast(props.session.sessionId);
			if (res.data) {
				ElMessage.success(res.data);
				await loadSessionData();
				emit('files-changed');
			} else if (res.msg) {
				ElMessage.error(res.msg);
			}
		} catch (e: any) {
			ElMessage.error(e?.message || '回滚失败');
		} finally {
			state.rollingBack = false;
		}
	}).catch(() => {});
};

const onApplyTemplate = () => {
	if (!props.session?.sessionId) return;
	ElMessageBox.confirm('确认将此模板应用到正式模板目录？应用后将切换到正式模板编辑。', '提示', {
		type: 'warning',
	}).then(async () => {
		state.applying = true;
		try {
			const res = await templateApi.applyTemplate(props.session.sessionId);
			if (res.data) {
				// 后端返回 ApplyResult：{ message, templateId }（应用后的正式模板 ID）
				ElMessage.success(res.data.message || '应用成功');
				emit('applied', res.data.templateId);
			} else if (res.msg) {
				ElMessage.error(res.msg);
			}
		} catch (e: any) {
			ElMessage.error(e?.message || '应用失败');
		} finally {
			state.applying = false;
		}
	}).catch(() => {});
};

/**
 * 旧模板「样式组件化升级」（AI 改造管线）
 *
 * 流程：确认后走 chat SSE（styleUpgrade 标志）：后端确定性前置（备份/锚点扫描/组件 CSS 引入）
 * → AI 分批改造页面（保留 id/JS 锚点/脚本/FreeMarker，追加 utility class）→ 写盘前锚点校验
 * → 渲染校验。进度在对话流实时展示；中断后再次发起从断点续传。
 */
const onUpgradeLegacy = () => {
	if (!props.session?.sessionId || state.chatting) return;
	const resuming = state.legacyTotal > 0 && state.legacyDone > 0;
	ElMessageBox.confirm(
		resuming
			? `上次样式组件化升级未完成（已完成 ${state.legacyDone}/${state.legacyTotal} 个页面）。继续升级将从剩余 ${state.legacyPending} 个页面断点续传：保留全部 JS 功能、元素 id 与脚本，引入组件库 CSS 焕新页面视觉。是否继续？`
			: '将对此模板执行样式组件化升级：保留全部 JS 功能、元素 id 与脚本，引入组件库 CSS 焕新页面视觉。原文件自动备份，改造过程由 AI 分批完成（可在对话中看到进度，中断后可续传）。是否继续？',
		resuming ? '继续样式组件化升级' : '样式组件化升级',
		{ confirmButtonText: resuming ? '继续升级' : '开始升级', cancelButtonText: '取 消', type: 'warning' }
	).then(() => {
		state.upgrading = true;
		// 走标准 chat 流：输入框填入升级指令并携带 styleUpgrade 标志发送
		state.inputText = resuming
			? '继续样式组件化升级（从剩余页面断点续传，保留网站功能，焕新页面视觉）'
			: '开始样式组件化升级（保留网站功能，焕新页面视觉）';
		state.pendingStyleUpgrade = true;
		onSend();
	}).catch(() => {});
};

/**
 * 「智能焕新」（升级完成后的精准重刷，默认推荐）
 *
 * 先由 AI 评估各页面的方向耦合度，判定最小重做集合（必含 _layout.html 公共布局，
 * 通常还有首页等含 hero/深色区的页面）；未选中的页面保留当前版本，经 tokens.css
 * 变量自动换肤。耗时可降到全量的 1/3 左右。评估失败自动回退全量焕新。
 */
const onDeepRefresh = () => {
	if (!props.session?.sessionId || state.chatting) return;
	ElMessageBox.confirm(
		'智能焕新：AI 先评估哪些页面与新设计方向强耦合（公共布局/首页等），只重做这些页面；其余页面保留并通过主题变量自动换肤，速度快、消耗少。评估失败时自动回退全量焕新。JS 功能与元素 id 仍全部保留，原备份不变。是否继续？',
		'智能焕新',
		{ confirmButtonText: '开始焕新', cancelButtonText: '取 消', type: 'warning' }
	).then(() => {
		state.upgrading = true;
		// 走标准 chat 流：携带 styleUpgrade + deepRefresh 标志（后端先做范围评估再重置计划）
		state.inputText = '智能焕新样式组件化（AI 评估范围，重做方向耦合页面含 _layout.html，其余保留换肤，重写组件样式库，保留网站功能）';
		state.pendingStyleUpgrade = true;
		state.pendingDeepRefresh = true;
		state.pendingFullRefresh = false;
		onSend();
	}).catch(() => {});
};

/**
 * 「全量焕新」（整体换设计方向的兜底选项）
 *
 * 跳过范围评估，全部计划页面（含 _layout.html）恢复原始备份底稿重新改造一轮。
 * 适合对整体风格彻底不满意、想整套换设计方向的场景。
 */
const onFullRefresh = () => {
	if (!props.session?.sessionId || state.chatting) return;
	ElMessageBox.confirm(
		'全量焕新：跳过范围评估，全部页面（含 _layout.html 公共布局）恢复原始底稿、以新的设计方向整体重新改造，并重写组件样式库。耗时与 token 消耗为智能焕新的数倍，建议先试智能焕新。是否继续？',
		'全量焕新',
		{ confirmButtonText: '开始全量焕新', cancelButtonText: '取 消', type: 'warning' }
	).then(() => {
		state.upgrading = true;
		state.inputText = '全量焕新样式组件化（重置计划，全部页面含 _layout.html 重新改造，重写组件样式库，保留网站功能）';
		state.pendingStyleUpgrade = true;
		state.pendingDeepRefresh = true;
		state.pendingFullRefresh = true;
		onSend();
	}).catch(() => {});
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

// 判断消息是否处于"思考中"：对话进行中 + 最后一条消息 + 正文尚未开始输出
const reasoningThinking = (msg: any, msgIndex: number) => {
	return state.chatting && msgIndex === state.messages.length - 1 && !msg.content;
};

// 进度卡已完成文件数
const progressDoneCount = (msg: any) => {
	return (msg.progress || []).filter((f: any) => f.status === 'done').length;
};

// 思考过程渲染：模型思考文本常混有 markdown 结构（## 标题、- 列表、**加粗**），
// 纯文本显示这些符号可读性差。这里先做 HTML 转义（防注入），再做轻量 markdown
// 格式化 + 超长段落按句断行，中英文都适用
const renderReasoning = (text: string, thinking: boolean): string => {
	if (!text) return '';
	const escaped = text
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;');
	const lines = escaped.split(/\n/);
	const out: string[] = [];
	for (const raw of lines) {
		const line = raw.trim();
		if (!line) {
			out.push('');
			continue;
		}
		// markdown 标题（# ~ ######）→ 独立加粗行
		const heading = line.match(/^#{1,6}\s+(.*)$/);
		if (heading) {
			out.push(`<span class="ri-heading">${inlineFmt(heading[1])}</span>`);
			continue;
		}
		// 列表项（- / * / 1.）→ 缩进行，保留原始序号/圆点标记
		const item = line.match(/^([-*]|\d+[.)])\s+(.*)$/);
		if (item) {
			out.push(`<span class="ri-list-item"><span class="ri-marker">${item[1]}</span> ${inlineFmt(item[2])}</span>`);
			continue;
		}
		// 普通段落：按句断行后输出（含行内格式）
		out.push(
			breakSentences(line)
				.split('\n')
				.map((l) => (l ? inlineFmt(l) : ''))
				.join('<br>')
		);
	}
	let html = out.join('<br>');
	if (thinking) html += '<span class="typing-cursor">▌</span>';
	return html;
};

// 行内格式：**加粗**、`代码`
const inlineFmt = (s: string): string =>
	s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/`([^`]+)`/g, '<code>$1</code>');

// 按句断行：中文句末标点直接断；英文句末标点仅在后跟大写/数字时断（避免误伤小数、版本号）
const breakSentences = (s: string): string =>
	s.replace(/([。！？；])\s*/g, '$1\n').replace(/([.!?;])\s+(?=[A-Z0-9])/g, '$1\n');
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
</style>
