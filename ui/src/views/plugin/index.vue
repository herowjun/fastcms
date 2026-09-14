<template>
	<div>
		<el-card shadow="hover">
			<div class="mb15 plugin-toolbar">
				<el-radio-group v-model="state.tagFilter" size="default" @change="initTableData">
					<el-radio-button label="">全部</el-radio-button>
					<el-radio-button v-for="tag in tagOptions" :key="tag.value" :label="tag.value">{{ tag.label }}</el-radio-button>
				</el-radio-group>
				<el-upload
					:action="state.uploadUrl"
					name="file"
					:headers="state.headers"
					:show-file-list="false"
					:on-success="uploadSuccess"
					:on-error="onHandleUploadError"
					:on-exceed="onHandleExceed"
					accept=".jar,.zip">
					<el-button type="primary" size="default"><el-icon><ele-Plus /></el-icon>安装插件</el-button>
				</el-upload>
			</div>
			<el-table :data="filteredPlugins" stripe style="width: 100%">
				<el-table-column prop="pluginId" label="ID" show-overflow-tooltip></el-table-column>
				<el-table-column prop="pluginClass" label="插件名称" show-overflow-tooltip></el-table-column>
				<el-table-column prop="provider" label="作者" show-overflow-tooltip></el-table-column>
				<el-table-column label="分类" width="240">
					<template #default="scope">
						<template v-if="scope.row.tags && scope.row.tags.length">
							<el-tag v-for="tag in scope.row.tags" :key="tag" :type="tagType(tag)" size="small" class="mr5">{{ tagLabel(tag) }}</el-tag>
						</template>
						<el-tag v-else size="small" type="info">功能</el-tag>
					</template>
				</el-table-column>
				<el-table-column prop="pluginState" label="状态" width="100" show-overflow-tooltip></el-table-column>
				<el-table-column prop="description" label="描述" show-overflow-tooltip></el-table-column>
				<el-table-column label="操作" width="220">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onRowAssets(scope.row)">资产</el-button>
						<el-button size="small" text type="primary" @click="beforeOnRowConfig(scope.row)">配置</el-button>
						<el-button size="small" text type="primary" @click="onRowUnInstall(scope.row)">卸载</el-button>
					</template>
				</el-table-column>
			</el-table>
			<el-pagination
				@size-change="onHandleSizeChange"
				@current-change="onHandleCurrentChange"
				class="mt15"
				:pager-count="5"
				:page-sizes="[10, 20, 30]"
				v-model:current-page="state.tableData.param.pageNum"
				background
				v-model:page-size="state.tableData.param.pageSize"
				layout="total, sizes, prev, pager, next, jumper"
				:total="state.tableData.total"
			>
			</el-pagination>
		</el-card>

		<el-dialog
			title="插件配置"
			fullscreen
			:model-value="state.dialogVisible"
			:before-close="handleClose"
			@opened="onRowConfig"
		>
			<iframe :src="state.pluginConfigUrl" frameborder="0" style="width:100%;height:600px" ref="iframeRef"></iframe>
		</el-dialog>

		<!-- 插件资产抽屉：skill / 能力 / AI 工具 / 组件包 -->
		<el-drawer v-model="state.assetsVisible" :title="'插件资产 — ' + (state.assets && state.assets.pluginId || '')" size="620px">
			<div v-if="state.assets" class="asset-drawer">
				<el-descriptions :column="1" border size="small" class="mb15">
					<el-descriptions-item label="分类标签">
						<el-tag v-for="tag in state.assets.tags" :key="tag" :type="tagType(tag)" size="small" class="mr5">{{ tagLabel(tag) }}</el-tag>
						<span v-if="!state.assets.tags || !state.assets.tags.length" class="asset-empty">无（通用功能插件）</span>
					</el-descriptions-item>
				</el-descriptions>

				<div class="asset-section">
					<div class="asset-section-title">技能 Skills</div>
					<template v-if="state.assets.skills && state.assets.skills.length">
						<div v-for="skill in state.assets.skills" :key="skill.id" class="asset-item">
							<div class="asset-item-head">
								<span class="asset-item-name">{{ skill.name || skill.id }}</span>
								<el-tag v-if="skill.error" type="danger" size="small">无效：{{ skill.error }}</el-tag>
								<el-button v-else size="small" text type="primary" @click="onViewSkillContent(skill)">查看规则</el-button>
							</div>
							<div class="asset-item-desc">{{ skill.id }}<template v-if="skill.description"> — {{ skill.description }}</template></div>
						</div>
					</template>
					<div v-else class="asset-empty">本插件未贡献技能</div>
				</div>

				<div class="asset-section">
					<div class="asset-section-title">业务能力 Capabilities</div>
					<template v-if="state.assets.capabilities && state.assets.capabilities.length">
						<div v-for="cap in state.assets.capabilities" :key="cap.capabilityId" class="asset-item">
							<div class="asset-item-head">
								<span class="asset-item-name">{{ cap.name || cap.capabilityId }}</span>
								<el-tag v-if="cap.requiresChannel" size="small" type="warning">需 {{ cap.requiresChannel }}</el-tag>
							</div>
							<div class="asset-item-desc">{{ cap.capabilityId }}<template v-if="cap.description"> — {{ cap.description }}</template></div>
						</div>
					</template>
					<div v-else class="asset-empty">本插件未贡献业务能力</div>
				</div>

				<div class="asset-section">
					<div class="asset-section-title">AI 工具 Tools</div>
					<template v-if="state.assets.tools && state.assets.tools.length">
						<div v-for="tool in state.assets.tools" :key="tool.name" class="asset-item">
							<div class="asset-item-name">{{ tool.name }}</div>
							<div class="asset-item-desc">{{ tool.description }}</div>
						</div>
					</template>
					<div v-else class="asset-empty">本插件未贡献 AI 工具</div>
				</div>

				<div class="asset-section">
					<div class="asset-section-title">组件包 Components</div>
					<template v-if="state.assets.components && state.assets.components.length">
						<div v-for="pack in state.assets.components" :key="pack.packId" class="asset-item">
							<div class="asset-item-head">
								<span class="asset-item-name">{{ pack.packId }}</span>
								<el-tag size="small" type="info">{{ pack.foundation }}</el-tag>
								<el-tag size="small">{{ pack.componentCount }} 个组件</el-tag>
							</div>
							<div v-if="pack.components && pack.components.length" class="component-list">
								<div v-for="comp in pack.components" :key="comp.id" class="component-item">
									<div class="asset-item-head">
										<span class="asset-item-name">{{ comp.name || comp.id }}</span>
										<el-tag v-if="comp.category" size="small" type="info">{{ categoryLabel(comp.category) }}</el-tag>
										<el-tag v-if="comp.appliesTo && comp.appliesTo.length" size="small" type="warning">适用: {{ comp.appliesTo.join(' / ') }}</el-tag>
									</div>
									<div class="asset-item-desc">{{ comp.id }}<template v-if="comp.description"> — {{ comp.description }}</template></div>
									<div v-if="comp.variants && comp.variants.length" class="component-variants">
										<span v-for="v in comp.variants" :key="v.id" class="component-variant">
											{{ v.id }}<template v-if="v.description">（{{ v.description }}）</template>
										</span>
									</div>
								</div>
							</div>
						</div>
					</template>
					<div v-else class="asset-empty">本插件未贡献组件包</div>
				</div>
			</div>
		</el-drawer>

		<!-- SKILL.md 规则正文（只读） -->
		<el-dialog v-model="state.skillContentVisible" :title="'技能规则 — ' + state.skillContentTitle" width="760px">
			<pre class="skill-content-pre">{{ state.skillContent }}</pre>
		</el-dialog>

	</div>
