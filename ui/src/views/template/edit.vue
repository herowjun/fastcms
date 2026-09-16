<template>
<div class="container">
    <el-card>
        <div class="toolbar">
            <el-row :gutter="35" class="toolbar-row">
                <el-col :sm="5" class="mb20">
                    <el-select v-model="state.templateId" placeholder="选择模板" filterable style="width: 100%" @change="onTemplateChange">
                        <el-option v-for="item in state.templateList" :key="item.id" :value="item.id"
                                   :label="item.name + (item.active ? '（使用中）' : '')" />
                    </el-select>
                </el-col>
                <el-col :sm="19" class="mb20">
                    <div class="toolbar-actions">
                        <el-upload
                            class="upload-btn"
                            :action="state.uploadUrl"
                            name="files"
                            :data="uploadData"
                            multiple
                            :headers="state.headers"
                            :show-file-list="false"
                            :on-success="uploadSuccess"
                            :on-error="onHandleUploadError"
                            :before-upload="onBeforeUpload">
                            <el-button size="default" type="primary" :title="uploadTargetTip"><el-icon><ele-Plus /></el-icon>上传模板文件</el-button>
                        </el-upload>
                        <el-divider direction="vertical" />
                        <el-button @click="onPreview" :disabled="!state.loadedTemplateId">
                            <el-icon><ele-View /></el-icon>预览
                        </el-button>
                        <el-button type="warning" plain @click="onOpenAiAdjust" :disabled="!state.loadedTemplateId">
                            <el-icon><ele-MagicStick /></el-icon>AI 调整
                        </el-button>
                        <el-button type="warning" @click="onOpenAiCreate">
                            <el-icon><ele-MagicStick /></el-icon>AI 新建模板
                        </el-button>
                        <el-divider direction="vertical" />
                        <el-button type="primary" @click="onSaveFile" :disabled="!state.currEditFile">保 存</el-button>
                        <el-button type="danger" @click="onDelFile" :disabled="!state.currEditFile">删 除</el-button>
                        <span v-if="state.isDirty" class="dirty-tip">● 有未保存的修改</span>
                    </div>
                </el-col>
            </el-row>
        </div>
        <!-- 编辑区容器（历史遗留的 el-form 包裹已移除：无 model/rules/label，纯布局用 div 即可） -->
        <div style="padding-top: 5px;">
            <el-row :gutter="35" ref="editRowRef">
                <el-col :sm="5" class="mb20">
                    <div class="tree-container">
                        <!-- 树卡片与右侧编辑器等高（高度内联注入），内部 flex 让树占满卡片剩余空间 -->
                        <el-card shadow="hover" class="tree-card" :style="{ height: state.clientHeight }">
                            <template #header>
                                <div class="tree-card-header">
                                    <span>模板文件树</span>
                                    <el-button size="small" text :loading="tree.loading"
                                               title="刷新文件树" @click="loadFileTree()">
                                        <el-icon><ele-Refresh /></el-icon>
                                    </el-button>
                                </div>
                            </template>
                            <div v-loading="tree.loading" class="tree-body">
                                <el-tree :data="tree.data"
                                    :default-expand-all="false"
                                    :default-expanded-keys="tree.expandedKeys"
                                    highlight-current
                                    node-key="filePath"
                                    :props="tree.defaultProps"
                                    @node-click="onNodeClick"
                                    style="height: 100%;overflow: auto;"
                                    ref="treeTable">
                                </el-tree>
                            </div>
                        </el-card>
                    </div>
                </el-col>
                <el-col :sm="19" class="mb20">
                    <!-- 图片工作台：文件树点选图片文件时覆盖代码编辑器（左原图 / 右生成结果对比，确认后应用） -->
                    <ImageWorkbench v-if="workbenchVisible" v-model:visible="workbenchVisible" :template-id="state.loadedTemplateId"
                                    :file-path="workbenchFile" :height="state.clientHeight" @refresh-tree="loadFileTree" />
                    <Codemirror
                            v-else
                            v-model="state.content"
                            :style="{ height: state.clientHeight, width: '100%' }"
                            :autofocus="true"
                            @change="onChange"
                            v-bind="$attrs"
                            :extensions="extensions" />
                </el-col>
            </el-row>
        </div>

        <!-- AI 对话抽屉（全屏覆盖，完全独立于主编辑界面：调整型/会话编辑视图为左预览右对话，
             AI 每写一个文件自动刷新预览；关闭抽屉不影响主界面任何状态） -->
        <el-drawer v-model="state.aiDrawerVisible" size="100%" :close-on-click-modal="false" custom-class="ai-template-drawer" :before-close="onAiDrawerBeforeClose">
            <template #header>
                <div class="drawer-header">
                    <span class="drawer-title">{{ state.sessionView ? '编辑 AI 模板' : (state.aiMode === 'adjust' ? 'AI 调整模板' : 'AI 生成模板') }}</span>
                </div>
            </template>
            <div class="ai-drawer-body" :class="{ split: state.aiMode === 'adjust' || state.sessionView }">
                <div v-if="state.aiMode === 'adjust' || state.sessionView" class="ai-preview-col" :class="{ 'chat-collapsed': state.aiChatCollapsed }">
                    <div class="preview-toolbar">
                        <el-select v-model="preview.entry" size="small" filterable placeholder="选择预览页面">
                            <el-option v-for="p in previewPageOptions" :key="p" :value="p" :label="p" />
                        </el-select>
                        <!-- 换图/选区按钮在右侧 AI 聊天操作行（与发送按钮同排，随聊天列收缩） -->
                        <el-button size="small" @click="refreshAiPreview" title="刷新预览">
                            <el-icon><ele-Refresh /></el-icon>
                        </el-button>
                        <el-button size="small" @click="openAiPreviewNewWindow" title="新窗口打开">
                            <el-icon><ele-FullScreen /></el-icon>
                        </el-button>
                    </div>
                    <div class="preview-frame-wrap">
                        <iframe v-if="aiPreviewUrl" ref="aiPreviewFrameRef" :src="aiPreviewUrl"
                                class="preview-frame" title="模板实时预览" @load="onPreviewFrameLoad"></iframe>
                        <!-- 预览空白占位：新会话生成中（尚无页面文件）或无可路由 HTML 时 -->
                        <div v-else class="preview-empty">
                            <el-empty :description="previewEmptyTip" :image-size="80" />
                        </div>
                    </div>
                </div>
                <div class="ai-chat-col" :class="{ collapsed: (state.aiMode === 'adjust' || state.sessionView) && state.aiChatCollapsed }">
                    <!-- adjust/会话编辑视图：收缩/展开切换按钮 -->
                    <div v-if="state.aiMode === 'adjust' || state.sessionView" class="chat-collapse-bar">
                        <el-button size="small" text :title="state.aiChatCollapsed ? '展开 AI 对话框' : '收缩 AI 对话框'"
                                   @click="state.aiChatCollapsed = !state.aiChatCollapsed">
                            <el-icon :size="16">
                                <ele-Expand v-if="state.aiChatCollapsed" />
                                <ele-Fold v-else />
                            </el-icon>
                        </el-button>
                        <span v-if="state.aiChatCollapsed" class="collapsed-label">AI</span>
                    </div>
                    <div class="ai-chat-col-inner" v-show="!state.aiChatCollapsed">
                        <!-- 选区锁定标签：AI 对话聚焦目标（选区模式点选区块后出现） -->
                        <div v-if="state.selectedSection" class="focus-section-bar">
                            <el-tag size="small" type="success" closable @close="clearSelectedSection">
                                已选中区块：{{ state.selectedSection.sectionId }}<template v-if="state.selectedSection.elementHint"> · {{ state.selectedSection.elementHint }}</template>
                            </el-tag>
                            <span class="focus-section-tip">本轮对话聚焦该区块</span>
                        </div>
                        <ai-chat ref="aiChatRef" :session="state.currentAiSession" :mode="state.aiMode"
                                 :current-file="state.aiMode === 'adjust' ? (preview.entry || state.currEditFile) : ''"
                                 :focus-section="state.selectedSection?.sectionId || ''"
                                 :focus-element-hint="state.selectedSection?.elementHint || ''"
                                 :sessions="state.aiSessions" :creating-session="state.creatingAiSession"
                                 :session-active="state.sessionView"
                                 :image-pick-mode="state.imagePickMode" :section-select-mode="state.sectionSelectMode"
                                 @select-session="onSelectAiSession" @new-session="onNewAiSession"
                                 @files-changed="onAiFilesChanged" @file-written="onAiFileWritten" @switch-file="onAiSwitchFile"
                                 @applied="onAiTemplateApplied"
                                 @edit-files="onEditSessionFiles"
                                 @toggle-image-pick="toggleImagePickMode" @toggle-section-select="toggleSectionSelectMode" />
                    </div>
                </div>
            </div>
        </el-drawer>

        <!-- 预览页点选换图对话框（搜附件库 / AI 生成 / 上传，选定后更新图片槽位） -->
        <ImagePickDialog ref="imagePickDialogRef" v-model:visible="pickDialogVisible"
                         :session-id="state.currentAiSession?.sessionId || ''" @applied="onPickApplied" />

        <!-- AI 新建模板对话框（含历史生成记录入口） -->
        <CreateTemplateDialog ref="createDialogRef" v-model:visible="createDialogVisible"
                              @created="onCreateDialogCreated" @open-session="onOpenHistorySession" />
    </el-card>
