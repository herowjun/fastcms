<template>
	<div>
		<el-card shadow="hover">
			<div class="mb15">
				<el-button type="primary" size="default" @click="onOpenEdit(null)">
					<el-icon><ele-Plus /></el-icon>新增模型
				</el-button>
				<el-select v-model="providerFilter" placeholder="全部供应商" clearable size="default" class="ml10" style="width: 160px">
					<el-option v-for="p in presentProviders" :key="p.value" :label="p.label" :value="p.value" />
				</el-select>
				<el-tag class="ml10" type="info" v-if="state.activeName">
					当前激活：{{ state.activeName }}
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
		</el-card>

		<el-dialog :title="state.dialog.title" v-model="state.dialog.visible" width="640px" :close-on-click-modal="false">
			<el-form :model="state.form" :rules="state.rules" ref="myRefForm" label-width="100px">
				<el-form-item label="配置名称" prop="name">
					<el-input v-model="state.form.name" placeholder="如：DeepSeek-聊天" clearable />
				</el-form-item>
				<el-form-item label="供应商" prop="provider">
					<el-select v-model="state.form.provider" placeholder="请选择" @change="onProviderChange" style="width:100%">
						<el-option label="DeepSeek" value="deepseek" />
						<el-option label="通义千问" value="qwen" />
						<el-option label="智谱 GLM" value="zhipu" />
						<el-option label="Moonshot" value="moonshot" />
						<el-option label="OpenAI" value="openai" />
						<el-option label="Ollama" value="ollama" />
						<el-option label="自定义" value="custom" />
					</el-select>
				</el-form-item>
				<el-form-item label="API 端点" prop="baseUrl">
					<el-input v-model="state.form.baseUrl" :placeholder="`如：${providerPresets[state.form.provider]?.baseUrl || 'https://api.xxx.com'}`" clearable />
				</el-form-item>
				<el-form-item label="API Key" prop="apiKey">
					<el-input v-model="state.form.apiKey" placeholder="sk-xxx（Ollama 等本地调用可留空）" show-password clearable />
				</el-form-item>
				<el-form-item label="模型" prop="model">
					<el-select v-if="currentProviderModels.length" v-model="state.form.model" placeholder="请选择模型" filterable allow-create default-first-option clearable style="width:100%">
						<el-option v-for="m in currentProviderModels" :key="m" :label="m" :value="m" />
					</el-select>
					<el-input v-else v-model="state.form.model" placeholder="请输入模型名称，如 gpt-3.5-turbo" clearable />
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
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { AiModelApi } from '/@/api/ai/index';

const aiApi = AiModelApi();
const myRefForm = ref();

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
	activeName: '',
	dialog: {
		visible: false,
		title: '新增模型',
	},
	form: {
		id: null as any,
		name: '',
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
	const base = providerFilter.value
		? state.tableData.filter((r: any) => (r.provider || 'custom') === providerFilter.value)
		: [...state.tableData];
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
// 各供应商预设：API 网关 + 可选模型列表（第一个为默认模型）
const providerPresets: Record<string, { baseUrl: string; models: string[] }> = {
	deepseek: { baseUrl: 'https://api.deepseek.com', models: ['deepseek-chat', 'deepseek-reasoner'] },
	qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', models: ['qwen-plus', 'qwen-max', 'qwen-turbo', 'qwen-long'] },
	zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', models: ['glm-4.5', 'glm-4.5-air', 'glm-4-plus', 'glm-4-flash'] },
	moonshot: { baseUrl: 'https://api.moonshot.cn/v1', models: ['moonshot-v1-8k', 'moonshot-v1-32k', 'moonshot-v1-128k', 'kimi-k2-0711-preview'] },
	openai: { baseUrl: 'https://api.openai.com', models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4.1', 'gpt-4.1-mini', 'o3-mini'] },
	ollama: { baseUrl: 'http://localhost:11434', models: ['llama3.1', 'llama3.2', 'qwen2.5', 'mistral', 'gemma2'] },
	custom: { baseUrl: '', models: [] },
};

// 当前供应商可选模型（用于下拉列表）
const currentProviderModels = computed(() => providerPresets[state.form.provider]?.models || []);

// 切换供应商时，API 端点跟随该供应商网关，模型回落到该供应商默认模型
const onProviderChange = (provider: string) => {
	const preset = providerPresets[provider];
	if (!preset) return;
	state.form.baseUrl = preset.baseUrl;
	state.form.model = preset.models[0] || '';
};

const initTableData = () => {
	state.loading = true;
	aiApi.list().then((res: any) => {
		state.tableData = res.data || [];
		const active = state.tableData.find((item: any) => item.active);
		state.activeName = active ? `${active.name} (${active.model})` : '无';
	}).catch(() => {}).finally(() => {
		state.loading = false;
	});
};

const onOpenEdit = (row: any) => {
	if (row) {
		aiApi.get(row.id).then((res: any) => {
			state.form = { ...res.data, extraHeaders: res.data.extraHeaders || '' };
			state.form.apiKey = '********'; // 后端已脱敏，保持占位
			state.dialog.title = '编辑模型';
			state.dialog.visible = true;
		});
	} else {
		state.form = {
			id: null,
			name: '',
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
</style>