</template>

<script setup lang="ts" name="pluginManager">
import {ElMessage, ElMessageBox} from 'element-plus';
import {computed, onMounted, reactive, ref} from 'vue';
import {PluginApi} from '/@/api/plugin/index';
import {AiAgentApi} from '/@/api/ai/index';
import {Local} from '/@/utils/storage';

const iframeRef = ref<HTMLElement | null>(null); // iframe ref
const pluginApi = PluginApi();
const agentApi = AiAgentApi();
const state = reactive({
	dialogVisible: false,
	pluginConfigUrl: '',
	limit: 1,
	uploadUrl: import.meta.env.VITE_API_URL + "/admin/plugin/install",
	headers: {"Authorization": Local.get('token')},
	tagFilter: '',
	assetsVisible: false,
	assets: null as any,
	skillContentVisible: false,
	skillContentTitle: '',
	skillContent: '',
	tableData: {
		data: [] as any[],
		total: 0,
		loading: false,
		param: {
			pageNum: 1,
			pageSize: 10,
		},
	},
});

// 标签筛选选项（与后端 PluginAssetService 推断的标签对应）
const tagOptions = [
	{value: 'skill', label: '技能'},
	{value: 'capability', label: '能力'},
	{value: 'tool', label: '工具'},
	{value: 'component', label: '组件'},
	{value: 'payment', label: '支付'},
];

const tagLabelMap: Record<string, string> = {
	skill: '技能',
	capability: '能力',
	tool: '工具',
	component: '组件',
	payment: '支付',
};

const tagLabel = (tag: string) => tagLabelMap[tag] || tag;

const tagType = (tag: string) => {
	const map: Record<string, string> = {
		skill: 'success',
		capability: 'primary',
		tool: 'warning',
		component: '',
		payment: 'danger',
	};
	return (map[tag] || 'info') as any;
};

// 标签筛选（前端过滤：一页数据内过滤，翻页由后端分页控制）
const filteredPlugins = computed(() => {
	if (!state.tagFilter) return state.tableData.data;
	return state.tableData.data.filter((row: any) => (row.tags || []).includes(state.tagFilter));
});