</div>
</template>

<script lang="ts" name="templateEdit" setup>
import { reactive, computed, onMounted, onActivated, onBeforeUnmount, ref, nextTick, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessageBox, ElMessage } from 'element-plus';
import { Local } from '/@/utils/storage';
import { TemplateApi } from '/@/api/template/index';

import { AiTemplateApi } from '/@/api/ai/index';

import AiChat from '/@/views/template/aiChat.vue';

import ImagePickDialog from '/@/views/template/ImagePickDialog.vue';

import ImageWorkbench from '/@/views/template/ImageWorkbench.vue';

import CreateTemplateDialog from '/@/views/template/CreateTemplateDialog.vue';

import { useTemplateFileTree } from '/@/views/template/composables/useTemplateFileTree';

import { useAiPreview, isRoutableHtml } from '/@/views/template/composables/useAiPreview';
import { Codemirror } from "vue-codemirror";
import { html } from "@codemirror/lang-html";
import { javascript } from "@codemirror/lang-javascript";
import { css } from "@codemirror/lang-css";
import { search } from "@codemirror/search";
import { oneDark } from "@codemirror/theme-one-dark";

const treeTable = ref()
// 编辑区行（左树 + 右编辑器）：用于实测编辑区起点，计算高度自适应
const editRowRef = ref();
// 布局容器尺寸变化观察器（keep-alive 缓存页切回时 onMounted 不会重跑，靠 onActivated 兜底重算）
let editorHeightObserver: ResizeObserver | null = null;

/**
 * 按文件后缀切换语法高亮（state 定义在下方，getter 惰性求值时已初始化）：
 * css/scss/less → css、js/ts → javascript，其余（html/htm/xml/txt/json 等）回退 html
 */
const langExtensionFor = (filePath: string) => {
    const lower = (filePath || '').toLowerCase();
    if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return css();
    if (lower.endsWith('.ts')) return javascript({ typescript: true });
    if (lower.endsWith('.js') || lower.endsWith('.mjs') || lower.endsWith('.cjs')) return javascript();
    return html();
};
// search() 提供编辑器内搜索面板（Ctrl/Cmd+F），随文件切换语言扩展
const extensions = computed(() => [langExtensionFor(state.currEditFile), oneDark, search()]);

const templateApi = TemplateApi();
const aiApi = AiTemplateApi();
// 修图/生图任务状态机已随组件下沉：生图在 ImagePickDialog、修图在 ImageWorkbench
const aiChatRef = ref();
const aiPreviewFrameRef = ref();
// 换图对话框显示状态（v-model:visible，Ctrl+S 快捷键据此判断是否拦截）
const pickDialogVisible = ref(false);
const imagePickDialogRef = ref();
// 图片工作台状态（文件树点选图片文件打开；filePath 变化驱动组件内部重载原图）
const workbenchVisible = ref(false);
const workbenchFile = ref('');
// AI 新建模板对话框显示状态（v-model:visible，Ctrl+S 快捷键据此判断是否拦截）
const createDialogVisible = ref(false);
const createDialogRef = ref();
const state = reactive({
    clientHeight: "600px",
    // 模板选择（可编辑非激活模板）
    templateList: [] as any[],
    templateId: '',
    loadedTemplateId: '',
    currEditFile: "",
    content: '',
    // 最后一次保存/加载的内容，用于判断是否有未保存修改
    savedContent: '',
    isDirty: false,
    uploadUrl: import.meta.env.VITE_API_URL + "/admin/template/files/upload",
    headers: {"Authorization": Local.get('token')},
    uploadParam: {
        dirName: '',
        templateId: ''
    },
    // ===== AI 集成状态 =====
    // AI 抽屉
    aiDrawerVisible: false,
    // adjust：调整当前加载的正式模板；generate：生成新模板
    aiMode: 'adjust' as 'adjust' | 'generate',
    // 抽屉头部的会话下拉列表
    aiSessions: [] as any[],
    currentAiSessionId: '',
    currentAiSession: null as any,
    // ===== 会话编辑视图（抽屉内，生成型会话应用前的查看与打磨） =====
    // true 时抽屉切换为「编辑 AI 模板」：左预览右对话，预览走会话路由；
    // 纯抽屉内状态，主编辑界面不受任何影响，关闭抽屉即重置
    sessionView: false,
    // 新建调整会话按钮 loading
    creatingAiSession: false,
    // ===== 预览页点选换图 =====
    // 换图模式：开启后向预览 iframe 注入点选钩子（全部图片边框高亮可点选——
    // 槽位图 data-ai-slot 主色粗虚线、演示图浅色细虚线，点击弹操作窗）
    imagePickMode: false,
    // ===== 预览页选区修改 =====
    // 选区模式：开启后向预览 iframe 注入区块钩子（hover 高亮 data-ai-section-root 区块，点击锁定为 AI 对话目标）
    sectionSelectMode: false,
    // 已锁定的选中区块（AI 对话聚焦目标，随 chat 请求发送 focusSectionId）：
    // elementHint 为点选时命中的具体元素描述（如 标题「散养土鸡蛋」），做元素级语义提示
    selectedSection: null as { sectionId: string; elementHint: string } | null,
    // 换图模式注入的 click 捕获监听（跨 iframe 重载复用同一函数，便于移除）
    // AI 对话框收缩状态（仅 adjust 模式）
    aiChatCollapsed: false
});

// 文件树（正式模板目录树 + AI 会话工作目录树，加载与查找下沉到 composable）
const { tree, findIndexNode, load: loadTemplateTree, loadSession, clearSession } = useTemplateFileTree();

// ==================== AI 集成 ====================

