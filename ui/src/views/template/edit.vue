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
                            :data="state.uploadParam"
                            multiple
                            :headers="state.headers"
                            :show-file-list="false"
                            :on-success="uploadSuccess"
                            :on-exceed="onHandleExceed"
                            :on-error="onHandleUploadError"
                            :before-upload="onBeforeUpload"
                            :limit="state.limit">
                            <el-button size="default" type="primary"><el-icon><ele-Plus /></el-icon>上传模板文件</el-button>
                        </el-upload>
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
        <el-form style="padding-top: 5px;" size="default" label-width="100px" ref="myRefForm">
            <el-row :gutter="35">
                <el-col :sm="5" class="mb20">
                    <div class="tree-container">
                        <el-card shadow="hover" header="模板文件树">
                            <div v-loading="state.treeLoading">
                                <el-tree :data="state.treeTableData"
                                    :default-expand-all="false"
                                    :default-expanded-keys="state.expandedKeys"
                                    highlight-current
                                    node-key="filePath"
                                    :props="state.treeDefaultProps"
                                    @node-click="onNodeClick"
                                    style="height: 550px;overflow: auto;"
                                    ref="treeTable">
                                </el-tree>
                            </div>
                        </el-card>
                    </div>
                </el-col>
                <el-col :sm="19" class="mb20">
                    <Codemirror
                            ref="codeMirror"
                            v-model="state.content"
                            :style="{ height: state.clientHight, width: '100%' }"
                            :autofocus="true"
                            @change="onChange"
                            v-bind="$attrs"
                            :extensions="extensions" />
                </el-col>
            </el-row>
        </el-form>

        <!-- AI 对话抽屉（全屏覆盖；调整型：左预览右对话，AI 每写一个文件自动刷新预览） -->
        <el-drawer v-model="state.aiDrawerVisible" size="100%" :close-on-click-modal="false" custom-class="ai-template-drawer">
            <template #header>
                <div class="drawer-header">
                    <span class="drawer-title">{{ state.aiMode === 'adjust' ? 'AI 调整模板' : 'AI 生成模板' }}</span>
                </div>
            </template>
            <div class="ai-drawer-body" :class="{ split: state.aiMode === 'adjust' }">
                <div v-if="state.aiMode === 'adjust'" class="ai-preview-col">
                    <div class="preview-toolbar">
                        <el-select v-model="state.aiPreviewEntry" size="small" filterable placeholder="选择预览页面">
                            <el-option v-for="p in previewPageOptions" :key="p" :value="p" :label="p" />
                        </el-select>
                        <el-button size="small" @click="refreshAiPreview" title="刷新预览">
                            <el-icon><ele-Refresh /></el-icon>
                        </el-button>
                        <el-button size="small" @click="openAiPreviewNewWindow" title="新窗口打开">
                            <el-icon><ele-FullScreen /></el-icon>
                        </el-button>
                    </div>
                    <div class="preview-frame-wrap">
                        <iframe v-if="aiPreviewUrl" :src="aiPreviewUrl" class="preview-frame" frameborder="0"></iframe>
                    </div>
                </div>
                <div class="ai-chat-col">
                    <ai-chat ref="aiChatRef" :session="state.currentAiSession" :mode="state.aiMode"
                             :current-file="state.aiMode === 'adjust' ? (state.aiPreviewEntry || state.currEditFile) : ''"
                             :sessions="state.aiSessions" :creating-session="state.creatingAiSession"
                             @select-session="onSelectAiSession" @new-session="onNewAiSession"
                             @files-changed="onAiFilesChanged" @file-written="onAiFileWritten" @applied="onAiTemplateApplied" />
                </div>
            </div>
        </el-drawer>

        <!-- AI 新建模板对话框（含历史生成记录入口） -->
        <el-dialog v-model="state.createDialog.visible"
                   :title="state.createDialog.view === 'history' ? '历史生成记录' : 'AI 新建模板'"
                   :width="state.createDialog.view === 'history' ? '680px' : '520px'" :close-on-click-modal="false">
            <el-form v-if="state.createDialog.view === 'create'" label-width="90px">
                <el-form-item label="模板目录名" required>
                    <el-input v-model="state.createDialog.templateName" placeholder="英文目录名，以字母开头，如 my-company" />
                </el-form-item>
                <el-form-item label="需求描述" required>
                    <el-input v-model="state.createDialog.requirement" type="textarea" :rows="4"
                              placeholder="描述模板需求，例如：企业官网模板，蓝色调，响应式设计" />
                </el-form-item>
            </el-form>
            <template v-else>
                <el-table :data="state.createDialog.sessions" v-loading="state.createDialog.historyLoading" stripe size="small"
                          max-height="420" highlight-current-row class="history-session-table" @row-click="onOpenHistorySession">
                    <el-table-column prop="templateName" label="模板目录" min-width="110" show-overflow-tooltip />
                    <el-table-column label="状态" width="80">
                        <template #default="scope">
                            <el-tag size="small" :type="scope.row.status === 'applied' ? 'success' : 'warning'">
                                {{ scope.row.status === 'applied' ? '已应用' : '未应用' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="requirement" label="需求描述" min-width="200" show-overflow-tooltip />
                    <el-table-column label="创建时间" width="130">
                        <template #default="scope">{{ formatHistoryTime(scope.row.created) }}</template>
                    </el-table-column>
                </el-table>
                <el-empty v-if="!state.createDialog.historyLoading && state.createDialog.sessions.length === 0"
                          description="暂无生成记录" :image-size="60" />
                <div class="history-tip">点击记录打开 AI 抽屉：未应用的可继续对话或应用；已应用的仅回看</div>
            </template>
            <template #footer>
                <template v-if="state.createDialog.view === 'create'">
                    <el-button text type="primary" @click="onShowHistory">
                        <el-icon><ele-Clock /></el-icon>历史生成记录
                    </el-button>
                    <el-button @click="state.createDialog.visible = false">取 消</el-button>
                    <el-button type="primary" :loading="state.createDialog.loading" @click="onCreateConfirm">开始生成</el-button>
                </template>
                <template v-else>
                    <el-button @click="state.createDialog.view = 'create'">返回新建</el-button>
                    <el-button type="primary" @click="state.createDialog.visible = false">关 闭</el-button>
                </template>
            </template>
        </el-dialog>
    </el-card>
</div>
</template>

<script lang="ts" name="templateEdit" setup>
import { reactive, computed, onMounted, onBeforeUnmount, ref, nextTick } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessageBox, ElMessage } from 'element-plus';
import { Local } from '/@/utils/storage';
import { TemplateApi } from '/@/api/template/index';
import { AiTemplateApi } from '/@/api/ai/index';
import AiChat from '/@/views/template/aiChat.vue';
import { Codemirror } from "vue-codemirror";
import { html } from "@codemirror/lang-html";
import { oneDark } from "@codemirror/theme-one-dark";

const codeMirror = ref()
const treeTable = ref()
const extensions = [html(), oneDark];

const templateApi = TemplateApi();
const aiApi = AiTemplateApi();
const aiChatRef = ref();
const state = reactive({
    clientHight: "600px",
    treeLoading: false,
    treeTableData: [],
    treeDefaultProps: {
        children: 'children',
        label: 'label',
        filePath: 'filePath'
    },
    // 模板选择（可编辑非激活模板）
    templateList: [] as any[],
    templateId: '',
    loadedTemplateId: '',
    currEditFile: "",
    content: '',
    // 最后一次保存/加载的内容，用于判断是否有未保存修改
    savedContent: '',
    isDirty: false,
    limit: 3,
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
    // AI 新建模板对话框（create：新建表单；history：历史生成记录列表）
    createDialog: {
        visible: false,
        view: 'create' as 'create' | 'history',
        templateName: '',
        requirement: '',
        loading: false,
        // 历史生成型会话列表（未绑定 templateId，含已应用/未应用）
        sessions: [] as any[],
        historyLoading: false
    },
    // 新建调整会话按钮 loading
    creatingAiSession: false,
    // AI 调整抽屉的实时预览：当前预览页面 + 刷新键（变化即强制 iframe 重载）
    aiPreviewEntry: '',
    aiPreviewKey: 0,
    // 文件树默认展开的节点（第一层）
    expandedKeys: [] as string[]
});

// ==================== AI 集成 ====================

/**
 * 判断是否为可路由的 HTML 文件（非 _ 开头的布局/宏文件）
 */
const isRoutableHtml = (file: string) =>
    !!file && file.toLowerCase().endsWith('.html') && !file.split('/').pop()!.startsWith('_');

/**
 * 实时预览可选页面：文件树中的可路由 HTML（含模板目录前缀，预览后端会截掉）
 */
const previewPageOptions = computed<string[]>(() => {
    const result: string[] = [];
    const walk = (nodes: any[]) => {
        for (const n of nodes || []) {
            if (n.children && n.children.length > 0) {
                walk(n.children);
            } else if (n.sortNum === 1 && isRoutableHtml(n.filePath || '')) {
                result.push(n.filePath);
            }
        }
    };
    walk(state.treeTableData as any[]);
    return result;
});

/**
 * 实时预览 iframe 地址：刷新键变化（AI 写盘/手动刷新）即重载
 */
const aiPreviewUrl = computed(() => {
    if (!state.loadedTemplateId || !state.aiPreviewEntry) return '';
    return '/template/preview/' + state.loadedTemplateId + '/' + state.aiPreviewEntry + '?t=' + state.aiPreviewKey;
});

/**
 * 初始化实时预览入口：优先当前编辑的可路由 HTML，其次 index.html，最后取第一个可选页
 */
const initAiPreviewEntry = () => {
    const options = previewPageOptions.value;
    if (isRoutableHtml(state.currEditFile) && options.includes(state.currEditFile)) {
        state.aiPreviewEntry = state.currEditFile;
    } else if (options.includes('index.html')) {
        state.aiPreviewEntry = 'index.html';
    } else if (options.length > 0) {
        state.aiPreviewEntry = options[0];
    } else {
        state.aiPreviewEntry = '';
    }
    state.aiPreviewKey = Date.now();
};

const refreshAiPreview = () => {
    state.aiPreviewKey = Date.now();
};

const openAiPreviewNewWindow = () => {
    if (aiPreviewUrl.value) window.open(aiPreviewUrl.value, '_blank');
};

/**
 * AI 每写完一个文件（SSE file 事件）的实时回调：刷新右侧预览
 */
const onAiFileWritten = (_path: string) => {
    state.aiPreviewKey = Date.now();
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
    window.open('/template/preview/' + encodeURIComponent(state.loadedTemplateId) + '/' + entry, '_blank');
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
 * 打开 AI 新建模板对话框（generate 模式）
 */
const onOpenAiCreate = () => {
    state.createDialog.view = 'create';
    state.createDialog.templateName = '';
    state.createDialog.requirement = '';
    state.createDialog.visible = true;
};

/**
 * 查看历史生成记录：加载生成型会话（未绑定 templateId），
 * 已应用/未应用全部显示，点击记录可回到抽屉继续处理
 */
const onShowHistory = async () => {
    state.createDialog.view = 'history';
    state.createDialog.historyLoading = true;
    try {
        const res = await aiApi.listSessions();
        state.createDialog.sessions = (res.data || []).filter((s: any) => !s.templateId);
    } catch (e: any) {
        ElMessage.error(e?.message || '加载历史记录失败');
    } finally {
        state.createDialog.historyLoading = false;
    }
};

/**
 * 打开历史生成会话：进入 generate 模式抽屉恢复会话（不自动发送消息）
 */
const onOpenHistorySession = (row: any) => {
    state.createDialog.visible = false;
    state.aiMode = 'generate';
    state.aiSessions = state.createDialog.sessions;
    state.currentAiSessionId = row.sessionId;
    state.currentAiSession = row;
    state.aiDrawerVisible = true;
};

/** 历史记录创建时间格式化（月-日 时:分） */
const formatHistoryTime = (created: any) => {
    if (!created) return '';
    const d = new Date(created);
    if (isNaN(d.getTime())) return '';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/**
 * 确认新建模板：创建生成型会话并自动发送首条需求
 */
const onCreateConfirm = async () => {
    const name = state.createDialog.templateName.trim();
    const requirement = state.createDialog.requirement.trim();
    if (!name) {
        ElMessage.warning('请输入模板目录名');
        return;
    }
    if (!/^[a-zA-Z][a-zA-Z0-9_-]*$/.test(name)) {
        ElMessage.warning('模板目录名必须以英文字母开头，只能包含字母、数字、下划线、横线');
        return;
    }
    if (!requirement) {
        ElMessage.warning('请输入需求描述');
        return;
    }
    state.createDialog.loading = true;
    try {
        const res = await aiApi.createSession({ templateName: name, requirement });
        if (!res.data) {
            ElMessage.error(res.msg || '创建会话失败');
            return;
        }
        // 会话创建成功：切换到生成模式并打开抽屉
        state.aiMode = 'generate';
        const listRes = await aiApi.listSessions();
        state.aiSessions = (listRes.data || []).filter((s: any) => !s.templateId);
        state.currentAiSessionId = res.data.sessionId;
        state.currentAiSession = res.data;
        state.createDialog.visible = false;
        state.aiDrawerVisible = true;
        // 抽屉渲染后自动发送首条需求（aiChat 内部会等待会话历史加载完成）
        nextTick(() => {
            aiChatRef.value?.autoSend(requirement);
        });
    } catch (e: any) {
        ElMessage.error(e?.message || '创建会话失败');
    } finally {
        state.createDialog.loading = false;
    }
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
 */
const onSelectAiSession = (sessionId: string) => {
    const session = state.aiSessions.find((s: any) => s.sessionId === sessionId);
    state.currentAiSession = session || null;
};

/**
 * AI 写盘后联动：刷新文件树；当前编辑的文件被 AI 修改过则重新加载内容
 */
const onAiFilesChanged = () => {
    loadFileTree();
    if (!state.currEditFile) return;
    templateApi.getTemplateFile(state.currEditFile, state.loadedTemplateId || undefined).then((res: any) => {
        // 仅当服务器内容与本地保存基线不一致（即 AI 确实改了当前文件）时刷新编辑器
        if (normalizeEol(res.data || '') !== normalizeEol(state.savedContent || '')) {
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
 * 生成型会话应用模板成功：刷新模板列表
 */
const onAiTemplateApplied = () => {
    loadTemplateList();
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

const loadTemplateList = () => {
    templateApi.getTemplateList().then((res: any) => {
        state.templateList = res.data || [];
        // 默认选中当前激活模板
        const active = state.templateList.find((item: any) => item.active);
        const target = active || state.templateList[0];
        if (target) {
            state.templateId = target.id;
            state.loadedTemplateId = target.id;
            state.uploadParam.templateId = target.id;
        }
        loadFileTree(true);
    })
}

/**
 * 加载文件树
 * @param openDefault  是否同时默认打开 index.html（仅首次进入/切换模板时传 true，
 *                     AI 写盘后的树刷新不能重置用户正在编辑的文件）
 */
const loadFileTree = (openDefault = false) => {
    state.treeLoading = true;
    templateApi.getTemplateFileTree(state.loadedTemplateId || undefined).then((res) => {
        state.treeTableData = res.data;
        // 默认展开第一层（顶层节点）
        state.expandedKeys = (res.data || []).map((n: any) => n.filePath);
        if (openDefault) {
            openDefaultFile();
        }
    }).finally(() => {
        state.treeLoading = false;
    })
}

/**
 * 在文件树中查找 index.html 并加载到编辑器（含模板目录前缀，如 xjd2022/index.html）
 */
const openDefaultFile = () => {
    const find = (nodes: any[]): any => {
        for (const n of nodes || []) {
            if (n.children && n.children.length > 0) {
                const hit = find(n.children);
                if (hit) return hit;
            } else if ((n.filePath || '').toLowerCase().endsWith('/index.html') || n.filePath === 'index.html') {
                return n;
            }
        }
        return null;
    };
    const node = find(state.treeTableData as any[]);
    if (node) {
        state.currEditFile = node.filePath;
        templateApi.getTemplateFile(node.filePath, state.loadedTemplateId || undefined).then((res: any) => {
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

const onNodeClick = (node: any) => {
    const switchToFile = () => {
        // 用 sortNum 区分目录(0)与文件(1)：空目录（如仅剩 .properties 被过滤的 i18n 目录）children 为 null，不能按 children 判断
        if(node.sortNum === 0) {
            state.uploadParam.dirName = node.filePath;
            state.currEditFile = '';
            state.content = '';
            state.savedContent = '';
            checkDirty();
        }else {
            state.currEditFile = node.filePath;
            templateApi.getTemplateFile(node.filePath, state.loadedTemplateId || undefined).then((res: any) => {
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
        state.isDirty = false;
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
    ElMessage.success("上传成功");
    loadFileTree();
}
const onHandleExceed = () => {
    ElMessage.error("上传文件数量不能超过 "+state.limit+" 个!");
}
const onHandleUploadError = () => {
    ElMessage.error("上传失败");
}
const onBeforeUpload = () => {
    if(state.uploadParam.dirName == '') {
        ElMessage.warning("请选择上传目录");
        return false;
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
    let clientHight = document.documentElement.clientHeight;
    state.clientHight = clientHight + "px";
    window.addEventListener('beforeunload', onBeforeUnload);
});

onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload);
});
</script>

<style lang="scss">
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
// 抽屉主体撑满高度（抽屉默认 teleport 到 body，需用全局选择器）
.ai-template-drawer {
    .el-drawer__body {
        display: flex;
        flex-direction: column;
        overflow: hidden;
    }
}
// 历史生成记录列表（el-dialog 同样 teleport 到 body，需全局选择器）
.history-session-table {
    cursor: pointer;
}

.history-tip {
    margin-top: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
}
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

        // aiChat 根元素撑满列（组件内部 height:100% 在 flex 列中不稳）
        > :deep(.ai-chat-panel) {
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

            .preview-frame {
                width: 100%;
                height: 100%;
                border: 0;
                background: #fff;
                display: block;
            }
        }
    }
}
.dirty-tip {
    color: #e6a23c;
    font-size: 12px;
    margin-left: 10px;
}
.CodeMirror-scroll {
  overflow: scroll !important;
  margin-bottom: 0;
  margin-right: 0;
  padding-bottom: 0;
  height: 600;
  outline: none;
  position: relative;
  border: 1px solid #dddddd;
}
.code-mirror{
  font-size : 13px;
  line-height : 150%;
  height: 600px;
  text-align: left;
}
</style>
