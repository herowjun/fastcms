<template>
	<div>
		<el-tabs v-model="activeTab" type="border-card">
			<el-tab-pane label="模型配置" name="models">
				<div class="mb15">
					<el-button type="primary" size="default" @click="onOpenEdit(null)">
						<el-icon><ele-Plus /></el-icon>新增模型
					</el-button>
					<el-select v-model="providerFilter" placeholder="全部供应商" clearable size="default" class="ml10" style="width: 160px">
						<el-option v-for="p in presentProviders" :key="p.value" :label="p.label" :value="p.value" />
					</el-select>
					<el-select v-model="sceneFilter" placeholder="全部场景" clearable size="default" class="ml10" style="width: 130px">
						<el-option label="对话" value="chat" />
						<el-option label="生图" value="image" />
					</el-select>
					<el-tag class="ml10" type="info" v-if="state.activeChatName">
						对话激活：{{ state.activeChatName }}
					</el-tag>
					<el-tag class="ml10" type="info" v-if="state.activeImageName">
						生图激活：{{ state.activeImageName }}
					</el-tag>
				</div>
				<el-table :data="sortedTableData" stripe style="width: 100%" v-loading="state.loading" :span-method="providerSpanMethod">
					<el-table-column prop="name" label="配置名称" min-width="120" show-overflow-tooltip />
					<el-table-column prop="provider" label="供应商" width="110" show-overflow-tooltip>
						<template #default="scope">
							<el-tag size="small">{{ providerLabel(scope.row.provider) }}</el-tag>
						</template>
					</el-table-column>
					<el-table-column prop="model" label="模型" min-width="140" show-overflow-tooltip />
					<el-table-column label="场景" width="80">
						<template #default="scope">
							<el-tag size="small" :type="scope.row.scene === 'image' ? 'warning' : 'primary'">
								{{ scope.row.scene === 'image' ? '生图' : '对话' }}
							</el-tag>
						</template>
					</el-table-column>
					<el-table-column prop="baseUrl" label="端点" min-width="220" show-overflow-tooltip />
					<el-table-column prop="temperature" label="温度" width="80" />
					<el-table-column prop="maxTokens" label="MaxTokens" width="100" />
					<el-table-column label="状态" width="90">
						<template #default="scope">
							<el-tag v-if="scope.row.active" type="success">激活</el-tag>
							<el-tag v-else type="info">未激活</el-tag>
						</template>
					</el-table-column>
					<el-table-column prop="sortNum" label="排序" width="70" />
					<el-table-column label="操作" width="260" fixed="right">
						<template #default="scope">
							<el-button size="small" text type="primary" @click="onTest(scope.row)">测试</el-button>
							<el-button size="small" text type="primary" @click="onActivate(scope.row)" v-if="!scope.row.active">激活</el-button>
							<el-button size="small" text type="primary" @click="onOpenEdit(scope.row)">编辑</el-button>
							<el-button size="small" text type="danger" @click="onDelete(scope.row)">删除</el-button>
						</template>
					</el-table-column>
				</el-table>
			</el-tab-pane>

			<el-tab-pane label="使用统计" name="usage" lazy>
				<div class="mb15">
					<el-radio-group v-model="usageState.days" @change="loadUsageStats">
						<el-radio-button :label="1">今天</el-radio-button>
						<el-radio-button :label="7">最近 7 天</el-radio-button>
						<el-radio-button :label="30">最近 30 天</el-radio-button>
						<el-radio-button :label="90">最近 90 天</el-radio-button>
					</el-radio-group>
					<span class="ml10 usage-range" v-if="usageState.rangeText">{{ usageState.rangeText }}</span>
				</div>

				<el-row :gutter="15" class="mb15">
					<el-col :span="12">
						<el-card shadow="never" header="按场景统计">
							<el-table :data="usageState.byScene" size="small" v-loading="usageState.loadingStats">
								<el-table-column label="场景" min-width="130">
									<template #default="scope">{{ sceneLabel(scope.row.scene) }}</template>
								</el-table-column>
								<el-table-column prop="callCount" label="调用次数" width="90" />
								<el-table-column label="输入 tokens" width="110">
									<template #default="scope">{{ fmtNum(scope.row.promptTokens) }}</template>
								</el-table-column>
								<el-table-column label="输出 tokens" width="110">
									<template #default="scope">{{ fmtNum(scope.row.completionTokens) }}</template>
								</el-table-column>
								<el-table-column label="合计 tokens" width="110">
									<template #default="scope">{{ fmtNum(scope.row.totalTokens) }}</template>
								</el-table-column>
							</el-table>
						</el-card>
					</el-col>
					<el-col :span="12">
						<el-card shadow="never" header="按用户统计（Top 10）">
							<el-table :data="usageState.byUser" size="small" v-loading="usageState.loadingStats">
								<el-table-column prop="userId" label="用户 ID" width="90" />
								<el-table-column prop="callCount" label="调用次数" width="100" />
								<el-table-column label="合计 tokens">
									<template #default="scope">{{ fmtNum(scope.row.totalTokens) }}</template>
								</el-table-column>
							</el-table>
						</el-card>
					</el-col>
				</el-row>

				<el-card shadow="never" header="调用明细">
					<div class="mb15">
						<el-select v-model="usageState.sceneFilter" placeholder="全部场景" clearable size="default" style="width: 180px" @change="onUsagePageChange(1)">
							<el-option v-for="s in sceneOptions" :key="s.value" :label="s.label" :value="s.value" />
						</el-select>
					</div>
					<el-table :data="usageState.logs" size="small" v-loading="usageState.loadingLogs">
						<el-table-column label="时间" width="160">
							<template #default="scope">{{ scope.row.created }}</template>
						</el-table-column>
						<el-table-column label="场景" width="130">
							<template #default="scope">{{ sceneLabel(scope.row.scene) }}</template>
						</el-table-column>
						<el-table-column prop="model" label="模型" min-width="130" show-overflow-tooltip />
						<el-table-column prop="promptTokens" label="输入" width="90" />
						<el-table-column prop="completionTokens" label="输出" width="90" />
						<el-table-column prop="totalTokens" label="合计" width="90" />
						<el-table-column label="耗时" width="90">
							<template #default="scope">{{ fmtDuration(scope.row.durationMs) }}</template>
						</el-table-column>
						<el-table-column label="结果" width="80">
							<template #default="scope">
								<el-tag v-if="scope.row.success" type="success" size="small">成功</el-tag>
								<el-tooltip v-else :content="scope.row.errorMsg || '失败'" placement="top">
									<el-tag type="danger" size="small">失败</el-tag>
								</el-tooltip>
							</template>
						</el-table-column>
					</el-table>
					<div class="mt15" style="display: flex; justify-content: flex-end">
						<el-pagination
							background
							layout="total, prev, pager, next, sizes"
							:total="usageState.total"
							:current-page="usageState.page"
							:page-size="usageState.pageSize"
							:page-sizes="[10, 20, 50, 100]"
							@current-change="onUsagePageChange"
							@size-change="onUsageSizeChange"
						/>
					</div>
				</el-card>
			</el-tab-pane>
		</el-tabs>

		<el-dialog :title="state.dialog.title" v-model="state.dialog.visible" width="640px" :close-on-click-modal="false">
			<el-form :model="state.form" :rules="state.rules" ref="myRefForm" label-width="100px">
					<el-form-item label="配置名称" prop="name">
						<el-input v-model="state.form.name" placeholder="如：DeepSeek-聊天" clearable />
					</el-form-item>
					<el-form-item label="用途场景" prop="scene">
						<el-radio-group v-model="state.form.scene" @change="onSceneChange">
							<el-radio-button label="chat">对话</el-radio-button>
							<el-radio-button label="image">生图</el-radio-button>
						</el-radio-group>
						<span class="ml10 scene-tip">对话用于模板生成/文章等；生图用于 AI 文生图/修图（DashScope qwen-image 系列）</span>
					</el-form-item>
				<el-form-item label="供应商" prop="provider">
						<el-select v-model="state.form.provider" placeholder="请选择" @change="onProviderChange" style="width:100%">
							<template v-if="state.form.scene === 'image'">
								<el-option label="通义千问（DashScope）" value="qwen" />
								<el-option label="OpenAI" value="openai" />
								<el-option label="自定义" value="custom" />
							</template>
							<template v-else>
								<el-option label="DeepSeek" value="deepseek" />
								<el-option label="通义千问" value="qwen" />
								<el-option label="智谱 GLM" value="zhipu" />
								<el-option label="Moonshot" value="moonshot" />
								<el-option label="OpenAI" value="openai" />
								<el-option label="Ollama" value="ollama" />
								<el-option label="自定义" value="custom" />
							</template>
						</el-select>
					</el-form-item>
					<el-form-item label="API 端点" prop="baseUrl">
						<el-select v-model="state.form.baseUrl" filterable allow-create default-first-option clearable placeholder="选择预设网关或手动输入" style="width:100%">
							<el-option v-for="(preset, p) in currentScenePresets" :key="p" v-if="preset.baseUrl" :label="providerLabels[p as string] + '（' + preset.baseUrl + '）'" :value="preset.baseUrl" />
						</el-select>
					</el-form-item>
				<el-form-item label="API Key" prop="apiKey">
					<el-input v-model="state.form.apiKey" placeholder="sk-xxx（Ollama 等本地调用可留空）" show-password clearable />
				</el-form-item>
				<el-form-item label="模型" prop="model">
					<el-select v-if="state.form.provider === 'ollama'" v-model="state.form.model" filterable allow-create default-first-option clearable placeholder="选择本机已安装模型，或输入自定义模型名" style="width:100%">
						<el-option v-for="m in ollamaModels" :key="m" :label="m" :value="m" />
					</el-select>
					<el-select v-else-if="currentProviderModels.length" v-model="state.form.model" filterable allow-create default-first-option clearable placeholder="请选择模型" style="width:100%">
						<el-option v-for="m in currentProviderModels" :key="m" :label="m" :value="m" />
					</el-select>
					<el-input v-else v-model="state.form.model" placeholder="请输入模型名称，如 gpt-3.5-turbo" clearable />
					<el-button v-if="state.form.provider === 'ollama'" :loading="ollamaState.loading" @click="fetchOllamaModels()" class="ml10" size="small">刷新本机模型</el-button>
				</el-form-item>
				<el-form-item label="自定义请求头" prop="extraHeaders">
					<el-input v-model="state.form.extraHeaders" type="textarea" :rows="2" clearable placeholder='可选，JSON 对象格式，如 {"X-Tenant":"abc"}；Ollama 开启鉴权时可填 {"Authorization":"Bearer ollama"}' />
				</el-form-item>
				<el-row :gutter="20">
					<el-col :span="12">
						<el-form-item label="温度" prop="temperature">
							<el-input-number v-model="state.form.temperature" :min="0" :max="2" :step="0.1" :precision="2" style="width:100%" />
						</el-form-item>
					</el-col>
					<el-col :span="12">
						<el-form-item label="MaxTokens" prop="maxTokens">
							<el-input-number v-model="state.form.maxTokens" :min="1" :max="128000" :step="512" style="width:100%" />
						</el-form-item>
					</el-col>
				</el-row>
				<el-row :gutter="20">
					<el-col :span="12">
						<el-form-item label="排序" prop="sortNum">
							<el-input-number v-model="state.form.sortNum" :min="0" style="width:100%" />
						</el-form-item>
					</el-col>
					<el-col :span="12">
						<el-form-item label="是否激活">
							<el-switch v-model="state.form.active" />
							<span class="ml10 scene-tip">同场景内互斥</span>
						</el-form-item>
					</el-col>
				</el-row>
				<el-form-item label="备注">
					<el-input v-model="state.form.remark" type="textarea" :rows="2" clearable />
				</el-form-item>
			</el-form>
			<template #footer>
				<el-button @click="state.dialog.visible = false">取 消</el-button>
				<el-button type="primary" :loading="state.submitting" @click="onSubmit">保 存</el-button>
			</template>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="aiModel">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { AiModelApi, AiUsageApi } from '/@/api/ai/index';