// AI 实时预览（入口页面/刷新键/预览地址，getter 注入树与会话上下文）
const { preview, previewPageOptions, aiPreviewUrl, previewEmptyTip, initEntry: initAiPreviewEntry, refresh: refreshAiPreview, openInNewWindow: openAiPreviewNewWindow } = useAiPreview({
    getSessionView: () => state.sessionView,
    getCurrentSession: () => state.currentAiSession,
    getLoadedTemplateId: () => state.loadedTemplateId,
    getCurrEditFile: () => state.currEditFile,
    getTreeNodes: () => (state.sessionView ? tree.sessionData : tree.data) as any[],
    getSessionTreeNodes: () => tree.sessionData
});

/** 上传附加参数 */
const uploadData = computed(() => ({ dirName: state.uploadParam.dirName, templateId: state.uploadParam.templateId }));

/** 上传目标目录提示（按钮 tooltip）：未选目录时将兜底上传到模板根目录 */
const uploadTargetTip = computed(() =>
    state.uploadParam.dirName ? `将上传到目录：${state.uploadParam.dirName}` : '未选择目录，将上传到模板根目录');

/** 会话编辑视图下已应用的会话：只读（禁用选区等需要写会话的交互入口） */
const sessionReadonly = computed(() => state.sessionView && state.currentAiSession?.status === 'applied');

// ==================== 预览页点选换图 ====================

/**
 * 切换换图模式：开启后预览 iframe 中全部图片边框高亮可点选
 * （槽位图 data-ai-slot 主色粗虚线、演示图浅色细虚线），关闭时移除高亮
 *
 * 预览页与本页同源（/template/preview/**），通过 contentDocument 直接注入
 * 样式与 click 捕获监听；iframe 因刷新键变化重载时在 @load 中重新注入。
 */
const toggleImagePickMode = () => {
    state.imagePickMode = !state.imagePickMode;
    if (state.imagePickMode && !state.currentAiSession?.sessionId) {
        ElMessage.warning('当前无 AI 会话，请先打开 AI 调整');
        state.imagePickMode = false;
        return;
    }
    // 已应用的生成会话仅回看：沙箱目录与正式目录已分叉，换图改沙箱不生效
    if (state.imagePickMode && sessionReadonly.value) {
        ElMessage.warning('该会话已应用，仅支持回看；如需调整请在正式模板上使用「AI 调整」');
        state.imagePickMode = false;
        return;
    }
    if (state.imagePickMode && state.sectionSelectMode) {
        // 两个点选模式互斥
        state.sectionSelectMode = false;
        applySectionSelectHooks();
    }
    applyImagePickHooks();
    if (state.imagePickMode) {
        ElMessage.info('换图模式已开启：点击预览页中高亮虚线的图片即可更换');
    }
};

/**
 * iframe load 回调：点选模式开启时向新文档重新注入钩子（跨重载保持模式）；
 * 并同步页面下拉到 iframe 实际显示的页面（兜底 JS 跳转等非点击导航）
 */
const onPreviewFrameLoad = () => {
    applyImagePickHooks();
    applySectionSelectHooks();
    markSelectedSection();
    applyPreviewLinkHook();
    syncPreviewEntryFromIframe();
};

// ==================== 预览内导航联动页面下拉 ====================

/**
 * 从预览路由路径反解页面 entry（与 aiPreviewUrl 构造互逆）：
 * 会话视图 /ai/template/preview/{sessionId}/{templateName}/{entry}，
 * 调整模式 /template/preview/{templateId}/{entry}（entry 含模板目录前缀）
 */
const extractPreviewEntry = (pathname: string): string => {
    try {
        if (state.sessionView) {
            const sess = state.currentAiSession;
            if (!sess?.sessionId || !sess.templateName) return '';
            const prefix = '/ai/template/preview/' + sess.sessionId + '/' + sess.templateName + '/';
            if (!pathname.startsWith(prefix)) return '';
            return decodeURIComponent(pathname.substring(prefix.length));
        }
        if (!state.loadedTemplateId) return '';
        const prefix = '/template/preview/' + state.loadedTemplateId + '/';
        if (!pathname.startsWith(prefix)) return '';
        return decodeURIComponent(pathname.substring(prefix.length));
    } catch {
        return '';
    }
};

/**
 * 预览内链接点击拦截（普通模式）：点击菜单/链接时先把页面下拉切到目标页，
 * 再由 aiPreviewUrl 驱动 iframe 导航——状态先行、单次加载，无二次刷新
 */
const onPreviewLinkClick = (e: Event) => {
    // 换图/选区模式的捕获处理器已 preventDefault 全部点击，跳过避免干扰
    if (state.imagePickMode || state.sectionSelectMode || e.defaultPrevented) return;
    const anchor = (e.target as HTMLElement)?.closest?.('a') as HTMLAnchorElement | null;
    if (!anchor) return;
    const win = aiPreviewFrameRef.value?.contentWindow;
    if (!win) return;
    const href = anchor.getAttribute('href') || '';
    // 锚点/脚本链接/新窗口打开：放行默认行为
    if (!href || href.startsWith('#') || href.startsWith('javascript:') || anchor.target === '_blank') return;
    let resolved: URL;
    try {
        resolved = new URL(href, win.location.href);
    } catch {
        return;
    }
    // 站外链接放行
    if (resolved.origin !== win.location.origin) return;
    const entry = extractPreviewEntry(resolved.pathname);
    if (!entry || !isRoutableHtml(entry)) return;
    // 本页链接（含锚点跳转）放行默认行为
    if (resolved.pathname === win.location.pathname) return;
    e.preventDefault();
    if (entry !== preview.entry) {
        preview.entry = entry;
    } else {
        refreshAiPreview();
    }
};

/** 向预览 iframe 文档注册链接导航监听（每次导航文档重建，随 load 重新挂载） */
const applyPreviewLinkHook = () => {
    const doc: Document | null | undefined = aiPreviewFrameRef.value?.contentDocument;
    doc?.addEventListener('click', onPreviewLinkClick, true);
};

/** load 兜底：非点击导航（JS 跳转等）后按 iframe 实际地址同步页面下拉 */
const syncPreviewEntryFromIframe = () => {
    const win = aiPreviewFrameRef.value?.contentWindow;
    if (!win) return;
    try {
        const entry = extractPreviewEntry(win.location.pathname);
        if (entry && isRoutableHtml(entry) && entry !== preview.entry) {
            preview.entry = entry;
        }
    } catch {
        // 读不到 iframe 地址时忽略（不联动）
    }
};

/**
 * 注入/移除点选钩子（同源 iframe 可直接操作 contentDocument）
 */
const applyImagePickHooks = () => {
    const doc: Document | null | undefined = aiPreviewFrameRef.value?.contentDocument;
    if (!doc) return;
    const styleId = '__ai_pick_style__';
    const existing = doc.getElementById(styleId);
    if (state.imagePickMode) {
        if (!existing) {
            const style = doc.createElement('style');
            style.id = styleId;
            style.textContent = `
                img { outline: 2px dashed #e6a23c !important; outline-offset: 2px; cursor: pointer !important; }
                img:hover { outline-style: solid !important; filter: brightness(1.08); }
                [data-ai-slot] { outline: 2px dashed var(--el-color-primary, #3b82f6) !important; outline-offset: 2px; cursor: pointer !important; }
                [data-ai-slot]:hover { outline-style: solid !important; filter: brightness(1.08); }
            `;
            (doc.head || doc.documentElement).appendChild(style);
        }
        doc.addEventListener('click', onPickImageClick, true);
    } else {
        existing?.remove();
        doc.removeEventListener('click', onPickImageClick, true);
    }
};

