<template>
<div class="container">
    <el-card class="page-card">
        <!-- 主体两列（原型 v3）：左 = 统一操作对象区（对象卡片 + 文件树，全高，随工作对象切换）；
             右 = 顶部单行视图栏（tab + 按钮组）+ 双 tab 内容区（AI 工作台 / 手动编辑，围绕同一选中的工作对象）。
             列区显式注入高度：左右列经 stretch 拉满，右侧内部 height:100% 可靠解析 -->
        <div class="main-columns" :style="mainColumnsStyle">
            <div class="left-col" :class="{ collapsed: panelCollapsed }">
                <!-- 左列内容（整体收展）：统一操作对象卡片 + 文件树，收起时随整列一起隐藏。
                     对象卡片点击弹三页签选择器；三态 = 默认（正式模板，蓝）/
                     .draft（草稿·未应用，紫）/ .applied（已应用回看，绿） -->
                <div v-show="!panelCollapsed" class="left-col-inner">
                    <button class="obj-card" :class="objCardState" title="点击选择工作对象（正式模板 / 生成会话）"
                            @click="dialogVisible = true">
                        <span class="obj-line1">
                            <el-icon class="s-ico"><ele-FolderOpened /></el-icon>
                            <span class="s-name">{{ objCardName }}</span>
                            <span class="s-tag">{{ objCardTag }}</span>
                            <el-icon class="s-caret"><ele-ArrowDown /></el-icon>
                        </span>
                        <span class="obj-sub">{{ objCardSub }}</span>
                    </button>
                    <!-- 文件树行（工作对象经对象卡片弹窗选择后此处树随之切换） -->
                    <div class="tree-row">
                        <FileTreePanel ref="treePanelRef"
                                       :current="currentObject" :template-list="state.templateList" :tree="tree"
                                       @node-click="onTreePanelNodeClick" @refresh-tree="onTreePanelRefreshTree" />
                    </div>
                </div>
                <!-- 把手竖条（左列右缘，纵贯整列全高）：展开态=收起按钮，
                     收起态=展开按钮+竖排「文件」标签（与代码列收起交互同构） -->
                <div class="panel-side-bar">
                    <el-button size="small" text :title="panelCollapsed ? '展开对象区' : '收起对象区'"
                               @click="panelCollapsed = !panelCollapsed">
                        <el-icon :size="14">
                            <ele-DArrowRight v-if="panelCollapsed" />
                            <ele-DArrowLeft v-else />
                        </el-icon>
                    </el-button>
                    <span v-if="panelCollapsed" class="collapsed-label">文件</span>
                </div>
            </div>
            <!-- 右列（原型 v3）：顶部单行视图栏 + 双 tab 内容区 -->
            <div ref="rightColRef" class="right-col">
                <!-- 单行视图栏（右列内部，与原型一致）：左侧视图 tab，右侧按钮组按视图切换（同位互换，
                     两视图编辑起点恒等）；sticky 吸顶：窄屏堆叠页面滚动时视图切换恒可达 -->
                <div class="view-tabs">
                    <span class="view-tab" :class="{ active: currentView === 'ai' }" @click="currentView = 'ai'">
                        <el-icon><ele-MagicStick /></el-icon>AI 工作台
                    </span>
                    <span class="view-tab" :class="{ active: currentView === 'edit' }" @click="currentView = 'edit'">
                        <el-icon><ele-Edit /></el-icon>手动编辑
                    </span>
                    <!-- 手动编辑工具条 -->
                    <div v-if="currentView === 'edit'" class="toolbar-actions">
                        <el-upload
                            class="upload-btn"
                            :action="uploadAction"
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
                        <el-button type="primary" @click="onSaveFile" :disabled="!state.currEditFile">保 存</el-button>
                        <el-button type="danger" @click="onDelFile" :disabled="!state.currEditFile">删 除</el-button>
                        <span v-if="state.isDirty" class="dirty-tip">● 有未保存的修改</span>
                    </div>
                    <!-- AI 工作台工具条：按钮逻辑来自 aiWorkbench 的 defineExpose（模板 ref 访问自动解包） -->
                    <div v-else class="toolbar-actions">
                        <el-button type="warning" plain @click="aiWorkbenchRef?.openCreateDialog()">
                            <el-icon><ele-MagicStick /></el-icon>新建模板
                        </el-button>
                        <el-button v-if="aiWorkbenchRef?.canRollback" type="danger" plain :loading="aiWorkbenchRef?.rollingBack"
                                   @click="aiWorkbenchRef?.onRollback()">
                            <el-icon><ele-RefreshLeft /></el-icon>回滚最近
                        </el-button>
                        <el-button v-if="aiWorkbenchRef?.showApply" type="success" :loading="aiWorkbenchRef?.applying"
                                   @click="aiWorkbenchRef?.onApplyTemplate()">
                            <el-icon><ele-Check /></el-icon>应用模板
                        </el-button>
                        <el-button v-if="aiWorkbenchRef?.showEditApplied" type="primary" plain
                                   title="把应用后的正式模板加载到主编辑视图继续修改"
                                   @click="aiWorkbenchRef?.onEditApplied()">
                            <el-icon><ele-EditPen /></el-icon>编辑此模板
                        </el-button>
                    </div>
                </div>
                <!-- 两 tab 同容器 v-show 保活内容区（恒在 DOM，高度实测容器，两 tab 起点恒等；
                     v-show 保活：AI 会话状态、编辑器内容、树选中态切走再切回不丢） -->
                <div ref="tabPanesRef" class="tab-panes">
                    <!-- 手动编辑视图：内联预览 | 代码编辑（可收起）两列（文件树已上提左侧面板） -->
                    <div v-show="currentView === 'edit'" class="edit-columns">
                        <div class="edit-col-preview">
                            <!-- 内联预览（中列）：预览已保存内容，保存成功后自动刷新 -->
                            <TemplatePreviewPanel v-model:entry="manualPreview.entry"
                                                  :page-options="manualPreviewOptions" :url="manualPreviewUrl"
                                                  :empty-tip="manualPreviewTip" v-model:viewport="manualViewport"
                                                  @refresh="refreshManualPreview" />
                        </div>
                        <div class="edit-col-mid" :class="{ collapsed: midCollapsed, 'editor-fullscreen': editorFullscreen }">
                            <!-- 侧边把手条（贴右缘竖条）：展开态=收起+全屏按钮，收起态=展开按钮+竖排「代码」，全屏态=退出全屏 -->
                            <div class="mid-side-bar">
                                <el-button v-if="!editorFullscreen" size="small" text
                                           :title="midCollapsed ? '展开代码编辑' : '收起代码编辑'"
                                           @click="midCollapsed = !midCollapsed">
                                    <el-icon :size="14">
                                        <ele-DArrowLeft v-if="midCollapsed" />
                                        <ele-DArrowRight v-else />
                                    </el-icon>
                                </el-button>
                                <el-button size="small" text :title="editorFullscreen ? '退出全屏' : '编辑器全屏'"
                                           @click="toggleEditorFullscreen">
                                    <el-icon :size="14"><ele-FullScreen /></el-icon>
                                </el-button>
                                <span v-if="midCollapsed && !editorFullscreen" class="collapsed-label">代码</span>
                            </div>
                            <div class="edit-col-mid-inner" v-show="!midCollapsed || editorFullscreen">
                                <!-- 图片工作台：文件树点选图片文件时覆盖代码编辑器（左原图 / 右生成结果对比，确认后应用） -->
                                <ImageWorkbench v-if="workbenchVisible" v-model:visible="workbenchVisible" :template-id="state.loadedTemplateId"
                                                :file-path="workbenchFile" :height="editorHeight" @refresh-tree="loadFileTree" />
                                <Codemirror
                                        v-else
                                        v-model="state.content"
                                        :style="{ height: editorHeight, width: '100%' }"
                                        :autofocus="true"
                                        @change="onChange"
                                        v-bind="$attrs"
                                        :extensions="extensions" />
                            </div>
                        </div>
                    </div>
                    <!-- AI 工作台视图（已去树化）：内联预览 | AI 对话 两列（文件树在左侧共享面板）。
                         共享树实例与加载回调注入；树高亮经 highlight-file 转发左面板 -->
                    <ai-workbench v-show="currentView === 'ai'" ref="aiWorkbenchRef" :template-id="state.loadedTemplateId"
                                  :template-list="state.templateList" :sessions="state.allSessions"
                                  :tree="tree" :load-template-tree="loadTemplateTreeForChild"
                                  :load-session-tree="loadSessionTreeForChild"
                                  :active="currentView === 'ai'"
                                  @files-changed="onWorkbenchFilesChanged"
                                  @edit-file="onEditAiFile" @applied="onAiTemplateApplied"
                                  @edit-applied="onEditAppliedTemplate"
                                  @sessions-changed="refreshSessions"
                                  @highlight-file="onHighlightFile"
                                  @session-opened="onSessionOpened" />
                </div>
            </div>
        </div>
        <!-- 工作对象选择器对话框（左列对象卡片触发，原版交互）：三页签选择，
             行点击选中不切 tab，行内按钮可指定落点视图 -->
        <WorkObjectDialog v-model:visible="dialogVisible"
                          :template-list="state.templateList" :sessions="state.allSessions"
                          :badge-ctx="aiWorkbenchRef?.getSessionBadgeCtx?.() || {}"
                          :current="currentObject"
                          @select="onDialogSelect" @create="onDialogCreate"
                          @apply="onDialogApply" @goto-template="onDialogGotoTemplate" />
    </el-card>
