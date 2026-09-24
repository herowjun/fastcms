<template>
    <!-- AI 工作台视图（已去树化）：内联预览 | AI 对话 两列。
         文件树与工作对象选择已上提到父页面左侧常驻面板（共享树实例经 props 注入），
         会话编排自 edit.vue 迁入：本组件持有 AI 会话状态与预览点选钩子；
         工具条按钮逻辑经 defineExpose 暴露给父组件调用 -->
    <div class="ai-workbench">
        <!-- 两列骨架：内联预览 | AI 对话（可收起）；树中选中/高亮经 emit('highlight-file') 由父面板呈现 -->
        <div class="wb-columns">
            <div class="wb-col-preview">
                <TemplatePreviewPanel ref="previewPanelRef" v-model:entry="preview.entry"
                                      :page-options="previewPageOptions" :url="aiPreviewUrl"
                                      :empty-tip="previewEmptyTip" v-model:viewport="viewport"
                                      @refresh="refreshAiPreview" @frame-load="onPreviewFrameLoad" />
            </div>

            <div class="wb-col-chat" :class="{ collapsed: chatCollapsed }">
                <!-- 侧边把手条（贴右缘竖条）：展开态=收起按钮，收起态=展开按钮+竖排 AI 标签 -->
                <div class="chat-side-bar">
                    <el-button size="small" text :title="chatCollapsed ? '展开 AI 对话' : '收起 AI 对话'"
                               @click="chatCollapsed = !chatCollapsed">
                        <el-icon :size="14">
                            <ele-DArrowLeft v-if="chatCollapsed" />
                            <ele-DArrowRight v-else />
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
                             :ensure-session="ensureSessionBeforeSend"
                             :image-pick-mode="pickMode" :section-select-mode="sectionMode"
                             @select-session="onSelectAiSession" @new-session="onNewAiSession"
                             @files-changed="onAiFilesChanged" @file-written="onAiFileWritten"
                             @switch-file="onAiSwitchFile"
                             @edit-file="onEditSessionFile"
                              @toggle-image-pick="toggleImagePickMode" @toggle-section-select="toggleSectionSelectMode" />
                </div>
            </div>
        </div>

        <!-- 预览页点选换图对话框（搜附件库 / AI 生成 / 上传，选定后更新图片槽位） -->
        <ImagePickDialog ref="imagePickDialogRef" v-model:visible="pickDialogVisible"
                         :session-id="currentSession?.sessionId || ''" @applied="onPickApplied" />

        <!-- AI 新建模板对话框（新建表单视图；历史生成记录已由父组件"工作对象选择器"对话框接管） -->
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
import { useAiPreview, isRoutableHtml } from '/@/views/template/composables/useAiPreview';
import { usePreviewIframeHooks } from '/@/views/template/composables/usePreviewIframeHooks';

const props = defineProps<{
    /** 作用模板 ID（与主编辑视图同步：切换由父页面工作对象选择发起，切换后回流） */
    templateId: string;
    /** 模板列表（编辑此模板回定位用） */
    templateList?: any[];
    /** 全部会话（父组件统一持有与刷新，单一数据源；本组件只读，变更经 sessions-changed 通知父组件重拉） */
    sessions?: any[];
    /** 共享文件树实例（父组件 useTemplateFileTree 的 tree 对象：data=正式模板目录，sessionData=会话工作目录） */
    tree: { loading: boolean; data: any[]; sessionData: any[]; expandedKeys: string[]; defaultProps: any };
    /** 加载正式模板目录树（父组件包装共享实例的 load，返回 Promise） */
    loadTemplateTree: (templateId?: string) => Promise<any>;
    /** 加载会话工作目录树（父组件包装共享实例的 loadSession，返回 Promise） */
    loadSessionTree: (sessionId?: string) => Promise<any>;
    /** 视图是否激活（首次激活时初始化调整会话，避免页面加载就产生会话副作用） */
    active?: boolean;
}>();

