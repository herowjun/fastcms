<template>
	<el-menu
		router
		:default-active="state.defaultActive"
		background-color="transparent"
		:collapse="state.isCollapse"
		:unique-opened="getThemeConfig.isUniqueOpened"
		:collapse-transition="false"
	>
		<template v-for="(group, gIndex) in groupedMenus" :key="gIndex">
			<!-- 深空蓝：分组小标题（折叠态隐藏） -->
			<div v-if="group.title && !state.isCollapse" class="menu-group-title">{{ group.title }}</div>
			<template v-for="val in group.items">
				<el-sub-menu :index="val.path" v-if="val.children && val.children.length > 0" :key="val.path">
					<template #title>
						<SvgIcon :name="val.meta.icon" />
						<span>{{ $t(val.meta.title) }}</span>
					</template>
					<SubItem :chil="val.children" />
				</el-sub-menu>
				<template v-else>
					<el-menu-item :index="val.path" :key="val.path">
						<SvgIcon :name="val.meta.icon" />
						<template #title v-if="!val.meta.isLink || (val.meta.isLink && val.meta.isIframe)">
							<span>{{ $t(val.meta.title) }}</span>
						</template>
						<template #title v-else>
							<a class="w100" @click.prevent="onALinkClick(val)">{{ $t(val.meta.title) }}</a>
						</template>
					</el-menu-item>
				</template>
			</template>
		</template>
	</el-menu>
</template>

<script setup lang="ts" name="navMenuVertical">
import { defineAsyncComponent, reactive, computed, onMounted, watch } from 'vue';
import { useRoute, onBeforeRouteUpdate, RouteRecordRaw } from 'vue-router';
import { storeToRefs } from 'pinia';
import { useThemeConfig } from '/@/stores/themeConfig';
import other from '/@/utils/other';

// 引入组件
const SubItem = defineAsyncComponent(() => import('/@/layout/navMenu/subItem.vue'));

// 定义父组件传过来的值
const props = defineProps({
	// 菜单列表
	menuList: {
		type: Array<RouteRecordRaw>,
		default: () => [],
	},
});

// 定义变量内容
const storesThemeConfig = useThemeConfig();
const { themeConfig } = storeToRefs(storesThemeConfig);
const route = useRoute();
const state = reactive({
	// 修复：https://gitee.com/lyt-top/vue-next-admin/issues/I3YX6G
	defaultActive: route.meta.isDynamic ? route.meta.isDynamicPath : route.path,
	isCollapse: false,
});

// 获取父级菜单数据
const menuLists = computed(() => {
	return <RouteItems>props.menuList;
});
// 深空蓝：菜单按域分组（前端展示层分组，不改路由；未命中分组的路径按原顺序保留）
const menuGroupMap: Record<string, string> = {
	// 内容管理
	'/article': '内容管理',
	'/page': '内容管理',
	'/attach': '内容管理',
	'/order': '内容管理',
	// 模板与 AI
	'/template': '模板与 AI',
	// 站点设置
	'/plugin': '站点设置',
	'/user': '站点设置',
	'/setting': '站点设置',
	// 系统
	'/system': '系统',
};
const groupedMenus = computed(() => {
	const groups: { title: string; items: RouteItems }[] = [];
	const pushToGroup = (title: string, item: RouteItems) => {
		let group = title ? groups.find((g) => g.title === title) : groups.find((g) => !g.title);
		if (!group) {
			group = { title, items: [] };
			groups.push(group);
		}
		group.items.push(item);
	};
	menuLists.value.forEach((item) => {
		pushToGroup(menuGroupMap[item.path] || '', item);
	});
	return groups;
});
// 获取布局配置信息
const getThemeConfig = computed(() => {
	return themeConfig.value;
});
// 菜单高亮（详情时，父级高亮）
const setParentHighlight = (currentRoute: RouteToFrom) => {
	const { path, meta } = currentRoute;
	const pathSplit = meta?.isDynamic ? meta.isDynamicPath!.split('/') : path!.split('/');
	if (pathSplit.length >= 4 && meta?.isHide) return pathSplit.splice(0, 3).join('/');
	else return path;
};
// 打开外部链接
const onALinkClick = (val: RouteItem) => {
	other.handleOpenLink(val);
};
// 页面加载时
onMounted(() => {
	state.defaultActive = setParentHighlight(route);
});
// 路由更新时
onBeforeRouteUpdate((to) => {
	// 修复：https://gitee.com/lyt-top/vue-next-admin/issues/I3YX6G
	state.defaultActive = setParentHighlight(to);
	const clientWidth = document.body.clientWidth;
	if (clientWidth < 1000) themeConfig.value.isCollapse = false;
});
// 设置菜单的收起/展开
watch(
	() => themeConfig.value.isCollapse,
	(isCollapse) => {
		document.body.clientWidth <= 1000 ? (state.isCollapse = false) : (state.isCollapse = isCollapse);
	},
	{
		immediate: true,
	}
);
</script>

<style scoped lang="scss">
// 深空蓝：菜单分组小标题（原型 side-group：11px/600/#475569/letter-spacing .1em）
.menu-group-title {
	padding: 14px 20px 6px;
	font-size: 11px;
	font-weight: 600;
	letter-spacing: 0.1em;
	color: #475569;
	user-select: none;
}
</style>