</div>
</template>

<script lang="ts" name="templateEdit" setup>
import { reactive, computed, onMounted, onActivated, onBeforeUnmount, ref, nextTick, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessageBox, ElMessage, ElNotification } from 'element-plus';
import { Local } from '/@/utils/storage';
import { TemplateApi } from '/@/api/template/index';
import { AiTemplateApi } from '/@/api/ai/index';

import ImageWorkbench from '/@/views/template/ImageWorkbench.vue';

import AiWorkbench from '/@/views/template/aiWorkbench.vue';
import WorkObjectDialog from '/@/views/template/WorkObjectDialog.vue';
import FileTreePanel from '/@/views/template/FileTreePanel.vue';

import TemplatePreviewPanel from '/@/views/template/TemplatePreviewPanel.vue';

import { useTemplateFileTree } from '/@/views/template/composables/useTemplateFileTree';

import { useAiPreview, isRoutableHtml } from '/@/views/template/composables/useAiPreview';
import { Codemirror } from "vue-codemirror";
import { html } from "@codemirror/lang-html";
import { javascript } from "@codemirror/lang-javascript";
import { css } from "@codemirror/lang-css";
import { search } from "@codemirror/search";
import { oneDark } from "@codemirror/theme-one-dark";

// 左侧文件树面板 ref（expose setTreeCurrentKey：树高亮控制）
const treePanelRef = ref();
// 工作对象选择器对话框显示状态（左列对象卡片触发）
const dialogVisible = ref(false);
// 右列容器（含顶部视图栏 + 内容区）：主体两列总高实测起点（恒在 DOM）
const rightColRef = ref();
// 右列内容区包裹层（视图栏下方，两 tab 同容器）：内容区高度实测容器（恒在 DOM，两 tab 起点恒等）
const tabPanesRef = ref();
// AI 工作台根：会话编排与聚焦分发目标
const aiWorkbenchRef = ref();
// 布局容器尺寸变化观察器（keep-alive 缓存页切回时 onMounted 不会重跑，靠 onActivated 兜底重算）
let editorHeightObserver: ResizeObserver | null = null;

const templateApi = TemplateApi();
const aiApi = AiTemplateApi();
// 视图切换：默认 AI 工作台（对齐原型），手动编辑 v-show 保活，切换不丢状态。
// 选工作对象不切 tab（对象选择在左列卡片弹窗），AI 工作台 / 手动编辑围绕同一对象
const currentView = ref<'edit' | 'ai'>('ai');
// 代码编辑列收起状态（收起后预览列吃满剩余空间）
const midCollapsed = ref(false);
// 左列收起态：对象卡片与文件树整体收为 38px 竖条（与代码列收起交互同构），展开恢复 280px
const panelCollapsed = ref(false);
/**
 * 当前工作对象（显式单一事实来源）：
 * - { kind: 'template', templateId }：正式模板（手动编辑=正式目录，AI=调整会话）
 * - { kind: 'session', session }：生成会话（AI=续聊/回看，手动编辑=会话工作目录草稿）
 */