const emit = defineEmits<{
    /** AI 直写正式模板目录（调整会话）→ 父组件刷新主编辑文件树与当前编辑文件 */
    (e: 'files-changed'): void;
    /** 「编辑文件」桥：切到手动编辑视图并打开该文件 */
    (e: 'edit-file', filePath: string): void;
    /** 生成型会话应用成功（templateId：应用后的正式模板 ID，父组件加载并提示跳转） */
    (e: 'applied', templateId?: string): void;
    /** 「编辑此模板」桥（已应用回看态）：父组件把该正式模板加载到主编辑视图并切手动编辑 */
    (e: 'edit-applied', templateId: string): void;
    /** 会话数据变化（创建会话等）→ 父组件统一重拉会话列表（单一数据源，父组件 refreshSessions） */
    (e: 'sessions-changed'): void;
    /** 左面板共享文件树高亮驱动：AI 写盘/切页/清空选中时由父组件转发给 WorkObjectPanel 呈现 */
    (e: 'highlight-file', path: string): void;
    /** 进入会话视图（面板选择会话 / 新建模板成功）→ 父组件把 currentObject 同步为该会话（单一同步路径） */
    (e: 'session-opened', session: any): void;
}>();

const aiApi = AiTemplateApi();

// ==================== 会话状态（自 edit.vue 迁入） ====================

const aiChatRef = ref();
const previewPanelRef = ref();

// 换图对话框（v-model:visible）
const pickDialogVisible = ref(false);
const imagePickDialogRef = ref();
// AI 新建模板对话框
const createDialogVisible = ref(false);
const createDialogRef = ref();

// 全部会话（调整 + 生成）：父组件统一持有（props 注入，单一数据源）。
// 本组件不再自拉/自写会话列表，任何会话变化经 emit('sessions-changed') 由父组件 refreshSessions 重拉
const allSessions = computed<any[]>(() => props.sessions || []);
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

// ==================== 共享文件树（父页面注入）/ 实时预览 / 点选钩子 ====================

// 文件树已上提父页面左侧常驻面板（单一实例），本组件经 props 协作：
// 数据读 props.tree（reactive 引用，直接别名保留响应式），加载调父组件包装的回调。
// 树 UI（渲染/过滤/高亮）在左侧面板，本组件只消费数据并经 emit('highlight-file') 驱动高亮
const tree = props.tree;

/** 当前生效的树数据（会话视图=会话工作目录，否则=正式模板目录）：预览页面下拉与默认入口的数据源 */
const currentTreeData = computed<any[]>(() => (sessionView.value ? tree.sessionData : tree.data) as any[]);

/** 树中查找 index.html 节点（默认入口，含模板目录前缀，如 my-company/index.html） */
const findIndexNode = (nodes: any[]): any => {
    for (const n of nodes || []) {
        if (n.children && n.children.length > 0) {
            const hit = findIndexNode(n.children);
            if (hit) return hit;
        } else if ((n.filePath || '').toLowerCase().endsWith('/index.html') || n.filePath === 'index.html') {
            return n;
        }
    }
    return null;
};

/** 加载作用模板目录树 / 会话工作目录树（父组件共享实例的加载回调） */
const loadScopeTree = (templateId?: string) => props.loadTemplateTree(templateId);
const loadSession = (sessionId?: string) => props.loadSessionTree(sessionId);

const loadSessionFileTree = () => loadSession(currentSession.value?.sessionId);

// 对话头会话下拉数据源（收窄）：仅当前作用模板的调整会话。
// 生成会话（草稿/已应用）的切换统一走顶栏"工作对象选择器"对话框，避免同一批数据在两个入口重复；
// 会话视图下传空数组 → aiChat 按 v-if 自动隐藏下拉、显示会话标题（切换生成会话走顶栏选择器）
const dropdownSessions = computed(() =>
    sessionView.value ? [] :
    (allSessions.value || []).filter((s: any) => s.templateId === props.templateId));

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
 * 左面板文件树点选（AI 工作台 tab 下的分发目标，父组件经 expose 调入）：
 * 聚焦该文件（对话头 current-file 注入），树高亮选中；选中可路由 HTML 页面时预览列联动切到该页面
 */
const focusTreeNode = (node: any) => {
    if (node.sortNum === 0) return;
    selectedFile.value = node.filePath;
    if (isRoutableHtml(node.filePath)) {
        preview.entry = node.filePath;
    }
    emit('highlight-file', node.filePath);
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
        emit('highlight-file', idx.filePath);
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
        emit('highlight-file', node.filePath);
    }
};

// ==================== 会话编排（自 edit.vue 迁入） ====================

/** 应用会话并重置与旧会话绑定的临时状态（选区/聚焦文件/点选模式） */
const applySession = (session: any) => {
    currentSession.value = session || null;
    selectedFile.value = '';
    emit('highlight-file', '');
    // 旧会话锁定的选区/点选模式对新会话无意义，一并清除
    resetModes();
};

