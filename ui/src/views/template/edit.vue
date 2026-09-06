<template>
<div class="container">
    <el-card>
        <div class="toolbar">
            <el-row :gutter="35" class="toolbar-row">
                <el-col :sm="5" class="mb20">
                    <!-- 会话编辑模式：AI 生成模板（未应用）横幅，应用后无缝切回正式模板编辑 -->
                    <div v-if="state.sessionMode" class="session-banner">
                        <el-tag size="default" type="warning" class="session-banner-tag">AI 模板：{{ state.currentAiSession?.templateName }}</el-tag>
                        <el-tag v-if="state.currentAiSession?.status === 'applied'" size="default" type="success">已应用（只读）</el-tag>
                        <template v-else>
                            <el-button size="default" type="success" :loading="state.sessionApplying" @click="onApplySessionTemplate">
                                <el-icon><ele-Check /></el-icon>应用模板
                            </el-button>
                        </template>
                        <el-button size="default" type="warning" plain @click="state.aiDrawerVisible = true"
                                   title="打开 AI 对话抽屉：左边实时预览，右边 AI 对话调整">
                            <el-icon><ele-ChatDotRound /></el-icon>AI 对话
                        </el-button>
                        <el-button size="default" @click="exitSessionMode" title="退出会话编辑，回到正式模板编辑">
                            <el-icon><ele-Back /></el-icon>退出编辑
                        </el-button>
                    </div>
                    <el-select v-else v-model="state.templateId" placeholder="选择模板" filterable style="width: 100%" @change="onTemplateChange">
                        <el-option v-for="item in state.templateList" :key="item.id" :value="item.id"
                                   :label="item.name + (item.active ? '（使用中）' : '')" />
                    </el-select>
                </el-col>
                <el-col :sm="19" class="mb20">
                    <div class="toolbar-actions">
                        <el-upload
                            class="upload-btn"
                            :action="uploadAction"
                            name="files"
                            :data="uploadData"
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
                        <el-divider direction="vertical" />
                        <el-button @click="onPreview" :disabled="!state.sessionMode && !state.loadedTemplateId">
                            <el-icon><ele-View /></el-icon>预览
                        </el-button>
                        <el-button v-if="!state.sessionMode" type="warning" plain @click="onOpenAiAdjust" :disabled="!state.loadedTemplateId">
                            <el-icon><ele-MagicStick /></el-icon>AI 调整
                        </el-button>
                        <el-button v-if="!state.sessionMode" type="warning" @click="onOpenAiCreate">
                            <el-icon><ele-MagicStick /></el-icon>AI 新建模板
                        </el-button>
                        <el-divider direction="vertical" />
                        <el-button type="primary" @click="onSaveFile" :disabled="!state.currEditFile || sessionReadonly">保 存</el-button>
                        <el-button type="danger" @click="onDelFile" :disabled="!state.currEditFile || sessionReadonly">删 除</el-button>
                        <span v-if="state.isDirty" class="dirty-tip">● 有未保存的修改</span>
                    </div>
                </el-col>
            </el-row>
        </div>
        <el-form style="padding-top: 5px;" size="default" label-width="100px" ref="myRefForm">
            <el-row :gutter="35">
                <el-col :sm="5" class="mb20">
                    <div class="tree-container">
                        <el-card shadow="hover">
                            <template #header>
                                <div class="tree-card-header">
                                    <span>模板文件树</span>
                                    <el-button size="small" text :loading="state.treeLoading"
                                               title="刷新文件树" @click="loadFileTree()">
                                        <el-icon><ele-Refresh /></el-icon>
                                    </el-button>
                                </div>
                            </template>
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
                    <!-- 图片工作台：文件树点选图片文件时覆盖代码编辑器（左原图 / 右生成结果对比，确认后应用） -->
                    <div v-if="state.imagePreview.visible" class="img-workbench" :style="{ height: state.clientHight }">
                        <div class="img-workbench-toolbar">
                            <el-tag size="small" type="info">{{ state.imagePreview.filePath }}</el-tag>
                            <div class="img-workbench-toolbar-actions">
                                <el-button size="small" :loading="state.imagePreview.restoring" title="用 .bak 备份覆盖回当前图片（撤销已应用的修改）"
                                           @click="restoreTemplateImage">
                                    <el-icon><ele-RefreshLeft /></el-icon>恢复原图
                                </el-button>
                                <el-button size="small" title="关闭图片工作台，回到代码编辑器" @click="closeImageWorkbench">
                                    <el-icon><ele-Close /></el-icon>关闭
                                </el-button>
                            </div>
                        </div>
                        <div class="img-compare">
                            <div class="img-compare-pane">
                                <div class="img-compare-label">原图</div>
                                <div class="img-compare-body">
                                    <img v-if="state.imagePreview.url" :src="state.imagePreview.url"
                                         class="img-compare-el" title="点击新窗口查看原图" @click="openImageRaw" />
                                </div>
                            </div>
                            <div class="img-compare-pane">
                                <div class="img-compare-label">生成结果</div>
                                <div class="img-compare-body">
                                    <img v-if="state.imageEdit.resultUrl" :src="state.imageEdit.resultUrl"
                                         class="img-compare-el" title="点击新窗口查看生成结果" @click="openResultRaw" />
                                    <div v-else class="img-compare-empty">
                                        提交修图要求后，生成结果将显示在此处与原图对比
                                    </div>
                                </div>
                            </div>
                        </div>
                        <div class="img-edit-form" v-if="!state.sessionMode">
                            <el-input v-model="state.imageEdit.prompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                                      placeholder="描述修图要求，例如：把背景换成浅蓝色，去掉右下角的水印" />
                            <div class="img-edit-actions">
                                <el-button type="primary" size="small" :loading="state.imageEdit.submitting"
                                           :disabled="state.imageEdit.status === 'pending' || state.imageEdit.status === 'running'"
                                           @click="submitTemplateImageEdit">
                                    <el-icon><ele-MagicStick /></el-icon>AI 修图
                                </el-button>
                                <el-button v-if="state.imageEdit.resultUrl && state.imageEdit.status === 'success'"
                                           type="success" size="small" :loading="state.imageEdit.applying"
                                           title="将右侧生成结果写入模板文件（原件备份为 .bak）"
                                           @click="applyImageEdit">
                                    <el-icon><ele-Check /></el-icon>应用
                                </el-button>
                                <el-button v-if="state.imageEdit.taskId && state.imageEdit.status === 'failed'" size="small"
                                           type="warning" @click="retryTemplateImageEdit">重试</el-button>
                            </div>
                            <div class="gen-status">
                                <template v-if="state.imageEdit.status === 'pending' || state.imageEdit.status === 'running'">
                                    <el-icon class="is-loading"><ele-Loading /></el-icon>
                                    <span>AI 修图中（约 10~60 秒）...</span>
                                </template>
                                <template v-else-if="state.imageEdit.status === 'failed'">
                                    <span class="gen-error">修图失败：{{ state.imageEdit.error || '未知错误' }}</span>
                                </template>
                                <span v-else-if="state.imageEdit.status === 'success'" class="gen-done">修图完成，请对比左右图片，满意后点击「应用」写入模板（原件备份为 .bak，可「恢复原图」撤销）</span>
                            </div>
                        </div>
                    </div>
                    <Codemirror
                            v-else
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

        <!-- AI 对话抽屉（全屏覆盖；调整型/会话编辑：左预览右对话，AI 每写一个文件自动刷新预览） -->
        <el-drawer v-model="state.aiDrawerVisible" size="100%" :close-on-click-modal="false" custom-class="ai-template-drawer">
            <template #header>
                <div class="drawer-header">
                    <span class="drawer-title">{{ state.sessionMode ? '编辑 AI 模板' : (state.aiMode === 'adjust' ? 'AI 调整模板' : 'AI 生成模板') }}</span>
                </div>
            </template>
            <div class="ai-drawer-body" :class="{ split: state.aiMode === 'adjust' || state.sessionMode }">
                <div v-if="state.aiMode === 'adjust' || state.sessionMode" class="ai-preview-col" :class="{ 'chat-collapsed': state.aiChatCollapsed }">
                    <div class="preview-toolbar">
                        <el-select v-model="state.aiPreviewEntry" size="small" filterable placeholder="选择预览页面">
                            <el-option v-for="p in previewPageOptions" :key="p" :value="p" :label="p" />
                        </el-select>
                        <el-button size="small" :type="state.imagePickMode ? 'primary' : ''"
                                   :title="state.imagePickMode ? '换图模式已开启：点击预览页中的图片进行更换' : '开启换图模式：点选预览页中的图片进行更换'"
                                   @click="toggleImagePickMode">
                            <el-icon><ele-PictureFilled /></el-icon>换图
                        </el-button>
                        <el-button v-if="state.sessionMode && !sessionReadonly" size="small"
                                   :type="state.sectionSelectMode ? 'primary' : ''"
                                   :title="state.sectionSelectMode ? '选区模式已开启：点击预览页中的区块锁定为 AI 对话目标' : '开启选区模式：点选预览页中的区块，后续 AI 对话只修改该区块'"
                                   @click="toggleSectionSelectMode">
                            <el-icon><ele-Position /></el-icon>选区
                        </el-button>
                        <el-button size="small" @click="refreshAiPreview" title="刷新预览">
                            <el-icon><ele-Refresh /></el-icon>
                        </el-button>
                        <el-button size="small" @click="openAiPreviewNewWindow" title="新窗口打开">
                            <el-icon><ele-FullScreen /></el-icon>
                        </el-button>
                    </div>
                    <div class="preview-frame-wrap" :class="{ picking: state.imagePickMode, sectioning: state.sectionSelectMode }">
                        <iframe v-if="aiPreviewUrl" ref="aiPreviewFrameRef" :src="aiPreviewUrl"
                                class="preview-frame" frameborder="0" @load="onPreviewFrameLoad"></iframe>
                        <div v-if="state.imagePickMode" class="pick-mode-tip">换图模式：点击预览页中高亮的图片（再次点击「换图」按钮退出）</div>
                        <div v-else-if="state.sectionSelectMode" class="pick-mode-tip">选区模式：点击预览页中的区块锁定为 AI 对话目标（再次点击「选区」按钮退出）</div>
                    </div>
                </div>
                <div class="ai-chat-col" :class="{ collapsed: (state.aiMode === 'adjust' || state.sessionMode) && state.aiChatCollapsed }">
                    <!-- adjust/会话编辑模式：收缩/展开切换按钮 -->
                    <div v-if="state.aiMode === 'adjust' || state.sessionMode" class="chat-collapse-bar">
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
                                 :current-file="state.aiMode === 'adjust' ? (state.aiPreviewEntry || state.currEditFile) : ''"
                                 :focus-section="state.selectedSection?.sectionId || ''"
                                 :focus-element-hint="state.selectedSection?.elementHint || ''"
                                 :sessions="state.aiSessions" :creating-session="state.creatingAiSession"
                                 @select-session="onSelectAiSession" @new-session="onNewAiSession"
                                 @files-changed="onAiFilesChanged" @file-written="onAiFileWritten" @applied="onAiTemplateApplied"
                                 @edit-files="onEditSessionFiles" />
                    </div>
                </div>
            </div>
        </el-drawer>

        <!-- 预览页点选换图对话框（搜附件库 / AI 生成 / 上传，选定后更新图片槽位） -->
        <el-dialog v-model="state.imagePickDialog.visible" width="720px" top="6vh" append-to-body
                   :close-on-click-modal="false">
            <template #header>
                <div class="pick-dialog-title">
                    <span>更换图片</span>
                    <el-tag v-if="state.imagePickDialog.rawSrc" size="small" type="warning">演示图片 · 仅预览生效</el-tag>
                    <el-tag v-else size="small" type="info">{{ state.imagePickDialog.slot }} @ {{ state.imagePickDialog.sectionId }}</el-tag>
                </div>
            </template>
            <el-tabs v-model="state.imagePickDialog.tab">
                <!-- 附件库：搜索 + 分页图片网格，点选即应用 -->
                <el-tab-pane label="附件库" name="library">
                    <div class="pick-toolbar">
                        <el-input v-model="state.attLib.keyword" size="small" placeholder="按文件名搜索图片，回车搜索" clearable
                                  style="width: 260px" @keyup.enter="searchAttImages">
                            <template #append>
                                <el-button @click="searchAttImages">
                                    <el-icon><ele-Search /></el-icon>
                                </el-button>
                            </template>
                        </el-input>
                        <span class="pick-toolbar-tip">共 {{ state.attLib.total }} 张</span>
                    </div>
                    <div v-loading="state.attLib.loading" class="pick-grid"
                         :style="{ minHeight: '200px' }">
                        <div v-for="img in state.attLib.list" :key="img.id" class="pick-cell"
                             :title="img.fileName" @click="applyImageSlot(img.id)">
                            <el-image :src="img.typePath" fit="cover" class="pick-img" lazy />
                            <div class="pick-name">{{ img.fileName }}</div>
                        </div>
                        <el-empty v-if="!state.attLib.loading && state.attLib.list.length === 0"
                                  description="附件库暂无图片" :image-size="60" />
                    </div>
                    <el-pagination v-if="state.attLib.total > state.attLib.pageSize" small background layout="prev, pager, next"
                                   :total="state.attLib.total" :page-size="state.attLib.pageSize"
                                   :current-page="state.attLib.page" class="pick-pager"
                                   @current-change="(p: number) => { state.attLib.page = p; loadAttImages(); }" />
                </el-tab-pane>
                <!-- AI 生成：prompt 提交 → 轮询任务 → 结果网格点选应用 -->
                <el-tab-pane label="AI 生成" name="generate">
                    <div class="gen-form">
                        <el-input v-model="state.imageGen.prompt" type="textarea" :rows="3" maxlength="500" show-word-limit
                                  placeholder="描述想要的图片，例如：现代简约风格的办公室照片，自然光，蓝白色调" />
                        <div class="gen-actions">
                            <el-select v-model="state.imageGen.size" size="small" style="width: 140px">
                                <el-option label="1024*1024 方图" value="1024*1024" />
                                <el-option label="1280*720 横图" value="1280*720" />
                                <el-option label="720*1280 竖图" value="720*1280" />
                            </el-select>
                            <el-select v-model="state.imageGen.num" size="small" style="width: 110px">
                                <el-option v-for="n in [1, 2, 4]" :key="n" :label="n + ' 张'" :value="n" />
                            </el-select>
                            <el-button type="primary" size="small" :loading="state.imageGen.submitting"
                                       :disabled="!state.imageGen.prompt.trim()" @click="submitImageGen">
                                <el-icon><ele-MagicStick /></el-icon>生成
                            </el-button>
                            <el-button v-if="state.imageGen.taskId && state.imageGen.status === 'failed'" size="small"
                                      type="warning" @click="retryImageGen">重试</el-button>
                        </div>
                    </div>
                    <div class="gen-status">
                        <template v-if="state.imageGen.status === 'pending' || state.imageGen.status === 'running'">
                            <el-icon class="is-loading"><ele-Loading /></el-icon>
                            <span>AI 生图中（约 10~60 秒）...</span>
                        </template>
                        <template v-else-if="state.imageGen.status === 'failed'">
                            <span class="gen-error">生成失败：{{ state.imageGen.error || '未知错误' }}</span>
                        </template>
                        <span v-else-if="state.imageGen.status === 'success'" class="gen-done">生成完成，点击图片应用到该位置（已存入附件库）</span>
                    </div>
                    <div v-if="state.imageGen.results.length" class="pick-grid">
                        <div v-for="(r, i) in state.imageGen.results" :key="i" class="pick-cell"
                             :title="r.url" @click="applyImageSlot(r.attachmentId)">
                            <el-image :src="r.url" fit="cover" class="pick-img" />
                            <div class="pick-name">生成结果 {{ i + 1 }}</div>
                        </div>
                    </div>
                </el-tab-pane>
                <!-- 上传：直传附件库，成功后切到附件库 tab 点选刚上传的图片 -->
                <el-tab-pane label="上传图片" name="upload">
                    <el-upload class="pick-upload" drag multiple accept="image/*"
                              :action="state.pickUploadUrl" name="files" :headers="state.headers"
                              :show-file-list="false" :on-success="onPickUploadSuccess" :on-error="onPickUploadError">
                        <el-icon class="el-icon--upload"><ele-UploadFilled /></el-icon>
                        <div class="el-upload__text">拖拽图片到此处，或<em>点击上传</em></div>
                        <template #tip>
                            <div class="el-upload__tip">上传后存入附件库，请在列表中点击刚上传的图片应用（按上传时间倒序排在最前）</div>
                        </template>
                    </el-upload>
                </el-tab-pane>
            </el-tabs>
            <div v-if="state.imagePickDialog.applying" class="pick-applying">
                <el-icon class="is-loading"><ele-Loading /></el-icon>
                <span>正在更新图片槽位并重渲染模板...</span>
            </div>
        </el-dialog>

        <!-- AI 新建模板对话框（含历史生成记录入口） -->
        <el-dialog v-model="state.createDialog.visible"
                   :title="state.createDialog.view === 'history' ? '历史生成记录' : 'AI 新建模板'"
                   :width="state.createDialog.view === 'history' ? '680px' : '520px'" :close-on-click-modal="false">
            <el-form v-if="state.createDialog.view === 'create'" label-width="90px">
                <el-form-item label="模板目录名" required>
                    <el-input v-model="state.createDialog.templateName" placeholder="英文目录名，以字母开头，如 my-company"
                              @input="onTemplateNameInput" />
                </el-form-item>
                <el-form-item label="需求描述" required>
                    <el-input v-model="state.createDialog.requirement" type="textarea" :rows="4"
                              placeholder="描述模板需求，例如：企业官网模板，蓝色调，响应式设计" />
                </el-form-item>
                <el-form-item label="移动端适配">
                    <el-checkbox v-model="state.createDialog.mobileAdaptive">生成响应式布局（多端断点 + 移动端汉堡菜单）</el-checkbox>
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
import { reactive, computed, onMounted, onBeforeUnmount, ref, nextTick, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessageBox, ElMessage } from 'element-plus';
import { Local } from '/@/utils/storage';
import { TemplateApi } from '/@/api/template/index';
import { AiTemplateApi, AiImageApi } from '/@/api/ai/index';
import { AttachApi } from '/@/api/attach/index';
import AiChat from '/@/views/template/aiChat.vue';
import { Codemirror } from "vue-codemirror";
import { html } from "@codemirror/lang-html";
import { oneDark } from "@codemirror/theme-one-dark";