const currentObject = ref<{ kind: 'template' | 'session'; templateId?: string; session?: any }>({ kind: 'template', templateId: '' });
// 编辑器全屏（fixed 覆盖视口；与收起互斥，切视图自动退出）
const editorFullscreen = ref(false);
// 编辑器高度：全屏时占满视口（把手条与内边距留 16px），否则用实测的内容区自适应高度。
// 注意区分两个高度（原型 v3：视图栏在右列内部）：clientHeight = 主体两列总高（含视图栏），
// editorZoneHeight = 视图栏下方内容区高度（编辑器/图片工作台共用），两者相差一个视图栏高度
const editorHeight = computed(() => (editorFullscreen.value ? 'calc(100vh - 16px)' : state.editorZoneHeight));
// 主体两列显式高度（窄屏堆叠时退回 auto，让左右列按各自内容高度堆叠）
const mainColumnsStyle = computed(() => (isNarrow.value ? undefined : { height: state.clientHeight }));
const toggleEditorFullscreen = () => {
    if (!editorFullscreen.value) midCollapsed.value = false;
    editorFullscreen.value = !editorFullscreen.value;
};
// 窄屏媒体查询：进入窄屏时默认收起中列与左侧工作面板（预览占主屏，与 AI 工作台对话列同一交互）
let narrowMq: MediaQueryList | null = null;
// 窄屏标记：列区高度注入退回 auto（堆叠布局按内容高），供 mainColumnsStyle 使用
const isNarrow = ref(false);
const onNarrowMqChange = () => {
    isNarrow.value = !!narrowMq?.matches;
    if (narrowMq?.matches) {
        midCollapsed.value = true;
        panelCollapsed.value = true;
    }
};
// 手动编辑预览视口档位
const manualViewport = ref<'desktop' | 'tablet' | 'mobile'>('desktop');
// 图片工作台状态（文件树点选图片文件打开；filePath 变化驱动组件内部重载原图）
const workbenchVisible = ref(false);
const workbenchFile = ref('');
const state = reactive({
    // 主体两列总高（含右列顶部视图栏）：mainColumnsStyle 高度注入用
    clientHeight: "600px",
    // 右列视图栏下方内容区高度：编辑器/图片工作台高度注入用（与 clientHeight 相差一个视图栏高度）
    editorZoneHeight: "600px",
    // 模板选择（可编辑非激活模板）
    templateList: [] as any[],
    loadedTemplateId: '',
    // 全部 AI 会话（调整 + 生成）：本组件统一持有与刷新（单一数据源），
    // 经 props 注入 AI 工作台（对话头下拉）与工作对象选择器对话框
    allSessions: [] as any[],
    currEditFile: "",
    content: '',
    // 最后一次保存/加载的内容，用于判断是否有未保存修改
    savedContent: '',
    isDirty: false,
    headers: {"Authorization": Local.get('token')},
    uploadParam: {
        dirName: '',
        templateId: ''
    }
});

/**
 * 按文件后缀切换语法高亮（state 定义在上方已初始化）：
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

// 文件树（单一共享实例：正式模板目录树 tree.data + AI 会话工作目录树 tree.sessionData，
// 加载与查找下沉到 composable）：左侧面板渲染、AI 工作台消费数据、手动编辑共用
const { tree, findIndexNode, load: loadTemplateTree, loadSession } = useTemplateFileTree();

// ==================== 手动编辑内联预览 ====================

// 内联预览（入口页面/刷新键/预览地址）：复用 useAiPreview（跟随当前工作对象）：
// 正式模板 → 正式模板路由；生成会话 → 会话预览路由（按 sessionId 定位工作目录）
const { preview: manualPreview, previewPageOptions: manualPreviewOptions, aiPreviewUrl: manualPreviewUrl,
        previewEmptyTip: manualPreviewTip, initEntry: initManualPreviewEntry, refresh: refreshManualPreview } = useAiPreview({
    getSessionView: () => currentObject.value.kind === 'session',
    getCurrentSession: () => (currentObject.value.kind === 'session' ? currentObject.value.session : null),
    getLoadedTemplateId: () => state.loadedTemplateId,
    getCurrEditFile: () => state.currEditFile,
    getTreeNodes: () => (currentObject.value.kind === 'session' ? tree.sessionData : tree.data) as any[],
    getSessionTreeNodes: () => tree.sessionData as any[]
});

/**
 * 上传目标地址（按当前工作对象分流）：
 * 生成会话 → 会话工作目录上传接口（sessionId 在 URL path，仅未应用可传）；
 * 正式模板 → 正式模板目录上传接口
 */
const uploadAction = computed(() => {
    const obj = currentObject.value;
    if (obj.kind === 'session' && obj.session?.sessionId) {
        return aiApi.sessionUploadUrl(obj.session.sessionId);
    }
    return import.meta.env.VITE_API_URL + "/admin/template/files/upload";
});

/**
 * 上传附加参数：会话分支仅 dirName（sessionId 已在 URL path，后端 uploadSessionFiles 只收 dirName + files）；
 * 正式模板分支带 templateId
 */
const uploadData = computed(() => {
    if (currentObject.value.kind === 'session') {
        return { dirName: state.uploadParam.dirName };
    }
    return { dirName: state.uploadParam.dirName, templateId: state.uploadParam.templateId };
});

/** 上传目标目录提示（按钮 tooltip）：未选目录时将兜底上传到模板根目录 */
const uploadTargetTip = computed(() =>
    state.uploadParam.dirName ? `将上传到目录：${state.uploadParam.dirName}` : '未选择目录，将上传到模板根目录');

// ==================== 工作对象（左侧常驻面板，对象切换唯一入口） ====================

/**
 * 刷新全部会话（单一数据源）：页面初始化、会话创建/应用成功等时机调用；
 * 子组件不自拉，经 sessions-changed 通知这里统一重拉
 */
const refreshSessions = () => {
    aiApi.listSessions().then((res: any) => {
        state.allSessions = res.data || [];
    }).catch(() => {
        // 拉取失败不阻断页面（面板展示空态，稍后刷新即重试）
    });
};

/** 重置手动编辑器状态（切工作对象时；未保存确认统一由调用方处理，避免二次弹窗） */
const resetEditorState = () => {
    state.currEditFile = '';
    state.content = '';
    state.savedContent = '';
    state.uploadParam.dirName = '';
    checkDirty();
    workbenchVisible.value = false;
    treePanelRef.value?.setTreeCurrentKey(null);
};

/**
 * 切换正式模板并重置编辑状态（不含未保存确认：确认统一由调用方处理，避免二次弹窗）
 */
const doSwitchTemplate = (val: string) => {
    state.loadedTemplateId = val;
    state.uploadParam.templateId = val;
    resetEditorState();
    loadFileTree(true);
};

