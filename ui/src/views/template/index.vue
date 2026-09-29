<template>
	<div>
		<el-card shadow="hover">
			<div class="mb15">
				<el-upload 
					:action="state.uploadUrl"
					name="file"
					:headers="state.headers"
					:show-file-list="false"
					:on-success="uploadSuccess"
					:on-error="onHandleUploadError"
					:on-exceed="onHandleExceed"
					accept=".zip">
					<el-button class="mt15" type="primary" size="default"><el-icon><ele-Plus /></el-icon>安装模板</el-button>
				</el-upload>
			</div>
			<el-table :data="state.tableData" stripe style="width: 100%">
				<el-table-column prop="id" label="ID" show-overflow-tooltip></el-table-column>
				<el-table-column prop="name" label="模板名称" show-overflow-tooltip></el-table-column>
				<el-table-column prop="path" label="模板路径" show-overflow-tooltip></el-table-column>
				<el-table-column prop="version" label="版本" show-overflow-tooltip></el-table-column>
				<el-table-column prop="provider" label="作者" show-overflow-tooltip></el-table-column>
				<el-table-column prop="description" label="描述" show-overflow-tooltip></el-table-column>
				<el-table-column label="操作" width="200">
					<template #default="scope">
						<el-tag v-if="scope.row.active == true" type="success">使用中</el-tag>
						<el-button v-if="scope.row.active == false" size="small" text type="primary" @click="onRowEnable(scope.row)">启用</el-button>
						<el-button v-if="scope.row.active == false" size="small" text type="primary" @click="onRowUnInstall(scope.row)">卸载</el-button>
						<el-button size="small" text type="primary" @click="onRowPreview(scope.row)">浏览</el-button>
					</template>
				</el-table-column>
			</el-table>
		</el-card>

		<!-- 卸载模板：默认移动到备份目录；勾选「彻底删除」才不可恢复地删库删文件 -->
		<el-dialog v-model="state.uninstallVisible" title="卸载模板" width="480px" :close-on-click-modal="false" append-to-body>
			<div class="uninstall-body">
				<p class="uninstall-title">
					确定要卸载模板 <b>[{{ state.uninstallRow?.id }}]</b> 吗？
				</p>

				<el-tooltip placement="top" effect="dark" :show-after="0" :hide-after="0">
					<template #content>
						<div class="uninstall-tooltip">
							<div class="uninstall-tooltip-title">彻底删除（不可恢复）</div>
							<div>1. 不生成备份，直接删除模板目录及其全部文件</div>
							<div>2. 清理该模板的专属菜单，并从全局菜单的「排除模板」里摘掉它</div>
							<div class="uninstall-tooltip-note">AI 会话与 token 用量记录属计费审计数据，不会被删除</div>
						</div>
					</template>
					<span class="uninstall-option-trigger">
						<el-checkbox v-model="state.permanent">彻底删除</el-checkbox>
						<el-icon class="uninstall-help"><ele-QuestionFilled /></el-icon>
					</span>
				</el-tooltip>

				<p v-if="!state.permanent" class="uninstall-hint">
					未勾选：模板文件将<b>移动到备份目录</b>，数据库记录保留；把备份目录拷回模板根目录即可恢复。
				</p>
				<p v-else class="uninstall-hint uninstall-hint-danger">
					已勾选：模板文件将被永久删除且<b>不留备份</b>，模板的菜单绑定一并清理，操作<b>无法撤销</b>。
				</p>
			</div>

			<template #footer>
				<el-button @click="state.uninstallVisible = false">取消</el-button>
				<el-button
					:type="state.permanent ? 'danger' : 'primary'"
					:loading="state.uninstallLoading"
					@click="doUnInstall()"
				>{{ state.permanent ? '彻底删除' : '卸载' }}</el-button>
			</template>
		</el-dialog>
	</div>
</template>

<script lang="ts" name="articleManager" setup>
import { ElMessageBox, ElMessage } from 'element-plus';
import { reactive, onMounted } from 'vue';
import { TemplateApi } from '/@/api/template/index';
import { Local } from '/@/utils/storage';

