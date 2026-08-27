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
		</div>

		<!-- 对话区域 -->
		<div class="chat-area" ref="chatAreaRef">
			<div v-for="(msg, msgIndex) in state.messages" :key="msgIndex" class="chat-message" :class="msg.role">
				<div class="message-role">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
				<div class="message-content">
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
					<pre class="message-text">{{ msg.content }}<span
						v-if="state.chatting && msgIndex === state.messages.length - 1 && !msg.reasoning"
						class="typing-cursor"
					>▌</span></pre>
				</div>
			</div>
			<el-empty v-if="!state.loading && state.messages.length === 0"
				:description="mode === 'adjust' ? '描述你想调整的内容，例如：把首页导航改为深色，banner 换成轮播图' : '开始对话生成模板'" />
		</div>

		<!-- 输入区域 -->
		<div class="chat-input">
			<el-input
				v-model="state.inputText"
				type="textarea"
				:rows="3"
				:placeholder="mode === 'adjust'
					? (currentFileName ? `AI 当前聚焦页面：${currentFileName}。描述你想调整的内容，例如：把这里的导航改为深色` : '描述你想调整的内容，例如：把首页导航改为深色、文章列表改为卡片式布局')
					: '描述你的需求，例如：生成一个企业官网模板，蓝色调，响应式设计'"
				:disabled="state.chatting"
			/>
			<div class="chat-actions">
				<el-button type="primary" @click="onSend" :loading="state.chatting" :disabled="!state.inputText.trim()">
					<el-icon><ele-Promotion /></el-icon>{{ state.chatting ? '生成中...' : '发送' }}
				</el-button>
				<el-button v-if="state.chatting" type="danger" @click="onStop">
					<el-icon><ele-VideoPause /></el-icon>停止
				</el-button>
			</div>
		</div>

		<!-- 文件列表区域 -->
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
					<el-button v-if="mode === 'generate'" type="success" size="small" @click="onApplyTemplate" :loading="state.applying">
						<el-icon><ele-Check /></el-icon>应用模板
					</el-button>
				</div>
			</div>
			<el-table :data="state.files" stripe size="small" max-height="180">
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
import { Local } from '/@/utils/storage';

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
	/** 可切换的会话列表（父组件管理，为空时面板头部只显示标题） */
	sessions?: any[];
	/** 新建会话请求进行中（按钮 loading） */
	creatingSession?: boolean;
}>();

const emit = defineEmits<{
	/** AI 写盘后通知（父组件刷新文件树/编辑器） */
	(e: 'files-changed'): void;
	/** AI 每写完一个文件的实时通知（SSE file 事件，父组件用于刷新实时预览） */
	(e: 'file-written', path: string): void;
	/** 生成型会话应用模板成功 */
	(e: 'applied'): void;
	/** 切换会话（sessionId） */
	(e: 'select-session', sessionId: string): void;
	/** 新建会话 */
	(e: 'new-session'): void;
}>();

const templateApi = AiTemplateApi();
const chatAreaRef = ref();

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
	abortController: null as AbortController | null,
	files: [] as any[],
	loadingFiles: false,
	applying: false,
	rollingBack: false,
	fileDialogVisible: false,
	viewingFile: null as any,
});

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
		const [messagesRes, filesRes] = await Promise.all([
			templateApi.listMessages(props.session.sessionId),
			templateApi.listFiles(props.session.sessionId),
		]);
		// 历史消息：思考面板默认收起（reasoning 落库后刷新仍可回看）
		if (messagesRes.data) {
			state.messages = messagesRes.data.map((m: any) => ({ ...m, reasoningExpanded: false }));
		}
		if (filesRes.data) state.files = filesRes.data;
	} catch (e) {
		console.error(e);
		ElMessage.error('加载会话数据失败');
	} finally {
		state.loading = false;
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

	// 先把用户输入加入消息列表（UI 即时反馈）
	state.messages.push({
		role: 'user',
		content: state.inputText,
		created: new Date().toISOString(),
	});

	const userInput = state.inputText;
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
		created: new Date().toISOString(),
	});
	const assistantIndex = state.messages.length - 1;

	const finish = () => {
		state.abortController = null;
		state.chatting = false;
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
		// AI 已写盘，通知父组件刷新文件树/编辑器
		emit('files-changed');
	};

	const handleError = (e: any) => {
		if (e.data) {
			try {
				const data = JSON.parse(e.data);
				ElMessage.error(data.message || '生成失败');
			} catch (err) {
				ElMessage.error('生成失败');
			}
		}
		finish();
	};

	try {
		const token = Local.get('token') as string | undefined;
		const resp = await fetch(templateApi.chatUrl(props.session.sessionId), {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				...(token ? { Authorization: 'Bearer ' + token } : {})
			},
			body: JSON.stringify({ input: userInput, currentFile: props.currentFile || '' }),
			signal: controller.signal
		});

		if (!resp.ok || !resp.body) {
			let msg = '请求失败（' + resp.status + '）';
			try {
				const errRes = await resp.json();
				if (errRes && errRes.msg) msg = errRes.msg;
			} catch (err) {
				/* ignore */
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
					state.messages[assistantIndex].content = assistantContent;
					scrollToBottom();
					break;
				case 'reasoning':
					reasoningContent += data;
					state.messages[assistantIndex].reasoning = reasoningContent;
					scrollToBottom();
					break;
				case 'file':
					// AI 每写完一个文件推送一次：实时更新文件列表 + 通知父组件（刷新实时预览）
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
			ElMessage.error('生成失败：' + (e?.message || '网络错误'));
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
	ElMessageBox.confirm('确认将此模板应用到正式模板目录？应用后可在模板列表中切换使用。', '提示', {
		type: 'warning',
	}).then(async () => {
		state.applying = true;
		try {
			const res = await templateApi.applyTemplate(props.session.sessionId);
			if (res.data) {
				ElMessage.success(res.data);
				emit('applied');
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
		if (chatAreaRef.value) {
			chatAreaRef.value.scrollTop = chatAreaRef.value.scrollHeight;
		}
	});
};

// 判断消息是否处于"思考中"：对话进行中 + 最后一条消息 + 正文尚未开始输出
const reasoningThinking = (msg: any, msgIndex: number) => {
	return state.chatting && msgIndex === state.messages.length - 1 && !msg.content;
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

	.message-text {
		margin: 0;
		font-family: 'Consolas', 'Monaco', monospace;
		font-size: 13px;
		white-space: pre-wrap;
		word-break: break-all;
		line-height: 1.5;
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
	.chat-actions {
		margin-top: 8px;
		text-align: right;
	}
}

.files-area {
	margin-top: 12px;

	.files-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
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
