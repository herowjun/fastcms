<template>
	<div class="ai-agent-container">
		<div>
			<div class="mb15">
				<el-button type="primary" size="default" @click="onOpenEdit(null)">
					<el-icon><ele-Plus /></el-icon>新建智能体
				</el-button>
				<el-input v-model="keyword" placeholder="搜索名称 / 描述" clearable size="default" class="ml10" style="width: 220px" prefix-icon="ele-Search" />
				<span class="ml10 tip-text">自定义智能体为 chat 型；pipeline 型由系统代码驱动，只读可复制</span>
			</div>

			<el-table :data="filteredTableData" stripe style="width: 100%" v-loading="state.loading">
			<el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip>
				<template #default="scope">
					<span class="agent-name">{{ scope.row.name }}</span>
					<div class="agent-desc">{{ scope.row.description || '—' }}</div>
				</template>
			</el-table-column>
			<el-table-column label="执行模式" width="100" align="center">
				<template #default="scope">
					<el-tag size="small" :type="scope.row.executionMode === 'pipeline' ? 'warning' : 'primary'">
						{{ scope.row.executionMode === 'pipeline' ? 'pipeline' : 'chat' }}
					</el-tag>
				</template>
			</el-table-column>
			<el-table-column label="来源" width="80" align="center">
				<template #default="scope">
					<el-tag size="small" :type="scope.row.source === 'custom' ? 'success' : 'info'">
						{{ scope.row.source === 'custom' ? '自定义' : '内置' }}
					</el-tag>
				</template>
			</el-table-column>
			<el-table-column label="Skill / Tool" width="120" align="center">
				<template #default="scope">
					<span>{{ (scope.row.skills || []).length }} skill / {{ (scope.row.tools || []).length }} tool</span>
				</template>
			</el-table-column>
			<el-table-column label="模型" min-width="130" show-overflow-tooltip>
				<template #default="scope">
					{{ modelLabel(scope.row.modelConfigId) }}
				</template>
			</el-table-column>
			<el-table-column label="今日用量" width="130" align="center">
				<template #default="scope">
					<span v-if="scope.row.dailyTokenQuota > 0" :class="{ 'usage-over': (scope.row.todayTokens || 0) >= scope.row.dailyTokenQuota }">
						{{ formatTokens(scope.row.todayTokens) }} / {{ formatTokens(scope.row.dailyTokenQuota) }}
					</span>
					<span v-else>{{ formatTokens(scope.row.todayTokens) }}</span>
				</template>
			</el-table-column>
			<el-table-column label="状态" width="80" align="center">
				<template #default="scope">
					<el-tag v-if="scope.row.status !== 0" type="success" size="small">启用</el-tag>
					<el-tag v-else type="danger" size="small">停用</el-tag>
				</template>
			</el-table-column>
			<el-table-column label="操作" width="200" fixed="right">
				<template #default="scope">
					<el-button size="small" text type="primary" @click="onView(scope.row)">{{ isCustom(scope.row) ? '编辑' : '查看' }}</el-button>
					<el-button size="small" text type="primary" @click="onCopy(scope.row)">复制</el-button>
					<el-button v-if="isCustom(scope.row)" size="small" text type="danger" @click="onDelete(scope.row)">删除</el-button>
				</template>
			</el-table-column>
		</el-table>
		</div>

		<el-drawer v-model="state.drawer.visible" :title="drawerTitle" size="620px" :close-on-click-modal="false" destroy-on-close>
			<el-form :model="state.form" :rules="state.rules" ref="myRefForm" label-width="92px" :disabled="state.readonly">
				<el-divider content-position="left">基本信息</el-divider>
				<el-form-item label="名称" prop="name">
					<el-input v-model="state.form.name" placeholder="如：SEO 文案写手" clearable maxlength="64" show-word-limit />
				</el-form-item>
				<el-form-item label="描述">
					<el-input v-model="state.form.description" type="textarea" :rows="2" placeholder="一句话说明该智能体的用途" maxlength="255" show-word-limit />
				</el-form-item>
				<el-row :gutter="20">
					<el-col :span="12">
						<el-form-item label="排序">
							<el-input-number v-model="state.form.sortNum" :min="0" style="width: 100%" />
						</el-form-item>
					</el-col>
					<el-col :span="12">
						<el-form-item label="启用">
							<el-switch v-model="state.form.status" :active-value="1" :inactive-value="0" />
						</el-form-item>
					</el-col>
				</el-row>

				<el-divider content-position="left">模型与参数</el-divider>
				<el-form-item label="模型配置">
					<el-select v-model="state.form.modelConfigId" placeholder="继承当前激活的对话模型" clearable style="width: 100%">
						<el-option v-for="m in chatModels" :key="m.id" :label="`${m.name} (${m.model})`" :value="m.id" />
					</el-select>
				</el-form-item>
				<el-row :gutter="20">
					<el-col :span="12">
						<el-form-item label="温度">
							<el-input-number v-model="state.form.temperature" :min="0" :max="2" :step="0.1" :precision="2" placeholder="继承默认" style="width: 100%" />
							<div class="tip-text">留空继承模型配置默认值</div>
						</el-form-item>
					</el-col>
					<el-col :span="12">
						<el-form-item label="MaxTokens">
							<el-input-number v-model="state.form.maxTokens" :min="1" :max="128000" :step="512" placeholder="继承默认" style="width: 100%" />
							<div class="tip-text">留空继承模型配置默认值</div>
						</el-form-item>
					</el-col>
				</el-row>

				<el-divider content-position="left">系统提示词</el-divider>
				<el-form-item label="提示词" prop="systemPrompt">
					<el-input
						v-model="state.form.systemPrompt"
						type="textarea"
						:rows="8"
						placeholder="定义该智能体的人设、写作要求与输出规范。支持 {{site.name}} 等站点变量，运行时自动替换。"
					/>
				</el-form-item>

				<el-divider content-position="left">能力绑定（白名单）</el-divider>
				<el-form-item label="Skill">
					<div class="bind-toolbar">
						<el-input v-model="skillFilter" placeholder="搜索名称 / 描述" clearable size="small" style="width: 200px" prefix-icon="ele-Search" />
						<span class="tip-text ml10">已选 {{ state.form.skills.length }} / {{ state.skillOptions.length }}</span>
					</div>
					<el-checkbox-group v-model="state.form.skills">
						<div class="bind-list">
							<div v-for="s in filteredSkillOptions" :key="s.id" class="bind-item" :class="{ 'bind-item-selected': state.form.skills.includes(s.id) }">
								<el-checkbox :value="s.id">
									<span>{{ s.name || s.id }}</span>
									<el-tag v-if="s.source === 'file'" size="small" type="info" class="ml5">文件库</el-tag>
									<el-tag v-else-if="s.pluginId" size="small" type="warning" class="ml5">插件</el-tag>
									<el-tag v-if="s.error" size="small" type="danger" class="ml5">无效</el-tag>
									<el-button v-if="!s.error" size="small" text type="primary" class="ml5" @click.prevent="onViewSkillRule(s)">规则</el-button>
								</el-checkbox>
								<div class="bind-desc">{{ s.description }}<template v-if="s.error">（{{ s.error }}）</template></div>
							</div>
							<div v-if="!filteredSkillOptions.length" class="bind-desc">无匹配的 skill</div>
						</div>
					</el-checkbox-group>
					<div class="tip-text">仅勾选的 skill 会注入该智能体的 system prompt（L1 摘要常驻，完整指令按需加载）；插件技能随插件分发装卸</div>
				</el-form-item>
				<el-form-item label="Tools">
					<div class="bind-toolbar">
						<el-input v-model="toolFilter" placeholder="搜索名称 / 描述" clearable size="small" style="width: 200px" prefix-icon="ele-Search" />
						<span class="tip-text ml10">已选 {{ state.form.tools.length }} / {{ state.toolOptions.length }}</span>
					</div>
					<el-checkbox-group v-model="state.form.tools">
						<div class="bind-list">
							<div v-for="t in filteredToolOptions" :key="t.name" class="bind-item" :class="{ 'bind-item-selected': state.form.tools.includes(t.name) }">
								<el-checkbox :value="t.name">
									<span>{{ t.name }}</span>
								</el-checkbox>
								<div class="bind-desc">{{ t.description || '—' }}</div>
							</div>
							<div v-if="!state.toolOptions.length" class="bind-desc">暂无已注册的全局工具（插件贡献的工具注册后会出现在此处）</div>
							<div v-else-if="!filteredToolOptions.length" class="bind-desc">无匹配的工具</div>
						</div>
					</el-checkbox-group>
					<div class="tip-text">工具白名单：模型在对话中只允许调用勾选的工具</div>
				</el-form-item>

				<el-divider content-position="left">配额策略</el-divider>
				<el-form-item label="日Token配额">
					<el-input-number v-model="state.form.dailyTokenQuota" :min="0" :step="10000" style="width: 100%" />
					<div class="tip-text">该智能体每日消耗 token 上限，0 = 不限</div>
				</el-form-item>
			</el-form>
			<template #footer>
				<el-button @click="state.drawer.visible = false">{{ state.readonly ? '关 闭' : '取 消' }}</el-button>
				<el-button v-if="state.readonly" type="primary" @click="onCopy(state.viewing)">复制为自定义</el-button>
				<el-button v-else type="primary" :loading="state.submitting" @click="onSubmit">保 存</el-button>
			</template>
		</el-drawer>

		<!-- 技能详情规则（SKILL.md 正文，只读） -->
		<el-dialog v-model="state.skillRule.visible" :title="'技能规则 — ' + state.skillRule.title" width="760px">
			<pre class="skill-rule-pre">{{ state.skillRule.content }}</pre>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="aiAgent">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { AiAgentApi, AiModelApi } from '/@/api/ai/index';