const aiApi = AiModelApi();
const usageApi = AiUsageApi();
const myRefForm = ref();

// 当前激活的 tab（models=模型配置 / usage=使用统计）
const activeTab = ref('models');

// 场景常量（与后端 IAiUsageLogService.Scene 对齐）
const sceneOptions = [
	{ value: 'TEMPLATE_GEN', label: '模板生成' },
	{ value: 'TEMPLATE_ADJUST', label: '模板调整' },
	{ value: 'ARTICLE_GEN', label: '文章生成' },
	{ value: 'ARTICLE_REWRITE', label: '文章改写' },
	{ value: 'ARTICLE_FIELD', label: '字段生成' },
];
const sceneLabel = (scene?: string) => sceneOptions.find((s) => s.value === scene)?.label || scene || '-';

// 用量统计状态
const usageState = reactive({
	days: 7,
	rangeText: '',
	byScene: [] as any[],
	byUser: [] as any[],
	logs: [] as any[],
	total: 0,
	page: 1,
	pageSize: 20,
	sceneFilter: '',
	loadingStats: false,
	loadingLogs: false,
	loaded: false,
});

const fmtNum = (n?: number) => (n == null ? '-' : Number(n).toLocaleString());

const fmtDuration = (ms?: number) => {
	if (ms == null) return '-';
	if (ms < 1000) return ms + 'ms';
	const sec = ms / 1000;
	return sec < 60 ? sec.toFixed(1) + 's' : Math.floor(sec / 60) + '分' + Math.round(sec % 60) + '秒';
};