/**
 * 左侧面板选中工作对象（唯一写入口，不切 tab：AI 工作台 / 手动编辑围绕同一对象）：
 * - 生成会话 → AI 工作台打开会话（未应用续聊 / 已应用只读回看），左侧树切会话工作目录；
 *   手动编辑 tab 可直接编辑草稿（文件 API 按对象分流）
 * - 正式模板 → 先退出会话视图（恢复调整上下文），再切换模板；两 tab 均围绕该正式目录
 */
const selectObject = (obj: any) => {
    if (obj?.kind === 'session') {
        // 幂等：重复点同一会话不重置编辑状态
        if (currentObject.value.kind === 'session' && currentObject.value.session?.sessionId === obj.session?.sessionId) return;
        const doOpen = () => {
            // currentObject 由 openSessionByRow 内的 session-opened 事件回流统一设置（单一同步路径）
            resetEditorState();
            aiWorkbenchRef.value?.openSessionByRow(obj.session);
        };
        if (checkDirty()) {
            confirmDiscard().then(doOpen).catch(() => {});
        } else {
            doOpen();
        }
        return;
    }
    const templateId = obj?.templateId;
    if (!templateId) return;
    // 幂等：重复点同一模板（且不在会话视图）不重置
    if (currentObject.value.kind === 'template' && currentObject.value.templateId === templateId) return;
    const doSwitch = () => {
        currentObject.value = { kind: 'template', templateId };
        // 先退出会话视图（enterAdjustContext 对 sessionView 有 early-return，退出后切换链路才完整）
        aiWorkbenchRef.value?.exitSessionView();
        doSwitchTemplate(templateId);
    };
    if (checkDirty()) {
        confirmDiscard().then(doSwitch).catch(() => {});
    } else {
        doSwitch();
    }
};

/**
 * AI 工作台进入会话视图（面板选择 / 新建模板成功）：currentObject 同步为该会话（单一同步路径）。
 * 必须同步完成切换，不做交互确认——selectObject 入口（对话框选会话）已先行处理未保存修改，
 * 唯一未经确认的入口是新建模板成功（新建必然进入新会话）；若此处再弹确认且用户取消，
 * 会出现"工作台已切新会话、currentObject 仍是旧对象"的分裂态：左侧树显示新会话文件、
 * 点选却按旧会话读盘（后端按会话目录前缀校验路径 → "文件不存在或不可读取"）。
 * 未保存修改在切换前按旧对象自动保存（保存失败不阻断切换，仅提示）
 */
const onSessionOpened = (session: any) => {
    const doSet = () => {
        currentObject.value = { kind: 'session', session };
        resetEditorState();
        // 会话树与对象强一致兜底（幂等刷新：新建链路已加载，此处对齐；脱钩场景由此修复）
        loadSessionTreeForChild(session?.sessionId);
    };
    if (checkDirty()) {
        doSave().then(doSet).catch(() => {
            ElMessage.warning('之前的文件保存失败，未保存修改已舍弃');
            doSet();
        });
    } else {
        doSet();
    }
};

// ==================== 工作对象选择器对话框（弹窗选择，触发卡片在左列顶部） ====================

/** 左列对象卡片三态样式：正式模板（默认蓝）/ 草稿·未应用（紫）/ 已应用回看（绿） */
const objCardState = computed(() => {
    const obj = currentObject.value;
    if (obj.kind === 'session') {
        return obj.session?.status === 'applied' ? 'applied' : 'draft';
    }
    return '';
});

/** 卡片主名称：模板名 / 会话模板目录名 */
const objCardName = computed(() => {
    const obj = currentObject.value;
    if (obj.kind === 'session') return obj.session?.templateName || '生成会话';
    const tpl = state.templateList.find((t: any) => String(t.id) === String(obj.templateId));
    return tpl?.name || '选择工作对象';
});

/** 卡片副标签：正式模板显示"使用中"，会话显示状态 */
const objCardTag = computed(() => {
    const obj = currentObject.value;
    if (obj.kind === 'session') return obj.session?.status === 'applied' ? '已应用回看' : '草稿·未应用';
    const tpl = state.templateList.find((t: any) => String(t.id) === String(obj.templateId));
    return tpl?.active ? '使用中' : '正式模板';
});

/** 卡片副行说明（对象类型信息）：正式模板带 ID，会话区分草稿 / 已应用回看 */
const objCardSub = computed(() => {
    const obj = currentObject.value;
    if (obj.kind === 'session') {
        if (obj.session?.status === 'applied') {
            // 已应用回看：优先按持久化指针 appliedTemplateId 找模板名，无指针回退目录名
            const tplId = obj.session?.appliedTemplateId ? String(obj.session.appliedTemplateId) : '';
            const tpl = tplId ? state.templateList.find((t: any) => String(t.id) === tplId) : null;
            return `已应用于「${tpl?.name || obj.session?.templateName || '正式模板'}」 · 回看模式`;
        }
        return '生成会话 · 点击切换';
    }
    return obj.templateId ? `正式模板 · ID ${obj.templateId} · 点击切换` : '点击选择工作对象';
});

/**
 * 对话框：选中工作对象（payload={obj, view?}）。view=显式落点（行内"手动编辑 / AI 调整"按钮）
 * 时切对应 tab；行点击不带 view → 停留当前 tab（原版"选模板强制跳 AI"的 bug 在此消解）。
 * 选中后关闭对话框（原版父组件行为）
 */
const onDialogSelect = (payload: any) => {
    dialogVisible.value = false;
    const { obj, view } = payload || {};
    selectObject(obj);
    if (view) currentView.value = view;
};

/** 对话框：新建模板 → 打开既有"新建模板"独立表单（对话框已先关闭，避免叠加） */
const onDialogCreate = () => {
    aiWorkbenchRef.value?.openCreateDialog();
};

/**
 * 对话框：未应用行「应用」→ 先选中该会话，再走工作台既有应用链路（确认弹窗在工作台）。
 * 手动编辑器有未保存修改时先处理（此时 currentObject 尚未切换，保存 API 指向正确对象），
 * 避免"未保存确认"与工作台"应用确认"两弹窗叠加乱序
 */
const onDialogApply = (session: any) => {
    const openAndApply = () => {
        aiWorkbenchRef.value?.openSessionByRow(session);
        aiWorkbenchRef.value?.onApplyTemplate();
    };
    if (checkDirty()) {
        confirmDiscard().then(openAndApply).catch(() => {});
    } else {
        openAndApply();
    }
};