// 初始化表格数据
const initTableData = () => {
	pluginApi.getPluginList(state.tableData.param).then((res) => {
		state.tableData.data = res.data.records;
		state.tableData.total = res.data.total;
	}).catch(() => {
	})
};

const onRowAssets = (row: any) => {
	pluginApi.getPluginAssets(row.pluginId).then((res) => {
		state.assets = res.data;
		state.assetsVisible = true;
	}).catch((e) => {
		ElMessage.error("获取插件资产失败");
	})
};

const onViewSkillContent = (skill: any) => {
	agentApi.getSkillContent(skill.id).then((res) => {
		state.skillContentTitle = skill.name || skill.id;
		state.skillContent = res.data && res.data.content || '';
		state.skillContentVisible = true;
	}).catch(() => {
		ElMessage.error("获取技能规则失败");
	})
};

// 组件分类标签（与 ComponentDescriptor.CATEGORY_* 对应）
const categoryLabel = (category: string) => {
	const map: Record<string, string> = {
		'structural': '结构',
		'content': '内容',
		'social-proof': '背书',
		'conversion': '转化',
		'footer': '页脚',
	};
	return map[category] || category;
};

const onRowUnInstall = (row: any) => {
	ElMessageBox.confirm('此操作将卸载插件, 是否继续?', '提示', {
		confirmButtonText: '卸载',
		cancelButtonText: '取消',
		type: 'warning',
	}).then(() => {
		pluginApi.unInstallPlugin(row.pluginId).then(() => {
			ElMessage.success("卸载成功");
			initTableData();
		}).catch((res) => {
			ElMessage.error(res.message);
		});
	})
	.catch(() => {});
};

let currentConfigRow: any | null = null;
const beforeOnRowConfig = (row: any) => {
currentConfigRow = row;
state.dialogVisible = true;
};

const onRowConfig = () => {
	if (!currentConfigRow) return;
	pluginApi.getPluginConfigUrl(currentConfigRow.pluginId).then((res) => {
		if (!res.data) {
			state.dialogVisible = false;
			ElMessage.info("该插件不支持配置");
			return;
		}
		state.pluginConfigUrl = res.data + "?accessToken=" + Local.get('token');
	}).catch((e) => {
		ElMessage.error("插件不支持配置" + e);
	})

};

const handleClose = () => {
	state.dialogVisible = false;
};

// 分页改变
const onHandleSizeChange = (val: number) => {
	state.tableData.param.pageSize = val;
	initTableData();
};
// 分页改变
const onHandleCurrentChange = (val: number) => {
	state.tableData.param.pageNum = val;
	initTableData();
};

const uploadSuccess = (res :any) => {
	if(res.code == 200) {
		ElMessage.success("安装成功");
		initTableData();
	}else {
		ElMessage.error(res.message);
	}
}
const onHandleUploadError = (error: any) => {
	ElMessage.error("安装失败," + error);
}
const onHandleExceed = () => {

}

// 页面加载时
onMounted(() => {
	initTableData();
});
</script>

<style scoped lang="scss">
.plugin-toolbar {
	display: flex;
	justify-content: space-between;
	align-items: center;
	flex-wrap: wrap;
	gap: 10px;
}
.mr5 {
	margin-right: 5px;
}
.asset-drawer {
	.asset-section {
		margin-bottom: 18px;

		.asset-section-title {
			font-weight: 600;
			font-size: 14px;
			margin-bottom: 8px;
			padding-left: 8px;
			border-left: 3px solid var(--el-color-primary);
		}

		.asset-item {
				padding: 8px 10px;
				border: 1px solid var(--el-border-color-lighter);
				border-radius: 6px;
				margin-bottom: 6px;

				.asset-item-head {
					display: flex;
					align-items: center;
					gap: 8px;
					flex-wrap: wrap;

					.asset-item-name {
						font-weight: 500;
					}
				}

				.asset-item-desc {
					font-size: 12px;
					color: var(--el-text-color-secondary);
					margin-top: 4px;
					word-break: break-all;
				}
			}

			.component-list {
				margin-top: 8px;
				padding-left: 10px;
				border-left: 2px dashed var(--el-border-color-lighter);

				.component-item {
					padding: 6px 0;

					& + .component-item {
						border-top: 1px dashed var(--el-border-color-lighter);
					}
				}

				.component-variants {
					margin-top: 4px;
					display: flex;
					flex-wrap: wrap;
					gap: 6px;

					.component-variant {
						font-size: 12px;
						color: var(--el-text-color-secondary);
						background: var(--el-fill-color-light);
						padding: 1px 8px;
						border-radius: 10px;
					}
				}
			}

		.asset-empty {
			font-size: 12px;
			color: var(--el-text-color-secondary);
			padding: 4px 2px;
		}
	}
}
.skill-content-pre {
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