const loadUsageStats = () => {
	usageState.loadingStats = true;
	usageApi
		.stats(usageState.days)
		.then((res: any) => {
			const data = res.data || {};
			usageState.byScene = data.byScene || [];
			usageState.byUser = data.byUser || [];
			// 后端返回 ISO 时间串，截取日期部分展示
			const st = String(data.startTime || '').slice(0, 10);
			const et = String(data.endTime || '').slice(0, 10);
			usageState.rangeText = st && et ? `${st} ~ ${et}` : '';
		})
		.finally(() => {
			usageState.loadingStats = false;
		});
};

const loadUsageLogs = () => {
	usageState.loadingLogs = true;
	usageApi
		.logs({
			page: usageState.page,
			pageSize: usageState.pageSize,
			scene: usageState.sceneFilter || undefined,
		})
		.then((res: any) => {
			const pageData = res.data || {};
			usageState.logs = pageData.records || [];
			usageState.total = pageData.total || 0;
		})
		.finally(() => {
			usageState.loadingLogs = false;
		});
};

const onUsagePageChange = (page: number) => {
	usageState.page = page;
	loadUsageLogs();
};

const onUsageSizeChange = (size: number) => {
	usageState.pageSize = size;
	usageState.page = 1;
	loadUsageLogs();
};