/**
 * 点选捕获监听（capture 阶段拦截）：
 * 换图模式下拦截 iframe 内所有点击的默认行为（含 <a> 跳转），
 * 防止预览被导航离开（如文章列表图片点到文章详情页）。
 * 两类图片均可点选换图：
 * - 槽位图（data-ai-slot 标记）：改 _pagespec.json，模板资产正式生效
 * - 演示图（mock 数据图，如文章封面）：改 _preview_data.json，仅预览生效
 */
const onPickImageClick = (e: Event) => {
    if (!state.imagePickMode) return;
    const target = e.target as HTMLElement;
    if (!target) return;
    const slotEl = target.closest?.('[data-ai-slot]') as HTMLElement | null;
    // 无论是否命中槽位，一律阻断默认行为（a 跳转/按钮提交等），保持预览稳定
    e.preventDefault();
    e.stopPropagation();
    if (slotEl) {
        const slot = slotEl.getAttribute('data-ai-slot') || '';
        const sectionId = slotEl.getAttribute('data-ai-section') || '';
        if (!slot || !sectionId) {
            ElMessage.warning('该图片缺少槽位标记，无法点选更换（可让 AI 调整该区域的图片）');
            return;
        }
        imagePickDialogRef.value?.open(sectionId, slot, '');
        return;
    }
    // 无槽位标记：点击的是 img 才进入演示图换图（点链接文字等不响应）
    const imgEl = target.tagName === 'IMG' ? target : (target.querySelector?.('img') as HTMLImageElement | null);
    if (!imgEl) return;
    // 原样 src 作为替换映射 key（含内联 SVG data URI；不用 img.src 属性避免浏览器解析改写）
    const rawSrc = imgEl.getAttribute('src') || '';
    if (!rawSrc) {
        ElMessage.warning('该图片缺少地址，无法更换');
        return;
    }
    imagePickDialogRef.value?.open('', '', rawSrc);
};

// ==================== 预览页选区修改 ====================

/** 选区模式注入的样式/类名常量（与 iframe 文档内约定一致） */
const SECTION_STYLE_ID = '__ai_section_select_style__';
const SECTION_SELECTED_CLASS = '__ai_section_selected__';

/**
 * 切换选区模式：开启后预览 iframe 中的组件区块（data-ai-section-root 根标记）
 * hover 高亮，点击锁定为 AI 对话目标（后续对话只修改该区块）
 *
 * 调整模式与会话编辑视图均可用：组件化模板走后端定位（调整=提示词约束路线 B，
 * 会话=PageSpec 往返），非组件化模板点选时提示不可用
 */
const toggleSectionSelectMode = () => {
    state.sectionSelectMode = !state.sectionSelectMode;
    if (state.sectionSelectMode && !state.currentAiSession?.sessionId) {
        ElMessage.warning('当前无 AI 会话，请先打开 AI 调整');
        state.sectionSelectMode = false;
        return;
    }
    // 已应用的生成会话仅回看：不允许锁定选区发起对话
    if (state.sectionSelectMode && sessionReadonly.value) {
        ElMessage.warning('该会话已应用，仅支持回看；如需调整请在正式模板上使用「AI 调整」');
        state.sectionSelectMode = false;
        return;
    }
    if (state.sectionSelectMode && (state.imagePickMode)) {
        // 两个点选模式互斥
        state.imagePickMode = false;
        applyImagePickHooks();
    }
    applySectionSelectHooks();
    if (state.sectionSelectMode) {
        ElMessage.info('选区模式已开启：点击预览页中的区块，锁定后在右侧对话中描述修改需求');
    }
};

/**
 * 注入/移除选区钩子：hover 高亮样式 + click 捕获监听 + 已选中区块的持续高亮
 */
const applySectionSelectHooks = () => {
    const doc: Document | null | undefined = aiPreviewFrameRef.value?.contentDocument;
    if (!doc) return;
    const existing = doc.getElementById(SECTION_STYLE_ID);
    if (state.sectionSelectMode) {
        if (!existing) {
            const style = doc.createElement('style');
            style.id = SECTION_STYLE_ID;
            style.textContent = `
                [data-ai-section-root], [data-ai-section] { cursor: pointer !important; }
                [data-ai-section-root]:hover { outline: 2px dashed var(--el-color-success, #67c23a) !important; outline-offset: 2px; }
                .__ai_section_selected__ { outline: 2px solid var(--el-color-success, #67c23a) !important; outline-offset: 2px; }
            `;
            (doc.head || doc.documentElement).appendChild(style);
        }
        doc.addEventListener('click', onSectionSelectClick, true);
    } else {
        existing?.remove();
        doc.removeEventListener('click', onSectionSelectClick, true);
    }
    markSelectedSection();
};

/**
 * 选区点击捕获：锁定目标区块（含点击元素语义提示）
 *
 * 优先按组件根标记 data-ai-section-root 定位（S6 注入，重渲染后的模板才有）；
 * 旧渲染产物兜底用 media 槽位 img 的 data-ai-section（只有点中图片时可选中）
 */
const onSectionSelectClick = (e: Event) => {
    if (!state.sectionSelectMode) return;
    const target = e.target as HTMLElement;
    if (!target) return;
    // 阻断默认行为（a 跳转等），保持预览稳定
    e.preventDefault();
    e.stopPropagation();
    const rootEl = (target.closest?.('[data-ai-section-root]') as HTMLElement | null)
        || (target.closest?.('[data-ai-section]') as HTMLElement | null);
    if (!rootEl) {
        ElMessage.warning('该位置不在组件区块内（可让 AI 调整一轮后重试，区块标记随重渲染生成）');
        return;
    }
    const sectionId = rootEl.getAttribute('data-ai-section-root') || rootEl.getAttribute('data-ai-section') || '';
    if (!sectionId) {
        ElMessage.warning('该区块缺少标记，无法选中');
        return;
    }
    state.selectedSection = { sectionId, elementHint: buildElementHint(target, rootEl) };
    markSelectedSection();
    ElMessage.success(`已选中区块「${sectionId}」，在右侧对话中描述修改需求`);
};

/**
 * 元素语义提示：用户点选区块时命中的具体元素描述（如 标题「散养土鸡蛋」）
 *
 * 点中区块根本身时无提示（需求针对整个区块）
 */
const buildElementHint = (target: HTMLElement, rootEl: HTMLElement): string => {
    if (target === rootEl) return '';
    const tag = (target.tagName || '').toLowerCase();
    const text = (target.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 20);
    const nameMap: Record<string, string> = {
        h1: '标题', h2: '标题', h3: '标题', h4: '标题', h5: '标题', h6: '标题',
        p: '段落', a: '链接', button: '按钮', img: '图片', span: '文本', li: '列表项'
    };
    const label = nameMap[tag] || tag;
    return text ? `${label}「${text}」` : label;
};

/**
 * 在 iframe 中给已锁定区块的根元素加持续高亮（跨 iframe 重载后重新标记）
 */
const markSelectedSection = () => {
    const doc = aiPreviewFrameRef.value?.contentDocument;
    if (!doc) return;
    doc.querySelectorAll('.' + SECTION_SELECTED_CLASS).forEach((el) => el.classList.remove(SECTION_SELECTED_CLASS));
    if (!state.selectedSection) return;
    const root = doc.querySelector(`[data-ai-section-root="${CSS.escape(state.selectedSection.sectionId)}"]`);
    root?.classList.add(SECTION_SELECTED_CLASS);
};

/**
 * 清除选区锁定（标签 ✕ / ESC）
 */
const clearSelectedSection = () => {
    state.selectedSection = null;
    markSelectedSection();
};

/**
 * 换图对话框应用成功回调：
 * 刷新预览 iframe；槽位图重渲染了模板文件需同步刷新文件树
 */
const onPickApplied = (isPreviewOnly: boolean) => {
    refreshAiPreview();
    if (!isPreviewOnly) {
        loadFileTree();
    }
};

