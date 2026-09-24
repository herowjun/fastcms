<template>
    <!-- 左侧常驻文件树面板（独立于工作对象选择弹窗）：
         展示当前工作对象的文件树（正式模板目录 / 会话工作目录，随对象切换）。
         树实例由父组件 useTemplateFileTree 持有经 props 注入（AI 工作台 / 手动编辑两 tab 共享）；
         点选经 emit('node-click') 上抛，父组件按右侧当前 tab 分发行为 -->
    <div class="ftp-panel">
        <el-card shadow="hover" class="tree-card">
            <template #header>
                <div class="tree-card-header">
                    <span :title="treeTitleTip">{{ isSessionObject ? '会话工作目录' : '模板文件树' }}</span>
                    <el-button size="small" text :loading="tree.loading" title="刷新文件树" @click="emit('refresh-tree')">
                        <el-icon><ele-Refresh /></el-icon>
                    </el-button>
                </div>
            </template>
            <div class="tree-filter">
                <el-input v-model="treeFilter" size="small" clearable placeholder="输入关键字过滤文件">
                    <template #prefix>
                        <el-icon><ele-Search /></el-icon>
                    </template>
                </el-input>
            </div>
            <div v-loading="tree.loading" class="tree-body">
                <el-tree ref="treeRef" :data="treeData"
                    :default-expand-all="false"
                    :default-expanded-keys="expandedKeys"
                    highlight-current
                    node-key="filePath"
                    :props="tree.defaultProps"
                    :filter-node-method="filterTreeNode"
                    @node-click="onTreeNodeClick"
                    style="height: 100%;overflow: auto;">
                </el-tree>
            </div>
        </el-card>
    </div>
</template>

<script lang="ts" name="fileTreePanel" setup>
import { computed, nextTick, ref, watch } from 'vue';

const props = defineProps<{
    /** 当前工作对象（父组件 currentObject）：决定文件树数据源与标题 */
    current?: { kind: 'template' | 'session'; templateId?: string; session?: any };
    /** 正式模板列表（标题悬浮提示按 templateId 找模板名） */
    templateList?: any[];
    /** 共享文件树实例（父组件 useTemplateFileTree 的 tree 对象：data=正式模板目录，sessionData=会话工作目录） */
    tree: { loading: boolean; data: any[]; sessionData: any[]; expandedKeys: string[]; defaultProps: any };
}>();

const emit = defineEmits<{
    /** 文件树点选：父组件按右侧当前 tab 分发（AI=聚焦对话 / 手动编辑=打开编辑器） */
    (e: 'node-click', node: any): void;
    /** 文件树刷新按钮 */
    (e: 'refresh-tree'): void;
}>();

/** 当前对象是否为生成会话（决定文件树数据源与标题） */
const isSessionObject = computed(() => props.current?.kind === 'session');

/** 树标题悬浮提示：正式模板名 / 会话需求描述 */
const treeTitleTip = computed(() => {
    if (isSessionObject.value) {
        const s = props.current?.session || {};
        return `会话工作目录：${s.templateName || ''}${s.requirement ? ' · ' + s.requirement : ''}`;
    }
    const tpl = (props.templateList || []).find((t: any) => String(t.id) === String(props.current?.templateId));
    return tpl ? `正式模板：${tpl.name}` : '正式模板';
});

/** 树数据源：会话对象 = 会话工作目录树，正式模板 = 正式模板目录树 */
const treeData = computed<any[]>(() => (isSessionObject.value ? props.tree.sessionData : props.tree.data) as any[]);

// 本地展开键：树数据替换（切对象/刷新）后默认展开第一层
const expandedKeys = ref<string[]>([]);
watch(treeData, (nodes) => {
    expandedKeys.value = (nodes || []).map((n: any) => n.filePath);
}, { immediate: true });

// 文件树关键字过滤：按文件/目录名模糊匹配（不区分大小写）
const treeFilter = ref('');
const filterTreeNode = (value: string, data: any) => {
    if (!value) return true;
    return String(data.label || '').toLowerCase().includes(value.toLowerCase());
};
watch(treeFilter, (val) => {
    treeRef.value?.filter(val);
});

const treeRef = ref();

/** 文件树点选：原样上抛节点，父组件按右侧当前 tab 分发行为 */
const onTreeNodeClick = (node: any) => {
    emit('node-click', node);
};

/**
 * 树高亮控制（供父组件驱动）：
 * - 打开/删除文件后设置或清除高亮（highlight-current 残留清理）
 * - AI 写盘（SSE file 事件）时把正在写的文件在树中选中高亮
 */
const setTreeCurrentKey = (path: string | null) => {
    nextTick(() => treeRef.value?.setCurrentKey(path));
};

defineExpose({ setTreeCurrentKey });
</script>

<style lang="scss" scoped>
// 左侧文件树面板：撑满左列高度
.ftp-panel {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
}

// 文件树卡片（flex 让树占满 header 之外的剩余空间）
.tree-card {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;

    :deep(.el-card__body) {
        flex: 1;
        min-height: 0;
        overflow: hidden;
        display: flex;
        flex-direction: column;
    }

    .tree-body {
        flex: 1;
        min-height: 120px;
        overflow: hidden;
    }
}

.tree-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;

    // 标题过长截断（面板窄，模板/会话名可能超长）
    span {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
}

.tree-filter {
    padding: 8px 10px 4px;
}
</style>
