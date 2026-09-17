<template>
    <!-- AI 工作台视图：与手动编辑同构的三列布局（树 | AI 对话 | 内联预览）。
         会话编排自 edit.vue 迁入：本组件持有 AI 会话状态与预览点选钩子，
         模板作用对象（scope）经 props.templateId 与父组件双向同步（scope-change） -->
    <div class="ai-workbench" :style="{ height: height }">
        <!-- 工具条：新建/历史/回滚/应用 + 预览指向提示 -->
        <div class="wb-toolbar">
            <el-button type="warning" plain @click="openCreateDialog">
                <el-icon><ele-MagicStick /></el-icon>新建模板
            </el-button>
            <!-- 历史生成模板清单：未应用=活跃工作集（续聊/应用），已应用=回看后可「编辑此模板」 -->
            <el-dropdown trigger="click" @command="(id: string) => onSelectAiSession(id)">
                <el-button>
                    <el-icon><ele-FolderOpened /></el-icon>生成模板
                    <el-icon class="el-icon--right"><ele-ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                    <el-dropdown-menu>
                        <el-dropdown-item v-if="!pendingTemplates.length && !appliedTemplates.length" disabled>
                            暂无生成模板
                        </el-dropdown-item>
                        <template v-if="pendingTemplates.length">
                            <el-dropdown-item disabled class="dd-group">未应用模板（{{ pendingTemplates.length }}）</el-dropdown-item>
                            <el-dropdown-item v-for="s in pendingTemplates" :key="s.sessionId" :command="s.sessionId"
                                              :title="s.requirement">
                                <el-tag size="small" type="warning" style="margin-right: 6px">待应用</el-tag>
                                {{ s.templateName || s.title }} · {{ fmtTime(s.created) }}
                            </el-dropdown-item>
                        </template>
                        <template v-if="appliedTemplates.length">
                            <el-dropdown-item disabled class="dd-group">已应用模板（{{ appliedTemplates.length }}）</el-dropdown-item>
                            <el-dropdown-item v-for="s in appliedTemplates" :key="s.sessionId" :command="s.sessionId"
                                              :title="s.requirement">
                                <el-tag size="small" type="success" style="margin-right: 6px">已应用</el-tag>
                                {{ s.templateName || s.title }} · {{ fmtTime(s.created) }}
                            </el-dropdown-item>
                        </template>
                    </el-dropdown-menu>
                </template>
            </el-dropdown>
            <el-button v-if="canRollback" type="danger" plain :loading="rollingBack" @click="onRollback">
                <el-icon><ele-RefreshLeft /></el-icon>回滚最近
            </el-button>
            <el-button v-if="showApply" type="success" :loading="applying" @click="onApplyTemplate">
                <el-icon><ele-Check /></el-icon>应用模板
            </el-button>
            <el-button v-if="showEditApplied" type="primary" plain @click="onEditApplied"
                       title="把应用后的正式模板加载到主编辑视图继续修改">
                <el-icon><ele-EditPen /></el-icon>编辑此模板
            </el-button>
            <span class="prev-hint">预览指向：{{ prevHint }}</span>
        </div>

        <!-- 三列骨架：左文件树（作用对象+聚焦导航）| 中 AI 对话（可收起）| 右内联预览 -->
        <div class="wb-columns">
            <div class="wb-col-tree">
                <el-select :model-value="templateId" size="small" filterable placeholder="作用模板"
                           class="scope-select" title="切换 AI 调整的作用模板（主编辑视图同步切换）"
                           @change="(v: string) => emit('scope-change', v)">
                    <el-option v-for="item in templateList" :key="item.id" :value="item.id"
                               :label="item.name + (item.active ? '（使用中）' : '')" />
                </el-select>
                <el-input v-model="treeFilter" size="small" clearable placeholder="搜索文件" class="tree-filter" />
                <el-card shadow="hover" class="tree-card">
                    <template #header>
                        <div class="tree-card-header">
                            <span>{{ sessionView ? '会话工作目录' : '模板文件树' }}</span>
                            <el-button size="small" text :loading="tree.loading" title="刷新文件树" @click="onRefreshTree">
                                <el-icon><ele-Refresh /></el-icon>
                            </el-button>
                        </div>
                    </template>
                    <div v-loading="tree.loading" class="tree-body">
                        <!-- 文件树不渲染代码内容：点文件 = 告诉 AI 聚焦该文件（经 current-file 注入对话），
                             选中可路由 HTML 页面时预览列联动切页 -->
                        <el-tree ref="wbTreeRef" :data="currentTreeData" node-key="filePath" highlight-current
                                 :default-expanded-keys="tree.expandedKeys" :props="tree.defaultProps"
                                 :filter-node-method="filterTreeNode"
                                 @node-click="onTreeNodeClick" />
                    </div>
                </el-card>
            </div>

            <div class="wb-col-chat" :class="{ collapsed: chatCollapsed }">
                <!-- 收缩/展开切换按钮条（与手动编辑中列收起同一套交互） -->
                <div class="chat-collapse-bar">
                    <el-button size="small" text :title="chatCollapsed ? '展开 AI 对话' : '收起 AI 对话'"
                               @click="chatCollapsed = !chatCollapsed">
                        <el-icon :size="16">
                            <ele-Fold v-if="!chatCollapsed" />
                            <ele-Expand v-else />
                        </el-icon>
                    </el-button>
                    <span v-if="chatCollapsed" class="collapsed-label">AI</span>
                </div>
                <div class="wb-col-chat-inner" v-show="!chatCollapsed">
                    <!-- 选区锁定标签：AI 对话聚焦目标（选区模式点选区块后出现） -->
                    <div v-if="selectedSection" class="focus-section-bar">
                        <el-tag size="small" type="success" closable @close="clearSelectedSection">
                            已选中区块：{{ selectedSection.sectionId }}<template v-if="selectedSection.elementHint"> · {{ selectedSection.elementHint }}</template>
                        </el-tag>
                        <span class="focus-section-tip">本轮对话聚焦该区块</span>
                    </div>
                    <ai-chat ref="aiChatRef" :session="currentSession" :mode="aiMode"
                             :current-file="aiMode === 'adjust' ? selectedFile : ''"
                             :focus-section="selectedSection?.sectionId || ''"
                             :focus-element-hint="selectedSection?.elementHint || ''"
                             :sessions="dropdownSessions" :creating-session="creatingAiSession"
                             :image-pick-mode="pickMode" :section-select-mode="sectionMode"
                             @select-session="onSelectAiSession" @new-session="onNewAiSession"
                             @files-changed="onAiFilesChanged" @file-written="onAiFileWritten"
                             @switch-file="onAiSwitchFile"
                             @edit-file="onEditSessionFile"
                             @toggle-image-pick="toggleImagePickMode" @toggle-section-select="toggleSectionSelectMode" />
                </div>
            </div>

            <div class="wb-col-preview">
                <TemplatePreviewPanel ref="previewPanelRef" v-model:entry="preview.entry"
                                      :page-options="previewPageOptions" :url="aiPreviewUrl"
                                      :empty-tip="previewEmptyTip" v-model:viewport="viewport"
                                      @refresh="refreshAiPreview" @frame-load="onPreviewFrameLoad" />
            </div>
        </div>

        <!-- 预览页点选换图对话框（搜附件库 / AI 生成 / 上传，选定后更新图片槽位） -->
        <ImagePickDialog ref="imagePickDialogRef" v-model:visible="pickDialogVisible"
                         :session-id="currentSession?.sessionId || ''" @applied="onPickApplied" />

        <!-- AI 新建模板对话框（生成完整模板的唯一入口） -->
        <CreateTemplateDialog ref="createDialogRef" v-model:visible="createDialogVisible"
                              @created="onCreateDialogCreated" />
    </div>