const codeMirror = ref()
const treeTable = ref()
const extensions = [html(), oneDark];

const templateApi = TemplateApi();
const aiApi = AiTemplateApi();
const aiImageApi = AiImageApi();
const attachApi = AttachApi();
const aiChatRef = ref();
const aiPreviewFrameRef = ref();
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
    // ===== 会话编辑模式（生成型会话应用前的手工打磨） =====
    // true 时文件树/编辑器/预览/保存/删除/上传全部切到会话工作目录
    sessionMode: false,
    // banner「应用模板」按钮 loading
    sessionApplying: false,
    // AI 新建模板对话框（create：新建表单；history：历史生成记录列表）
    createDialog: {
        visible: false,
        view: 'create' as 'create' | 'history',
        templateName: '',
        requirement: '',
        // 是否适配移动端（默认开启：响应式布局 + 移动端汉堡菜单）
        mobileAdaptive: true,
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
    // ===== 预览页点选换图 =====
    // 换图模式：开启后向预览 iframe 注入点选钩子（高亮 data-ai-slot 图片，点击弹操作窗）
    imagePickMode: false,
    // ===== 预览页选区修改 =====
    // 选区模式：开启后向预览 iframe 注入区块钩子（hover 高亮 data-ai-section-root 区块，点击锁定为 AI 对话目标）
    sectionSelectMode: false,
    // 已锁定的选中区块（AI 对话聚焦目标，随 chat 请求发送 focusSectionId）：
    // elementHint 为点选时命中的具体元素描述（如 标题「散养土鸡蛋」），做元素级语义提示
    selectedSection: null as { sectionId: string; elementHint: string } | null,
    // 换图模式注入的 click 捕获监听（跨 iframe 重载复用同一函数，便于移除）
    // 点选换图对话框（槽位图：slot/sectionId 来自预览页 data-ai-slot/data-ai-section 标记；
    // 演示图：rawSrc 为点击图片的原样 src，改 _preview_data.json 仅预览生效）
    imagePickDialog: {
        visible: false,
        sectionId: '',
        slot: '',
        rawSrc: '',
        tab: 'library' as 'library' | 'generate' | 'upload',
        applying: false
    },
    // 附件库图片检索（typePath 为图片直显地址）
    attLib: {
        keyword: '',
        page: 1,
        pageSize: 12,
        total: 0,
        list: [] as any[],
        loading: false
    },
    // AI 生图任务（提交即返回 + 轮询 task/{id} 至 success/failed）
    imageGen: {
        prompt: '',
        size: '1280*720',
        num: 2,
        submitting: false,
        taskId: null as any,
        status: '' as '' | 'pending' | 'running' | 'success' | 'failed',
        error: '',
        results: [] as any[],
        pollTimer: null as any
    },
    // 换图上传接口（直传附件库）
    pickUploadUrl: import.meta.env.VITE_API_URL + '/admin/attachment/upload',
    // ===== 模板图片工作台（覆盖代码编辑器：左原图 / 右生成结果对比，确认后应用） =====
    // 图片工作台激活状态（文件树点选图片文件打开）：url 带刷新键，应用/恢复原图后重载左侧原图
    imagePreview: {
        visible: false,
        filePath: '',
        url: '',
        key: 0,
        restoring: false
    },
    // 模板图片 AI 修图任务（edit 类型 + sourceTemplateId/sourceFilePath，
    // 成功后结果仅存附件库展示在右侧，用户点「应用」才回写模板文件）
    imageEdit: {
        prompt: '',
        submitting: false,
        taskId: null as any,
        status: '' as '' | 'pending' | 'running' | 'success' | 'failed',
        error: '',
        // 最新一次修图的结果图（显示在右侧与原图对比，应用后清空）
        resultUrl: '',
        applying: false,
        pollTimer: null as any
    },
    // 文件树默认展开的节点（第一层）
    expandedKeys: [] as string[],
    // AI 对话框收缩状态（仅 adjust 模式）
    aiChatCollapsed: false
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
 *
 * 会话编辑模式走会话预览路由（按 sessionId 定位工作目录），
 * 文件路径含模板目录前缀，后端 AiTemplatePreviewController 会截掉
 */
const aiPreviewUrl = computed(() => {
    if (state.sessionMode) {
        const sess = state.currentAiSession;
        if (!sess?.sessionId || !sess.templateName || !state.aiPreviewEntry) return '';
        return '/ai/template/preview/' + sess.sessionId + '/' + sess.templateName + '/' + state.aiPreviewEntry + '?t=' + state.aiPreviewKey;
    }
    if (!state.loadedTemplateId || !state.aiPreviewEntry) return '';
    return '/template/preview/' + state.loadedTemplateId + '/' + state.aiPreviewEntry + '?t=' + state.aiPreviewKey;
});

// ==================== 会话编辑模式：接口分流 ====================

/** 上传地址：会话编辑模式传会话工作目录，否则传正式模板目录 */
const uploadAction = computed(() =>
    state.sessionMode && state.currentAiSession?.sessionId
        ? aiApi.sessionUploadUrl(state.currentAiSession.sessionId)
        : state.uploadUrl
);

/** 上传附加参数：会话模式仅需 dirName（sessionId 在 URL 中）；正式模式带 templateId */
const uploadData = computed(() =>
    state.sessionMode
        ? { dirName: state.uploadParam.dirName }
        : { dirName: state.uploadParam.dirName, templateId: state.uploadParam.templateId }
);

/** 会话编辑模式下已应用的会话：只读（后端拒绝写入，前端同步禁用保存/删除/上传） */
const sessionReadonly = computed(() => state.sessionMode && state.currentAiSession?.status === 'applied');

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

// ==================== 预览页点选换图 ====================

/**
 * 切换换图模式：开启后预览 iframe 中带 data-ai-slot 标记的图片高亮可点选
 *
 * 预览页与本页同源（/template/preview/**），通过 contentDocument 直接注入
 * 样式与 click 捕获监听；iframe 因刷新键变化重载时在 @load 中重新注入。
 */
const toggleImagePickMode = () => {
    state.imagePickMode = !state.imagePickMode;
    if (state.imagePickMode && !state.currentAiSession?.sessionId) {
        ElMessage.warning('当前无 AI 调整会话，请先打开 AI 调整');
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
 * 会话模式 /ai/template/preview/{sessionId}/{templateName}/{entry}，
 * 调整模式 /template/preview/{templateId}/{entry}（entry 含模板目录前缀）
 */
const extractPreviewEntry = (pathname: string): string => {
    try {
        if (state.sessionMode) {
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
    if (entry !== state.aiPreviewEntry) {
        state.aiPreviewEntry = entry;
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
        if (entry && isRoutableHtml(entry) && entry !== state.aiPreviewEntry) {
            state.aiPreviewEntry = entry;
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
                [data-ai-slot] { outline: 2px dashed var(--el-color-primary, #409eff) !important; outline-offset: 2px; cursor: pointer !important; }
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
        openImagePickDialog(sectionId, slot, '');
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
    openImagePickDialog('', '', rawSrc);
};

/**
 * 打开换图操作窗并加载附件库图片
 *
 * 槽位图传 sectionId/slot；演示图传 rawSrc（仅预览生效）
 */
const openImagePickDialog = (sectionId: string, slot: string, rawSrc: string) => {
    state.imagePickDialog.sectionId = sectionId;
    state.imagePickDialog.slot = slot;
    state.imagePickDialog.rawSrc = rawSrc;
    state.imagePickDialog.tab = 'library';
    state.imagePickDialog.visible = true;
    resetImageGen();
    state.attLib.page = 1;
    loadAttImages();
};

// ==================== 预览页选区修改 ====================

/** 选区模式注入的样式/类名常量（与 iframe 文档内约定一致） */
const SECTION_STYLE_ID = '__ai_section_select_style__';
const SECTION_SELECTED_CLASS = '__ai_section_selected__';

/**
 * 切换选区模式：开启后预览 iframe 中的组件区块（data-ai-section-root 根标记）
 * hover 高亮，点击锁定为 AI 对话目标（后续对话只修改该区块）
 *
 * 仅会话编辑模式可用（按钮有 v-if 门控；组件化会话走 PageSpec 往返，AI 可按 section 修改）
 */
const toggleSectionSelectMode = () => {
    state.sectionSelectMode = !state.sectionSelectMode;
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
 * 加载附件库图片（分页 + 文件名模糊搜索，只取 image 类型）
 */
const loadAttImages = async () => {
    state.attLib.loading = true;
    try {
        const res: any = await attachApi.getAttachList({
            page: state.attLib.page,
            pageSize: state.attLib.pageSize,
            fileType: 'image',
            fileName: state.attLib.keyword || undefined
        });
        state.attLib.list = res.data?.records || [];
        state.attLib.total = res.data?.total || 0;
    } catch (e: any) {
        ElMessage.error(e?.message || '加载附件库图片失败');
    } finally {
        state.attLib.loading = false;
    }
};

/** 附件库搜索（回到第一页） */
const searchAttImages = () => {
    state.attLib.page = 1;
    loadAttImages();
};

/**
 * 应用换图：按图片来源分流
 * - 槽位图（有 sectionId/slot）：更新 _pagespec.json（spec 替换 → 校验 → 重渲染 → 持久化），正式生效
 * - 演示图（有 rawSrc）：更新 _preview_data.json 的 imageOverrides，仅预览生效
 *
 * attachmentId 三个来源归一：附件库点选 / AI 生成结果（已自动入库）/ 上传后入库
 */
const applyImageSlot = async (attachmentId: number) => {
    const sessionId = state.currentAiSession?.sessionId;
    if (!sessionId) {
        ElMessage.warning('当前无 AI 调整会话');
        return;
    }
    if (!attachmentId) {
        ElMessage.warning('缺少附件 ID');
        return;
    }
    const isPreviewOnly = !!state.imagePickDialog.rawSrc && !state.imagePickDialog.slot;
    state.imagePickDialog.applying = true;
    try {
        const res: any = isPreviewOnly
            ? await aiApi.updatePreviewImage(sessionId, {
                imageUrl: state.imagePickDialog.rawSrc,
                attachmentId
            })
            : await aiApi.updateImageSlot(sessionId, {
                sectionId: state.imagePickDialog.sectionId,
                slot: state.imagePickDialog.slot,
                attachmentId
            });
        if (res.data) {
            ElMessage.success(isPreviewOnly ? '演示图片已更换（仅预览生效）' : '图片已更换');
            state.imagePickDialog.visible = false;
            stopImageGenPolling();
            // 刷新预览 iframe；槽位图重渲染了模板文件需同步刷新文件树
            refreshAiPreview();
            if (!isPreviewOnly) {
                loadFileTree();
            }
        } else {
            ElMessage.error(res.msg || '更换图片失败');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '更换图片失败');
    } finally {
        state.imagePickDialog.applying = false;
    }
};

/** 重置 AI 生图表单（打开操作窗/应用成功后） */
const resetImageGen = () => {
    stopImageGenPolling();
    state.imageGen.prompt = '';
    state.imageGen.submitting = false;
    state.imageGen.taskId = null;
    state.imageGen.status = '';
    state.imageGen.error = '';
    state.imageGen.results = [];
};

/** 停止生图任务轮询 */
const stopImageGenPolling = () => {
    if (state.imageGen.pollTimer) {
        clearInterval(state.imageGen.pollTimer);
        state.imageGen.pollTimer = null;
    }
};

/**
 * 提交 AI 生图任务（t2i 文生图），提交即返回，前端轮询至完成
 */
const submitImageGen = async () => {
    const prompt = state.imageGen.prompt.trim();
    if (!prompt) {
        ElMessage.warning('请描述想要生成的图片');
        return;
    }
    state.imageGen.submitting = true;
    state.imageGen.results = [];
    state.imageGen.error = '';
    try {
        const res: any = await aiImageApi.generate({
            taskType: 't2i',
            prompt,
            size: state.imageGen.size,
            num: state.imageGen.num
        });
        if (!res.data) {
            ElMessage.error(res.msg || '提交生图任务失败');
            return;
        }
        state.imageGen.taskId = res.data.id;
        state.imageGen.status = res.data.status || 'pending';
        startImageGenPolling();
    } catch (e: any) {
        ElMessage.error(e?.message || '提交生图任务失败');
    } finally {
        state.imageGen.submitting = false;
    }
};

/** 重试失败的生图任务 */
const retryImageGen = async () => {
    if (!state.imageGen.taskId) return;
    try {
        const res: any = await aiImageApi.retry(state.imageGen.taskId);
        if (res.data) {
            state.imageGen.taskId = res.data.id;
            state.imageGen.status = res.data.status || 'pending';
            state.imageGen.error = '';
            startImageGenPolling();
        } else {
            ElMessage.error(res.msg || '重试失败');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '重试失败');
    }
};

/**
 * 轮询生图任务状态：3 秒一次，success 时解析 results（url + attachmentId），failed 时展示错误
 */
const startImageGenPolling = () => {
    stopImageGenPolling();
    state.imageGen.pollTimer = setInterval(async () => {
        if (!state.imageGen.taskId) {
            stopImageGenPolling();
            return;
        }
        try {
            const res: any = await aiImageApi.getTask(state.imageGen.taskId);
            const task = res.data;
            if (!task) return;
            state.imageGen.status = task.status;
            if (task.status === 'success') {
                stopImageGenPolling();
                state.imageGen.results = (task.results || []).filter((r: any) => r.attachmentId);
            } else if (task.status === 'failed') {
                stopImageGenPolling();
                state.imageGen.error = task.error || '';
            }
        } catch (e) {
            // 单次轮询异常不打断（网络抖动等），下轮继续
        }
    }, 3000);
};

/** 换图上传成功：入库成功后切到附件库 tab（最新上传排在最前），由用户点选应用 */
const onPickUploadSuccess = () => {
    ElMessage.success('上传成功，请在列表中点击刚上传的图片应用');
    state.imagePickDialog.tab = 'library';
    state.attLib.keyword = '';
    state.attLib.page = 1;
    loadAttImages();
};

const onPickUploadError = () => {
    ElMessage.error('上传失败');
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
 * 会话编辑模式走会话预览路由（/ai/template/preview/{sessionId}/{templateName}/**）
 */
const onPreview = () => {
    let entry = 'index.html';
    if (isRoutableHtml(state.currEditFile)) {
        entry = state.currEditFile;
    }
    if (state.sessionMode && state.currentAiSession?.sessionId) {
        const sess = state.currentAiSession;
        window.open('/ai/template/preview/' + sess.sessionId + '/' + sess.templateName + '/' + entry, '_blank');
        return;
    }
    if (!state.loadedTemplateId) return;
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
    state.createDialog.mobileAdaptive = true;
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
 * 模板目录名输入过滤：只允许字母、数字、下划线、横线，非法字符（含中文、空格、粘贴内容）即时剥离
 *
 * "以字母开头"不在输入时强制（避免与用户输入过程打架），提交时校验兜底
 */
const onTemplateNameInput = (val: string) => {
    state.createDialog.templateName = (val || '').replace(/[^a-zA-Z0-9_-]/g, '');
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
        const res = await aiApi.createSession({ templateName: name, requirement, mobileAdaptive: state.createDialog.mobileAdaptive });
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
 *
 * 会话编辑模式下切到其他生成会话：文件树/预览随之切到新会话的工作目录；
 * 切到已应用的会话则退出会话编辑（只读，回到正式模板编辑）
 */
const onSelectAiSession = (sessionId: string) => {
    const session = state.aiSessions.find((s: any) => s.sessionId === sessionId);
    state.currentAiSession = session || null;
    if (!state.sessionMode) return;
    if (!session?.sessionId || session.status === 'applied') {
        exitSessionMode();
        return;
    }
    loadFileTree(true)?.then?.(() => initAiPreviewEntry());
};

/**
 * AI 写盘后联动：刷新文件树；当前编辑的文件被 AI 修改过则重新加载内容
 */
const onAiFilesChanged = () => {
    loadFileTree();
    if (!state.currEditFile) return;
    loadFileContent(state.currEditFile).then((res: any) => {
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

// ==================== 会话编辑模式（生成型会话，应用前编辑） ====================

/**
 * 进入会话编辑模式（aiChat「编辑文件」入口）
 *
 * 文件树/编辑器/保存/删除/上传/预览全部切换到会话工作目录（沙箱），
 * AI 抽屉同步变为「左预览 + 右对话」的分栏布局；
 * 正式模板上下文（loadedTemplateId）临时置空防误操作，退出时恢复
 */
const onEditSessionFiles = () => {
    const session = state.currentAiSession;
    if (!session?.sessionId || !session.templateName) return;
    if (session.status === 'applied') {
        ElMessage.warning('该会话已应用，如需继续调整请应用后在正式模板上使用「AI 调整」');
        return;
    }
    const doEnter = () => {
        state.sessionMode = true;
        // 正式模板上下文置空：所有读写已按 sessionMode 分流，置空兜底防误操作
        state.loadedTemplateId = '';
        state.currEditFile = '';
        state.content = '';
        state.savedContent = '';
        closeImageWorkbench();
        checkDirty();
        // 会话文件树加载完成后初始化抽屉左侧预览入口
        loadFileTree(true)?.then?.(() => initAiPreviewEntry());
    };
    if (checkDirty()) {
        confirmDiscard().then(doEnter).catch(() => {});
    } else {
        doEnter();
    }
};

/**
 * 退出会话编辑模式：恢复原先选中的正式模板（文件树/编辑器/预览切回正式目录）
 */
const exitSessionMode = () => {
    const doExit = () => {
        state.sessionMode = false;
        state.currEditFile = '';
        state.content = '';
        state.savedContent = '';
        state.uploadParam.dirName = '';
        // 会话上下文结束：选区锁定一并清除
        state.sectionSelectMode = false;
        clearSelectedSection();
        closeImageWorkbench();
        checkDirty();
        // 恢复正式模板上下文（templateId 在会话编辑期间未被改动）
        state.loadedTemplateId = state.templateId;
        state.uploadParam.templateId = state.templateId;
        loadFileTree(true);
    };
    if (checkDirty()) {
        confirmDiscard().then(doExit).catch(() => {});
    } else {
        doExit();
    }
};

/**
 * banner「应用模板」：应用当前生成会话到正式模板目录，成功后无缝切换
 */
const onApplySessionTemplate = () => {
    const session = state.currentAiSession;
    if (!session?.sessionId) return;
    ElMessageBox.confirm('确认将此模板应用到正式模板目录？应用后将切换到正式模板编辑。', '提示', {
        type: 'warning',
    }).then(async () => {
        state.sessionApplying = true;
        try {
            const res: any = await aiApi.applyTemplate(session.sessionId);
            if (res.data) {
                ElMessage.success(res.data.message || '应用成功');
                onAiTemplateApplied(res.data.templateId);
            } else if (res.msg) {
                ElMessage.error(res.msg);
            }
        } catch (e: any) {
            ElMessage.error(e?.message || '应用失败');
        } finally {
            state.sessionApplying = false;
        }
    }).catch(() => {});
};

/**
 * 生成型会话应用模板成功：无缝切换
 *
 * 应用后即「预览态」：关闭 AI 对话抽屉（无 AI 对话），退出会话编辑模式，
 * 直接载入应用后的正式模板（templateId 来自后端 ApplyResult）；
 * 后续如需 AI 继续调整，走正式模板的「AI 调整」（新建调整型会话）
 */
const onAiTemplateApplied = (templateId?: string) => {
    // 会话标记为已应用（若抽屉内还有引用，标签/输入禁用即时生效）
    if (state.currentAiSession) state.currentAiSession.status = 'applied';
    state.aiDrawerVisible = false;
    if (state.sessionMode) {
        state.sessionMode = false;
        state.currEditFile = '';
        state.content = '';
        state.savedContent = '';
        closeImageWorkbench();
        checkDirty();
    }
    // 刷新模板列表并选中应用后的模板（preferId 缺省回落到当前激活模板）
    loadTemplateList(templateId || undefined);
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
 * 加载文件树（会话编辑模式加载会话工作目录，否则加载正式模板目录）
 * @param openDefault  是否同时默认打开 index.html（仅首次进入/切换模板时传 true，
 *                     AI 写盘后的树刷新不能重置用户正在编辑的文件）
 * @returns 加载 Promise（供调用方在树就绪后初始化预览入口）
 */
const loadFileTree = (openDefault = false) => {
    state.treeLoading = true;
    const req = state.sessionMode && state.currentAiSession?.sessionId
        ? aiApi.getSessionFileTree(state.currentAiSession.sessionId)
        : templateApi.getTemplateFileTree(state.loadedTemplateId || undefined);
    return req.then((res: any) => {
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
 * 读取文件内容（会话编辑模式读会话工作目录，否则读正式模板目录）
 * filePath 约定与文件树一致：以模板目录名开头
 */
const loadFileContent = (filePath: string) => {
    return state.sessionMode && state.currentAiSession?.sessionId
        ? aiApi.getSessionFile(state.currentAiSession.sessionId, filePath)
        : templateApi.getTemplateFile(filePath, state.loadedTemplateId || undefined);
};

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
        // 会话编辑模式读会话工作目录，否则读正式模板目录
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
    // 会话编辑模式保存到会话工作目录（沙箱），否则保存到正式模板目录
    const req = state.sessionMode && state.currentAiSession?.sessionId
        ? aiApi.saveSessionFile(state.currentAiSession.sessionId, {
            filePath: state.currEditFile,
            fileContent: contentToSave
        })
        : templateApi.saveTemplateFile({
            filePath: state.currEditFile,
            fileContent: contentToSave,
            templateId: state.loadedTemplateId
        });
    return req.then(() => {
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
        // 会话编辑模式删会话工作目录的文件，否则删正式模板目录的文件
        const req = state.sessionMode && state.currentAiSession?.sessionId
            ? aiApi.delSessionFile(state.currentAiSession.sessionId, state.currEditFile)
            : templateApi.delTemplateFile(state.currEditFile, state.loadedTemplateId || undefined);
        req.then(() => {
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

/**
 * 图片预览地址：复用预览路由的静态文件分支，
 * filePath 含模板目录前缀（与文件树一致），后端会截掉前缀解析；
 * 会话编辑模式走会话预览路由（/ai/template/preview/{sessionId}/{templateName}/**），
 * 否则走正式模板预览路由（/template/preview/{templateId}/**）；
 * 逐段编码（保留 / 分隔符，避免 %2F 被 Tomcat 拒绝）
 */
const buildImageUrl = (filePath: string) => {
    const encodedPath = filePath.split('/').map(encodeURIComponent).join('/');
    if (state.sessionMode && state.currentAiSession?.sessionId) {
        const sess = state.currentAiSession;
        return '/ai/template/preview/' + encodeURIComponent(sess.sessionId) + '/' + encodeURIComponent(sess.templateName) + '/' + encodedPath + '?t=' + state.imagePreview.key;
    }
    return '/template/preview/' + encodeURIComponent(state.loadedTemplateId) + '/' + encodedPath + '?t=' + state.imagePreview.key;
};

/** 重置修图任务状态（keepPrompt：应用/恢复后保留提示词便于继续微调） */
const resetImageEditState = (keepPrompt = false) => {
    stopImageEditPolling();
    if (!keepPrompt) state.imageEdit.prompt = '';
    state.imageEdit.submitting = false;
    state.imageEdit.taskId = null;
    state.imageEdit.status = '';
    state.imageEdit.error = '';
    state.imageEdit.resultUrl = '';
    state.imageEdit.applying = false;
};

/** 打开图片工作台（文件树点选图片文件，覆盖代码编辑器） */
const openImagePreview = (filePath: string) => {
    state.imagePreview.filePath = filePath;
    state.imagePreview.key = Date.now();
    state.imagePreview.url = buildImageUrl(filePath);
    state.imagePreview.visible = true;
    resetImageEditState();
};

/** 关闭图片工作台，回到代码编辑器 */
const closeImageWorkbench = () => {
    state.imagePreview.visible = false;
    resetImageEditState();
};

/** 左侧原图重载（应用生成结果/恢复原图后） */
const refreshImagePreview = () => {
    if (!state.imagePreview.visible) return;
    state.imagePreview.key = Date.now();
    state.imagePreview.url = buildImageUrl(state.imagePreview.filePath);
};

/** 新窗口查看原图 */
const openImageRaw = () => {
    if (state.imagePreview.url) window.open(state.imagePreview.url, '_blank');
};

/** 新窗口查看生成结果图 */
const openResultRaw = () => {
    if (state.imageEdit.resultUrl) window.open(state.imageEdit.resultUrl, '_blank');
};

/** 提交模板图片 AI 修图任务（结果先存附件库展示对比，用户应用后才回写） */
const submitTemplateImageEdit = async () => {
    if (state.sessionMode) {
        // 会话工作目录的图片不支持 AI 修图（修图回写走正式模板文件接口）
        ElMessage.warning('会话编辑模式下暂不支持 AI 修图，可应用模板后再使用');
        return;
    }
    const prompt = state.imageEdit.prompt.trim();
    if (!prompt) {
        ElMessage.warning('请描述修图要求');
        return;
    }
    if (!state.loadedTemplateId || !state.imagePreview.filePath) {
        ElMessage.warning('缺少模板或图片文件信息');
        return;
    }
    state.imageEdit.submitting = true;
    state.imageEdit.error = '';
    state.imageEdit.taskId = null;
    state.imageEdit.status = '';
    state.imageEdit.resultUrl = '';
    try {
        const res: any = await aiImageApi.generate({
            taskType: 'edit',
            prompt,
            num: 1,
            sourceTemplateId: state.loadedTemplateId,
            sourceFilePath: state.imagePreview.filePath
        });
        if (!res.data) {
            ElMessage.error(res.msg || '提交修图任务失败');
            return;
        }
        state.imageEdit.taskId = res.data.id;
        state.imageEdit.status = res.data.status || 'pending';
        startImageEditPolling();
    } catch (e: any) {
        ElMessage.error(e?.message || '提交修图任务失败');
    } finally {
        state.imageEdit.submitting = false;
    }
};

/** 重试失败的修图任务 */
const retryTemplateImageEdit = async () => {
    if (!state.imageEdit.taskId) return;
    try {
        const res: any = await aiImageApi.retry(state.imageEdit.taskId);
        if (res.data) {
            state.imageEdit.taskId = res.data.id;
            state.imageEdit.status = res.data.status || 'pending';
            state.imageEdit.error = '';
            startImageEditPolling();
        } else {
            ElMessage.error(res.msg || '重试失败');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '重试失败');
    }
};

/**
 * 轮询修图任务：success 时取第一张结果图展示在右侧（不回写模板，等用户点「应用」）；
 * failed 时展示错误
 */
const startImageEditPolling = () => {
    stopImageEditPolling();
    state.imageEdit.pollTimer = setInterval(async () => {
        if (!state.imageEdit.taskId) {
            stopImageEditPolling();
            return;
        }
        try {
            const res: any = await aiImageApi.getTask(state.imageEdit.taskId);
            const task = res.data;
            if (!task) return;
            state.imageEdit.status = task.status;
            if (task.status === 'success') {
                stopImageEditPolling();
                const results = (task.results || []).filter((r: any) => r.url);
                state.imageEdit.resultUrl = results.length > 0 ? results[0].url : '';
                if (!state.imageEdit.resultUrl) {
                    ElMessage.warning('修图完成但未返回结果图，请重试');
                }
            } else if (task.status === 'failed') {
                stopImageEditPolling();
                state.imageEdit.error = task.error || '';
            }
        } catch (e) {
            // 单次轮询异常不打断（网络抖动等），下轮继续
        }
    }, 3000);
};

/** 停止修图任务轮询 */
const stopImageEditPolling = () => {
    if (state.imageEdit.pollTimer) {
        clearInterval(state.imageEdit.pollTimer);
        state.imageEdit.pollTimer = null;
    }
};

/** 应用修图结果：回写模板文件（后端先备份原图 .bak），成功后刷新左侧原图并可继续修图 */
const applyImageEdit = async () => {
    if (!state.imageEdit.taskId) {
        ElMessage.warning('暂无可应用的修图结果');
        return;
    }
    state.imageEdit.applying = true;
    try {
        const res: any = await aiImageApi.apply(state.imageEdit.taskId);
        if (res.data) {
            ElMessage.success('已应用：模板图片已更新（原件备份为 .bak，可「恢复原图」撤销）');
            refreshImagePreview();
            // 保留提示词便于继续微调，清掉已应用的生成结果
            resetImageEditState(true);
        } else {
            ElMessage.error(res.msg || '应用失败');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '应用失败');
    } finally {
        state.imageEdit.applying = false;
    }
};

/** 恢复 AI 修图前的原图（.bak 备份覆盖回原路径），同时清掉未应用的生成结果 */
const restoreTemplateImage = () => {
    if (!state.imagePreview.filePath) return;
    // 会话工作目录无 .bak 备份机制（AI 修图仅支持正式模板），按钮已隐藏，此处兜底拦截
    if (state.sessionMode) {
        ElMessage.warning('会话编辑模式下不支持恢复原图');
        return;
    }
    state.imagePreview.restoring = true;
    templateApi.restoreImage(state.imagePreview.filePath, state.loadedTemplateId || undefined).then((res: any) => {
        if (res.data !== undefined && res.data !== null) {
            ElMessage.success('已恢复原图');
            refreshImagePreview();
            // 恢复后原图已变化，之前基于旧图的生成结果不再适用
            resetImageEditState(true);
        } else {
            ElMessage.error(res.msg || '恢复失败');
        }
    }).catch((e: any) => {
        ElMessage.error(e?.message || '恢复失败');
    }).finally(() => {
        state.imagePreview.restoring = false;
    });
};

const onNodeClick = (node: any) => {
    const switchToFile = () => {
        // 用 sortNum 区分目录(0)与文件(1)：空目录（如仅剩 .properties 被过滤的 i18n 目录）children 为 null，不能按 children 判断
        if(node.sortNum === 0) {
            state.uploadParam.dirName = node.filePath;
            state.currEditFile = '';
            state.content = '';
            state.savedContent = '';
            closeImageWorkbench();
            checkDirty();
        }else if (isImageFile(node.filePath)) {
            // 图片文件：清空代码编辑状态，切换为图片工作台（左原图/右生成图对比，AI 修图/恢复原图）
            state.currEditFile = '';
            state.content = '';
            state.savedContent = '';
            checkDirty();
            openImagePreview(node.filePath);
        } else {
            closeImageWorkbench();
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
        state.isDirty = false;
        closeImageWorkbench();
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
    // ESC 清除选区锁定（选区模式内点错区块也可直接再点别的区块覆盖，ESC 是快速取消入口）
    window.addEventListener('keydown', onSectionEscKey);
});

// AI 抽屉关闭：退出换图/选区模式 + 停止生图轮询 + 关闭换图操作窗
watch(() => state.aiDrawerVisible, (visible) => {
    if (!visible) {
        state.imagePickMode = false;
        state.sectionSelectMode = false;
        state.imagePickDialog.visible = false;
        stopImageGenPolling();
    }
});

// 图片工作台关闭：停止修图轮询（后台任务继续执行，未应用的结果不会写入模板）
watch(() => state.imagePreview.visible, (visible) => {
    if (!visible) {
        stopImageEditPolling();
    }
});

/** ESC 清除选区锁定 */
const onSectionEscKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && state.selectedSection) {
        clearSelectedSection();
    }
};

onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload);
    window.removeEventListener('keydown', onSectionEscKey);
    stopImageGenPolling();
    stopImageEditPolling();
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
// 会话编辑模式横幅（替代模板选择下拉）：模板名 + 应用/AI 对话/退出
.session-banner {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    width: 100%;

    .session-banner-tag {
        // 模板名可能较长，超出省略
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
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

            // 换图模式提示条（覆盖在预览区顶部）
            .pick-mode-tip {
                position: absolute;
                top: 0;
                left: 0;
                right: 0;
                padding: 4px 12px;
                background: var(--el-color-primary-light-9);
                color: var(--el-color-primary);
                font-size: 12px;
                border-bottom: 1px dashed var(--el-color-primary-light-7);
                pointer-events: none;
                z-index: 5;
            }
        }
    }
}

// ===== 点选换图操作窗 =====
.pick-dialog-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
    font-weight: 600;
}
.pick-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 10px;

    .pick-toolbar-tip {
        color: var(--el-text-color-secondary);
        font-size: 12px;
    }
}
.pick-grid {
    display: grid;
    grid-template-columns: repeat(6, 1fr);
    gap: 10px;

    .pick-cell {
        cursor: pointer;
        border: 1px solid var(--el-border-color-lighter);
        border-radius: 6px;
        overflow: hidden;
        transition: border-color 0.2s, box-shadow 0.2s;

        &:hover {
            border-color: var(--el-color-primary);
            box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
        }

        .pick-img {
            width: 100%;
            height: 90px;
            display: block;
        }

        .pick-name {
            padding: 4px 6px;
            font-size: 12px;
            color: var(--el-text-color-secondary);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
    }
}
.pick-pager {
    margin-top: 10px;
    justify-content: center;
}
.gen-form {
    .gen-actions {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 8px;
    }
}
.gen-status {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 12px 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;

    .gen-error {
        color: var(--el-color-danger);
    }

    .gen-done {
        color: var(--el-color-success);
    }
}
.pick-applying {
    display: flex;
    align-items: center;
    gap: 6px;
    padding-top: 10px;
    border-top: 1px solid var(--el-border-color-lighter);
    color: var(--el-color-primary);
    font-size: 13px;
}
.pick-upload {
    width: 100%;

    :deep(.el-upload-dragger) {
        width: 100%;
    }
}

// ===== 模板图片工作台（覆盖代码编辑器：左原图 / 右生成结果对比） =====
.img-workbench {
    display: flex;
    flex-direction: column;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    overflow: hidden;

    .img-workbench-toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        padding: 8px 12px;
        border-bottom: 1px solid var(--el-border-color-lighter);
        background: var(--el-fill-color-light);

        .img-workbench-toolbar-actions {
            display: flex;
            align-items: center;
            gap: 8px;
        }
    }

    .img-compare {
        flex: 1;
        display: flex;
        min-height: 0;

        .img-compare-pane {
            flex: 1;
            display: flex;
            flex-direction: column;
            min-width: 0;

            & + .img-compare-pane {
                border-left: 1px solid var(--el-border-color-lighter);
            }

            .img-compare-label {
                padding: 4px 12px;
                font-size: 12px;
                color: var(--el-text-color-secondary);
                border-bottom: 1px dashed var(--el-border-color-lighter);
            }

            .img-compare-body {
                flex: 1;
                display: flex;
                align-items: center;
                justify-content: center;
                min-height: 0;
                overflow: auto;
                padding: 12px;
                // 棋盘格底纹：透明图片（png/svg）边界可辨识
                background-color: var(--el-fill-color-lighter);
                background-image:
                    linear-gradient(45deg, var(--el-fill-color) 25%, transparent 25%, transparent 75%, var(--el-fill-color) 75%),
                    linear-gradient(45deg, var(--el-fill-color) 25%, transparent 25%, transparent 75%, var(--el-fill-color) 75%);
                background-size: 16px 16px;
                background-position: 0 0, 8px 8px;

                .img-compare-el {
                    max-width: 100%;
                    max-height: 100%;
                    object-fit: contain;
                    cursor: zoom-in;
                }

                .img-compare-empty {
                    color: var(--el-text-color-placeholder);
                    font-size: 13px;
                    text-align: center;
                    padding: 0 20px;
                }
            }
        }
    }

    .img-edit-form {
        padding: 10px 12px;
        border-top: 1px solid var(--el-border-color-lighter);

        .img-edit-actions {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-top: 8px;
        }

        .gen-status {
            margin: 8px 0 0;
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
