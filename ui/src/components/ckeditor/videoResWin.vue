<template>
  <el-dialog title="选择视频" fullscreen v-model="state.isShowDialog" :append-to-body="true">
		<div class="res-body">
			<el-card shadow="hover" class="dir-panel">
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
						</div>
					</template>
				</el-tree>
			</el-card>
			<div class="res-main">
				<el-upload
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
					<el-button type="primary"><el-icon><ele-Plus /></el-icon>上传视频</el-button>
				</el-upload>

				<el-card shadow="hover">
					<template v-if="state.tableData.data.length > 0">
						<el-checkbox-group :max="state.max" v-model="state.checkedObjs" class="imgWrap">
							<div class="mb10" v-for="(v, k) in state.tableData.data" :key="k" @click="onTableItemClick(v)">
								<el-card :body-style="{ padding: '6px' }">
									<video :src="v.path" preload="metadata" controls class="video"></video>
									<div style="padding: 14px;">
										<el-checkbox :label="v" style="width:100%">
											<el-tooltip class="item" effect="dark" :content="v.fileName" placement="top-start">
												<div class="filename">{{ v.fileName }}</div>
											</el-tooltip>
										</el-checkbox>
									</div>
								</el-card>
							</div>
						</el-checkbox-group>
				</template>
					<el-empty v-else description="暂无数据"></el-empty>
					<template v-if="state.tableData.data.length > 0">
						<el-pagination
							style="text-align: right"
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
				</el-card>
			</div>
		</div>
		<template #footer>
				<span class="dialog-footer">
					<el-button @click="closeDialog" size="default">取 消</el-button>
					<el-button type="primary" size="default" @click="onSubmit">确 定</el-button>
				</span>
			</template>
	</el-dialog>
</template>

<script lang="ts" setup name="ckeditorVideoDialog">
import {reactive, ref, onMounted } from "vue";
import { AttachApi } from '/@/api/attach/index';
import { ClientAttachApi } from '/@/api/attach/client';
import { ElMessage } from 'element-plus';
import { Local } from '/@/utils/storage';
import insertVideo from "./videoPlugin/insertVideo";
import connect from "./videoPlugin/connect";


const articleApi = AttachApi();
const clientAttachApi = ClientAttachApi();
const dirTreeRef = ref();

const props = defineProps({
	fileType: String,
	isClient: {
		type: Boolean,
		default: false,
	},
})

let _uploadUrl = import.meta.env.VITE_API_URL + "/admin/attachment/upload";
if(props.isClient && props.isClient == true) {
	_uploadUrl = import.meta.env.VITE_API_URL + "/client/attachment/upload";
}

const state = reactive({
	isShowDialog: false,
	queryParams: {},
	showSearch: true,
	max: 1,
	limit: 3,
	uploadUrl: _uploadUrl,
	headers: {"Authorization": Local.get('token')},
	checkedObjs: [],	//选中的视频元素
	// 当前选中目录：-1=全部附件，0=未分类，>0=具体目录
	currentDirectory: -1,
	// 上传时附带的目录参数（当前在具体目录下时自动归档）
	uploadData: {} as any,
	dirTree: [] as any[],
	tableData: {
		data: [],
		total: 99,
		loading: false,
		param: {
			pageNum: 1,
			pageSize: 10,
			fileType: 'video',
		},
	},
});

const openDialog = (max: number) => {
	state.isShowDialog = true;
	state.max = max;
	loadDirTree();
	initTableData();
};
// 关闭弹窗
const closeDialog = () => {
	state.isShowDialog = false;
	state.checkedObjs = [];
	state.max = 1;
};

// 构造目录树数据（顶部固定"全部附件/未分类"两个虚拟节点）
const buildDirTree = (roots: any[]) => {
	const convert = (list: any[]): any[] => (list || []).map((d: any) => ({
		key: 'dir-' + d.id,
		id: d.id,
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
	if(props.isClient && props.isClient == true) {
		clientAttachApi.getDirTree().then((res: any) => {
			buildDirTree(res.data || []);
		});
	} else {
		articleApi.getDirTree().then((res: any) => {
			buildDirTree(res.data || []);
		});
	}
};

const initTableData = () => {
	const params: any = { ...state.tableData.param };
	if(state.currentDirectory !== -1) {
		params.directoryId = state.currentDirectory;
	}
	if(props.isClient && props.isClient == true) {
		clientAttachApi.getAttachList(params).then((res) => {
			state.tableData.data = res.data.records;
			state.tableData.total = res.data.total;
		});
	} else {
		articleApi.getAttachList(params).then((res) => {
			state.tableData.data = res.data.records;
			state.tableData.total = res.data.total;
		});
	}
};

// 切换目录：更新上传归档参数 + 重查列表
const onDirClick = (data: any) => {
	state.currentDirectory = data.id;
	state.uploadData = data.id > 0 ? { directoryId: data.id } : {};
	state.tableData.param.pageNum = 1;
	initTableData();
};

const uploadSuccess = () => {
	initTableData();
	loadDirTree();
}

const onHandleExceed = () => {
	ElMessage.error("上传文件数量不能超过 "+state.limit+" 个!");
}
// 上传失败
const onHandleUploadError = () => {
	ElMessage.error("上传失败");
}

onMounted(() => {
	initTableData();
});

// 当前列表项点击
const onTableItemClick = (v: object) => {
	console.log(v);
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

const onSubmit = () => {
	//把选中的视频插入编辑器（videoBlock 模型元素，可缩放、可拖动的视频部件）
	state.checkedObjs.forEach((item: any) => insertVideo(connect.editorObj.model, item.path));
	closeDialog();
};

defineExpose({
	openDialog
})
</script>

<style scoped lang="scss">
// 左右分栏：左侧目录树筛选，右侧上传+网格+分页
.res-body {
	display: flex;
	gap: 15px;
	align-items: flex-start;
}

.dir-panel {
	width: 220px;
	flex-shrink: 0;

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
	}
}

.res-main {
	flex: 1;
	min-width: 0;
}

.upload-btn {
	padding-bottom: 10px;
}
.bottom {
    margin-top: 13px;
    line-height: 12px;
}

.button {
	padding: 0;
	float: right;
}

// 与图片选择弹窗保持一致：固定高度、黑底等比缩放显示首帧
.video {
    width: 100%;
	height: 110px;
    display: block;
    object-fit: contain;
    background: #000;
}

.clearfix:before,
.clearfix:after {
    display: table;
    content: "";
}

.clearfix:after {
	clear: both
}
.imgWrap {
	width: 100%;
	display: grid;
	// 固定每行 10 个，与图片选择弹窗一致，数量不足时不拉伸占满整行
	grid-template-columns: repeat(10, minmax(0, 1fr));
	grid-template-rows:auto;
	grid-row-gap: 10px;
	grid-column-gap: 12px;
}

.filename{
	white-space:nowrap;
	overflow:hidden;
	text-overflow:ellipsis;
}
:deep .el-checkbox__label {
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
	line-height: 30px;
}
</style>
