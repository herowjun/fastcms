import request from '/@/utils/request';

/**
 * 解析 API 基础地址（兼容 dev 绝对地址 / prod 相对路径两种形态）
 *
 * - dev:  http://localhost:8080/fastcms/api  → 原样返回（跨域，后端 CorsFilter 放行）
 * - prod: fastcms/api（无斜杠）               → 归一化为 /fastcms/api（同域）
 */
function apiBaseUrl(): string {
	const base = (import.meta.env.VITE_API_URL as string) || '';
	if (!base) return '';
	return /^https?:\/\//i.test(base) ? base : '/' + base.replace(/^\/+/, '');
}

export function AiModelApi() {
	return {
		/**
		 * 模型配置列表
		 */
		list() {
			return request({
				url: '/admin/ai/model/list',
				method: 'get'
			});
		},

		/**
		 * 获取当前激活的模型
		 */
		getActive() {
			return request({
				url: '/admin/ai/model/active',
				method: 'get'
			});
		},

		/**
		 * 获取单个配置详情
		 */
		get(id: number | string) {
			return request({
				url: '/admin/ai/model/get/' + id,
				method: 'get'
			});
		},

		/**
		 * 新增或更新配置
		 */
		save(data: object) {
			return request({
				url: '/admin/ai/model/save',
				method: 'post',
				data: data
			});
		},

		/**
		 * 删除配置
		 */
		remove(id: number | string) {
			return request({
				url: '/admin/ai/model/delete/' + id,
				method: 'post'
			});
		},

		/**
		 * 激活某个配置
		 */
		activate(id: number | string) {
			return request({
				url: '/admin/ai/model/activate/' + id,
				method: 'post'
			});
		},

		/**
		 * 测试连接
		 */
		test(id: number | string) {
			return request({
				url: '/admin/ai/model/test/' + id,
				method: 'post'
			});
		}
	};
}

/**
 * AI 模板生成器 API
 *
 * 对话使用 fetch POST 请求 + ReadableStream 监听 SSE 事件，不通过 request 封装。
 */