const agentApi = AiAgentApi();
const modelApi = AiModelApi();
const myRefForm = ref();

const keyword = ref('');

const isCustom = (row: any) => row.source === 'custom';

const state = reactive({
	loading: false,
	submitting: false,
	readonly: false,
	viewing: null as any,
	tableData: [] as any[],
	skillOptions: [] as any[],
	toolOptions: [] as any[],
	models: [] as any[],
	drawer: {
		visible: false,
		title: '新建智能体',
	},
	skillRule: {
		visible: false,
		title: '',
		content: '',
	},
	form: {
		id: null as any,
		name: '',
		description: '',
		systemPrompt: '',
		modelConfigId: null as any,
		temperature: null as any,
		maxTokens: null as any,
		skills: [] as string[],
		tools: [] as string[],
		dailyTokenQuota: 0,
		sortNum: 0,
		status: 1,
	},
	rules: {
		name: [{ required: true, message: '请输入智能体名称', trigger: 'blur' }],
		systemPrompt: [{ required: true, message: '请输入系统提示词', trigger: 'blur' }],
	},
});

// 对话场景的模型（智能体只能绑定 chat 模型）
const chatModels = computed(() => state.models.filter((m: any) => (m.scene || 'chat') === 'chat'));

const modelLabel = (modelConfigId: any) => {
	if (modelConfigId == null) return '继承激活配置';
	const m = state.models.find((item: any) => item.id === modelConfigId);
	return m ? `${m.name} (${m.model})` : `#${modelConfigId}`;
};