/**
 * 对话框：已应用行「去正式模板」→ 优先会话持久化指针 appliedTemplateId（应用成功时后端回写），
 * 存量会话无指针时回退按目录名匹配；选中该正式模板，停留当前 tab
 */
const onDialogGotoTemplate = (session: any) => {
    let templateId = session?.appliedTemplateId ? String(session.appliedTemplateId) : '';
    if (!templateId) {
        const tpl = state.templateList.find((t: any) => t.name === session?.templateName);
        templateId = tpl ? String(tpl.id) : '';
    }
    if (!templateId) {
        ElMessage.warning('未找到对应的正式模板，请从"正式模板"页签中选择');
        return;
    }
    selectObject({ kind: 'template', templateId });
};

/**
 * 左侧文件树点选 → 按右侧当前 tab 分发行为：
 * AI 工作台 tab = 聚焦该文件（注入对话 + 预览联动，AI 工作台内部回调树高亮）；
 * 手动编辑 tab = 打开编辑器（正式模板 / 会话草稿按对象分流 API）
 */
const onTreePanelNodeClick = (node: any) => {
    if (currentView.value === 'ai') {
        aiWorkbenchRef.value?.focusTreeNode(node);
        return;
    }
    onNodeClick(node);
};

/** 左侧文件树刷新按钮 → 按当前对象分流加载（正式模板 / 会话工作目录） */
const onTreePanelRefreshTree = () => {
    loadFileTree();
};

/**
 * AI 工作台树高亮转发（AI 写盘 / 切页 / 清除选中）：驱动左侧面板文件树的高亮呈现，
 * 空路径 = 清除高亮
 */
const onHighlightFile = (path: string) => {
    treePanelRef.value?.setTreeCurrentKey(path || null);
};

/**
 * AI 工作台「files-changed」联动（调整会话直写正式模板目录）：
 * 刷新主编辑文件树，当前编辑的文件被改过则重新加载内容
 */
const onWorkbenchFilesChanged = () => {
    loadFileTree();
    // AI 改的是磁盘上的正式模板，手动编辑内联预览同步刷新（当前正指向的页面可能已被改写）
    refreshManualPreview();
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

/**
 * AI 工作台「编辑文件」桥（调整会话文件行）：切到手动编辑视图并打开该文件——
 * 把 AI 的改动接回手动编辑继续打磨
 */
const onEditAiFile = (filePath: string) => {
    if (!filePath) return;
    const doOpen = () => {
        currentView.value = 'edit';
        state.currEditFile = filePath;
        loadFileContent(filePath).then((res: any) => {
            state.content = res.data;
            state.savedContent = res.data;
            checkDirty();
            nextTick(() => {
                treePanelRef.value?.setTreeCurrentKey(filePath);
                updateEditorHeight();
            });
        }).catch(() => {
            ElMessage.error('文件读取失败，请从文件树重新打开');
        });
    };
    if (checkDirty()) {
        confirmDiscard().then(doOpen).catch(() => {});
    } else {
        doOpen();
    }
};

/**
 * 生成型会话应用模板成功：加载应用后的正式模板（templateId 来自后端 ApplyResult），
 * 主界面如有未保存修改先确认再切换；toast 提供跳转入口（点击切到手动编辑），不强制跳转
 */
const onAiTemplateApplied = (templateId?: string) => {
    const doLoad = () => {
        // 会话状态已变为"已应用"，统一重拉（选择器按钮/对话框页签数据随之更新）
        refreshSessions();
        loadTemplateList(templateId || undefined);
        ElNotification({
            title: '模板已应用',
            message: '点击此处切换到手动编辑视图查看正式模板',
            type: 'success',
            duration: 8000,
            onClick: () => { currentView.value = 'edit'; }
        });
    };
    if (checkDirty()) {
        confirmDiscard().then(doLoad).catch(() => {});
    } else {
        doLoad();
    }
};

/**
 * AI 工作台「编辑此模板」（已应用回看态）：把应用后的正式模板加载到主编辑视图
 * 并切到手动编辑——重新编辑应用后的模板；有未保存修改先确认，取消则留在工作台
 */
const onEditAppliedTemplate = (templateId: string) => {
    const doLoad = () => {
        // 工作对象切到该正式模板（面板高亮/树/手动编辑随之切换），并落到手动编辑 tab
        currentObject.value = { kind: 'template', templateId };
        aiWorkbenchRef.value?.exitSessionView();
        doSwitchTemplate(templateId);
        currentView.value = 'edit';
        ElMessage.success('已加载该模板，可继续编辑');
    };
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
            state.loadedTemplateId = target.id;
            state.uploadParam.templateId = target.id;
            // 初始进入（工作对象尚未绑定模板）时同步 currentObject；
            // 其后 loadTemplateList 只更新模板列表与 loadedTemplateId，不隐式切换工作对象
            if (currentObject.value.kind === 'template' && !currentObject.value.templateId) {
                currentObject.value = { kind: 'template', templateId: target.id };
            }
        }
        loadFileTree(true);
    })
}

/**
 * 加载文件树（按当前工作对象分流：正式模板目录 / 会话工作目录）
 * @param openDefault  是否同时默认打开 index.html（仅首次进入/切换对象时传 true，
 *                     AI 写盘后的树刷新不能重置用户正在编辑的文件）
 * @returns 加载 Promise（供调用方在树就绪后初始化预览入口）
 */
const loadFileTree = (openDefault = false) => {
    const obj = currentObject.value;
    if (obj.kind === 'session') {
        return loadSession(obj.session?.sessionId).then(() => {
            if (openDefault) openDefaultFile();
        });
    }
    return loadTemplateTree(state.loadedTemplateId || undefined, openDefault ? openDefaultFile : undefined);
};

/** 正式模板目录树加载（共享实例包装，注入 AI 工作台的 loadScopeTree） */
const loadTemplateTreeForChild = (templateId?: string) => loadTemplateTree(templateId);

/**
 * 会话工作目录树加载（共享实例包装，注入 AI 工作台的 loadSession）：
 * 树就绪后同步初始化手动编辑预览入口（会话对象下预览走会话路由，页面选项来自会话树）
 */
const loadSessionTreeForChild = (sessionId?: string) => {
    return loadSession(sessionId).then((res: any) => {
        if (currentObject.value.kind === 'session') {
            initManualPreviewEntry();
        }
        return res;
    });
};

/**
 * 读取文件内容（按当前工作对象分流 API）：
 * 正式模板 → templateApi（正式目录）；生成会话 → aiApi（会话工作目录草稿）
 * filePath 约定与文件树一致：以模板目录名开头
 */
