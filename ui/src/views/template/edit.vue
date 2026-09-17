<template>
<div class="container">
    <el-card>
        <div class="toolbar">
            <!-- 视图切换 tab（v-show 保活两视图：AI 会话状态、编辑器内容、树选中态切走再切回不丢） -->
            <div class="view-tabs">
                <span class="view-tab" :class="{ active: currentView === 'ai' }" @click="currentView = 'ai'">
                    <el-icon><ele-MagicStick /></el-icon>AI 工作台
                </span>
                <span class="view-tab" :class="{ active: currentView === 'edit' }" @click="currentView = 'edit'">
                    <el-icon><ele-Edit /></el-icon>手动编辑
                </span>
            </div>
            <el-row v-show="currentView === 'edit'" :gutter="35" class="toolbar-row">
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
                        <el-button type="primary" @click="onSaveFile" :disabled="!state.currEditFile">保 存</el-button>
                        <el-button type="danger" @click="onDelFile" :disabled="!state.currEditFile">删 除</el-button>
                        <span v-if="state.isDirty" class="dirty-tip">● 有未保存的修改</span>
                    </div>
                </el-col>
            </el-row>
        </div>
        <!-- 手动编辑视图：树 | 代码编辑（可收起）| 内联预览 三列（flex 布局，与 AI 工作台同一套收起交互） -->
        <div v-show="currentView === 'edit'" style="padding-top: 5px;">
            <div ref="editRowRef" class="edit-columns">
                <div class="edit-col-tree">
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
                        <div class="tree-filter">
                            <el-input v-model="treeFilter" size="small" clearable placeholder="输入关键字过滤文件">
                                <template #prefix>
                                    <el-icon><ele-Search /></el-icon>
                                </template>
                            </el-input>
                        </div>
                        <div v-loading="tree.loading" class="tree-body">
                            <el-tree :data="tree.data"
                                :default-expand-all="false"
                                :default-expanded-keys="tree.expandedKeys"
                                highlight-current
                                node-key="filePath"
                                :props="tree.defaultProps"
                                :filter-node-method="filterTreeNode"
                                @node-click="onNodeClick"
                                style="height: 100%;overflow: auto;"
                                ref="treeTable">
                            </el-tree>
                        </div>
                    </el-card>
                </div>
                <div class="edit-col-mid" :class="{ collapsed: midCollapsed }">
                    <!-- 收起/展开切换按钮条（收起后预览列自动吃满剩余空间） -->
                    <div class="mid-collapse-bar">
                        <el-button size="small" text :title="midCollapsed ? '展开代码编辑' : '收起代码编辑'"
                                   @click="midCollapsed = !midCollapsed">
                            <el-icon :size="16">
                                <ele-Fold v-if="!midCollapsed" />
                                <ele-Expand v-else />
                            </el-icon>
                        </el-button>
                        <span v-if="midCollapsed" class="collapsed-label">代码</span>
                    </div>
                    <div class="edit-col-mid-inner" v-show="!midCollapsed">
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
                    </div>
                </div>
                <div class="edit-col-preview">
                    <!-- 内联预览：预览已保存内容，保存成功后自动刷新 -->
                    <TemplatePreviewPanel v-model:entry="manualPreview.entry"
                                          :page-options="manualPreviewOptions" :url="manualPreviewUrl"
                                          :empty-tip="manualPreviewTip" v-model:viewport="manualViewport"
                                          @refresh="refreshManualPreview" />
                </div>
            </div>
        </div>

        <!-- AI 工作台视图：工具条 + 树|对话|预览 三列（会话编排内聚在组件内） -->
        <ai-workbench v-show="currentView === 'ai'" :template-id="state.loadedTemplateId"
                      :template-list="state.templateList" :height="state.clientHeight"
                      :active="currentView === 'ai'"
                      @scope-change="onScopeChange" @files-changed="onWorkbenchFilesChanged"
                      @edit-file="onEditAiFile" @applied="onAiTemplateApplied" />
    </el-card>
</div>
</template>

<script lang="ts" name="templateEdit" setup>
import { reactive, computed, onMounted, onActivated, onBeforeUnmount, ref, nextTick, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessageBox, ElMessage, ElNotification } from 'element-plus';
import { Local } from '/@/utils/storage';
import { TemplateApi } from '/@/api/template/index';