export function AiTemplateApi() {
	return {
		/**
		 * 会话列表
		 */
		listSessions() {
			return request({
				url: '/admin/ai/template/sessions',
				method: 'get'
			});
		},

		/**
		 * 获取会话详情
		 */
		getSession(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId,
				method: 'get'
			});
		},

		/**
		 * 创建会话
		 * @param data { templateName, title?, requirement, mobileAdaptive?, createMode?, designDirection?, confirmAuto? }
		 */
		createSession(data: object) {
			return request({
				url: '/admin/ai/template/sessions',
				method: 'post',
				data: data
			});
		},

		/**
		 * 设计稿先行模式选项（新建模板对话框"生成模式"数据源）：
		 * { enabled: 总开关（false 时前端隐藏模式选项）, directions: [{key, name, summary}] }
		 */
		designOptions() {
			return request({
				url: '/admin/ai/template/design-options',
				method: 'get'
			});
		},

		/**
		 * HTML 导入（zip 站包 / 单 HTML 文件；createMode=import 会话专用）
		 *
		 * 同步完成解压 + 归一化 + plan.json 落盘（秒级返回）；
		 * 转化由前端随后走既有 chat 对话触发。
		 * @param sessionId import 模式会话 ID
		 * @param file 上传的 zip 或 HTML 文件
		 * @returns { pageCount, assetCount, notes[] }（notes 为导入报告标注）
		 */
		importHtml(sessionId: string, file: File) {
			const formData = new FormData();
			formData.append('file', file);
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/import',
				method: 'post',
				data: formData
			});
		},

		/**
		 * 设计稿先行模式确认状态（会话加载后查询，刷新恢复确认卡片用）：
		 * AWAITING_CONFIRM 时返回 { state, issues[], previewUrl, confirmAuto }，否则 data 为 null
		 */
		designStatus(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/design-status',
				method: 'get'
			});
		},

		/**
		 * 删除会话
		 */
		deleteSession(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/delete',
				method: 'post'
			});
		},

		/**
		 * 获取会话消息列表
		 */
		listMessages(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/messages',
				method: 'get'
			});
		},

		/**
		 * 获取会话生成的文件列表
		 */
		listFiles(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/files',
				method: 'get'
			});
		},

		/**
		 * 应用模板
		 */
		applyTemplate(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/apply',
				method: 'post'
			});
		},

		/**
		 * 回滚最近一轮 AI 修改（仅调整型会话支持）
		 */
		rollbackLast(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/rollback',
				method: 'post'
			});
		},
		/**
		 * 旧模板升级状态（有 html 且无 _pagespec.json 时返回 true，前端据此展示升级按钮）
		 */
		legacyStatus(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/legacy-status',
				method: 'get'
			});
		},

		/**
		 * 构造 SSE 对话的 POST URL
		 *
		 * <p>对话接口已改为 POST：input 通过 JSON 请求体传递，认证走
		 * Authorization 请求头（不再需要 accessToken query 参数），
		 * 因此 URL 必须使用 VITE_API_URL 前缀（/fastcms/api/admin/...），
		 * 否则会被 SPA 静态页 fallback 拦截。
		 *
		 * 前端使用方式（fetch + ReadableStream 读取 SSE）：
		 *   const resp = await fetch(templateApi.chatUrl(sessionId), {
		 *     method: 'POST',
		 *     headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
		 *     body: JSON.stringify({ input })
		 *   });
		 *   // 解析 resp.body（text/event-stream），按事件类型分发：message / file / done / error
		 */
		chatUrl(sessionId: string) {
			return (
				apiBaseUrl() +
				'/admin/ai/template/sessions/' +
				sessionId +
				'/chat'
			);
		},

		// ==================== 任务运行态（关页后台续跑 + 重开续看） ====================

		/**
		 * 会话运行态探测：任务与连接解耦后，SSE 断开（关页面/断网）不取消任务。
		 * 打开会话时探测：仍在跑则续看 stream（回放思考过程 + 实时续接），
		 * 已结束则走终态恢复（listMessages/listFiles）
		 */
		runStatus(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/run-status',
				method: 'get'
			});
		},

		/**
		 * 构造续看运行任务的 SSE GET URL
		 *
		 * <p>since = 已收到的最新事件 seq（后端事件携带 SSE 标准 id 字段）：
		 * 先回放 since 之后的历史事件（含已发生的思考过程），再实时续接。
		 * 页面新开传 0（全量回放）；断流重连传 lastSeq（增量续接）。</p>
		 */
		streamUrl(sessionId: string, since: number = 0) {
			return (
				apiBaseUrl() +
				'/admin/ai/template/sessions/' +
				sessionId +
				'/stream?since=' +
				since
			);
		},

		/**
		 * 显式停止运行中的任务（断开连接不触发取消——关页任务后台续跑，停止只能显式调用）
		 */
		stopSession(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/stop',
				method: 'post'
			});
		},

		/**
		 * 构造预览 URL
		 *
		 * 直接在浏览器打开此 URL 即可预览模板（模板内部的 ${ctx()} 引用需要应用模板后才能正常解析）
		 */
		previewUrl(sessionId: string, templateName: string, filePath: string = 'index.html') {
			return '/ai/template/preview/' + sessionId + '/' + templateName + '/' + filePath;
		},

		/**
		 * 更新图片槽位（预览页点选换图，不经 AI 对话）
		 * @param sessionId 会话 ID
		 * @param data { sectionId, slot, attachmentId }
		 * @returns 本次写出的模板内相对路径清单
		 */
		updateImageSlot(sessionId: string, data: { sectionId: string; slot: string; attachmentId: number }) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/image-slot',
				method: 'post',
				data: data
			});
		},

		/**
		 * 更新预览演示图片（预览页点选无槽位标记的 mock 图片换图）
		 *
		 * 改 _preview_data.json 的 imageOverrides 映射，仅预览渲染生效；
		 * 正式环境的图片由数据库文章数据决定。
		 * @param sessionId 会话 ID
		 * @param data { imageUrl: 模板渲染输出的原样图片地址, attachmentId }
		 */
		updatePreviewImage(sessionId: string, data: { imageUrl: string; attachmentId: number }) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/preview-image',
				method: 'post',
				data: data
			});
		},

		// ==================== 会话工作目录文件编辑（生成型会话，应用前的手工打磨） ====================

		/**
		 * 会话工作目录文件树（与正式模板文件树同构：filePath 以模板目录名开头）
		 */
		getSessionFileTree(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/files/tree',
				method: 'get'
			});
		},

		/**
		 * 读取会话工作目录的文本文件内容
		 * @param filePath 文件路径（以模板目录名开头，与文件树返回值一致）
		 */
		getSessionFile(sessionId: string, filePath: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/file/get?filePath=' + encodeURIComponent(filePath),
				method: 'get'
			});
		},

		/**
		 * 保存（或新建）会话工作目录的文本文件（仅未应用的生成型会话可写）
		 * @param params { filePath, fileContent }
		 */
		saveSessionFile(sessionId: string, params: object) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/file/save',
				method: 'post',
				headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
				data: params
			});
		},

		/**
		 * 删除会话工作目录的文件（仅未应用的生成型会话可删）
		 */
		delSessionFile(sessionId: string, filePath: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/file/delete?filePath=' + encodeURIComponent(filePath),
				method: 'post'
			});
		},

		/**
		 * 构造会话文件上传 URL（el-upload action 使用；data 携带 dirName + sessionId）
		 */
		sessionUploadUrl(sessionId: string) {
			return import.meta.env.VITE_API_URL + '/admin/ai/template/sessions/' + sessionId + '/files/upload';
		}
	};
}

/**
 * AI 生图 API（文生图/修图，任务异步：提交即返回 + 轮询 task/{id}）
 */