/**
 * AI 每写完一个文件（SSE file 事件）的实时回调：刷新左侧预览
 *
 * 会话编辑视图下若预览入口尚未初始化（新会话首个页面还没写盘），
 * 先刷新会话文件树并初始化预览入口——首个可路由 HTML 落地的瞬间预览自动出现；
 * 入口就绪后仅刷新预览键（key 变化重载 iframe），但若落盘的是下拉选项之外的新
 * 可路由 HTML（设计稿逐页落盘 / 转化产物逐文件落盘），须重载会话文件树让下拉
 * 选项同步长出来——树只在首个文件落地时加载一次，后续新页将永远不可选
 */
const onAiFileWritten = (path: string) => {
    if (state.sessionView) {
        if (!preview.entry) {
            loadSessionFileTree().then(() => initAiPreviewEntry());
            return;
        }
        // 树路径含模板目录前缀（如 xxx/index.html），与后端推送的模板内相对路径按后缀匹配
        const known = previewPageOptions.value.some((p) => p === path || p.endsWith('/' + path));
        if (isRoutableHtml(path) && !known) {
            loadSessionFileTree();
        }
    }
    preview.key = Date.now();
};

/**
 * AI 页面自动切换（SSE switch-file 事件）：调整对话中用户说"改首页某某问题"，
 * 后端在流式期间识别到 AI 正在处理的首个可路由 HTML 即推送该事件，
 * 实时预览立即切到目标页面（旧版本先行展示，文件写盘后经预览键自动重载新内容）
 *
 * 后端推送的是模板内相对路径（如 index.html），主编辑界面的预览选项含模板目录前缀
 * （如 my-company/index.html），按后缀匹配到实际选项再切换，与下拉框保持一致
 */
const onAiSwitchFile = (path: string) => {
    if (!isRoutableHtml(path)) return;
    const target = previewPageOptions.value.find((p: string) => p === path || p.endsWith('/' + path));
    if (target && target !== preview.entry) {
        preview.entry = target;
    }
};

/**
 * 预览当前模板：复用 AI 预览的 mock 渲染引擎（后端 /template/preview/{templateId}/**）
 *
 * 当前编辑的文件是可路由 HTML（非 _ 开头的布局/宏文件）时预览该文件，
 * 否则预览首页 index.html。
 */
const onPreview = () => {
    if (!state.loadedTemplateId) return;
    let entry = 'index.html';
    if (isRoutableHtml(state.currEditFile)) {
        entry = state.currEditFile;
    }
    const doOpen = () => window.open('/template/preview/' + encodeURIComponent(state.loadedTemplateId) + '/' + entry, '_blank');
    if (checkDirty()) {
        // 预览渲染的是服务器已保存内容，未保存修改不会出现，先给用户选择
        ElMessageBox.confirm('当前文件有未保存的修改，预览展示的是已保存的内容。可先保存再预览。', '未保存的修改', {
            confirmButtonText: '保存并预览',
            cancelButtonText: '仍要预览',
            distinguishCancelAndClose: true,
            type: 'warning',
        }).then(() => doSave().then(doOpen))
        .catch((action: string) => {
            if (action === 'cancel') doOpen();
        });
    } else {
        doOpen();
    }
};

/**
 * 打开 AI 调整抽屉（adjust 模式）
 *
 * 同一模板复用已有的调整型会话（templateId 匹配），没有则新建。
 * 调整型会话的 AI 输出直写正式模板目录（后端写前自动备份），支持按轮次回滚。
 */