import ImageWorkbench from '/@/views/template/ImageWorkbench.vue';

import AiWorkbench from '/@/views/template/aiWorkbench.vue';

import TemplatePreviewPanel from '/@/views/template/TemplatePreviewPanel.vue';

import { useTemplateFileTree } from '/@/views/template/composables/useTemplateFileTree';

import { useAiPreview, isRoutableHtml } from '/@/views/template/composables/useAiPreview';
import { Codemirror } from "vue-codemirror";
import { html } from "@codemirror/lang-html";
import { javascript } from "@codemirror/lang-javascript";
import { css } from "@codemirror/lang-css";
import { search } from "@codemirror/search";
import { oneDark } from "@codemirror/theme-one-dark";

const treeTable = ref();
// 编辑区行（左树 + 中编辑器 + 右预览）：用于实测编辑区起点，计算高度自适应
const editRowRef = ref();
// 布局容器尺寸变化观察器（keep-alive 缓存页切回时 onMounted 不会重跑，靠 onActivated 兜底重算）
let editorHeightObserver: ResizeObserver | null = null;

const templateApi = TemplateApi();
// 视图切换：手动编辑（默认，保持既有入口行为）/ AI 工作台（v-show 保活，切换不丢状态）
const currentView = ref<'edit' | 'ai'>('edit');
// 代码编辑列收起状态（收起后预览列吃满剩余空间）
const midCollapsed = ref(false);
// 窄屏媒体查询：进入窄屏时默认收起中列（手动编辑列与 AI 工作台对话列同一交互）
let narrowMq: MediaQueryList | null = null;
const onNarrowMqChange = () => {
    if (narrowMq?.matches) midCollapsed.value = true;
};
// 手动编辑预览视口档位
const manualViewport = ref<'desktop' | 'tablet' | 'mobile'>('desktop');
// 图片工作台状态（文件树点选图片文件打开；filePath 变化驱动组件内部重载原图）
const workbenchVisible = ref(false);
const workbenchFile = ref('');
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

// 文件树（正式模板目录树 + AI 会话工作目录树，加载与查找下沉到 composable）
const { tree, findIndexNode, load: loadTemplateTree } = useTemplateFileTree();

// 文件树关键字过滤：按文件/目录名模糊匹配（不区分大小写），关键字变化即触发 el-tree 过滤
const treeFilter = ref('');
const filterTreeNode = (value: string, data: any) => {
    if (!value) return true;
    return String(data.label || '').toLowerCase().includes(value.toLowerCase());
};
watch(treeFilter, (val) => {
    treeTable.value?.filter(val);
});

// ==================== 手动编辑内联预览 ====================

// 内联预览（入口页面/刷新键/预览地址）：复用 useAiPreview（固定走正式模板路由），
// getter 固定非会话视图，树数据用主编辑文件树
const { preview: manualPreview, previewPageOptions: manualPreviewOptions, aiPreviewUrl: manualPreviewUrl,
        previewEmptyTip: manualPreviewTip, initEntry: initManualPreviewEntry, refresh: refreshManualPreview } = useAiPreview({
    getSessionView: () => false,
    getCurrentSession: () => null,
    getLoadedTemplateId: () => state.loadedTemplateId,
    getCurrEditFile: () => state.currEditFile,
    getTreeNodes: () => tree.data as any[],
    getSessionTreeNodes: () => []
});

/** 上传附加参数 */
const uploadData = computed(() => ({ dirName: state.uploadParam.dirName, templateId: state.uploadParam.templateId }));

/** 上传目标目录提示（按钮 tooltip）：未选目录时将兜底上传到模板根目录 */
const uploadTargetTip = computed(() =>
    state.uploadParam.dirName ? `将上传到目录：${state.uploadParam.dirName}` : '未选择目录，将上传到模板根目录');

/**
 * AI 工作台作用模板切换：走与手动编辑模板下拉同一套切换流程（未保存修改确认 + 失败还原）。
 * 切换成功后 loadedTemplateId 变化经 props 回流工作台，其文件树/预览/调整会话随之切换
 */
