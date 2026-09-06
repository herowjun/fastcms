<template>
	<div class="list-adapt-container">
		<el-card shadow="hover">
			<el-row :gutter="20">
				<el-col :xs="24" :sm="8" :md="6" :lg="5" :xl="4">
					<div class="dir-panel">
						<div class="dir-toolbar">
							<span class="dir-title">附件目录</span>
							<el-button size="small" text type="primary" title="新建目录" @click="onCreateDir()">
								<el-icon><ele-FolderAdd /></el-icon>新建
							</el-button>
						</div>
						<el-tree
							ref="dirTreeRef"
							:data="state.dirTree"
							node-key="key"
							highlight-current
							default-expand-all
							:expand-on-click-node="false"
							@current-change="onDirClick">
							<template #default="{ data }">
								<div class="dir-node">
									<el-icon class="dir-node-icon"><ele-Folder /></el-icon>
									<span class="dir-node-name" :title="data.name">{{ data.name }}</span>
									<el-badge v-if="data.attachmentCount > 0" :value="data.attachmentCount" type="info" class="dir-node-badge" />
									<span v-if="data.id > 0" class="dir-node-actions" @click.stop>
										<el-icon title="重命名" @click.stop="onRenameDir(data)"><ele-EditPen /></el-icon>
										<el-icon title="删除目录" @click.stop="onDeleteDir(data)"><ele-Delete /></el-icon>
									</span>
								</div>
							</template>
						</el-tree>
					</div>
				</el-col>
				<el-col :xs="24" :sm="16" :md="18" :lg="19" :xl="20">
					<div class="attach-toolbar">
						<el-upload
							ref="uploadRef"
							class="upload-btn"
							:action="state.uploadUrl"
							name="files"
							:data="state.uploadData"
							multiple
							:headers="state.headers"
							:show-file-list="false"
							:on-success="uploadSuccess"
							:on-exceed="onHandleExceed"
							:on-error="onHandleUploadError"
							:limit="state.limit">
							<el-button size="default" type="primary"><el-icon><ele-Plus /></el-icon>上传附件</el-button>
						</el-upload>
					</div>
					<div v-if="state.tableData.data.length > 0">
						<el-row :gutter="15">
							<el-col :xs="24" :sm="12" :md="8" :lg="6" :xl="4" class="" v-for="(v, k) in state.tableData.data" :key="k" @click="onTableItemClick(v)">
								<el-card :body-style="{ padding: '0px', width:120 }">
									<img :src="v.typePath" :fit="state.fit" class="image">
									<div style="padding: 14px;">
										<span>{{ v.fileName }}</span>
									</div>
								</el-card>
							</el-col>
						</el-row>
					</div>
					<el-empty v-else description="暂无数据"></el-empty>
					<template v-if="state.tableData.data.length > 0">
						<el-pagination
							style="text-align: right; padding: 15px;"
							background
							@size-change="onHandleSizeChange"
							@current-change="onHandleCurrentChange"
							:page-sizes="[10, 20, 30]"
							:current-page="state.tableData.param.pageNum"
							:page-size="state.tableData.param.pageSize"
							layout="total, sizes, prev, pager, next, jumper"
							:total="state.tableData.total"
						>
						</el-pagination>
					</template>
				</el-col>
			</el-row>
		</el-card>
		<Detail ref="detailRef" @reloadTable="initTableData"/>
	</div>
</template>

<script setup lang="ts" name="attachManager">
import { ref, reactive, onMounted } from 'vue';
import { AttachApi } from '/@/api/attach/index';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Local } from '/@/utils/storage';
import Detail from '/@/views/attach/detail.vue';

const attachApi = AttachApi();
const detailRef = ref();
const uploadRef = ref();
const dirTreeRef = ref();
const state = reactive({
	fit: "fill",
	showSearch: true,
	limit: 5,
	// 当前选中目录：-1=全部附件，0=未分类，>0=具体目录
	currentDirectory: -1,
	// 上传时附带的目录参数（当前在具体目录/未分类下时自动归档）
	uploadData: {} as any,
	uploadUrl: import.meta.env.VITE_API_URL + "/admin/attachment/upload",
	headers: {"Authorization": Local.get('token')},
	dirTree: [] as any[],
	tableData: {
		data: [],
		total: 0,
		loading: false,
		param: {
			pageNum: 1,
			pageSize: 10,
		},
	},
});

// 构造目录树数据（顶部固定"全部附件/未分类"两个虚拟节点）
const buildDirTree = (roots: any[]) => {
	const convert = (list: any[]): any[] => (list || []).map((d: any) => ({
		key: 'dir-' + d.id,
		id: d.id,
		parentId: d.parentId,
		name: d.name,
		attachmentCount: d.attachmentCount || 0,
		children: convert(d.children),
	}));
	state.dirTree = [
		{ key: 'all', id: -1, name: '全部附件' },
		{ key: 'unclassified', id: 0, name: '未分类' },
		...convert(roots),
	];
};

const loadDirTree = () => {
	attachApi.getDirTree().then((res: any) => {
		buildDirTree(res.data || []);
	});
};

const initTableData = () => {
	const params: any = { ...state.tableData.param };
	if (state.currentDirectory !== -1) {
		params.directoryId = state.currentDirectory;
	}
	attachApi.getAttachList(params).then((res) => {
		state.tableData.data = res.data.records;
		state.tableData.total = res.data.total;
	});
};