// 首次切到"使用统计"tab 时加载（lazy 渲染，避免进页面就查库）
watch(activeTab, (tab) => {
	if (tab === 'usage' && !usageState.loaded) {
		usageState.loaded = true;
		loadUsageStats();
		loadUsageLogs();
	}
});

// Ollama/自定义 供应商不需要 API Key（本地调用或走自定义请求头）
const NO_API_KEY_PROVIDERS = ['ollama', 'custom'];

// 注意：以下验证函数需在 state 声明之前定义（rules 初始化时会立即引用）
const validateApiKey = (_rule: any, value: string, callback: any) => {
	if (NO_API_KEY_PROVIDERS.includes(state.form.provider)) {
		callback();
		return;
	}
	if (!value || value.trim() === '' || value === '********') {
		callback(new Error('请输入 API Key'));
	} else {
		callback();
	}
};

const validateExtraHeaders = (_rule: any, value: string, callback: any) => {
	if (!value || !value.trim()) {
		callback();
		return;
	}
	try {
		const obj = JSON.parse(value);
		if (typeof obj !== 'object' || obj === null || Array.isArray(obj)) {
			callback(new Error('必须是 JSON 对象，如 {"X-Tenant":"abc"}'));
		} else {
			callback();
		}
	} catch (e) {
		callback(new Error('不是合法的 JSON'));
	}
};