const onScopeChange = (val: string) => {
    // 作用模板下拉未绑定 state.templateId，先同步主工具栏下拉选中项（取消切换时由 doSwitch 还原）
    state.templateId = val;
    onTemplateChange(val);
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
                treeTable.value?.setCurrentKey(filePath);
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
    // 内联预览入口：优先当前编辑的可路由 HTML，其次首页，最后第一个可选页
    initManualPreviewEntry();
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
        templateApi.delTemplateFile(state.currEditFile, state.loadedTemplateId || undefined).then(() => {
            ElMessage.success("删除成功");
            state.content = '';
            state.savedContent = '';
            state.currEditFile = '';
            checkDirty();
            // 清除 el-tree 中已删节点的高亮（highlight-current 残留指向不存在的文件）
            treeTable.value?.setCurrentKey(null);
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
    // Ctrl/Cmd + S 快捷保存
    window.addEventListener('keydown', onSaveShortcut);
    // 窄屏默认收起代码编辑列（预览占主屏，与 AI 工作台对话列同一交互）
    if (typeof window.matchMedia === 'function') {
        narrowMq = window.matchMedia('(max-width: 768px)');
        if (narrowMq.addEventListener) narrowMq.addEventListener('change', onNarrowMqChange);
        onNarrowMqChange();
    }
});

// 切回手动编辑视图时重算高度（隐藏期间布局可能变化，且 display:none 下无法实测）
watch(currentView, (view) => {
    if (view === 'edit') nextTick(() => updateEditorHeight());
});

// 选区锁定/换图模式的 ESC 与生命周期清理随钩子内聚到 aiWorkbench 组件

/**
 * 编辑器高度自适应：视口高度 - 编辑区起点到视口顶的距离（含布局头部、tagsview、工具栏等占位） - 底部留白。
 * 以 DOM 实测代替写死高度，保证左树卡片与右编辑器等高、页面整体不出现整页滚动；
 * 窄屏（<768px）左右栏堆叠时退回固定高度，避免编辑器被压得过高过矮。
 */
const updateEditorHeight = () => {
    const rowEl = editRowRef.value?.$el || editRowRef.value;
    if (!rowEl) return;
    // 视图隐藏（v-show display:none）时 getBoundingClientRect 全为 0，跳过本次计算沿用旧值
    if (currentView.value !== 'edit') return;
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

/** Ctrl/Cmd + S 快捷保存：AI 工作台视图下忽略（工作台内对话框自管快捷键语境） */
const onSaveShortcut = (e: KeyboardEvent) => {
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
/* 文件树关键字过滤框：位于树卡片头部与树体之间 */
.tree-filter {
    padding: 8px 10px 4px;
}
.view-tabs {
    display: flex;
    align-items: center;
    gap: 4px;
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

// ===== 手动编辑三列：树 | 代码编辑（可收起 38px）| 内联预览 =====
.edit-columns {
    display: flex;
    gap: 12px;
    align-items: stretch;
    min-height: 0;
}

.edit-col-tree {
    flex: 0 0 20%;
    min-width: 190px;
    min-height: 0;
}

.edit-col-mid {
    display: flex;
    flex-direction: column;
    flex: 0 0 30%;
    min-width: 0;
    min-height: 0;

    // 收起态：变成一条窄竖条，显示切换按钮 + 竖排「代码」标签，预览列吃满剩余空间
    &.collapsed {
        flex: 0 0 38px;
        max-width: 38px;

        .mid-collapse-bar {
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

    // 顶部收起按钮条（展开态：靠右显示）
    .mid-collapse-bar {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        padding: 2px 8px 6px;
        border-bottom: 1px solid var(--el-border-color-lighter);
        margin-bottom: 8px;
    }

    .edit-col-mid-inner {
        flex: 1;
        min-height: 0;
    }
}

.edit-col-preview {
    flex: 1 1 0;
    min-width: 260px;
    min-height: 0;
}

// 窄屏：三列退回堆叠（进入窄屏时中列默认收起，预览占主屏）
@media (max-width: 768px) {
    .edit-columns {
        flex-direction: column;
    }

    .edit-col-tree,
    .edit-col-mid,
    .edit-col-preview {
        flex: none;
        width: 100%;
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
