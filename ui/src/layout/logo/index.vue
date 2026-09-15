<template>
	<div class="layout-logo" v-if="setShowLogo" @click="onThemeConfigChange">
		<div class="logo-mark">{{ logoInitial }}</div>
		<span>{{ themeConfig.globalTitle }}</span>
	</div>
	<div class="layout-logo-size" v-else @click="onThemeConfigChange">
		<div class="logo-mark">{{ logoInitial }}</div>
	</div>
</template>

<script setup lang="ts" name="layoutLogo">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';
import { useThemeConfig } from '/@/stores/themeConfig';

// 定义变量内容
const storesThemeConfig = useThemeConfig();
const { themeConfig } = storeToRefs(storesThemeConfig);

// 原型 side-logo：渐变方块取标题首字符（品牌标识，替代位图 logo）
const logoInitial = computed(() => {
	const title = (themeConfig.value.globalTitle || 'F').trim();
	return title.charAt(0).toUpperCase();
});

// 设置 logo 的显示。classic 经典布局默认显示 logo
const setShowLogo = computed(() => {
	let { isCollapse, layout } = themeConfig.value;
	return !isCollapse || layout === 'classic' || document.body.clientWidth < 1000;
});
// logo 点击实现菜单展开/收起
const onThemeConfigChange = () => {
	if (themeConfig.value.layout === 'transverse') return false;
	themeConfig.value.isCollapse = !themeConfig.value.isCollapse;
};
</script>

<style scoped lang="scss">
// 原型 side-logo：56px 高、左对齐、底部 1px 分隔线（深色侧栏场景）
.layout-logo {
	width: 220px;
	height: 56px;
	flex: none;
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 0 18px;
	border-bottom: 1px solid rgba(148, 163, 184, 0.1);
	color: #fff;
	font-size: 15.5px;
	font-weight: 700;
	letter-spacing: 0.02em;
	white-space: nowrap;
	cursor: pointer;
	span {
		display: inline-block;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
}
// 折叠态：仅渐变方块居中
.layout-logo-size {
	width: 100%;
	height: 56px;
	flex: none;
	display: flex;
	align-items: center;
	justify-content: center;
	border-bottom: 1px solid rgba(148, 163, 184, 0.1);
	cursor: pointer;
}
// 原型 logo-mark：30px 渐变圆角方块
.logo-mark {
	flex: none;
	width: 30px;
	height: 30px;
	border-radius: 8px;
	background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
	display: grid;
	place-items: center;
	font-weight: 800;
	font-size: 13px;
	color: #fff;
}
</style>