</template>

<script lang="ts" name="aiWorkbench" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { AiTemplateApi } from '/@/api/ai/index';
import AiChat from '/@/views/template/aiChat.vue';
import ImagePickDialog from '/@/views/template/ImagePickDialog.vue';
import CreateTemplateDialog from '/@/views/template/CreateTemplateDialog.vue';
import TemplatePreviewPanel from '/@/views/template/TemplatePreviewPanel.vue';
import { useTemplateFileTree } from '/@/views/template/composables/useTemplateFileTree';
import { useAiPreview, isRoutableHtml } from '/@/views/template/composables/useAiPreview';
import { usePreviewIframeHooks } from '/@/views/template/composables/usePreviewIframeHooks';

const props = defineProps<{
    /** 作用模板 ID（与主编辑视图同步：scope-change 通知父组件切换，父组件切换后回流） */
    templateId: string;
    /** 模板列表（作用模板下拉数据源，与主编辑视图共用） */
    templateList?: any[];
    /** 工作区高度（与手动编辑视图同源注入） */
    height?: string;
    /** 视图是否激活（首次激活时初始化调整会话，避免页面加载就产生会话副作用） */
    active?: boolean;
}>();

const emit = defineEmits<{
    /** 用户切换作用模板 → 父组件按未保存修改流程切换主编辑视图的模板 */
    (e: 'scope-change', templateId: string): void;
    /** AI 直写正式模板目录（调整会话）→ 父组件刷新主编辑文件树与当前编辑文件 */
    (e: 'files-changed'): void;
    /** 「编辑文件」桥：切到手动编辑视图并打开该文件 */
    (e: 'edit-file', filePath: string): void;
    /** 生成型会话应用成功（templateId：应用后的正式模板 ID，父组件加载并提示跳转） */
    (e: 'applied', templateId?: string): void;
    /** 「编辑此模板」桥（已应用回看态）：父组件把该正式模板加载到主编辑视图并切手动编辑 */
    (e: 'edit-applied', templateId: string): void;
}>();

