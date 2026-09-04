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
		 * @param data { templateName, title?, requirement, mobileAdaptive? }
		 */
		createSession(data: object) {
			return request({
				url: '/admin/ai/template/sessions',
				method: 'post',
				data: data
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
		 * 旧模板确定性升级为组件化模板（不经 AI，秒级完成：
		 * 提取内容资产 → 默认 PageSpec → 备份 → 渲染 → 清理旧文件）
		 */
		upgradeLegacy(sessionId: string) {
			return request({
				url: '/admin/ai/template/sessions/' + sessionId + '/upgrade',
				method: 'post'
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

		/**
		 * 构造预览 URL
		 *
		 * 直接在浏览器打开此 URL 即可预览模板（模板内部的 ${ctx()} 引用需要应用模板后才能正常解析）
		 */
		previewUrl(sessionId: string, templateName: string, filePath: string = 'index.html') {
			return '/ai/template/preview/' + sessionId + '/' + templateName + '/' + filePath;
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