const state = reactive({
	loading: false,
	submitting: false,
	tableData: [] as any[],
	activeChatName: '',
	activeImageName: '',
	dialog: {
		visible: false,
		title: '新增模型',
	},
	form: {
		id: null as any,
		name: '',
		scene: 'chat',
		provider: 'deepseek',
		baseUrl: '',
		apiKey: '',
		model: '',
		temperature: 0.7,
		maxTokens: 2048,
		active: false,
		sortNum: 0,
		remark: '',
		extraHeaders: '',
	},
	rules: {
		name: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
		baseUrl: [{ required: true, message: '请输入 API 端点', trigger: 'blur' }],
		apiKey: [{ validator: validateApiKey, trigger: 'blur' }],
		model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
		extraHeaders: [{ validator: validateExtraHeaders, trigger: 'blur' }],
	},
});

// 供应商展示顺序 + 中文标签
const providerOrder: string[] = ['deepseek', 'qwen', 'zhipu', 'moonshot', 'openai', 'ollama', 'custom'];
const providerLabels: Record<string, string> = {
	deepseek: 'DeepSeek',
	qwen: '通义千问',
	zhipu: '智谱 GLM',
	moonshot: 'Moonshot',
	openai: 'OpenAI',
	ollama: 'Ollama',
	custom: '自定义',
};

const providerLabel = (p?: string) => providerLabels[p] || p || '自定义';

const providerIndex = (p?: string) => {
	const i = providerOrder.indexOf(p || 'custom');
	return i === -1 ? providerOrder.length : i;
};

// 供应商筛选（'' 表示全部）
const providerFilter = ref('');
// 场景筛选（'' 表示全部）
const sceneFilter = ref('');
const presentProviders = computed(() => {
	const map = new Map<string, string>();
	state.tableData.forEach((row: any) => {
		const p = row.provider || 'custom';
		if (!map.has(p)) map.set(p, providerLabel(p));
	});
	// 按 providerOrder 固定顺序输出，未知供应商排在后面
	return providerOrder
		.filter((p) => map.has(p))
		.map((p) => ({ value: p, label: map.get(p)! }))
		.concat(
			[...new Set(state.tableData.map((r: any) => r.provider).filter(Boolean))]
				.filter((p) => !providerOrder.includes(p))
				.map((p) => ({ value: p as string, label: providerLabel(p) })),
		);
});

// 表格按供应商分组排序：先按 provider 固定顺序，再按 sortNum / id
const sortedTableData = computed(() => {
	let base = providerFilter.value
		? state.tableData.filter((r: any) => (r.provider || 'custom') === providerFilter.value)
		: [...state.tableData];
	if (sceneFilter.value) {
		base = base.filter((r: any) => (r.scene === 'image' ? 'image' : 'chat') === sceneFilter.value);
	}
	return base.sort(
		(a: any, b: any) =>
			providerIndex(a.provider) - providerIndex(b.provider) ||
			(a.sortNum ?? 0) - (b.sortNum ?? 0) ||
			(a.id - b.id),
	);
});