const aiApi = AiTemplateApi();

// ==================== 会话状态（自 edit.vue 迁入） ====================

const aiChatRef = ref();
const wbTreeRef = ref();
const previewPanelRef = ref();

// 换图对话框（v-model:visible）
const pickDialogVisible = ref(false);
const imagePickDialogRef = ref();
// AI 新建模板对话框
const createDialogVisible = ref(false);
const createDialogRef = ref();

// 全部会话（调整 + 生成），对话头下拉按类型分组
const allSessions = ref<any[]>([]);
const currentSession = ref<any>(null);
// 会话编辑视图：预览与文件树走会话工作目录（生成型会话应用前）
const sessionView = ref(false);
// 新建调整会话 loading
const creatingAiSession = ref(false);
// AI 对话列收起状态
const chatCollapsed = ref(false);
// 预览视口档位
const viewport = ref<'desktop' | 'tablet' | 'mobile'>('desktop');
// 文件树选中的聚焦文件（点文件 = 告诉 AI 聚焦该文件）
const selectedFile = ref('');
// 工具条回滚/应用 loading
const rollingBack = ref(false);
const applying = ref(false);

/** 模式由会话推导：绑定 templateId 即调整会话，否则生成会话 */
const aiMode = computed<'adjust' | 'generate'>(() => (currentSession.value?.templateId ? 'adjust' : 'generate'));

/** 已应用的生成型会话只读回看（禁用写会话的点选交互） */
const sessionReadonly = computed(() => sessionView.value && currentSession.value?.status === 'applied');

const canRollback = computed(() => aiMode.value === 'adjust' && !!currentSession.value?.sessionId);
const showApply = computed(() => aiMode.value === 'generate' && !!currentSession.value?.sessionId
    && currentSession.value?.status !== 'applied');
/** 已应用会话回看态：提供「编辑此模板」一键回到主编辑视图（重新编辑应用后的正式模板） */
const showEditApplied = computed(() => aiMode.value === 'generate' && currentSession.value?.status === 'applied'
    && !!currentSession.value?.templateName);