export function AiImageApi() {
	return {
		/**
		 * 提交生图任务
		 * @param data { taskType: 't2i'|'edit', prompt, size?, num?, sourceAttachmentId? }
		 */
		generate(data: object) {
			return request({
				url: '/admin/ai/image/generate',
				method: 'post',
				data: data
			});
		},

		/**
		 * 查询任务状态（前端轮询至 success/failed）
		 */
		getTask(id: number | string) {
			return request({
				url: '/admin/ai/image/task/' + id,
				method: 'get'
			});
		},

		/**
		 * 重试失败的任务
		 */
		retry(id: number | string) {
			return request({
				url: '/admin/ai/image/retry/' + id,
				method: 'post'
			});
		},

		/**
		 * 应用模板图片修图结果（用户对比原图/生成图确认后调用：原图备份 .bak，结果图覆盖模板原路径）
		 */
		apply(id: number | string) {
			return request({
				url: '/admin/ai/image/apply/' + id,
				method: 'post'
			});
		}
	};
}

/**
 * AI 用量审计 API（ai_usage_log 表）
 */
export function AiUsageApi() {
	return {
		/**
		 * 用量统计：按场景聚合 + 按用户聚合（默认最近 7 天）
		 * @param days 统计天数（1~90）
		 */
		stats(days: number = 7) {
			return request({
				url: '/admin/ai/usage/stats',
				method: 'get',
				params: { days }
			});
		},

		/**
		 * 调用明细分页（可按场景过滤）
		 */
		logs(params: { page: number; pageSize: number; scene?: string }) {
			return request({
				url: '/admin/ai/usage/logs',
				method: 'get',
				params
			});
		}
	};
}

/**
 * AI 智能体 API（多智能体架构管理：内置 + 自定义）
 */
export function AiAgentApi() {
	return {
		/**
		 * 智能体列表（内置 + 自定义合并）
		 */
		list() {
			return request({
				url: '/admin/ai/agent/list',
				method: 'get'
			});
		},

		/**
		 * 智能体详情（按 agentId，内置或自定义）
		 */
		get(agentId: string) {
			return request({
				url: '/admin/ai/agent/get/' + agentId,
				method: 'get'
			});
		},

		/**
		 * 保存自定义智能体（新建或更新）
		 * @param data { id?, name, description?, systemPrompt, modelConfigId?, temperature?, maxTokens?, skills?, tools?, dailyTokenQuota?, sortNum?, status? }
		 */
		save(data: object) {
			return request({
				url: '/admin/ai/agent/save',
				method: 'post',
				data: data
			});
		},

		/**
		 * 复制智能体为自定义副本（内置/自定义皆可复制）
		 */
		copy(agentId: string) {
			return request({
				url: '/admin/ai/agent/copy/' + agentId,
				method: 'post'
			});
		},

		/**
		 * 删除自定义智能体
		 */
		remove(id: number | string) {
			return request({
				url: '/admin/ai/agent/delete/' + id,
				method: 'post'
			});
		},

		/**
		 * 可绑定的 skill 清单（能力绑定用）
		 */
		listSkills() {
			return request({
				url: '/admin/ai/agent/skills',
				method: 'get'
			});
		},

		/**
		 * skill 详情规则（SKILL.md 正文，只读查看）
		 */
		getSkillContent(skillId: string) {
			return request({
				url: '/admin/ai/agent/skill-content',
				method: 'get',
				params: { skillId }
			});
		},

		/**
		 * 可绑定的工具清单（能力绑定用）
		 */
		listTools() {
			return request({
				url: '/admin/ai/agent/tools',
				method: 'get'
			});
		}
	};
}

/**
 * AI 文章内容生产 API（无状态）
 *
 * generate/rewrite 使用 fetch POST + ReadableStream 监听 SSE 事件
 * （message / reasoning / done / error），与模板对话的前端消费方式一致。
 */
export function AiArticleApi() {
	return {
		/**
		 * 构造全文生成的 SSE POST URL
		 */
		generateUrl() {
			return apiBaseUrl() + '/admin/ai/article/generate';
		},

		/**
		 * 构造划词改写的 SSE POST URL
		 */
		rewriteUrl() {
			return apiBaseUrl() + '/admin/ai/article/rewrite';
		},

		/**
		 * 构造单字段候选生成的 SSE POST URL
		 */
		fieldUrl() {
			return apiBaseUrl() + '/admin/ai/article/field';
		},

		/**
		 * 查询文章的 AI 操作历史（划词改写记录，含思考过程）
		 */
		listOps(articleId: string | number) {
			return request({
				url: '/admin/ai/article/ops/' + articleId,
				method: 'get'
			});
		},

		/**
		 * 绑定操作记录到文章（新建文章保存成功后调用）
		 * @param data { articleId, opIds }
		 */
		bindOps(data: object) {
			return request({
				url: '/admin/ai/article/ops/bind',
				method: 'post',
				data: data
			});
		}
	};
}