/**
 * 进入草稿会话（不落库）：点「新建会话」/ 无历史调整会话时的前端占位状态，
 * 用户真实发起首条 AI 对话时才创建会话落库（ensureSessionBeforeSend）。
 * 草稿对象绑定当前作用模板，树/预览按调整模式初始化
 */
const enterDraftSession = () => {
    sessionView.value = false;
    currentSession.value = {
        sessionId: null,
        templateId: props.templateId,
        status: 'draft',
        created: new Date().toISOString(),
        title: '',
    };
    // 从生成会话切回调整模式时树可能尚未加载/已过期，统一重载后初始化预览与默认选中
    loadScopeTree(props.templateId).then(() => {
        initPreviewEntry();
        selectDefaultEntry();
    });
};

/**
 * 草稿会话懒创建：首条真实 AI 对话发出前回调（useAiRunStream onSend 前置钩子）。
 * 已是真实会话原样返回；草稿会话此时才调用 createSession 落库并切换上下文。
 * ensuringSession 互斥：连点发送时并发请求只创建一条会话
 */
let ensuringSession = false;
const ensureSessionBeforeSend = async (): Promise<any> => {
    const s = currentSession.value;
    if (s?.sessionId) return s;
    if (ensuringSession || !props.templateId) return null;
    ensuringSession = true;
    try {
        const res = await aiApi.createSession({ templateId: props.templateId });
        if (!res.data) {
            ElMessage.error(res.msg || '创建会话失败');
            return null;
        }
        // 会话列表由父组件统一持有，通知其重拉（单一数据源）
        emit('sessions-changed');
        applySession(res.data);
        return res.data;
    } finally {
        ensuringSession = false;
    }
};