const onOpenAiAdjust = async () => {
    if (!state.loadedTemplateId) return;
    state.aiMode = 'adjust';
    // 每次打开抽屉都从初始视图开始（会话编辑视图随上次关闭已重置）
    state.sessionView = false;
    try {
        const res = await aiApi.listSessions();
        // 按创建时间倒序（最近的在最前），默认选中也取第一个
        const adjustSessions = (res.data || [])
            .filter((s: any) => s.templateId === state.loadedTemplateId)
            .sort((a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime());
        if (adjustSessions.length > 0) {
            state.aiSessions = adjustSessions;
            state.currentAiSessionId = adjustSessions[0].sessionId;
            state.currentAiSession = adjustSessions[0];
        } else {
            // 尚无该模板的调整型会话：创建一个（requirement 为空，后续对话即调整需求）
            const created = await aiApi.createSession({ templateId: state.loadedTemplateId });
            if (!created.data) {
                ElMessage.error(created.msg || '创建调整会话失败');
                return;
            }
            state.aiSessions = [created.data];
            state.currentAiSessionId = created.data.sessionId;
            state.currentAiSession = created.data;
        }
        state.aiDrawerVisible = true;
        // 初始化右侧实时预览的入口页（当前编辑页优先）
        initAiPreviewEntry();
    } catch (e: any) {
        ElMessage.error(e?.message || '加载 AI 会话失败');
    }
};

/**
 * 打开 AI 新建模板对话框：字段重置与方向清单拉取由组件内部 open() 处理
 */
const onOpenAiCreate = () => {
    createDialogRef.value?.open();
};

/**
 * 新建模板对话框创建会话成功回调：切换到生成模式进入会话编辑视图（左预览右对话），
 * 生成过程中 AI 每写完一个文件实时刷新预览；
 * 抽屉渲染后自动发送首条消息（aiChat 内部会等待会话历史加载完成）
 */
const onCreateDialogCreated = async (session: any, firstMessage: string) => {
    state.aiMode = 'generate';
    state.sessionView = true;
    try {
        const listRes = await aiApi.listSessions();
        state.aiSessions = (listRes.data || []).filter((s: any) => !s.templateId);
    } catch (e) {
        // 会话列表刷新失败不阻断进入抽屉（下拉列表稍旧，下次打开会重拉）
    }
    state.currentAiSessionId = session.sessionId;
    state.currentAiSession = session;
    state.aiDrawerVisible = true;
    // 初始化会话文件树与预览入口（新会话尚无文件，首个页面写盘后预览自动出现）
    loadSessionFileTree().then(() => initAiPreviewEntry());
    nextTick(() => {
        aiChatRef.value?.autoSend(firstMessage);
    });
};

/**
 * 打开历史生成会话：直接进入会话编辑视图恢复会话（不自动发送消息）；
 * 未应用的可继续对话打磨，已应用的只读回看（预览照常显示）
 */
const onOpenHistorySession = (row: any, sessions: any[]) => {
    state.aiMode = 'generate';
    state.sessionView = true;
    state.aiSessions = sessions;
    state.currentAiSessionId = row.sessionId;
    state.currentAiSession = row;
    state.aiDrawerVisible = true;
    loadSessionFileTree().then(() => initAiPreviewEntry());
};

/**
 * 新建调整会话：为当前模板创建一个空白会话（历史会话仍可从下拉切回）
 */
const onNewAiSession = async () => {
    if (!state.loadedTemplateId) return;
    state.creatingAiSession = true;
    try {
        const res = await aiApi.createSession({ templateId: state.loadedTemplateId });
        if (!res.data) {
            ElMessage.error(res.msg || '创建会话失败');
            return;
        }
        state.aiSessions.unshift(res.data);
        state.currentAiSessionId = res.data.sessionId;
        state.currentAiSession = res.data;
    } catch (e: any) {
        ElMessage.error(e?.message || '创建会话失败');
    } finally {
        state.creatingAiSession = false;
    }
};

/**
 * 切换 AI 会话
 *
 * 会话编辑视图下切到其他生成会话：左侧预览随之切到新会话的工作目录；
 * 已应用的会话保持编辑视图只读回看（sessionReadonly）
 */
const onSelectAiSession = (sessionId: string) => {
    const session = state.aiSessions.find((s: any) => s.sessionId === sessionId);
    state.currentAiSession = session || null;
    // 切换会话即切换预览工作目录：旧会话锁定的选区对新会话无意义，一并清除
    if (state.sectionSelectMode || state.selectedSection) {
        state.sectionSelectMode = false;
        clearSelectedSection();
    }
    if (!state.sessionView) return;
    if (!session?.sessionId) {
        exitSessionView();
        return;
    }
    // 已应用会话同样保持编辑视图（sessionReadonly 只读回看，预览照常显示）
    loadSessionFileTree().then(() => initAiPreviewEntry());
};

/**
 * AI 写盘后联动：
 * - 会话编辑视图：AI 改的是会话工作目录 → 刷新会话文件树（预览页面下拉随之更新）
 * - 调整模式：AI 直写正式模板目录 → 刷新主页面文件树，当前编辑的文件被改过则重新加载内容
 */
const onAiFilesChanged = () => {
    if (state.sessionView) {
        loadSessionFileTree();
        return;
    }
    loadFileTree();
    if (!state.currEditFile) return;
    loadFileContent(state.currEditFile).then((res: any) => {
        // 仅当服务器内容与本地保存基线不一致（即 AI 确实改了当前文件）时刷新编辑器
        if (normalizeEol(res.data || '') !== normalizeEol(state.savedContent || '')) {
            // 本地有未保存修改时交给用户选择，避免 AI 版本静默覆盖本地修改
            if (checkDirty()) {
                ElMessageBox.confirm('AI 修改了当前文件，而您有未保存的本地修改。加载 AI 版本将丢弃本地修改，是否继续？', '文件已被 AI 修改', {
                    confirmButtonText: '加载 AI 版本',
                    cancelButtonText: '保留本地修改',
                    type: 'warning',
                }).then(() => {
                    state.content = res.data;
                    state.savedContent = res.data;
                    checkDirty();
                    ElMessage.info('编辑器内容已刷新为 AI 版本');
                }).catch(() => {
                    ElMessage.info('已保留本地修改；如需查看 AI 版本，请从文件树重新打开该文件');
                });
                return;
            }
            state.content = res.data;
            state.savedContent = res.data;
            checkDirty();
            ElMessage.info('AI 已更新当前文件，编辑器内容已刷新');
        }
    }).catch(() => {
        // 文件可能被 AI 删除
        state.currEditFile = '';
        state.content = '';
        state.savedContent = '';
        checkDirty();
    });
};

// ==================== 会话编辑视图（抽屉内，生成型会话应用前查看打磨） ====================

/**
 * 加载会话工作目录文件树（仅用于抽屉左侧预览的页面下拉，与主编辑界面无关）；
 * 树数据由 useTemplateFileTree 托管，此处仅桥接当前会话 ID
 */
const loadSessionFileTree = () => loadSession(state.currentAiSession?.sessionId);

/**
 * 退出会话编辑视图：抽屉回到 aiChat 独占全宽（无左侧预览）
 */
const exitSessionView = () => {
    state.sessionView = false;
    clearSession();
    // 选区锁定随会话上下文一并清除
    state.sectionSelectMode = false;
    clearSelectedSection();
};

/**
 * 进入会话编辑视图（aiChat「编辑文件」入口，兜底路径）：抽屉切换为「编辑 AI 模板」——左预览右对话
 *
 * generate 会话现在创建/恢复时即直接进入编辑视图，本入口仅在极端情况下（sessionView 被重置）可达；
 * 纯抽屉内状态切换，主编辑界面（正式模板的文件树/编辑器/按钮）不受任何影响；
 * 关闭抽屉即回到主界面原样，想再次进入走「AI 新建模板 → 历史生成记录」
 */
const onEditSessionFiles = async () => {
    const session = state.currentAiSession;
    if (!session?.sessionId || !session.templateName) return;
    if (session.status === 'applied') {
        ElMessage.warning('该会话已应用，如需继续调整请应用后在正式模板上使用「AI 调整」');
        return;
    }
    state.sessionView = true;
    await loadSessionFileTree();
    // 会话文件树就绪后初始化左侧预览入口
    initAiPreviewEntry();
};

/**
 * 生成型会话应用模板成功：无缝切换
 *
 * 应用后关闭 AI 抽屉（会话编辑视图一并重置），载入应用后的正式模板
 * （templateId 来自后端 ApplyResult）；主界面如有未保存修改先确认再切换；
 * 后续如需 AI 继续调整，走正式模板的「AI 调整」（新建调整型会话）
 */
const onAiTemplateApplied = (templateId?: string) => {
    // 会话标记为已应用（若抽屉内还有引用，标签/输入禁用即时生效）
    if (state.currentAiSession) state.currentAiSession.status = 'applied';
    state.aiDrawerVisible = false;
    state.sessionView = false;
    clearSession();
    // 刷新模板列表并选中应用后的模板（preferId 缺省回落到当前激活模板）
    const doLoad = () => loadTemplateList(templateId || undefined);
    if (checkDirty()) {
        confirmDiscard().then(doLoad).catch(() => {});
    } else {
        doLoad();
    }
};

/**
 * 统一换行符为 LF 后再比较：CodeMirror 加载内容时会把 CRLF 规范化为 LF 并回写 v-model，
 * 直接比较原始字符串会导致 CRLF 文件刚打开就被误判为“有未保存的修改”
 */
const normalizeEol = (s: string) => (s || '').replace(/\r\n?/g, '\n');

const checkDirty = () => {
    state.isDirty = state.currEditFile != '' && normalizeEol(state.content) !== normalizeEol(state.savedContent);
    return state.isDirty;
};

/**
 * 加载模板列表并选中目标模板
 * @param preferId 优先选中的模板 ID（如应用后的新模板）；缺省选中当前激活模板
 */
const loadTemplateList = (preferId?: string) => {
    templateApi.getTemplateList().then((res: any) => {
        state.templateList = res.data || [];
        // 优先 preferId，缺省选中当前激活模板
        const preferred = preferId ? state.templateList.find((item: any) => item.id === preferId) : null;
        const active = state.templateList.find((item: any) => item.active);
        const target = preferred || active || state.templateList[0];
        if (target) {
            state.templateId = target.id;
            state.loadedTemplateId = target.id;
            state.uploadParam.templateId = target.id;
        }
        loadFileTree(true);
    })
}

/**
 * 加载文件树（主编辑界面只加载正式模板目录）
 * @param openDefault  是否同时默认打开 index.html（仅首次进入/切换模板时传 true，
 *                     AI 写盘后的树刷新不能重置用户正在编辑的文件）
 * @returns 加载 Promise（供调用方在树就绪后初始化预览入口）
 */
const loadFileTree = (openDefault = false) => {
    return loadTemplateTree(state.loadedTemplateId || undefined, openDefault ? openDefaultFile : undefined);
};

/**
 * 读取文件内容（正式模板目录）
 * filePath 约定与文件树一致：以模板目录名开头
 */
const loadFileContent = (filePath: string) => {
    return templateApi.getTemplateFile(filePath, state.loadedTemplateId || undefined);
};

/**
 * 在文件树中查找 index.html 并加载到编辑器（含模板目录前缀，如 xjd2022/index.html）
 */
const openDefaultFile = () => {
    const node = findIndexNode(tree.data as any[]);
    if (node) {
        state.currEditFile = node.filePath;
        loadFileContent(node.filePath).then((res: any) => {
            state.content = res.data;
            state.savedContent = res.data;
            checkDirty();
            // 树中高亮选中该节点
            nextTick(() => {
                treeTable.value?.setCurrentKey(node.filePath);
            });
        }).catch(() => {
            // 读取失败回退为空选状态
            state.currEditFile = '';
        });
    }
};

const doSave = () => {
    // 还原文件原有换行风格：编辑器内统一为 LF，若原文件为 CRLF 则保存时还原，避免整文件换行符被静默改写
    let contentToSave = state.content;
    if (state.savedContent.indexOf('\r\n') >= 0) {
        contentToSave = normalizeEol(state.content).replace(/\n/g, '\r\n');
    }
    // 主编辑界面只保存正式模板目录
    return templateApi.saveTemplateFile({
        filePath: state.currEditFile,
        fileContent: contentToSave,
        templateId: state.loadedTemplateId
    }).then(() => {
        state.savedContent = contentToSave;
        checkDirty();
        ElMessage.success("保存成功");
    }).catch((res: any) => {
        ElMessage.error(res?.message || "保存失败");
        return Promise.reject(res);
    });
};

/**
 * 未保存修改的三态确认
 * resolve：可以继续切换（已保存或用户放弃修改）
 * reject：用户取消操作（或保存失败）
 */
const confirmDiscard = () => {
    return ElMessageBox.confirm('当前文件有未保存的修改，是否保存后继续？', '未保存的修改', {
        confirmButtonText: '保 存',
        cancelButtonText: '放弃修改',
        distinguishCancelAndClose: true,
        type: 'warning',
    }).then(() => doSave())
    .catch((action: string) => {
        if (action === 'cancel') {
            // 用户选择放弃修改，继续切换
            return;
        }
        // 关闭弹窗（X/ESC）或保存失败，取消操作
        return Promise.reject(action);
    });
};

const onSaveFile = () => {
    if(state.currEditFile == '') {
        ElMessage.warning("请选择需要编辑的文件");
        return;
    }
    if(state.content == null || state.content == '') {
        ElMessage.warning("文件内容不能为空");
        return;
    }
    doSave();
};

const onDelFile = () => {
    if(state.currEditFile == '') {
        ElMessage.warning("请选择需要删除的文件");
        return;
    }

    ElMessageBox.confirm('此操作将永久删除['+state.currEditFile+']文件, 是否继续?', '提示', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
    }).then(() => {
        templateApi.delTemplateFile(state.currEditFile, state.loadedTemplateId || undefined).then(() => {
            ElMessage.success("删除成功");
            state.content = '';
            state.savedContent = '';
            state.currEditFile = '';
            checkDirty();
            loadFileTree();
        }).catch((res) => {
            ElMessage.error(res.message);
        })
    })
    .catch(() => {});
}

