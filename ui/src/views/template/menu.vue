<template>
	<div >
		<el-card shadow="hover">
			<el-button @click="onOpenAddMenu" class="mt15" size="default" type="primary"><el-icon><ele-Plus /></el-icon>新建菜单</el-button>
			<el-table :data="menuTableData" stripe style="width: 100%" row-key="id" :tree-props="{ children: 'children', hasChildren: 'hasChildren' }">
				<el-table-column prop="menuName" label="名称" show-overflow-tooltip></el-table-column>
				<el-table-column prop="menuUrl" label="跳转地址" show-overflow-tooltip></el-table-column>
				<el-table-column label="显示范围" width="220">
					<template #default="scope">
						<el-tag v-if="scope.row.templateId" type="warning" size="small">专属：{{ templateNameOf(scope.row.templateId) }}</el-tag>
						<template v-else>
							<el-tag type="success" size="small">全局</el-tag>
							<el-tag v-if="countCsv(scope.row.excludeTemplateIds) > 0" type="info" size="small" style="margin-left: 4px">
								排除{{ countCsv(scope.row.excludeTemplateIds) }}个模板
							</el-tag>
							<el-tag v-if="countCsv(scope.row.excludeSiteKeys) > 0" type="danger" size="small" style="margin-left: 4px">
								排除{{ countCsv(scope.row.excludeSiteKeys) }}个站点
							</el-tag>
						</template>
					</template>
				</el-table-column>
				<el-table-column prop="sortNum" label="排序" show-overflow-tooltip></el-table-column>
				<el-table-column prop="target" label="打开方式" show-overflow-tooltip></el-table-column>
				<el-table-column label="操作" show-overflow-tooltip width="160">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onOpenAddMenu(scope.row)">新增</el-button>
						<el-button size="small" text type="primary" @click="onOpenEditMenu(scope.row)">修改</el-button>
						<el-button size="small" text type="primary" @click="onTabelRowDel(scope.row)">删除</el-button>
					</template>
				</el-table-column>
			</el-table>
		</el-card>
		<AddMenu ref="addMenuRef" @reloadTable="loadMenuList"/>
	</div>
</template>

<script lang="ts" name="templateMenu" setup>
import { ref, reactive, computed, onMounted } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { TemplateApi } from '/@/api/template/index';
import AddMenu from '/@/views/template/addMenu.vue';

const templateApi = TemplateApi();
const addMenuRef = ref();
const state = reactive({
	menuData: null,
	// 已安装模板列表（显示范围列展示模板名）
	templateList: [] as Array<any>,
});
// 获取 vuex 中的路由
const menuTableData = computed(() => {
	return state.menuData;
});
// 模板ID → 模板名（找不到时回退显示 ID）
const templateNameOf = (templateId: string): string => {
	const template = state.templateList.find((item: any) => item.id === templateId);
	return template ? template.name : templateId;
};
// 逗号分隔字符串的条目数
const countCsv = (csv: string | null | undefined): number => {
	return (csv || '').split(',').filter((item: string) => item.trim().length > 0).length;
};
// 打开新增菜单弹窗
const onOpenAddMenu = (row: object) => {
	addMenuRef.value.openDialog("add", row);
};
// 打开编辑菜单弹窗
const onOpenEditMenu = (row: object) => {
	addMenuRef.value.openDialog("edit", row);
};
// 删除当前行
const onTabelRowDel = (row: any) => {
	ElMessageBox.confirm('此操作将永久删除菜单, 是否继续?', '提示', {
		confirmButtonText: '删除',
		cancelButtonText: '取消',
		type: 'warning',
	}).then(() => {
		templateApi.delTemplateMenu(row.id).then(() => {
			ElMessage.success("删除成功");
			loadMenuList();
		}).catch((res) => {
			ElMessage.error(res.message);
		});
	}).catch(()=> {})
};

const loadMenuList = () => {
	templateApi.getTemplateMenuList().then((res) => {
		state.menuData = res.data;
	}).catch(() => {
	})
}

onMounted(() => {
	loadMenuList();
	templateApi.getTemplateList().then((res: any) => {
		state.templateList = res.data || [];
	}).catch(() => {});
});

</script>