const templateApi = TemplateApi();
const state = reactive({
	tableData: [],
	limit: 1,
	uploadUrl: import.meta.env.VITE_API_URL + "/admin/template/install",
	headers: {"Authorization": Local.get('token')},
	// 卸载弹框
	uninstallVisible: false,
	uninstallRow: null as any,
	permanent: false,
	uninstallLoading: false,
});

// 初始化表格数据
const initTableData = () => {
	templateApi.getTemplateList().then((res) => {
		state.tableData = res.data;
	}).catch(() => {
	})
};
// 当前行卸载：打开确认弹框（默认不勾选「彻底删除」＝移动到备份目录）
const onRowUnInstall = (row: any) => {
	state.uninstallRow = row;
	state.permanent = false;
	state.uninstallLoading = false;
	state.uninstallVisible = true;
};

// 确认卸载。overwriteBackup 仅在「移动到备份目录」且已存在同名备份时由二次确认置 true
const doUnInstall = (overwriteBackup = false) => {
	const row = state.uninstallRow;
	if (!row) return;
	state.uninstallLoading = true;
	templateApi.unInstallTemplate(row.id, state.permanent, overwriteBackup).then(() => {
		ElMessage.success(state.permanent ? "已彻底删除" : "已卸载并移动到备份目录");
		state.uninstallVisible = false;
		initTableData();
	}).catch((res) => {
		// 409＝备份目录已有同名备份，需要用户决定是否覆盖
		if (res && res.code === 409) {
			state.uninstallLoading = false;
			ElMessageBox.confirm(res.message, '提示', {
				confirmButtonText: '覆盖',
				cancelButtonText: '取消',
				type: 'warning',
			}).then(() => {
				doUnInstall(true);
			}).catch(() => {});
			return;
		}
		ElMessage.error(res && res.message ? res.message : '卸载失败');
	}).finally(() => {
		state.uninstallLoading = false;
	});
};
const onRowEnable = (row: object) => {
	ElMessageBox.confirm('确认启用['+row.id+']模板吗?', '提示', {
		confirmButtonText: '启用',
		cancelButtonText: '取消',
		type: 'warning',
	}).then(() => {
		templateApi.enableTemplate(row.id).then(() => {
			ElMessage.success("启用成功");
			initTableData();
		}).catch((res) => {
			ElMessage.error(res.message);
		})
	})
	.catch(() => {

	});
}

// 浏览模板：新标签页打开真实站点首页（真实数据渲染，非 mock 预览）
// 使用中的模板打开正式站点首页 /；
// 未启用的模板打开 /{模板path}/（如 /test001/），后端用该模板+真实数据渲染，启用前可评估真实效果
const onRowPreview = (row: any) => {
	if (row.active == true) {
		window.open('/', '_blank');
	} else {
		window.open(row.path, '_blank');
	}
}

const uploadSuccess = (res :any) => {
	if(res.code == 200) {
		ElMessage.success("上传成功");
		initTableData();
	}else {
		ElMessage.error(res.message);
	}
}
const onHandleUploadError = (error: any) => {
	ElMessage.error("上传失败," + error);
}
const onHandleExceed = () => {

}

// 页面加载时
onMounted(() => {
	initTableData();
});

</script>

<style scoped>
.uninstall-body .uninstall-title {
	margin: 0 0 12px;
	font-size: 14px;
}
.uninstall-option-trigger {
	display: inline-flex;
	align-items: center;
	gap: 2px;
	cursor: help;
}
.uninstall-help {
	color: var(--el-color-info);
	font-size: 14px;
}
.uninstall-hint {
	margin: 10px 0 0;
	font-size: 12px;
	line-height: 1.6;
	color: var(--el-text-color-secondary);
}
.uninstall-hint-danger {
	color: var(--el-color-danger);
}
.uninstall-tooltip {
	line-height: 1.7;
}
.uninstall-tooltip-title {
	margin-bottom: 4px;
	font-weight: 600;
}
.uninstall-tooltip-note {
	margin-top: 6px;
	opacity: 0.75;
}
</style>

