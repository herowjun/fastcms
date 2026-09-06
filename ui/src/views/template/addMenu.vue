<template>
	<div >
		<el-dialog title="新增菜单" v-model="state.isShowDialog" width="769px">
			<el-form :model="state.ruleForm" label-width="80px" :rules="state.rules" ref="myRefForm">
				<el-row :gutter="35">
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="菜单名称" prop="menuName">
							<el-input v-model="state.ruleForm.menuName" placeholder="请输入菜单名称" clearable></el-input>
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="菜单" prop="menuIcon">
							<IconSelector placeholder="请输入菜单图标" v-model="state.ruleForm.menuIcon" />
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="关联" prop="urlType">
							<el-select v-model="state.ruleForm.urlType" placeholder="请选择" clearable class="w100">
								<el-option label="网址" :value="0"></el-option>
								<el-option label="文章" :value="1"></el-option>
								<el-option label="页面" :value="2"></el-option>
								<el-option label="分类" :value="3"></el-option>
							</el-select>
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="菜单地址" prop="menuUrl">
							<el-input v-model="state.ruleForm.menuUrl" placeholder="请输入菜单地址" clearable></el-input>
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="排序" prop="sortNum">
							<el-input type="number" v-model="state.ruleForm.sortNum" placeholder="排序" clearable></el-input>
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="打开方式" prop="target">
							<el-select v-model="state.ruleForm.target" placeholder="请选择打开方式" clearable class="w100">
								<el-option label="本窗口" value="_self"></el-option>
								<el-option label="新开窗口" value="_blank"></el-option>
							</el-select>
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="24" :md="24" :lg="24" :xl="24" class="mb20">
						<el-form-item label="显示范围" prop="scope">
							<el-radio-group v-model="state.ruleForm.scope" @change="onScopeChange">
								<el-radio label="global">全局（所有模板共用）</el-radio>
								<el-radio label="template">指定模板专属</el-radio>
							</el-radio-group>
						</el-form-item>
					</el-col>
					<el-col v-if="state.ruleForm.scope === 'template'" :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
						<el-form-item label="所属模板" prop="templateId" :rules="[{ required: true, message: '请选择所属模板', trigger: 'change' }]">
							<el-select v-model="state.ruleForm.templateId" placeholder="请选择模板" clearable class="w100">
								<el-option v-for="item in state.templateList" :key="item.id" :label="item.name" :value="item.id"></el-option>
							</el-select>
						</el-form-item>
					</el-col>
					<template v-else>
						<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
							<el-form-item label="排除模板" prop="excludeTemplateIds">
								<el-select v-model="state.ruleForm.excludeTemplateIds" multiple collapse-tags placeholder="不选=所有模板都显示" clearable class="w100">
									<el-option v-for="item in state.templateList" :key="item.id" :label="item.name" :value="item.id"></el-option>
								</el-select>
							</el-form-item>
						</el-col>
						<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
							<el-form-item label="排除站点" prop="excludeSiteKeys">
								<el-select v-model="state.ruleForm.excludeSiteKeys" multiple filterable allow-create default-first-option
									:reserve-keyword="false" placeholder="输入站点域名回车，不填=所有站点都显示" class="w100">
								</el-select>
							</el-form-item>
						</el-col>
					</template>
				</el-row>
			</el-form>
			<template #footer>
				<span class="dialog-footer">
					<el-button @click="onCancel" size="default">取 消</el-button>
					<el-button type="primary" @click="onSubmit" size="default">保 存</el-button>
				</span>
			</template>
		</el-dialog>
	</div>
</template>

<script lang="ts" name="templateAddMenu" setup>
import { ref, reactive, nextTick } from 'vue';
import { ElMessage } from 'element-plus';
import IconSelector from '/@/components/iconSelector/index.vue';
import { TemplateApi } from '/@/api/template/index';