// token 数千分位格式化（null 显示 0）
const formatTokens = (val: any) => {
	const n = Number(val) || 0;
	return n.toLocaleString();
};

const filteredTableData = computed(() => {
	const kw = keyword.value.trim().toLowerCase();
	if (!kw) return state.tableData;
	return state.tableData.filter(
		(r: any) => (r.name || '').toLowerCase().includes(kw) || (r.description || '').toLowerCase().includes(kw)
	);
});

// 能力绑定列表：搜索过滤 + 已选置顶（稳定排序保持组内原顺序，勾选后自动聚拢到顶部形成已选区）
const skillFilter = ref('');
const toolFilter = ref('');

const filteredSkillOptions = computed(() => {
	const kw = skillFilter.value.trim().toLowerCase();
	const list = state.skillOptions.filter(
		(s: any) =>
			!kw ||
			(s.name || '').toLowerCase().includes(kw) ||
			(s.id || '').toLowerCase().includes(kw) ||
			(s.description || '').toLowerCase().includes(kw)
	);
	return [...list].sort(
		(a: any, b: any) => Number(state.form.skills.includes(b.id)) - Number(state.form.skills.includes(a.id))
	);
});

const filteredToolOptions = computed(() => {
	const kw = toolFilter.value.trim().toLowerCase();
	const list = state.toolOptions.filter(
		(t: any) =>
			!kw ||
			(t.name || '').toLowerCase().includes(kw) ||
			(t.description || '').toLowerCase().includes(kw)
	);
	return [...list].sort(
		(a: any, b: any) => Number(state.form.tools.includes(b.name)) - Number(state.form.tools.includes(a.name))
	);
});

const drawerTitle = computed(() => {
	if (state.readonly) return `查看智能体${state.viewing ? '：' + state.viewing.name : ''}`;
	return state.form.id ? '编辑智能体' : '新建智能体';
});

const initTableData = () => {
	state.loading = true;
	agentApi
		.list()
		.then((res: any) => {
			state.tableData = res.data || [];
		})
		.catch(() => {})
		.finally(() => {
			state.loading = false;
		});
};

const initBindOptions = () => {
	agentApi
		.listSkills()
		.then((res: any) => {
			state.skillOptions = res.data || [];
		})
		.catch(() => {});
	agentApi
		.listTools()
		.then((res: any) => {
			state.toolOptions = res.data || [];
		})
		.catch(() => {});
	modelApi
		.list()
		.then((res: any) => {
			state.models = res.data || [];
		})
		.catch(() => {});
};

