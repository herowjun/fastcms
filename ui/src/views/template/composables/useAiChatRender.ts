/**
 * AI 对话消息渲染 composable：思考过程 HTML 渲染（消息级 WeakMap 缓存）与进度统计
 *
 * 缓存键 = (消息对象, reasoning 全文, thinking 态)：输入未变直接复用旧 HTML，
 * v-html 新旧值相同即跳过 DOM 更新，避免流式期间（150ms 节流）所有历史消息
 * 全量重算 HTML 导致渲染进程内存飙升
 */

// 行内格式：**加粗**、`代码`
const inlineFmt = (s: string): string =>
	s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/`([^`]+)`/g, '<code>$1</code>');

// 按句断行：中文句末标点直接断；英文句末标点仅在后跟大写/数字时断（避免误伤小数、版本号）
const breakSentences = (s: string): string =>
	s.replace(/([。！？；])\s*/g, '$1\n').replace(/([.!?;])\s+(?=[A-Z0-9])/g, '$1\n');

// 思考过程渲染：模型思考文本常混有 markdown 结构（## 标题、- 列表、**加粗**），
// 纯文本显示这些符号可读性差。这里先做 HTML 转义（防注入），再做轻量 markdown
// 格式化 + 超长段落按句断行，中英文都适用
const doRenderReasoning = (text: string, thinking: boolean): string => {
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

export function useAiChatRender(options: {
	/** 对话是否进行中（"思考中"判定条件之一） */
	isChatting: () => boolean;
	/** 是否为最后一条消息（"思考中"判定条件之一，引用比较） */
	isLastMessage: (msg: any) => boolean;
}) {
	// 判断消息是否处于"思考中"：对话进行中 + 最后一条消息 + 正文尚未开始输出
	const reasoningThinking = (msg: any) => {
		return options.isChatting() && options.isLastMessage(msg) && !msg.content;
	};

	// 进度卡已完成文件数
	const progressDoneCount = (msg: any) => {
		return (msg.progress || []).filter((f: any) => f.status === 'done').length;
	};

	const reasoningCache = new WeakMap<object, { src: string; thinking: boolean; html: string }>();
	const renderReasoning = (msg: any): string => {
		const text = msg.reasoning;
		if (!text) return '';
		const thinking = reasoningThinking(msg);
		const cached = reasoningCache.get(msg);
		if (cached && cached.src === text && cached.thinking === thinking) {
			return cached.html;
		}
		const html = doRenderReasoning(text, thinking);
		reasoningCache.set(msg, { src: text, thinking, html });
		return html;
	};

	return { renderReasoning, progressDoneCount, reasoningThinking };
}