let ensuring = false;
const ensureAdjustSession = async () => {
    if (ensuring || !props.templateId) return;
    ensuring = true;
    sessionView.value = false;
    try {
        // 会话数据改由父组件 props 注入（单一数据源），这里只做筛选不再自拉
        // 按创建时间倒序（最近的在最前）
        const adjustSessions = allSessions.value
            .filter((s: any) => s.templateId === props.templateId)
            .sort((a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime());
        if (adjustSessions.length > 0) {
            applySession(adjustSessions[0]);
        } else {
            // 尚无该模板的调整型会话：进入草稿会话（不落库，首条真实对话时才创建）
            enterDraftSession();
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
/**
 * 新建会话（对话头 ＋ 按钮）：进入当前模板的草稿会话（不落库）。
 * 生成完整模板的入口收敛到工具条「✦ 新建模板」，避免这里重复弹生成对话框
 */
const onNewAiSession = () => {
    if (!props.templateId) return;
    enterDraftSession();
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
    // 会话列表由父组件统一持有，通知其重拉（单一数据源；不阻断进入会话）
    emit('sessions-changed');
    applySession(session);
    // 父组件 currentObject 同步为该新会话（左侧面板高亮/下半文件树随之切会话工作目录）
    emit('session-opened', session);
    // 初始化会话文件树与预览入口（新会话尚无文件，首个页面写盘后预览自动出现）
    loadSessionFileTree().then(() => {
        initPreviewEntry();
        selectDefaultEntry();
    });
    nextTick(() => {
        aiChatRef.value?.autoSend(firstMessage);
    });
};

/** 统一工作对象选择器入口：打开生成会话（未应用续聊 / 已应用只读回看），会话数据取 props（单一数据源） */
const openSessionByRow = (row: any) => {
    sessionView.value = true;
    applySession(row);
    // 父组件 currentObject 同步为该会话（面板选择会话的回流路径：selectObject 依赖此事件统一设置）
    emit('session-opened', row);
    loadSessionFileTree().then(() => {
        initPreviewEntry();
        selectDefaultEntry();
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
 * 「编辑此模板」（已应用回看态）：优先用会话持久化的 appliedTemplateId 直达正式模板
 * （应用成功时后端回写的指针）；存量会话无指针时回退按目录名匹配
 */
const onEditApplied = () => {
    const s = currentSession.value;
    if (s?.appliedTemplateId) {
        emit('edit-applied', String(s.appliedTemplateId));
        return;
    }
    const tpl = (props.templateList || []).find((i: any) => i.name === s?.templateName);
    if (!tpl) {
        ElMessage.warning('未找到对应的正式模板，请从"工作对象选择器"的正式模板页签中选择');
        return;
    }
    emit('edit-applied', String(tpl.id));
};

// ==================== 工具条对话框入口 ====================

/** 新建模板：打开新建表单 */
const openCreateDialog = () => {
    createDialogRef.value?.open();
};

/** 会话期瞬时态徽章上下文（生成中/失败，不落库）：供父组件选择器按钮与工作对象对话框读取 */
const getSessionBadgeCtx = () => {
    const running = aiChatRef.value?.isChatting?.();
    const failed = !running && aiChatRef.value?.isFailed?.();
    return {
        runningId: running ? currentSession.value?.sessionId || '' : '',
        failedId: failed ? currentSession.value?.sessionId || '' : ''
    };
};

/**
 * 退出会话视图（父组件"工作对象选择器"切回正式模板时调用）：
 * 清空会话上下文并恢复当前作用模板的调整上下文（树/预览/调整会话）
 */
const exitSessionView = () => {
    if (!sessionView.value) return;
    sessionView.value = false;
    applySession(null);
    // 复用视图激活链路：重载作用模板树 + 确保调整会话可用 + 初始化预览入口
    enterAdjustContext();
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

// 工具条逻辑暴露：工具条 UI 上移到父组件单行工具栏统一排版（与手动编辑工具条同位互换），
// 按钮点击/状态显示由父组件经模板 ref 调用（exposed ref 自动解包）
defineExpose({
    openCreateDialog,
    onRollback,
    onApplyTemplate,
    onEditApplied,
    canRollback,
    showApply,
    showEditApplied,
    rollingBack,
    applying,
    // ===== 统一工作对象选择支撑 =====
    /** 打开生成会话（未应用续聊 / 已应用只读回看） */
    openSessionByRow,
    /** 退出会话视图，恢复当前作用模板的调整上下文 */
    exitSessionView,
    /** 会话期瞬时态徽章上下文（生成中/失败） */
    getSessionBadgeCtx,
    /** 会话视图标记 / 当前会话（父组件推导"当前工作对象"用） */
    sessionView,
    currentSession,
    /** 左面板文件树点选在 AI tab 下的分发目标：聚焦该文件 + 预览联动 + 树高亮 */
    focusTreeNode,
});
</script>

<style lang="scss" scoped>
.ai-workbench {
    // 撑满父容器（父页面右区列注入显式高度）
    height: 100%;
    display: flex;
    flex-direction: column;
    min-height: 0;

    .wb-columns {
        display: flex;
        gap: 12px;
        flex: 1;
        min-height: 0;
        align-items: stretch;
    }

    // ===== 右列：AI 对话（30%，可收起 38px） =====
    .wb-col-chat {
        display: flex;
        flex-direction: row;
        flex: 0 0 30%;
        min-width: 0;
        min-height: 0;

        // 侧边把手条（贴右缘竖条）：与手动编辑编辑器列同一套交互
        .chat-side-bar {
            flex: 0 0 22px;
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 6px 0 10px;
            gap: 6px;
            border-left: 1px solid var(--el-border-color-lighter);
            border-radius: 0 6px 6px 0;
            background: var(--el-fill-color-lighter);

            .el-button {
                width: 100%;
                padding: 4px 0;
            }
        }

        .wb-col-chat-inner {
            flex: 1;
            min-width: 0;
            min-height: 0;
            display: flex;
            flex-direction: column;

            // aiChat 根元素撑满列
            > :deep(.ai-chat-panel) {
                flex: 1;
                min-height: 0;
            }
        }

        // 收缩态：整列变 38px 竖条，展开按钮 + 竖排 AI 标签，预览列吃满剩余空间
        &.collapsed {
            flex: 0 0 38px;
            max-width: 38px;

            .chat-side-bar {
                flex-basis: 38px;
                border-left: none;
                border: 1px solid var(--el-border-color-lighter);
                border-radius: 6px;
                background: var(--el-bg-color);
                padding: 10px 0 12px;
                gap: 12px;

                .collapsed-label {
                    writing-mode: vertical-rl;
                    font-size: 14px;
                    font-weight: 600;
                    color: var(--el-color-primary);
                    letter-spacing: 3px;
                }
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

    // ===== 左列：内联预览（吃满剩余空间，对话列收起时自动扩张） =====
    .wb-col-preview {
        flex: 1 1 0;
        min-width: 0;
        min-height: 0;
    }
}

// 窄屏：两列退回堆叠
@media (max-width: 768px) {
    .wb-columns {
        flex-direction: column;
    }

    .wb-col-chat,
    .wb-col-preview {
        flex: none;
        width: 100%;
    }

    .wb-col-chat {
        min-height: 420px;
    }

    .wb-col-preview {
        min-height: 420px;
    }
}
</style>