// 供应商列行合并：同一供应商连续行只渲染第一行，其余 rowspan=0
const providerSpanMethod = ({ row, columnIndex, rowIndex }: { row: any; columnIndex: number; rowIndex: number; column: any }) => {
	// 供应商列固定为第 1 列（第 0 列是配置名称）
	if (columnIndex !== 1) return { rowspan: 1, colspan: 1 };
	const data = sortedTableData.value;
	const p = row.provider || 'custom';
	const prev = data[rowIndex - 1];
	if (prev && (prev.provider || 'custom') === p) {
		return { rowspan: 0, colspan: 0 };
	}
	let span = 1;
	for (let i = rowIndex + 1; i < data.length; i++) {
		if ((data[i].provider || 'custom') === p) span++;
		else break;
	}
	return { rowspan: span, colspan: 1 };
};
// 各供应商预设：API 网关 + 可选模型列表（第一个为默认模型）。
// 模型清单按 2026-08 各家官方文档核对（deepseek.com / help.aliyun.com / bigmodel.cn / moonshot.cn），
// 下拉支持 allow-create 手动输入，文档未覆盖的新模型直接手输即可。
const providerPresets: Record<string, { baseUrl: string; models: string[] }> = {
	deepseek: { baseUrl: 'https://api.deepseek.com', models: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-v4-pro', 'deepseek-v4-flash'] },
	qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', models: ['qwen3.8-max', 'qwen3.8-flash', 'qwen3.7-plus'] },
	zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', models: ['glm-5.3', 'glm-5.3-flash', 'glm-5.2'] },
	moonshot: { baseUrl: 'https://api.moonshot.cn/v1', models: ['kimi-k2.5', 'kimi-k2.6', 'kimi-k3', 'kimi-k2-thinking', 'kimi-k2.7-code'] },
	openai: { baseUrl: 'https://api.openai.com', models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4.1', 'gpt-4.1-mini', 'o3-mini'] },
	ollama: { baseUrl: 'http://localhost:11434', models: ['llama3.1', 'llama3.2', 'qwen2.5', 'mistral', 'gemma2'] },
	custom: { baseUrl: '', models: [] },
};

// 生图场景预设：DashScope 原生多模态生图端点（qwen-image 系列，文生图/修图）
const imageProviderPresets: Record<string, { baseUrl: string; models: string[] }> = {
	qwen: { baseUrl: 'https://dashscope.aliyuncs.com/api/v1', models: ['qwen-image', 'qwen-image-edit'] },
	openai: { baseUrl: 'https://api.openai.com/v1', models: ['gpt-image-1'] },
	custom: { baseUrl: '', models: [] },
};

// 当前场景对应预设（生图场景只保留支持图像生成的供应商）
const currentScenePresets = computed(() => (state.form.scene === 'image' ? imageProviderPresets : providerPresets));

// 当前供应商可选模型（用于下拉列表，按场景取预设）
const currentProviderModels = computed(() => currentScenePresets.value[state.form.provider]?.models || []);

// 切换用途场景时：生图场景回落到 qwen + qwen-image；对话场景回落到 deepseek 默认
const onSceneChange = (scene: string) => {
	if (scene === 'image') {
		if (!imageProviderPresets[state.form.provider]) {
			state.form.provider = 'qwen';
			onProviderChange('qwen');
		}
	} else {
		if (!providerPresets[state.form.provider]) {
			state.form.provider = 'deepseek';
			onProviderChange('deepseek');
		}
	}
};

// 切换供应商时，API 端点跟随该供应商网关（按场景取预设），模型回落到该供应商默认模型（models[0]）
const onProviderChange = (provider: string) => {
	const preset = currentScenePresets.value[provider];
	if (!preset) return;
	state.form.baseUrl = preset.baseUrl;
	state.form.model = preset.models[0] || '';
	if (provider === 'ollama') fetchOllamaModels();
	else {
		ollamaState.fetched = false;
		ollamaState.error = '';
	}
};

// Ollama：本机已安装模型清单（动态拉取 /api/tags，失败时回落静态兜底清单 + 手动输入）
const ollamaState = reactive({ loading: false, fetched: false, error: '', list: [] as string[] });
const ollamaModels = computed(() => (ollamaState.fetched ? ollamaState.list : (providerPresets.ollama.models || [])));
const fetchOllamaModels = async (silent = false) => {
	const baseUrl = (state.form.baseUrl || providerPresets.ollama.baseUrl).replace(/\/+$/, '');
	ollamaState.loading = true;
	ollamaState.error = '';
	try {
		const resp = await fetch(`${baseUrl}/api/tags`, { method: 'GET' });
		if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
		const json = await resp.json();
		const names: string[] = (json.models || []).map((m: any) => m.name);
		if (names.length === 0) throw new Error('本机没有已安装的模型');
		ollamaState.list = names;
		ollamaState.fetched = true;
		// 动态拉到清单后，若当前选中模型不在其中，回落到第一个
		if (state.form.provider === 'ollama' && state.form.model && !names.includes(state.form.model)) {
			state.form.model = names[0];
		} else if (state.form.provider === 'ollama' && !state.form.model) {
			state.form.model = names[0];
		}
	} catch (e: any) {
		ollamaState.fetched = false;
		ollamaState.error = e?.message || '获取失败';
		if (!silent) {
			ElMessage.warning('无法获取 Ollama 本机模型清单（' + ollamaState.error + '）。浏览器直连受 CORS 限制，可在 Ollama 设置 OLLAMA_ORIGINS=* 后点「刷新」，或从下拉/输入框手动选择模型名。');
		}
	} finally {
		ollamaState.loading = false;
	}
};

const initTableData = () => {
	state.loading = true;
	aiApi.list().then((res: any) => {
		state.tableData = res.data || [];
		const fmt = (item: any) => `${item.name} (${item.model})`;
		// 场景归一化：后端历史数据 scene 为空视为 chat
		const activeChat = state.tableData.find((item: any) => item.active && (item.scene || 'chat') === 'chat');
		const activeImage = state.tableData.find((item: any) => item.active && item.scene === 'image');
		state.activeChatName = activeChat ? fmt(activeChat) : '';
		state.activeImageName = activeImage ? fmt(activeImage) : '';
	}).catch(() => {}).finally(() => {
		state.loading = false;
	});
};

const onOpenEdit = (row: any) => {
	if (row) {
		aiApi.get(row.id).then((res: any) => {
			state.form = { ...res.data, scene: res.data.scene || 'chat', extraHeaders: res.data.extraHeaders || '' };
			state.form.apiKey = '********'; // 后端已脱敏，保持占位
			state.dialog.title = '编辑模型';
			state.dialog.visible = true;
			if (state.form.provider === 'ollama') fetchOllamaModels(true);
		});
	} else {
		state.form = {
			id: null,
			name: '',
			scene: 'chat',
			provider: 'deepseek',
			baseUrl: '',
			apiKey: '',
			model: '',
			temperature: 0.7,
			maxTokens: 2048,
			active: false,
			sortNum: 0,
			remark: '',
			extraHeaders: '',
		};
		onProviderChange('deepseek');
		state.dialog.title = '新增模型';
		state.dialog.visible = true;
	}
};

const onSubmit = () => {
	myRefForm.value.validate((valid: boolean) => {
		if (!valid) return;
		state.submitting = true;
		aiApi.save(state.form).then(() => {
			ElMessage.success('保存成功');
			state.dialog.visible = false;
			initTableData();
		}).catch((err: any) => {
			ElMessage.error(err?.message || '保存失败');
		}).finally(() => {
			state.submitting = false;
		});
	});
};

const onDelete = (row: any) => {
	ElMessageBox.confirm(`确定要删除模型「${row.name}」吗？`, '提示', {
		confirmButtonText: '删除',
		cancelButtonText: '取消',
		type: 'warning',
	}).then(() => {
		aiApi.remove(row.id).then(() => {
			ElMessage.success('删除成功');
			initTableData();
		});
	}).catch(() => {});
};

const onActivate = (row: any) => {
	aiApi.activate(row.id).then(() => {
		ElMessage.success('已激活');
		initTableData();
	});
};

const onTest = (row: any) => {
	ElMessage.info(`正在测试 ${row.name}...`);
	aiApi.test(row.id).then((res: any) => {
		ElMessage.success('连接成功');
	}).catch((err: any) => {
		ElMessage.error(err?.message || '连接失败');
	});
};

onMounted(() => {
	initTableData();
});
</script>

<style scoped lang="scss">
.mb15 { margin-bottom: 15px; }
.ml10 { margin-left: 10px; }
.mt15 { margin-top: 15px; }
.usage-range { color: var(--el-text-color-secondary); font-size: 12px; }
.scene-tip { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