// ==================== 模板图片预览 + AI 修图 ====================

/** 图片文件后缀（文件树点选这些文件时打开图片预览而非代码编辑器） */
const IMAGE_SUFFIXES = ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.ico', '.svg'];

const isImageFile = (filePath: string) => {
    if (!filePath) return false;
    const lower = filePath.toLowerCase();
    return IMAGE_SUFFIXES.some((s) => lower.endsWith(s));
};

const onNodeClick = (node: any) => {
    const switchToFile = () => {
        // 用 sortNum 区分目录(0)与文件(1)：空目录（如仅剩 .properties 被过滤的 i18n 目录）children 为 null，不能按 children 判断
        if(node.sortNum === 0) {
            state.uploadParam.dirName = node.filePath;
            state.currEditFile = '';
            state.content = '';
            state.savedContent = '';
            workbenchVisible.value = false;
            checkDirty();
        }else if (isImageFile(node.filePath)) {
            // 图片文件：清空代码编辑状态，切换为图片工作台（左原图/右生成图对比，AI 修图/恢复原图）
            state.currEditFile = '';
            state.content = '';
            state.savedContent = '';
            checkDirty();
            workbenchFile.value = node.filePath;
            workbenchVisible.value = true;
        } else {
            workbenchVisible.value = false;
            state.currEditFile = node.filePath;
            loadFileContent(node.filePath).then((res: any) => {
                state.content = res.data;
                state.savedContent = res.data;
                checkDirty();
            }).catch((res) => {
                ElMessage.error(res.message);
            })
        }
    };

    if(checkDirty()) {
        confirmDiscard().then(switchToFile).catch(() => {});
    } else {
        switchToFile();
    }
}

const onTemplateChange = (val: string) => {
    const prevTemplateId = state.loadedTemplateId;
    const doSwitch = () => {
        state.loadedTemplateId = val;
        state.uploadParam.templateId = val;
        state.currEditFile = '';
        state.content = '';
        state.savedContent = '';
        state.uploadParam.dirName = '';
        checkDirty();
        workbenchVisible.value = false;
        loadFileTree(true);
    };

    if(checkDirty()) {
        confirmDiscard().then(doSwitch).catch(() => {
            // 用户取消切换，还原下拉选中项
            state.templateId = prevTemplateId;
        });
    } else {
        doSwitch();
    }
}

const uploadSuccess = () => {
    ElMessage.success("上传成功（" + (state.uploadParam.dirName || '模板根目录') + "）");
    loadFileTree();
}
const onHandleUploadError = () => {
    ElMessage.error("上传失败");
}
const onBeforeUpload = () => {
    if (!state.uploadParam.dirName) {
        // 未选择目录时默认上传到模板根目录（文件树顶层节点即模板目录），上传前置要求不再导致必失败
        const root = ((tree.data as any[]) || [])[0];
        if (!root?.filePath) {
            ElMessage.warning("文件树尚未加载完成，请稍后重试");
            return false;
        }
        state.uploadParam.dirName = root.filePath;
    }
}
const onChange = (value: string) => {
    state.content = value;
    checkDirty();
}

const onBeforeUnload = (e: BeforeUnloadEvent) => {
    if(checkDirty()) {
        e.preventDefault();
        e.returnValue = '';
    }
};

// 路由离开拦截：保存 / 放弃修改 / 留在本页
onBeforeRouteLeave((to, from, next) => {
    if(!checkDirty()) {
        next();
        return;
    }
    confirmDiscard().then(() => next()).catch(() => next(false));
});

onMounted(() => {
    loadTemplateList();
    // 高度自适应：初始计算 + 窗口变化时重算（编辑器/文件树等高，页面不整页滚动）
    updateEditorHeight();
    // 事件驱动兜底（无轮询）：冷启动（F5/直接 URL）时顶栏/标签栏（tagsview 异步加载）尚未定型，
    // el-main 的高度会经历一次真实变化（setMainHeight 57→94px）。观察 el-main：其高度由 CSS 决定、
    // 不依赖本页的 clientHeight（非循环依赖），在布局定型变化时触发 updateEditorHeight 重读 top，
    // 覆盖"首帧 top 偏大算出 400px 下限、之后无人重算"的空窗。
    // （top 本身是位置变化，无 DOM 事件可捕获；布局容器的高度变化才是可靠的事件源）
    if (typeof ResizeObserver !== 'undefined') {
        editorHeightObserver = new ResizeObserver(updateEditorHeight);
        const mainEl = document.querySelector('.layout-main');
        if (mainEl) editorHeightObserver.observe(mainEl);
    }
    window.addEventListener('resize', updateEditorHeight);
    window.addEventListener('beforeunload', onBeforeUnload);
    // ESC 清除选区锁定（选区模式内点错区块也可直接再点别的区块覆盖，ESC 是快速取消入口）
    window.addEventListener('keydown', onSectionEscKey);
    // Ctrl/Cmd + S 快捷保存
    window.addEventListener('keydown', onSaveShortcut);
});