const loadFileContent = (filePath: string) => {
    const obj = currentObject.value;
    if (obj.kind === 'session') {
        return aiApi.getSessionFile(obj.session?.sessionId, filePath);
    }
    return templateApi.getTemplateFile(filePath, state.loadedTemplateId || undefined);
};

/**
 * 在文件树中查找 index.html 并加载到编辑器（含模板目录前缀，如 xjd2022/index.html）；
 * 树数据按当前工作对象分流（正式模板目录 / 会话工作目录）
 */
const openDefaultFile = () => {
    const treeData = currentObject.value.kind === 'session' ? tree.sessionData : tree.data;
    const node = findIndexNode(treeData as any[]);
    if (node) {
        state.currEditFile = node.filePath;
        loadFileContent(node.filePath).then((res: any) => {
            state.content = res.data;
            state.savedContent = res.data;
            checkDirty();
            // 树中高亮选中该节点
            nextTick(() => {
                treePanelRef.value?.setTreeCurrentKey(node.filePath);
            });
        }).catch(() => {
            // 读取失败回退为空选状态
            state.currEditFile = '';
        });
    }
    // 内联预览入口：优先当前编辑的可路由 HTML，其次首页，最后第一个可选页
    initManualPreviewEntry();
};

const doSave = () => {
    // 还原文件原有换行风格：编辑器内统一为 LF，若原文件为 CRLF 则保存时还原，避免整文件换行符被静默改写
    let contentToSave = state.content;
    if (state.savedContent.indexOf('\r\n') >= 0) {
        contentToSave = normalizeEol(state.content).replace(/\n/g, '\r\n');
    }
    // 按当前工作对象分流保存 API：生成会话 → 会话工作目录草稿（仅未应用可写）；
    // 正式模板 → 正式模板目录
    const obj = currentObject.value;
    const savePromise = obj.kind === 'session'
        ? aiApi.saveSessionFile(obj.session?.sessionId, { filePath: state.currEditFile, fileContent: contentToSave })
        : templateApi.saveTemplateFile({
            filePath: state.currEditFile,
            fileContent: contentToSave,
            templateId: state.loadedTemplateId
        });
    return savePromise.then(() => {
        state.savedContent = contentToSave;
        checkDirty();
        ElMessage.success("保存成功");
        // 内联预览展示已保存内容，保存成功即刷新
        refreshManualPreview();
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
        // 按当前工作对象分流删除 API：生成会话 → 会话工作目录草稿（仅未应用可删）；正式模板 → 正式目录
        const obj = currentObject.value;
        const delPromise = obj.kind === 'session'
            ? aiApi.delSessionFile(obj.session?.sessionId, state.currEditFile)
            : templateApi.delTemplateFile(state.currEditFile, state.loadedTemplateId || undefined);
        delPromise.then(() => {
            ElMessage.success("删除成功");
            state.content = '';
            state.savedContent = '';
            state.currEditFile = '';
            checkDirty();
            // 清除左侧面板文件树中已删节点的高亮（highlight-current 残留指向不存在的文件）
            treePanelRef.value?.setTreeCurrentKey(null);
            loadFileTree();
            // 预览入口可能正指向被删文件，重新初始化（回落首页/第一个可选页）
            initManualPreviewEntry();
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
            if (currentObject.value.kind === 'session') {
                // 图片工作台（AI 修图）只作用于正式模板目录；会话工作目录的图片暂不支持
                ElMessage.info('生成会话的图片文件暂不支持 AI 修图，可应用模板后再处理');
                return;
            }
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
            // 可路由 HTML：内联预览同步指向该文件（选项含模板目录前缀，与树路径一致）
            if (isRoutableHtml(node.filePath)) {
                manualPreview.entry = node.filePath;
            }
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

// 上传结果聚合：multiple 批量上传时 el-upload 对每个文件各触发一次成功/失败回调，
// 计数 + 短防抖合并为一次提示与一次文件树刷新（避免 N 条消息 + N 次接口请求）
let uploadSuccessCount = 0;
let uploadFailCount = 0;
let uploadNotifyTimer: ReturnType<typeof setTimeout> | null = null;

const flushUploadResult = () => {
    uploadNotifyTimer = null;
    const ok = uploadSuccessCount;
    const fail = uploadFailCount;
    uploadSuccessCount = 0;
    uploadFailCount = 0;
    const target = `（目录：${state.uploadParam.dirName || '模板根目录'}）`;
    if (ok && fail) {
        ElMessage.warning(`上传完成：成功 ${ok} 个，失败 ${fail} 个${target}`);
    } else if (ok) {
        ElMessage.success(`上传成功 ${ok} 个文件${target}`);
    } else if (fail) {
        ElMessage.error(`上传失败 ${fail} 个文件`);
    }
    if (ok) {
        loadFileTree();
    }
};

const scheduleUploadNotify = () => {
    if (uploadNotifyTimer) clearTimeout(uploadNotifyTimer);
    uploadNotifyTimer = setTimeout(flushUploadResult, 400);
};

const uploadSuccess = () => {
    uploadSuccessCount++;
    scheduleUploadNotify();
}
const onHandleUploadError = () => {
    uploadFailCount++;
    scheduleUploadNotify();
}
const onBeforeUpload = () => {
    if (!state.uploadParam.dirName) {
        // 未选择目录时默认上传到根目录（文件树顶层节点即模板目录，按当前工作对象分流树数据），
        // 上传前置要求不再导致必失败
        const treeData = currentObject.value.kind === 'session' ? tree.sessionData : tree.data;
        const root = ((treeData as any[]) || [])[0];
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
    // 会话数据（单一数据源）：页面初始化即拉取，供 AI 工作台下拉与工作对象选择器对话框
    refreshSessions();
    // 高度自适应：初始计算 + 窗口变化时重算（编辑器/文件树等高，页面不整页滚动）
    updateEditorHeight();
    // 冷启动收敛兜底：首帧布局未定型实测偏小，布局定型后重读（见 scheduleHeightSettle 注释）
    scheduleHeightSettle();
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
    // Ctrl/Cmd + S 快捷保存
    window.addEventListener('keydown', onSaveShortcut);
    // 窄屏默认收起代码编辑列（预览占主屏，与 AI 工作台对话列同一交互）
    if (typeof window.matchMedia === 'function') {
        narrowMq = window.matchMedia('(max-width: 768px)');
        if (narrowMq.addEventListener) narrowMq.addEventListener('change', onNarrowMqChange);
        onNarrowMqChange();
    }
});

// 切换视图时重算高度（隐藏期间布局可能变化；视图栏按钮组同位互换，两视图起点恒等，
// AI 工作台可见时同样可实测）；切到 AI 工作台时退出编辑器全屏，避免 fixed 覆盖层残留
watch(currentView, () => {
    if (editorFullscreen.value && currentView.value !== 'edit') editorFullscreen.value = false;
    nextTick(() => updateEditorHeight());
});

// 选区锁定/换图模式的 ESC 与生命周期清理随钩子内聚到 aiWorkbench 组件

/**
 * 高度自适应（双基准实测，原型 v3：视图栏在右列内部）：
 * - clientHeight（主体两列总高，含右列顶部视图栏）：以右列容器（right-col）起点实测，注入
 *   mainColumnsStyle，保证左侧对象区/树与右列等高、页面整体不出现整页滚动；
 * - editorZoneHeight（视图栏下方内容区高度）：以内容区包裹层（tab-panes）起点实测，编辑器/
 *   图片工作台高度注入用（起点比右列容器低一个视图栏高度）。
 * 以 DOM 实测代替写死高度；两个实测容器均恒在 DOM（v-show 保活在包裹层内部，按钮组同位互换
 * 保证两 tab 起点恒等）；窄屏（<768px）左右栏堆叠时退回固定高度，避免编辑器被压得过高过矮。
 */
const updateEditorHeight = () => {
    // 编辑器全屏（fixed 布局脱离文档流）时跳过，沿用旧值
    if (editorFullscreen.value) return;
    const rowEl = rightColRef.value?.$el || rightColRef.value;
    // 容器未挂载时跳过（right-col 恒可见，无 display:none 实测失效问题）
    if (!rowEl || !rowEl.offsetHeight) return;
    const viewportW = document.documentElement.clientWidth;
    if (viewportW < 768) {
        state.clientHeight = '600px';
        state.editorZoneHeight = '600px';
        return;
    }
    const viewportH = document.documentElement.clientHeight;
    // 底部留白覆盖：el-col 的 mb20 + 页面级 el-card body 的 padding（合计约 40px）+ 少量呼吸空间
    const bottomGap = 48;
    // 内容区高度：从视图栏下方包裹层（tab-panes，恒可见）到视口底，两 tab 起点恒等
    const panesEl = tabPanesRef.value?.$el || tabPanesRef.value;
    if (panesEl && panesEl.offsetHeight) {
        const zoneHeight = Math.max(400, viewportH - panesEl.getBoundingClientRect().top - bottomGap) + 'px';
        // 同值短路：定时兜底/ResizeObserver 高频重算时避免同值赋值触发无谓的响应式更新（防观察循环）
        if (state.editorZoneHeight !== zoneHeight) state.editorZoneHeight = zoneHeight;
    }
    // 主体两列总高：从右列容器（与左列同一起点）到视口底
    const columnsHeight = Math.max(400, viewportH - rowEl.getBoundingClientRect().top - bottomGap) + 'px';
    if (state.clientHeight !== columnsHeight) state.clientHeight = columnsHeight;
};

/**
 * 高度收敛兜底：冷启动（F5/直接 URL）时 tagsview/面包屑等布局元素异步渲染尚未定型，
 * 首帧实测的编辑区起点偏大 → 算出偏小高度；布局定型是位置变化（无 DOM 事件可捕获，
 * ResizeObserver 只响应盒子尺寸变化），只能延迟重读。300/800ms 两档覆盖常见异步渲染时长
 */
const scheduleHeightSettle = () => {
    window.setTimeout(updateEditorHeight, 300);
    window.setTimeout(updateEditorHeight, 800);
};

/** Ctrl/Cmd + S 快捷保存：AI 工作台视图下忽略（工作台内对话框自管快捷键语境） */
const onSaveShortcut = (e: KeyboardEvent) => {
    // Esc 退出编辑器全屏（全屏仅在手动编辑视图存在）
    if (e.key === 'Escape') {
        if (editorFullscreen.value) editorFullscreen.value = false;
        return;
    }
    if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 's') return;
    e.preventDefault();
    if (currentView.value !== 'edit') return;
    if (!state.currEditFile) return;
    onSaveFile();
};

onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload);
    window.removeEventListener('keydown', onSaveShortcut);
    window.removeEventListener('resize', updateEditorHeight);
    if (narrowMq?.addEventListener) narrowMq.removeEventListener('change', onNarrowMqChange);
    if (editorHeightObserver) {
        editorHeightObserver.disconnect();
        editorHeightObserver = null;
    }
});

// keep-alive 缓存恢复时重算高度：从其他菜单切回本页走的是 onActivated 而非 onMounted，
// 不重算会沿用旧高度（窗口尺寸/布局已变时出现半高留白）；路由过渡动画期间实测偏移，同样延迟收敛
onActivated(() => {
    updateEditorHeight();
    scheduleHeightSettle();
});
</script>

<style lang="scss" scoped>
// 页面根卡片：顶部留白收紧（左列对象卡片贴近页首，对齐原型 12px 页边距），其余方向保持 el-card 默认
:deep(.page-card > .el-card__body) {
    padding-top: 10px;
}

// 统一操作对象卡片（左列顶部，点击弹三页签选择器）：三态 = 默认（正式模板，蓝）/
// .draft（草稿·未应用，紫）/ .applied（已应用回看，绿）；两行布局：主行名称+标签，
// 副行对象类型说明，超长名称/标签内部截断
.obj-card {
    display: flex;
    flex-direction: column;
    gap: 5px;
    width: 100%;
    padding: 9px 12px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 6px;
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    cursor: pointer;
    font-size: 13px;
    line-height: 1.2;
    text-align: left;
    transition: border-color 0.2s, box-shadow 0.2s;

    &:hover, &:focus-visible {
        border-color: var(--el-color-primary);
        outline: none;
        box-shadow: 0 2px 8px rgba(64, 158, 255, 0.18);
    }

    .obj-line1 {
        display: flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
    }

    .s-ico {
        flex: none;
        font-size: 14px;
    }

    .s-name {
        flex: 0 1 auto;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-weight: 600;
    }

    .s-tag {
        flex: 0 1 auto;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 12px;
        opacity: 0.75;
    }

    .s-caret {
        flex: none;
        margin-left: auto;
        font-size: 12px;
        opacity: 0.6;
    }

    // 副行：对象类型说明（灰字，不随三态变色）
    .obj-sub {
        font-size: 12px;
        color: var(--el-text-color-secondary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    // 草稿态（生成中/未应用草稿）：紫
    &.draft {
        border-color: #d8b4fe;
        background: #faf5ff;
        color: #7e22ce;
    }

    // 已应用回看态：绿
    &.applied {
        border-color: #b3e19d;
        background: #f0f9eb;
        color: #529b2e;
    }
}
.toolbar-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0;
    // 视图栏单行：tab 靠左，按钮组靠右
    margin-left: auto;
}
// 文件树卡片/过滤框样式随树下沉 FileTreePanel（scoped），主页不再持有
// 视图栏（原型 v3：右列内部首行，tab 左 + 按钮组右）；吸顶承接原工具栏职责（窄屏堆叠
// 页面滚动时视图切换恒可达），背景遮衬避免内容穿透
.view-tabs {
    display: flex;
    align-items: center;
    gap: 4px;
    flex: none;
    // 吸顶：页面滚动时视图栏（含保存/删除等按钮组）始终固定在顶部
    position: sticky;
    top: 0;
    z-index: 20;
    background: var(--el-bg-color);
    // 与下方内容区拉开间距（tab + 右侧按钮组同行）
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    .view-tab {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        padding: 6px 14px;
        font-size: 14px;
        color: var(--el-text-color-regular);
        border-radius: 6px 6px 0 0;
        cursor: pointer;
        user-select: none;
        border-bottom: 2px solid transparent;
        transition: color 0.2s, border-color 0.2s;

        &:hover {
            color: var(--el-color-primary);
        }

        &.active {
            color: var(--el-color-primary);
            font-weight: 600;
            border-bottom-color: var(--el-color-primary);
        }
    }
}

// ===== 主体两列：左侧统一操作对象区（对象卡片 + 文件树，固定 280px）| 右侧内容区 =====
// 高度经 mainColumnsStyle 显式注入（窄屏退回 auto 堆叠）
.main-columns {
    display: flex;
    gap: 12px;
    align-items: stretch;
    min-height: 0;
}

// 左列（统一操作对象区，整体收展）：内容区（对象卡片 + 文件树）+ 右缘把手竖条横向排布；
// 展开 280px / 收起 38px 竖条（与代码列收起交互同构，对象卡片随树一起收起展开）
.left-col {
    display: flex;
    flex: 0 0 280px;
    min-height: 0;

    // 内容区（对象卡片 + 文件树纵向堆叠）：吃满把手之外的宽度，随整列收起隐藏
    .left-col-inner {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 10px;
    }

    // 文件树行：树卡片吃满内容区剩余高度（对象卡片之外）
    .tree-row {
        flex: 1;
        min-height: 0;
        display: flex;
    }

    // 把手竖条（左列右缘，纵贯整列全高）：展开态=收起按钮
    .panel-side-bar {
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

    // 收起态：整列变 38px 竖条（内容区隐藏），把手变独立竖条：展开按钮 + 竖排「文件」标签
    &.collapsed {
        flex-basis: 38px;
        max-width: 38px;

        .panel-side-bar {
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
}

// 右列（原型 v3）：顶部视图栏（flex none）+ 内容区包裹层纵向堆叠；吃满剩余宽度，
// 高度由 mainColumnsStyle 显式注入（height:100% 相对 main-columns 可靠解析）
.right-col {
    flex: 1 1 0;
    min-width: 0;
    min-height: 0;
    height: 100%;
    display: flex;
    flex-direction: column;
}

// 两 tab 同容器内容区包裹层（恒在 DOM）：吃满视图栏之外的剩余高度；
// 内部两视图（edit-columns / ai-workbench，v-show 互斥显示）经 flex:1 吃满本层宽度、
// stretch 拉满高度（本层高度由 flex 拉伸确定，两视图自身 height:100% 亦可解析）
.tab-panes {
    flex: 1;
    min-height: 0;
    display: flex;

    > * {
        flex: 1;
        min-width: 0;
    }
}

// ===== 手动编辑两列（文件树在左侧面板）：内联预览（flex:1）| 代码编辑（右，可收起 38px） =====
.edit-columns {
    display: flex;
    gap: 12px;
    align-items: stretch;
    min-height: 0;
    height: 100%;
}

.edit-col-mid {
    display: flex;
    flex-direction: row;
    flex: 0 0 30%;
    min-width: 0;
    min-height: 0;

    // 侧边把手条（贴右缘竖条）：展开态放收起/全屏按钮，收起态整列变窄竖条
    .mid-side-bar {
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

    .edit-col-mid-inner {
        flex: 1;
        min-width: 0;
        min-height: 0;
    }

    // 收起态：整列变 38px 竖条，展开按钮 + 竖排「代码」标签，预览列吃满剩余空间
    &.collapsed {
        flex: 0 0 38px;
        max-width: 38px;

        .mid-side-bar {
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

    // 全屏态：fixed 覆盖视口（把手条保留「退出全屏」按钮，Esc 同效）
    &.editor-fullscreen {
        position: fixed;
        inset: 0;
        z-index: 2000;
        flex: none;
        max-width: none;
        padding: 8px;
        background: var(--el-bg-color);
        box-shadow: 0 4px 24px rgba(0, 0, 0, 0.18);

        .mid-side-bar {
            flex-basis: 30px;
            border-left: none;
        }
    }
}

.edit-col-preview {
    flex: 1 1 0;
    min-width: 260px;
    min-height: 0;
}

// 窄屏：主体两列与编辑两列退回堆叠（进入窄屏时左面板与中列默认收起，预览占主屏）
@media (max-width: 768px) {
    .main-columns {
        flex-direction: column;
    }

    .edit-columns {
        flex-direction: column;
    }

    .left-col,
    .edit-col-mid,
    .edit-col-preview {
        flex: none;
        width: 100%;
    }

    // 窄屏收起态：38px 竖条退化为整行横向展开条（竖排标签转横排）
    .left-col.collapsed {
        flex-basis: auto;
        max-width: none;

        .panel-side-bar {
            flex: 1 1 auto;
            flex-direction: row;
            padding: 6px 10px;
            gap: 8px;

            .el-button {
                width: auto;
                padding: 4px 8px;
            }

            .collapsed-label {
                writing-mode: horizontal-tb;
                letter-spacing: 1px;
            }
        }
    }

    .edit-col-preview {
        min-height: 420px;
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
