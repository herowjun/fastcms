<template>
  <el-dialog title="选择视频" fullscreen v-model="state.isShowDialog" :append-to-body="true">
		<div style="width:100%">
			<el-upload
				class="upload-btn"
				:action="state.uploadUrl"
				name="files"
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
		<template #footer>
				<span class="dialog-footer">
					<el-button @click="closeDialog" size="default">取 消</el-button>
					<el-button type="primary" size="default" @click="onSubmit">确 定</el-button>
				</span>
			</template>
	</el-dialog>
</template>

<script lang="ts" setup name="ckeditorVideoDialog">
import {reactive, onMounted } from "vue";
import { AttachApi } from '/@/api/attach/index';
import { ClientAttachApi } from '/@/api/attach/client';
import { ElMessage } from 'element-plus';
import { Local } from '/@/utils/storage';
import insertVideo from "./videoPlugin/insertVideo";
import connect from "./videoPlugin/connect";


const articleApi = AttachApi();
const clientAttachApi = ClientAttachApi();

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
	initTableData();
};
// 关闭弹窗
const closeDialog = () => {
	state.isShowDialog = false;
	state.checkedObjs = [];
	state.max = 1;
};

const initTableData = () => {
	if(props.isClient && props.isClient == true) {
		clientAttachApi.getAttachList(state.tableData.param).then((res) => {
			state.tableData.data = res.data.records;
			state.tableData.total = res.data.total;
		});
	} else {
		articleApi.getAttachList(state.tableData.param).then((res) => {
			state.tableData.data = res.data.records;
			state.tableData.total = res.data.total;
		});
	}
};

const uploadSuccess = () => {
	initTableData();
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