// 切换目录：更新上传归档参数 + 重查列表
const onDirClick = (data: any) => {
	state.currentDirectory = data.id;
	// 在"未分类/具体目录"下上传的附件自动归档到当前目录；"全部"视图下不指定
	state.uploadData = data.id > 0 ? { directoryId: data.id } : {};
	state.tableData.param.pageNum = 1;
	initTableData();
};

// 新建目录（挂到当前选中目录下，未选中具体目录则挂到根）
const onCreateDir = () => {
	const parentId = state.currentDirectory > 0 ? state.currentDirectory : 0;
	ElMessageBox.prompt('请输入目录名称', '新建目录', {
		confirmButtonText: '确 定',
		cancelButtonText: '取 消',
		inputPattern: /\S+/,
		inputErrorMessage: '目录名称不能为空',
	}).then(({ value }) => {
		attachApi.saveDir({ parentId, name: value }).then(() => {
			ElMessage.success('新建成功');
			loadDirTree();
		}).catch((res: any) => {
			ElMessage.error(res.message || '新建失败');
		});
	}).catch(() => {});
};

// 重命名目录
const onRenameDir = (data: any) => {
	ElMessageBox.prompt('请输入新的目录名称', '重命名目录', {
		confirmButtonText: '确 定',
		cancelButtonText: '取 消',
		inputValue: data.name,
		inputPattern: /\S+/,
		inputErrorMessage: '目录名称不能为空',
	}).then(({ value }) => {
		attachApi.saveDir({ id: data.id, parentId: data.parentId ?? 0, name: value }).then(() => {
			ElMessage.success('修改成功');
			loadDirTree();
		}).catch((res: any) => {
			ElMessage.error(res.message || '修改失败');
		});
	}).catch(() => {});
};

// 删除目录（附件移回未分类）
const onDeleteDir = (data: any) => {
	ElMessageBox.confirm(`删除目录「${data.name}」后，目录下的附件将移到未分类，是否继续？`, '提示', {
		confirmButtonText: '删 除',
		cancelButtonText: '取 消',
		type: 'warning',
	}).then(() => {
		attachApi.delDir(data.id).then(() => {
			ElMessage.success('删除成功');
			// 当前删的就是正在浏览的目录 → 回到"全部附件"
			if (state.currentDirectory === data.id) {
				state.currentDirectory = -1;
				state.uploadData = {};
				dirTreeRef.value?.setCurrentKey('all');
				initTableData();
			}
			loadDirTree();
		}).catch((res: any) => {
			ElMessage.error(res.message || '删除失败');
		});
	}).catch(() => {});
};

const uploadSuccess = (res: any) => {
	if (res.code == 200) {
		uploadRef.value!.clearFiles();
		// 当前"全部"视图下未带目录参数上传 → 附件为未分类；仅在具体目录视图才自动归档（已通过 data 参数带上）
		initTableData();
		loadDirTree();
	}else {
		ElMessage.error(res.message);
	}

}

const onHandleExceed = () => {
	ElMessage.error("上传文件数量不能超过 "+state.limit+" 个!");
}
// 上传失败
const onHandleUploadError = () => {
	ElMessage.error("上传失败");
}

onMounted(() => {
	loadDirTree();
	initTableData();
});

// 当前列表项点击
const onTableItemClick = (v: object) => {
	detailRef.value.openDialog(v.id);
};
// 分页点击
const onHandleSizeChange = (val: number) => {
	state.tableData.param.pageSize = val;
	initTableData();
};
// 分页点击
const onHandleCurrentChange = (val: number) => {
	state.tableData.param.pageNum = val;
	initTableData();
};

</script>

<style scoped lang="scss">
.attach-toolbar {
	padding-bottom: 10px;
}

.dir-panel {
	border: 1px solid var(--el-border-color-lighter);
	border-radius: 4px;
	padding: 8px;
	min-height: 400px;

	.dir-toolbar {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 4px 8px 8px;
		border-bottom: 1px solid var(--el-border-color-lighter);
		margin-bottom: 4px;

		.dir-title {
			font-weight: bold;
			font-size: 14px;
		}
	}

	:deep(.el-tree) {
		--el-tree-node-content-height: 32px;
		background: transparent;
	}

	.dir-node {
		flex: 1;
		display: flex;
		align-items: center;
		overflow: hidden;

		.dir-node-icon {
			margin-right: 6px;
			flex-shrink: 0;
		}

		.dir-node-name {
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}

		.dir-node-badge {
			margin-left: 8px;
			flex-shrink: 0;
			transform: scale(0.85);
		}

		.dir-node-actions {
			margin-left: auto;
			display: none;
			flex-shrink: 0;

			.el-icon {
				margin-left: 8px;
				cursor: pointer;
				color: var(--el-color-primary);

				&:hover {
					opacity: 0.7;
				}
			}
		}

		&:hover .dir-node-actions {
			display: inline-flex;
		}
	}
}

.bottom {
    margin-top: 13px;
    line-height: 12px;
}

.button {
    padding: 0;
    float: right;
}

.image {
    width: 100%;
    height: 200px;
    display: block;
}

.clearfix:before,
.clearfix:after {
    display: table;
    content: "";
}

.clearfix:after {
	clear: both
}
</style>
