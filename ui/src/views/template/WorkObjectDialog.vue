<template>
    <!-- 统一工作对象选择器对话框：正式模板 / 未应用草稿 / 已应用回看 三页签。
         数据全部由父组件 props 注入（模板列表 + 全部会话，单一数据源），本组件不自拉；
         对象切换经 emit('select') 交父组件唯一写入口 selectWorkObject 处理 -->
    <el-dialog v-model="visible" title="选择工作对象" width="720px" :close-on-click-modal="false">
        <!-- 顶部：关键字过滤 + 新建模板入口（打开父组件既有的"新建模板"独立表单） -->
        <div class="wobj-toolbar">
            <el-input v-model="keyword" size="default" clearable placeholder="按名称 / 需求描述过滤"
                      style="width: 260px">
                <template #prefix>
                    <el-icon><ele-Search /></el-icon>
                </template>
            </el-input>
            <el-button text type="primary" @click="onCreate">
                <el-icon><ele-MagicStick /></el-icon>新建模板
            </el-button>
        </div>
        <el-tabs v-model="activeTab">
            <el-tab-pane name="tpl">
                <template #label>
                    正式模板<el-badge v-if="filteredTemplates.length" :value="filteredTemplates.length"
                                    type="primary" class="wobj-tab-badge" />
                </template>
                <div class="tab-hint">{{ TAB_HINTS.tpl }}</div>
                <el-table :data="filteredTemplates" stripe size="small" max-height="380" highlight-current-row
                          class="wobj-table" :row-class-name="tplRowClass" @row-click="onTplRowClick">
                    <el-table-column prop="name" label="模板" min-width="220" show-overflow-tooltip>
                        <template #default="scope">
                            <span>{{ scope.row.name }}</span>
                            <el-tag v-if="scope.row.active" size="small" type="success" style="margin-left: 6px">使用中</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="160">
                        <template #default="scope">
                            <el-button size="small" text type="primary" @click.stop="onTplSelect(scope.row, 'edit')">手动编辑</el-button>
                            <el-button size="small" text type="primary" @click.stop="onTplSelect(scope.row, 'ai')">AI 调整</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-empty v-if="filteredTemplates.length === 0" description="暂无正式模板" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane name="pending">
                <template #label>
                    未应用<el-badge v-if="pendingSessions.length" :value="pendingSessions.length"
                                  type="warning" class="wobj-tab-badge" />
                </template>
                <div class="tab-hint">{{ TAB_HINTS.pending }}</div>
                <el-table :data="pendingSessions" stripe size="small" max-height="380" highlight-current-row
                          class="wobj-table" :row-class-name="sessionRowClass" @row-click="onSessionRowClick">
                    <el-table-column prop="templateName" label="模板目录" min-width="110" show-overflow-tooltip />
                    <el-table-column prop="requirement" label="需求描述" min-width="180" show-overflow-tooltip />
                    <el-table-column label="状态" width="86">
                        <template #default="scope">
                            <el-tag size="small" :type="sessionBadge(scope.row).type">{{ sessionBadge(scope.row).text }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="创建时间" width="110">
                        <template #default="scope">{{ formatTime(scope.row.created) }}</template>
                    </el-table-column>
                    <el-table-column label="操作" width="120">
                        <template #default="scope">
                            <el-button size="small" text type="primary" @click.stop="onSessionRowClick(scope.row)">打开续聊</el-button>
                            <el-button size="small" text type="success" @click.stop="onApply(scope.row)">应用</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-empty v-if="pendingSessions.length === 0" description="暂无未应用的生成会话" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane name="applied">
                <template #label>
                    已应用<el-badge v-if="appliedSessions.length" :value="appliedSessions.length"
                                  type="success" class="wobj-tab-badge" />
                </template>
                <div class="tab-hint">{{ TAB_HINTS.applied }}</div>
                <el-table :data="appliedSessions" stripe size="small" max-height="380" highlight-current-row
                          class="wobj-table" :row-class-name="sessionRowClass" @row-click="onSessionRowClick">
                    <el-table-column prop="templateName" label="模板目录" min-width="110" show-overflow-tooltip />
                    <el-table-column prop="requirement" label="需求描述" min-width="180" show-overflow-tooltip />
                    <el-table-column label="状态" width="86">
                        <template #default="scope">
                            <el-tag size="small" :type="sessionBadge(scope.row).type">{{ sessionBadge(scope.row).text }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="创建时间" width="110">
                        <template #default="scope">{{ formatTime(scope.row.created) }}</template>
                    </el-table-column>
                    <el-table-column label="操作" width="130">
                        <template #default="scope">
                            <el-button size="small" text type="primary" @click.stop="onSessionRowClick(scope.row)">回看会话</el-button>
                            <el-button size="small" text type="success" @click.stop="onGotoTemplate(scope.row)">去正式模板</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-empty v-if="appliedSessions.length === 0" description="暂无已应用的生成会话" :image-size="60" />
            </el-tab-pane>
        </el-tabs>
        <div class="wobj-tip">点击行即选中工作对象（停留当前页签）；行内按钮可指定进入手动编辑 / AI 调整</div>
        <template #footer>
            <el-button @click="visible = false">关 闭</el-button>
        </template>
    </el-dialog>
</template>

<script lang="ts" name="workObjectDialog" setup>
import { computed, ref, watch } from 'vue';

const props = defineProps<{
    /** 对话框显示状态（v-model:visible） */
    visible: boolean;
    /** 正式模板列表（父组件 state.templateList） */
    templateList?: any[];
    /** 全部会话（父组件 state.allSessions，单一数据源；本组件只读不自拉） */
    sessions?: any[];
    /** 会话期瞬时态徽章上下文（AI 工作台快照）：runningId=正在生成，failedId=本轮失败 */
    badgeCtx?: { runningId?: string; failedId?: string };
    /** 当前工作对象（父组件 currentWorkObject）：决定默认页签与当前行高亮 */
    current?: { kind: 'template' | 'session'; templateId?: string; session?: any };
}>();

const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    /** 行点击/行按钮选择工作对象：obj={kind:'template',templateId} 或 {kind:'session',session}；view=落点视图（仅模板对象有意义） */
    (e: 'select', payload: { obj: any; view?: 'edit' | 'ai' }): void;
    /** 新建模板：父组件关闭本对话框并打开既有"新建模板"独立表单 */
    (e: 'create'): void;
    /** 未应用行「应用」：父组件先打开该会话，再走工作台既有应用链路 */
    (e: 'apply', session: any): void;
    /** 已应用行「去正式模板」：父组件按 appliedTemplateId 指针（回退按目录名）定位正式模板 */
    (e: 'goto-template', session: any): void;
}>();

const visible = computed({
    get: () => props.visible,
    set: (v: boolean) => emit('update:visible', v)
});

// 当前页签（tpl/pending/applied）与关键字过滤
const activeTab = ref<'tpl' | 'pending' | 'applied'>('tpl');
const keyword = ref('');

// 页签提示（口径对齐交互原型 TAB_META：选择器 = 对象级入口，生成会话切换集中于此）
const TAB_HINTS: Record<string, string> = {
    tpl: '选择正式模板进入调整 / 编辑；「未应用」页签的草稿应用后会出现在这里',
    pending: '生成中的草稿：可继续打磨、重新生成或应用；应用后转入正式模板目录',
    applied: '已应用记录仅作回看：模板已转为正式模板目录，后续调整请在「正式模板」页签进入'
};

// 生成型会话（未绑定 templateId）：按落库 status 分流为未应用 / 已应用
const generateSessions = computed(() => (props.sessions || []).filter((s: any) => !s.templateId));
const pendingSessions = computed(() => filterByKeyword(generateSessions.value.filter((s: any) => s.status !== 'applied')));
const appliedSessions = computed(() => filterByKeyword(generateSessions.value.filter((s: any) => s.status === 'applied')));
const filteredTemplates = computed(() => {
    const kw = keyword.value.trim().toLowerCase();
    const list = props.templateList || [];
    return kw ? list.filter((t: any) => String(t.name || '').toLowerCase().includes(kw)) : list;
});
const filterByKeyword = (list: any[]) => {
    const kw = keyword.value.trim().toLowerCase();
    if (!kw) return list;
    return list.filter((s: any) =>
        String(s.templateName || '').toLowerCase().includes(kw) ||
        String(s.requirement || '').toLowerCase().includes(kw));
};

/**
 * 状态徽章四态：生成中（蓝）/ 生成失败（红）为会话期瞬时态（badgeCtx 覆盖，不落库）；
 * 已应用（绿）/ 进行中（黄）按落库 status 映射（文案对齐交互原型，"待应用"统一为"进行中"）
 */
const sessionBadge = (row: any): { text: string; type: 'primary' | 'danger' | 'warning' | 'success' } => {
    if (row.sessionId && row.sessionId === props.badgeCtx?.runningId) return { text: '生成中', type: 'primary' };
    if (row.sessionId && row.sessionId === props.badgeCtx?.failedId) return { text: '生成失败', type: 'danger' };
    if (row.status === 'applied') return { text: '已应用', type: 'success' };
    return { text: '进行中', type: 'warning' };
};

/** 创建时间格式化（月-日 时:分） */
const formatTime = (created: any) => {
    if (!created) return '';
    const d = new Date(created);
    if (isNaN(d.getTime())) return '';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/** 当前行高亮（当前工作对象所在行）：正式模板按 templateId、会话按 sessionId 比对 */
const tplRowClass = ({ row }: any) =>
    props.current?.kind === 'template' && props.current.templateId === row.id ? 'is-current-obj' : '';
const sessionRowClass = ({ row }: any) =>
    props.current?.kind === 'session' && props.current.session?.sessionId === row.sessionId ? 'is-current-obj' : '';

// 打开时：默认页签跟随当前工作对象（会话 → 对应状态页签；模板 → 正式模板页签），并重置过滤
watch(() => props.visible, (v) => {
    if (!v) return;
    keyword.value = '';
    if (props.current?.kind === 'session') {
        activeTab.value = props.current.session?.status === 'applied' ? 'applied' : 'pending';
    } else {
        activeTab.value = 'tpl';
    }
});

/** 正式模板行点击：中性选择（不指定落点视图，停留父组件当前 tab——避免"选模板被强制跳 AI"） */
const onTplRowClick = (row: any) => {
    emit('select', { obj: { kind: 'template', templateId: String(row.id) } });
};
const onTplSelect = (row: any, view: 'edit' | 'ai') => {
    emit('select', { obj: { kind: 'template', templateId: String(row.id) }, view });
};

/** 会话行点击（未应用续聊 / 已应用只读回看）：会话只能在 AI 工作台打开 */
const onSessionRowClick = (row: any) => {
    emit('select', { obj: { kind: 'session', session: row } });
};

/** 新建模板：父组件负责关本框并打开既有独立表单（不内嵌本对话框） */
const onCreate = () => emit('create');

/** 未应用行「应用」 */
const onApply = (row: any) => emit('apply', row);

/** 已应用行「去正式模板」 */
const onGotoTemplate = (row: any) => emit('goto-template', row);
</script>

<style lang="scss" scoped>
.wobj-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
}

.tab-hint {
    margin-bottom: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
}

// 可点击行（el-dialog teleport 到 body，插槽内容仍带组件 scoped 属性，可正常匹配）
.wobj-table {
    cursor: pointer;

    // 当前工作对象所在行标记（比 highlight-current-row 更持久：未被点击覆盖）
    :deep(.is-current-obj) {
        --el-table-tr-bg-color: var(--el-color-primary-light-9);
    }
}

.wobj-tip {
    margin-top: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
}

// 页签 label 内 badge：与文字垂直居中、间距收紧
.wobj-tab-badge {
    margin-left: 6px;
    vertical-align: 2px;

    :deep(.el-badge__content) {
        position: relative;
        transform: none;
    }
}
</style>