// AI 抽屉关闭前确认：后台有 AI 任务在跑时提示"任务将继续后台处理"——关闭只断开
// 续看连接，不中断任务（停止只能显式点击聊天内停止按钮）；重开抽屉自动续看思考与进度
const onAiDrawerBeforeClose = (done: () => void) => {
    if (aiChatRef.value?.isChatting?.()) {
        ElMessageBox.confirm(
            'AI 任务仍在运行，关闭后任务将在后台继续处理，进度与思考过程会保留，可随时重新打开继续查看。是否关闭？',
            '任务后台运行中',
            { confirmButtonText: '关 闭', cancelButtonText: '继 续 查 看', type: 'info' }
        ).then(() => done()).catch(() => {});
        return;
    }
    done();
};

// AI 抽屉关闭：退出换图/选区模式 + 停止生图轮询 + 关闭换图操作窗 + 重置会话编辑视图
// （抽屉是完全独立的临时工作台：关闭即整体关闭，主编辑界面不受任何影响；
//   想再次进入走「AI 新建模板 → 历史生成记录」）
watch(() => state.aiDrawerVisible, (visible) => {
    if (!visible) {
        state.imagePickMode = false;
        state.sectionSelectMode = false;
        // 选区锁定随抽屉上下文一并清除，避免换模板重开抽屉后残留旧区块标签
        clearSelectedSection();
        imagePickDialogRef.value?.close();
        state.sessionView = false;
        clearSession();
    }
});

// 图片工作台关闭的轮询停止已下沉到 ImageWorkbench 组件内部

/** ESC 清除选区锁定 */
const onSectionEscKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && state.selectedSection) {
        clearSelectedSection();
    }
};

/**
 * 编辑器高度自适应：视口高度 - 编辑区起点到视口顶的距离（含布局头部、tagsview、工具栏等占位） - 底部留白。
 * 以 DOM 实测代替写死高度，保证左树卡片与右编辑器等高、页面整体不出现整页滚动；
 * 窄屏（<768px）左右栏堆叠时退回固定高度，避免编辑器被压得过高过矮。
 */
const updateEditorHeight = () => {
    const rowEl = editRowRef.value?.$el || editRowRef.value;
    if (!rowEl) return;
    const viewportW = document.documentElement.clientWidth;
    if (viewportW < 768) {
        state.clientHeight = '600px';
        return;
    }
    const top = rowEl.getBoundingClientRect().top;
    const viewportH = document.documentElement.clientHeight;
    // 底部留白覆盖：el-col 的 mb20 + 页面级 el-card body 的 padding（合计约 40px）+ 少量呼吸空间
    const height = viewportH - top - 48;
    state.clientHeight = Math.max(400, height) + 'px';
};

/** Ctrl/Cmd + S 快捷保存：AI 抽屉与对话框打开时忽略（全屏工作台里误触发主编辑器保存） */
const onSaveShortcut = (e: KeyboardEvent) => {
    if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 's') return;
    e.preventDefault();
    if (state.aiDrawerVisible || createDialogVisible.value || pickDialogVisible.value) return;
    if (!state.currEditFile) return;
    onSaveFile();
};

onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload);
    window.removeEventListener('keydown', onSectionEscKey);
    window.removeEventListener('keydown', onSaveShortcut);
    window.removeEventListener('resize', updateEditorHeight);
    if (editorHeightObserver) {
        editorHeightObserver.disconnect();
        editorHeightObserver = null;
    }
});

// keep-alive 缓存恢复时重算高度：从其他菜单切回本页走的是 onActivated 而非 onMounted，
// 不重算会沿用旧高度（窗口尺寸/布局已变时出现半高留白）
onActivated(() => {
    updateEditorHeight();
});
</script>

<style lang="scss" scoped>
.toolbar {
    // 吸顶：页面滚动时工具栏（含保存/删除）始终固定在顶部
    position: sticky;
    top: 0;
    z-index: 20;
    background: var(--el-bg-color);
    padding: 8px 0 0;

    .toolbar-row {
        width: 100%;
    }
}
.toolbar-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0;
}
// 文件树卡片：高度与右侧编辑器一致（内联注入），flex 让树占满 header 之外的剩余空间
.tree-card {
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
        min-height: 0;
        overflow: hidden;
    }
}
.tree-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
}
.drawer-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    width: 100%;

    .drawer-title {
        font-weight: 600;
    }
}
// 抽屉主体撑满高度（el-drawer teleport 到 body，根元素带本组件 scoped 属性，:deep 可穿透内部）
.ai-template-drawer {
    :deep(.el-drawer__body) {
        display: flex;
        flex-direction: column;
        overflow: hidden;
    }
}
// 历史生成记录列表样式随组件下沉到 CreateTemplateDialog.vue
// AI 抽屉主体：调整型左右分栏（对话 + 实时预览）
.ai-drawer-body {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;

    &.split {
        flex-direction: row;
        gap: 12px;
    }

    // 对话 30% / 预览 70%：预览是主角，对话仅需满足输入与消息流展示
    .ai-chat-col {
        display: flex;
        flex-direction: column;
        flex: 3;
        min-width: 0;
        // generate 模式下 drawer-body 为纵向 flex，flex 项目默认 min-height:auto
        // 不会收缩到内容以下，导致内容超出被外层 overflow:hidden 裁掉、
        // chat-area 的 overflow-y:auto 失效（split 横向模式下无影响）
        min-height: 0;

        // 收缩态：变成一条窄竖条，显示切换按钮 + 竖排 AI 标签
        &.collapsed {
            flex: 0 0 56px;
            max-width: 56px;
            border-left: 1px solid var(--el-border-color-lighter);

            .chat-collapse-bar {
                flex-direction: column;
                justify-content: flex-start;
                align-items: center;
                padding: 16px 0 12px;
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

        // 顶部收缩按钮条（展开态：靠右显示一条分隔线下拉小按钮）
        .chat-collapse-bar {
            display: flex;
            align-items: center;
            justify-content: flex-end;
            padding: 2px 8px 6px;
            border-bottom: 1px solid var(--el-border-color-lighter);
            margin-bottom: 8px;
        }

        .ai-chat-col-inner {
            flex: 1;
            min-height: 0;
            display: flex;
            flex-direction: column;
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

        // aiChat 根元素撑满列（组件内部 height:100% 在 flex 列中不稳）
        .ai-chat-col-inner > :deep(.ai-chat-panel) {
            flex: 1;
            min-height: 0;
        }
    }

    .ai-preview-col {
        display: flex;
        flex-direction: column;
        flex: 7;
        min-width: 0;
        border: 1px solid var(--el-border-color-lighter);
        border-radius: 6px;
        overflow: hidden;

        // 对话框收缩时，预览列完全占满剩余空间
        &.chat-collapsed {
            flex: 1;
        }

        .preview-toolbar {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px;
            border-bottom: 1px solid var(--el-border-color-lighter);

            .el-select {
                flex: 1;
            }
        }

        .preview-frame-wrap {
            flex: 1;
            min-height: 0;
            position: relative;

            .preview-frame {
                width: 100%;
                height: 100%;
                border: 0;
                background: #fff;
                display: block;
            }

            // 预览空白占位（新会话生成中 / 无可路由 HTML）
            .preview-empty {
                width: 100%;
                height: 100%;
                display: flex;
                align-items: center;
                justify-content: center;
                background: var(--el-fill-color-lighter);
            }

        }
    }
}

// ===== 点选换图操作窗（样式随组件下沉到 ImagePickDialog.vue） =====
// ===== 模板图片工作台（样式随组件下沉到 ImageWorkbench.vue） =====
.dirty-tip {
    color: #e6a23c;
    font-size: 12px;
    margin-left: 10px;
}
</style>