/** 历史生成模板（工具条「生成模板」下拉数据源）：未应用/已应用两组，按创建时间倒序 */
const pendingTemplates = computed(() =>
    (allSessions.value || []).filter((s: any) => !s.templateId && s.status !== 'applied')
        .sort((a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime()));
const appliedTemplates = computed(() =>
    (allSessions.value || []).filter((s: any) => !s.templateId && s.status === 'applied')
        .sort((a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime()));

/** 模板清单时间格式化（月-日 时:分） */
const fmtTime = (created: any) => {
    if (!created) return '';
    const d = new Date(created);
    if (isNaN(d.getTime())) return '';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const prevHint = computed(() => {
    if (sessionView.value && currentSession.value?.templateName) {
        return `会话工作目录 · ${currentSession.value.templateName}`;
    }
    return '正式模板目录';
});

// ==================== 文件树 / 实时预览 / 点选钩子 ====================

// 独立树实例：调整模式=作用模板目录树，会话视图=会话工作目录树
const { tree, findIndexNode, load: loadScopeTree, loadSession } = useTemplateFileTree();

const currentTreeData = computed<any[]>(() => (sessionView.value ? tree.sessionData : tree.data) as any[]);

const loadSessionFileTree = () => loadSession(currentSession.value?.sessionId);

// 文件树关键字过滤：按文件/目录名模糊匹配（与手动编辑文件树同一交互）
const treeFilter = ref('');
const filterTreeNode = (value: string, data: any) => {
    if (!value) return true;
    return String(data.label || '').toLowerCase().includes(value.toLowerCase());
};
watch(treeFilter, (val) => {
    wbTreeRef.value?.filter(val);
});

// 对话头会话下拉数据源：全部生成会话 + 当前作用模板的调整会话（其他模板的调整会话与本工作台无关）
const dropdownSessions = computed(() =>
    (allSessions.value || []).filter((s: any) => !s.templateId || s.templateId === props.templateId));

// 实时预览（入口页面/刷新键/预览地址），getter 注入树与会话上下文
const { preview, previewPageOptions, aiPreviewUrl, previewEmptyTip, initEntry: initPreviewEntry, refresh: refreshAiPreview } = useAiPreview({
    getSessionView: () => sessionView.value,
    getCurrentSession: () => currentSession.value,
    getLoadedTemplateId: () => props.templateId,
    getCurrEditFile: () => selectedFile.value,
    getTreeNodes: () => (sessionView.value ? tree.sessionData : tree.data) as any[],
    getSessionTreeNodes: () => tree.sessionData
});

// 预览点选钩子：换图/选区/链接拦截（iframe 元素经预览面板 expose 获取）
const { pickMode, sectionMode, selectedSection, toggleImagePickMode, toggleSectionSelectMode, clearSelectedSection, resetModes, onPreviewFrameLoad } = usePreviewIframeHooks({
    getFrame: () => previewPanelRef.value?.frameEl(),
    hasSession: () => !!currentSession.value?.sessionId,
    isSessionReadonly: () => sessionReadonly.value,
    isSessionView: () => sessionView.value,
    getSession: () => currentSession.value,
    getLoadedTemplateId: () => props.templateId,
    getPreviewEntry: () => preview.entry,
    setPreviewEntry: (entry: string) => { preview.entry = entry; },
    refreshPreview: () => refreshAiPreview(),
    onOpenImagePick: (sectionId: string, slot: string, rawSrc: string) => imagePickDialogRef.value?.open(sectionId, slot, rawSrc)
});

/** 换图对话框应用成功：刷新预览；槽位图重渲染了模板文件需同步刷新文件树 */
const onPickApplied = (isPreviewOnly: boolean) => {
    refreshAiPreview();
    if (!isPreviewOnly) {
        onRefreshTree();
    }
};

const onRefreshTree = () => {
    if (sessionView.value) {
        loadSessionFileTree();
    } else {
        loadScopeTree(props.templateId || undefined);
    }
};

/**
 * 文件树点选：聚焦该文件（对话头 current-file 注入），树高亮选中；
 * 选中可路由 HTML 页面时预览列联动切到该页面
 */
const onTreeNodeClick = (node: any) => {
    if (node.sortNum === 0) return;
    selectedFile.value = node.filePath;
    if (isRoutableHtml(node.filePath)) {
        preview.entry = node.filePath;
    }
};

/**
 * 树加载后默认选中入口文件（index.html）：聚焦 AI 的当前文件 + 预览指向入口页，
 * 用户进来就能看到首页效果并直接对话调整
 */
const selectDefaultEntry = () => {
    const idx = findIndexNode(currentTreeData.value || []);
    if (idx && idx.sortNum !== 0) {
        selectedFile.value = idx.filePath;
        if (isRoutableHtml(idx.filePath)) {
            preview.entry = idx.filePath;
        }
        nextTick(() => wbTreeRef.value?.setCurrentKey(idx.filePath));
    }
};

/** AI 正在写/刚写完的文件自动在树中选中高亮（树路径含模板目录前缀，按后缀匹配） */
const highlightFile = (path: string) => {
    if (!path) return;
    const nodes = currentTreeData.value || [];
    const find = (list: any[]): any => {
        for (const n of list) {
            if (n.filePath === path || String(n.filePath || '').endsWith('/' + path)) return n;
            if (n.children && n.children.length) {
                const hit = find(n.children);
                if (hit) return hit;
            }
        }
        return null;
    };
    const node = find(nodes);
    if (node && node.sortNum !== 0) {
        selectedFile.value = node.filePath;
        nextTick(() => wbTreeRef.value?.setCurrentKey(node.filePath));
    }
};

// ==================== 会话编排（自 edit.vue 迁入） ====================

/** 应用会话并重置与旧会话绑定的临时状态（选区/聚焦文件/点选模式） */
const applySession = (session: any) => {
    currentSession.value = session || null;
    selectedFile.value = '';
    wbTreeRef.value?.setCurrentKey(null);
    // 旧会话锁定的选区/点选模式对新会话无意义，一并清除
    resetModes();
};

/**
 * 确保当前作用模板存在可用的调整会话：复用最新一个，没有则新建。
 * 首次激活 AI 视图或切换作用模板时触发
 */
let ensuring = false;
const ensureAdjustSession = async () => {
    if (ensuring || !props.templateId) return;
    ensuring = true;
    sessionView.value = false;
    try {
        const res = await aiApi.listSessions();
        allSessions.value = res.data || [];
        // 按创建时间倒序（最近的在最前）
        const adjustSessions = allSessions.value
            .filter((s: any) => s.templateId === props.templateId)
            .sort((a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime());
        if (adjustSessions.length > 0) {
            applySession(adjustSessions[0]);
        } else {
            // 尚无该模板的调整型会话：创建一个（requirement 为空，后续对话即调整需求）
            const created = await aiApi.createSession({ templateId: props.templateId });
            if (!created.data) {
                ElMessage.error(created.msg || '创建调整会话失败');
                return;
            }
            allSessions.value = [created.data, ...allSessions.value];
            applySession(created.data);
        }
        initPreviewEntry();
    } catch (e: any) {
        ElMessage.error(e?.message || '加载 AI 会话失败');
    } finally {
        ensuring = false;
    }
};

/**
 * 新建会话（对话头 ＋ 按钮）：调整模式建空白调整会话；生成模式弹新建模板对话框
 */
/**
 * 新建会话（对话头 ＋ 按钮）：新建当前模板的空白调整会话并进入对话。
 * 生成完整模板的入口收敛到工具条「✦ 新建模板」，避免这里重复弹生成对话框
 */
const onNewAiSession = async () => {
    if (!props.templateId) return;
    creatingAiSession.value = true;
    try {
        const res = await aiApi.createSession({ templateId: props.templateId });
        if (!res.data) {
            ElMessage.error(res.msg || '创建会话失败');
            return;
        }
        allSessions.value = [res.data, ...allSessions.value];
        sessionView.value = false;
        applySession(res.data);
        // 从生成会话切回调整模式时树可能尚未加载/已过期，统一重载后初始化预览与默认选中
        loadScopeTree(props.templateId).then(() => {
            initPreviewEntry();
            selectDefaultEntry();
        });
    } catch (e: any) {
        ElMessage.error(e?.message || '创建会话失败');
    } finally {
        creatingAiSession.value = false;
    }
};

/**
 * 切换会话（对话头下拉）：调整会话 → 预览走正式模板；生成会话 → 进入会话视图（预览走工作目录）
 */
const onSelectAiSession = (sessionId: string) => {
    const session = allSessions.value.find((s: any) => s.sessionId === sessionId);
    if (!session) return;
    applySession(session);
    if (session.templateId) {
        sessionView.value = false;
        // 作用模板树就绪后初始化预览入口（从生成会话切回时树可能尚未加载）
        loadScopeTree(session.templateId).then(() => {
            initPreviewEntry();
            selectDefaultEntry();
        });
        return;
    }
    sessionView.value = true;
    loadSessionFileTree().then(() => {
        initPreviewEntry();
        selectDefaultEntry();
    });
};

/** 新建模板对话框创建会话成功：进入会话视图并自动发送首条消息 */
const onCreateDialogCreated = async (session: any, firstMessage: string) => {
    sessionView.value = true;
    try {
        const listRes = await aiApi.listSessions();
        allSessions.value = listRes.data || [];
    } catch (e) {
        // 会话列表刷新失败不阻断进入会话（下拉列表稍旧，下次打开会重拉）
    }
    applySession(session);
    // 初始化会话文件树与预览入口（新会话尚无文件，首个页面写盘后预览自动出现）
    loadSessionFileTree().then(() => {
        initPreviewEntry();
        selectDefaultEntry();
    });
    nextTick(() => {
        aiChatRef.value?.autoSend(firstMessage);
    });
};

/**
 * AI 写盘后联动：
 * - 会话视图：AI 改的是会话工作目录 → 刷新会话文件树（预览页面下拉随之更新）
 * - 调整模式：AI 直写正式模板目录 → 刷新本列文件树，并通知父组件刷新主编辑视图
 */
const onAiFilesChanged = () => {
    if (sessionView.value) {
        loadSessionFileTree();
        return;
    }
    loadScopeTree(props.templateId || undefined);
    emit('files-changed');
};

/**
 * AI 每写完一个文件（SSE file 事件）的实时回调：刷新预览 + 树中选中高亮。
 * 会话视图下若预览入口尚未初始化，先刷新会话文件树并初始化预览入口；
 * 若落盘的是下拉选项之外的新可路由 HTML，须重载会话文件树让选项同步长出来
 */
const onAiFileWritten = (path: string) => {
    if (sessionView.value) {
        if (!preview.entry) {
            loadSessionFileTree().then(() => initPreviewEntry());
        } else {
            // 树路径含模板目录前缀（如 xxx/index.html），与后端推送的模板内相对路径按后缀匹配
            const known = previewPageOptions.value.some((p) => p === path || p.endsWith('/' + path));
            if (isRoutableHtml(path) && !known) {
                loadSessionFileTree();
            }
        }
    }
    preview.key = Date.now();
    highlightFile(path);
};

/**
 * AI 页面自动切换（SSE switch-file 事件）：流式期间识别到 AI 正在处理的首个可路由 HTML，
 * 预览立即切到目标页面并在树中高亮
 */
const onAiSwitchFile = (path: string) => {
    if (!isRoutableHtml(path)) return;
    const target = previewPageOptions.value.find((p: string) => p === path || p.endsWith('/' + path));
    if (target && target !== preview.entry) {
        preview.entry = target;
    }
    highlightFile(path);
};

/** 「编辑文件」桥（调整会话，对话内文件行）：切到手动编辑视图打开该文件 */
const onEditSessionFile = (filePath: string) => {
    if (!filePath) return;
    emit('edit-file', filePath);
};

/** 回滚最近一轮 AI 修改（调整会话，自 aiChat 上提到工具条） */
const onRollback = () => {
    if (!currentSession.value?.sessionId) return;
    ElMessageBox.confirm(
        '将把最近一轮 AI 修改的文件恢复到该轮修改前的状态（此后各轮对这些文件的改动也会一并撤销），是否继续？',
        '回滚最近一次修改',
        { confirmButtonText: '回 滚', cancelButtonText: '取 消', type: 'warning' }
    ).then(async () => {
        rollingBack.value = true;
        try {
            const res = await aiApi.rollbackLast(currentSession.value.sessionId);
            if (res.data) {
                ElMessage.success(res.data);
                // 对话消息与会话数据重载 + 预览/文件树刷新 + 主编辑视图联动
                aiChatRef.value?.loadSessionData?.();
                refreshAiPreview();
                onRefreshTree();
                emit('files-changed');
            } else if (res.msg) {
                ElMessage.error(res.msg);
            }
        } catch (e: any) {
            ElMessage.error(e?.message || '回滚失败');
        } finally {
            rollingBack.value = false;
        }
    }).catch(() => {});
};

/** 应用模板（生成会话，自 aiChat 上提到工具条）：成功后会话转只读回看，父组件加载新模板 */
const onApplyTemplate = () => {
    if (!currentSession.value?.sessionId) return;
    ElMessageBox.confirm('确认将此模板应用到正式模板目录？应用后将切换到正式模板编辑。', '提示', {
        type: 'warning',
    }).then(async () => {
        applying.value = true;
        try {
            const res = await aiApi.applyTemplate(currentSession.value.sessionId);
            if (res.data) {
                // 后端返回 ApplyResult：{ message, templateId }（应用后的正式模板 ID）
                ElMessage.success(res.data.message || '应用成功');
                // 会话转只读回看（modePill / 输入禁用即时生效）
                currentSession.value.status = 'applied';
                emit('applied', res.data.templateId);
            } else if (res.msg) {
                ElMessage.error(res.msg);
            }
        } catch (e: any) {
            ElMessage.error(e?.message || '应用失败');
        } finally {
            applying.value = false;
        }
    }).catch(() => {});
};

/**
 * 「编辑此模板」（已应用回看态）：按会话 templateName 找到对应正式模板，
 * 通知父组件加载到主编辑视图（重新编辑应用后的模板）
 */
const onEditApplied = () => {
    const name = currentSession.value?.templateName;
    const tpl = (props.templateList || []).find((i: any) => i.name === name);
    if (!tpl) {
        ElMessage.warning('未找到对应的正式模板，请从主编辑视图的模板下拉中选择');
        return;
    }
    emit('edit-applied', String(tpl.id));
};

// ==================== 工具条对话框入口 ====================

/** 新建模板：打开新建表单 */
const openCreateDialog = () => {
    createDialogRef.value?.open();
};

// ==================== 视图激活与作用模板联动 ====================

/**
 * 视图激活 / 作用模板变化 → 确保调整会话可用。
 * 生成会话视图（含回看）不被打断：切模板只影响后续调整会话
 */
const needEnsure = () => {
    if (!props.active || !props.templateId) return false;
    if (sessionView.value) return false;
    const s = currentSession.value;
    if (!s) return true;
    if (s.templateId) return s.templateId !== props.templateId;
    return false;
};

/**
 * 进入调整上下文：先加载作用模板文件树（预览页面下拉的数据源），
 * 树就绪后确保调整会话可用或初始化预览入口（避免入口初始化时树还未就绪的竞态）
 */
const enterAdjustContext = () => {
    if (sessionView.value || !props.templateId) return;
    loadScopeTree(props.templateId).then(() => {
        if (needEnsure()) {
            ensureAdjustSession();
        } else {
            initPreviewEntry();
        }
        // 树就绪后默认选中入口文件（聚焦 AI + 预览指向首页）
        selectDefaultEntry();
    });
};

watch(() => props.templateId, () => {
    enterAdjustContext();
});

watch(() => props.active, (active) => {
    if (active) enterAdjustContext();
});

// 窄屏默认收起对话列（预览占主屏）
let narrowMq: MediaQueryList | null = null;
const onMqChange = () => {
    if (narrowMq?.matches) chatCollapsed.value = true;
};

onMounted(() => {
    // 页面默认打开在 AI 视图时 active 初始即为 true，watch 不会触发，这里补一次初始化
    if (props.active) enterAdjustContext();
    if (typeof window.matchMedia === 'function') {
        narrowMq = window.matchMedia('(max-width: 768px)');
        if (narrowMq.addEventListener) {
            narrowMq.addEventListener('change', onMqChange);
        }
        onMqChange();
    }
});

onBeforeUnmount(() => {
    if (narrowMq?.addEventListener) {
        narrowMq.removeEventListener('change', onMqChange);
    }
});
</script>

<style lang="scss" scoped>
.ai-workbench {
    display: flex;
    flex-direction: column;
    min-height: 0;

    .wb-toolbar {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 0;
        padding: 2px 0 10px;

        .prev-hint {
            margin-left: auto;
            font-size: 12px;
            color: var(--el-text-color-secondary);
        }
    }

    .wb-columns {
        display: flex;
        gap: 12px;
        flex: 1;
        min-height: 0;
        align-items: stretch;
    }

    // ===== 左列：作用模板 + 文件树（与手动编辑树列同构） =====
    .wb-col-tree {
        flex: 0 0 20%;
        min-width: 190px;
        display: flex;
        flex-direction: column;
        min-height: 0;

        .scope-select {
            margin-bottom: 8px;
        }

        .tree-filter {
            margin-bottom: 8px;
        }

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

            .tree-card-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
            }

            .tree-body {
                flex: 1;
                min-height: 0;
                overflow: hidden;

                :deep(.el-tree) {
                    height: 100%;
                    overflow: auto;
                }
            }
        }
    }

    // ===== 中列：AI 对话（30%，可收起 38px） =====
    .wb-col-chat {
        display: flex;
        flex-direction: column;
        flex: 0 0 30%;
        min-width: 0;
        min-height: 0;

        // 收缩态：变成一条窄竖条，显示切换按钮 + 竖排 AI 标签
        &.collapsed {
            flex: 0 0 38px;
            max-width: 38px;
            border: 1px solid var(--el-border-color-lighter);
            border-radius: 6px;
            background: var(--el-bg-color);

            .chat-collapse-bar {
                flex-direction: column;
                justify-content: flex-start;
                align-items: center;
                padding: 10px 0 12px;
                gap: 12px;
                border-bottom: none;
                margin-bottom: 0;

                .collapsed-label {
                    writing-mode: vertical-rl;
                    font-size: 14px;
                    font-weight: 600;
                    color: var(--el-color-primary);
                    letter-spacing: 3px;
                }
            }
        }

        .chat-collapse-bar {
            display: flex;
            align-items: center;
            justify-content: flex-end;
            padding: 2px 8px 6px;
            border-bottom: 1px solid var(--el-border-color-lighter);
            margin-bottom: 8px;
        }

        .wb-col-chat-inner {
            flex: 1;
            min-height: 0;
            display: flex;
            flex-direction: column;

            // aiChat 根元素撑满列
            > :deep(.ai-chat-panel) {
                flex: 1;
                min-height: 0;
            }
        }

        // 选区锁定标签条：AI 对话聚焦目标（选区模式点选区块后出现）
        .focus-section-bar {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 6px 10px;
            border-bottom: 1px solid var(--el-border-color-lighter);
            background: var(--el-color-success-light-9);

            .focus-section-tip {
                font-size: 12px;
                color: var(--el-text-color-secondary);
            }
        }
    }

    // ===== 右列：内联预览（吃满剩余空间，中列收起时自动扩张） =====
    .wb-col-preview {
        flex: 1 1 0;
        min-width: 0;
        min-height: 0;
    }
}

// 窄屏：三列退回堆叠
@media (max-width: 768px) {
    .wb-columns {
        flex-direction: column;
    }

    .wb-col-tree,
    .wb-col-chat,
    .wb-col-preview {
        flex: none;
        width: 100%;
    }

    .wb-col-tree .tree-card {
        min-height: 260px;
    }

    .wb-col-chat {
        min-height: 420px;
    }

    .wb-col-preview {
        min-height: 420px;
    }
}
</style>