// 查看技能详情规则（SKILL.md 正文，只读）
const onViewSkillRule = (s: any) => {
	agentApi
		.getSkillContent(s.id)
		.then((res: any) => {
			state.skillRule.title = s.name || s.id;
			state.skillRule.content = (res.data && res.data.content) || '（无正文）';
			state.skillRule.visible = true;
		})
		.catch(() => {
			ElMessage.error('获取技能规则失败');
		});
};

const fillForm = (row: any) => {
	state.form = {
		id: isCustom(row) ? row.dbId : null,
		name: row.name || '',
		description: row.description || '',
		systemPrompt: row.systemPrompt || '',
		modelConfigId: row.modelConfigId ?? null,
		temperature: row.temperature ?? null,
		maxTokens: row.maxTokens ?? null,
		skills: [...(row.skills || [])],
		tools: [...(row.tools || [])],
		dailyTokenQuota: row.dailyTokenQuota ?? 0,
		sortNum: row.sortNum ?? 0,
		status: row.status ?? 1,
	};
};

const onOpenEdit = (row: any) => {
	state.readonly = false;
	state.viewing = null;
	if (row) {
		fillForm(row);
	} else {
		state.form = {
			id: null,
			name: '',
			description: '',
			systemPrompt: '',
			modelConfigId: null,
			temperature: null,
			maxTokens: null,
			skills: [],
			tools: [],
			dailyTokenQuota: 0,
			sortNum: 0,
			status: 1,
		};
	}
	state.drawer.visible = true;
};

const onView = (row: any) => {
	state.readonly = !isCustom(row);
	state.viewing = row;
	fillForm(row);
	state.drawer.visible = true;
};

const onSubmit = () => {
	myRefForm.value.validate((valid: boolean) => {
		if (!valid) return;
		state.submitting = true;
		agentApi
			.save({
				...state.form,
				// el-select 清空后为 ''，统一转 null 表示继承
				modelConfigId: state.form.modelConfigId === '' ? null : state.form.modelConfigId,
			})
			.then(() => {
				ElMessage.success('保存成功');
				state.drawer.visible = false;
				initTableData();
			})
			.catch((err: any) => {
				ElMessage.error(err?.message || '保存失败');
			})
			.finally(() => {
				state.submitting = false;
			});
	});
};

const onCopy = (row: any) => {
	if (!row) return;
	agentApi
		.copy(row.agentId)
		.then((res: any) => {
			ElMessage.success('已复制为自定义智能体');
			initTableData();
			// 直接打开副本编辑抽屉
			state.readonly = false;
			state.viewing = null;
			fillForm(res.data);
			state.drawer.visible = true;
		})
		.catch((err: any) => {
			ElMessage.error(err?.message || '复制失败');
		});
};

const onDelete = (row: any) => {
	ElMessageBox.confirm(`确定要删除智能体「${row.name}」吗？删除后不可恢复。`, '提示', {
		confirmButtonText: '删除',
		cancelButtonText: '取消',
		type: 'warning',
	})
		.then(() => {
			agentApi.remove(row.dbId).then(() => {
				ElMessage.success('删除成功');
				initTableData();
			});
		})
		.catch(() => {});
};

onMounted(() => {
	initTableData();
	initBindOptions();
});
</script>

<style scoped lang="scss">
.mb15 {
	margin-bottom: 15px;
}
.ml10 {
	margin-left: 10px;
}
.ml5 {
	margin-left: 5px;
}
.agent-name {
	font-weight: 600;
}
.usage-over {
	color: var(--el-color-danger);
	font-weight: 600;
}
.agent-desc {
	color: var(--el-text-color-secondary);
	font-size: 12px;
	margin-top: 2px;
}
.tip-text {
	color: var(--el-text-color-secondary);
	font-size: 12px;
}
.bind-toolbar {
	width: 100%;
	display: flex;
	align-items: center;
	margin-bottom: 6px;
}
.bind-list {
	width: 100%;
	max-height: 320px;
	overflow-y: auto;
	border: 1px solid var(--el-border-color-lighter);
	border-radius: 6px;
	padding: 0 10px;
	box-sizing: border-box;
}
.bind-item {
	padding: 4px 0;
	border-bottom: 1px dashed var(--el-border-color-lighter);
	&:last-child {
		border-bottom: none;
	}
	&.bind-item-selected {
		background: var(--el-color-primary-light-9);
		border-radius: 4px;
		padding-left: 6px;
		padding-right: 6px;
	}
}
.bind-desc {
	color: var(--el-text-color-secondary);
	font-size: 12px;
	padding-left: 24px;
	line-height: 1.6;
}
.skill-rule-pre {
	white-space: pre-wrap;
	word-break: break-word;
	background: var(--el-fill-color-light);
	padding: 14px;
	border-radius: 6px;
	font-size: 13px;
	line-height: 1.7;
	max-height: 60vh;
	overflow: auto;
	margin: 0;
}
</style>