const emit = defineEmits(["reloadTable"]);
const myRefForm = ref();
const templateApi = TemplateApi();
const state = reactive({
	isShowDialog: false,
	title: '',
	// 已安装模板列表（显示范围配置用）
	templateList: [] as Array<any>,
	ruleForm: {
		id: '',
		parentId: '',
		menuName: '',
		menuUrl: '',
		menuIcon: '',
		sortNum: '',
		target: '_self',
		urlType: '',
		// 显示范围：global=全局 / template=模板专属
		scope: 'global',
		templateId: '',
		excludeTemplateIds: [] as Array<string>,
		excludeSiteKeys: [] as Array<string>,
	},
	rules: {
		"menuName": { required: true, message: '请输入菜单名称', trigger: 'blur' },
		"menuUrl": { required: true, message: '请输入菜单地址', trigger: 'blur' },
	},
});
// 加载模板列表
const loadTemplateList = () => {
	if (state.templateList.length > 0) return;
	templateApi.getTemplateList().then((res: any) => {
		state.templateList = res.data || [];
	}).catch(() => {});
};
// 显示范围切换：模板专属时清空排除项，全局时清空专属模板
const onScopeChange = () => {
	if (state.ruleForm.scope === 'template') {
		state.ruleForm.excludeTemplateIds = [];
		state.ruleForm.excludeSiteKeys = [];
	} else {
		state.ruleForm.templateId = '';
	}
};
// 逗号分隔字符串 → 数组（编辑回显）
const csvToArray = (csv: string | null | undefined): Array<string> => {
	return (csv || '').split(',').map((item: string) => item.trim()).filter((item: string) => item.length > 0);
};
// 打开弹窗
const openDialog = (type: string, row?: any) => {
	loadTemplateList();
	if (type === 'edit') {
		state.title = '修改菜单';
		templateApi.getTemplateMenu(row.id).then(res => {
			delete res.data.created;
			delete res.data.updated;
			// 显示范围回显：templateId 非空=模板专属，否则全局 + 排除配置
			const data = res.data;
			state.ruleForm = {
				...data,
				scope: data.templateId ? 'template' : 'global',
				templateId: data.templateId || '',
				excludeTemplateIds: csvToArray(data.excludeTemplateIds),
				excludeSiteKeys: csvToArray(data.excludeSiteKeys),
			};
		})
	} else {
		state.title = '新增菜单';
		nextTick(() => {
			// 清空表单，此项需加表单验证才能使用
			myRefForm.value.resetFields();
			state.ruleForm.id = '';
			state.ruleForm.scope = 'global';
			state.ruleForm.templateId = '';
			state.ruleForm.excludeTemplateIds = [];
			state.ruleForm.excludeSiteKeys = [];
			// 设置菜单上级
			state.ruleForm.parentId = row == null ? 0 : (row.id || 0);
		});
	}
	state.isShowDialog = true;
};
// 关闭弹窗
const closeDialog = () => {
	state.isShowDialog = false;
	nextTick(()=> {
		myRefForm.value.resetFields();
	})
};

// 取消
const onCancel = () => {
	closeDialog();
	initForm();
};

// 新增
const onSubmit = () => {

	myRefForm.value.validate((valid: any) => {
		if (valid) {
			// 显示范围：模板专属 → templateId；全局 → 排除配置（数组转逗号串）
			const form: any = { ...state.ruleForm };
			delete form.scope;
			if (state.ruleForm.scope === 'template') {
				form.excludeTemplateIds = '';
				form.excludeSiteKeys = '';
			} else {
				form.templateId = '';
				form.excludeTemplateIds = (state.ruleForm.excludeTemplateIds || []).join(',');
				form.excludeSiteKeys = (state.ruleForm.excludeSiteKeys || []).join(',');
			}
			templateApi.saveTemplateMenu(form).then(() => {
				closeDialog(); // 关闭弹窗
				// 刷新菜单，未进行后端接口测试
				initForm();
				emit("reloadTable");
			}).catch((res) => {
				ElMessage({showClose: true, message: res.message ? res.message : '系统错误' , type: 'error'});
			})
		}
	});

};

// 表单初始化，方法：`resetFields()` 无法使用
const initForm = () => {
	nextTick(()=> {
		myRefForm.value.resetFields();
	})
};

defineExpose({
	openDialog
})
</script>
