<template>
    <el-dialog v-model="visible" width="720px" top="6vh" append-to-body
               :close-on-click-modal="false">
        <template #header>
            <div class="pick-dialog-title">
                <span>更换图片</span>
                <el-tag v-if="dialog.rawSrc" size="small" type="warning">演示图片 · 仅预览生效</el-tag>
                <el-tag v-else size="small" type="info">{{ dialog.slot }} @ {{ dialog.sectionId }}</el-tag>
            </div>
        </template>
        <el-tabs v-model="dialog.tab">
            <!-- 附件库：搜索 + 分页图片网格，点选即应用 -->
            <el-tab-pane label="附件库" name="library">
                <div class="pick-toolbar">
                    <el-input v-model="attLib.keyword" size="small" placeholder="按文件名搜索图片，回车搜索" clearable
                              style="width: 260px" @keyup.enter="searchAttImages">
                        <template #append>
                            <el-button @click="searchAttImages">
                                <el-icon><ele-Search /></el-icon>
                            </el-button>
                        </template>
                    </el-input>
                    <span class="pick-toolbar-tip">共 {{ attLib.total }} 张</span>
                </div>
                <div v-loading="attLib.loading" class="pick-grid"
                     :style="{ minHeight: '200px' }">
                    <div v-for="img in attLib.list" :key="img.id" class="pick-cell"
                         :title="img.fileName" @click="applyImageSlot(img.id)">
                        <el-image :src="img.typePath" fit="cover" class="pick-img" lazy />
                        <div class="pick-name">{{ img.fileName }}</div>
                    </div>
                    <el-empty v-if="!attLib.loading && attLib.list.length === 0"
                              description="附件库暂无图片" :image-size="60" />
                </div>
                <el-pagination v-if="attLib.total > attLib.pageSize" small background layout="prev, pager, next"
                               :total="attLib.total" :page-size="attLib.pageSize"
                               :current-page="attLib.page" class="pick-pager"
                               @current-change="(p: number) => { attLib.page = p; loadAttImages(); }" />
            </el-tab-pane>
            <!-- AI 生成：prompt 提交 → 轮询任务 → 结果网格点选应用 -->
            <el-tab-pane label="AI 生成" name="generate">
                <div class="gen-form">
                    <el-input v-model="imageGen.prompt" type="textarea" :rows="3" maxlength="500" show-word-limit
                              placeholder="描述想要的图片，例如：现代简约风格的办公室照片，自然光，蓝白色调" />
                    <div class="gen-actions">
                        <el-select v-model="imageGen.size" size="small" style="width: 140px">
                            <el-option label="1024*1024 方图" value="1024*1024" />
                            <el-option label="1280*720 横图" value="1280*720" />
                            <el-option label="720*1280 竖图" value="720*1280" />
                        </el-select>
                        <el-select v-model="imageGen.num" size="small" style="width: 110px">
                            <el-option v-for="n in [1, 2, 4]" :key="n" :label="n + ' 张'" :value="n" />
                        </el-select>
                        <el-button type="primary" size="small" :loading="genTask.task.submitting"
                                   :disabled="!imageGen.prompt.trim()" @click="submitImageGen">
                            <el-icon><ele-MagicStick /></el-icon>生成
                        </el-button>
                        <el-button v-if="genTask.task.taskId && genTask.task.status === 'failed'" size="small"
                                   type="warning" @click="retryImageGen">重试</el-button>
                    </div>
                </div>
                <div class="gen-status">
                    <template v-if="genTask.task.status === 'pending' || genTask.task.status === 'running'">
                        <el-icon class="is-loading"><ele-Loading /></el-icon>
                        <span>AI 生图中（约 10~60 秒）...</span>
                    </template>
                    <template v-else-if="genTask.task.status === 'failed'">
                        <span class="gen-error">生成失败：{{ genTask.task.error || '未知错误' }}</span>
                    </template>
                    <span v-else-if="genTask.task.status === 'success'" class="gen-done">生成完成，点击图片应用到该位置（已存入附件库）</span>
                </div>
                <div v-if="imageGen.results.length" class="pick-grid">
                    <div v-for="(r, i) in imageGen.results" :key="i" class="pick-cell"
                         :title="r.url" @click="applyImageSlot(r.attachmentId)">
                        <el-image :src="r.url" fit="cover" class="pick-img" />
                        <div class="pick-name">生成结果 {{ i + 1 }}</div>
                    </div>
                </div>
            </el-tab-pane>
            <!-- 上传：直传附件库，成功后切到附件库 tab 点选刚上传的图片 -->
            <el-tab-pane label="上传图片" name="upload">
                <el-upload class="pick-upload" drag multiple accept="image/*"
                          :action="pickUploadUrl" name="files" :headers="headers"
                          :show-file-list="false" :on-success="onPickUploadSuccess" :on-error="onPickUploadError">
                    <el-icon class="el-icon--upload"><ele-UploadFilled /></el-icon>
                    <div class="el-upload__text">拖拽图片到此处，或<em>点击上传</em></div>
                    <template #tip>
                        <div class="el-upload__tip">上传后存入附件库，请在列表中点击刚上传的图片应用（按上传时间倒序排在最前）</div>
                    </template>
                </el-upload>
            </el-tab-pane>
        </el-tabs>
        <div v-if="dialog.applying" class="pick-applying">
            <el-icon class="is-loading"><ele-Loading /></el-icon>
            <span>正在更新图片槽位并重渲染模板...</span>
        </div>
    </el-dialog>
</template>

<script lang="ts" name="imagePickDialog" setup>
import { computed, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import { Local } from '/@/utils/storage';
import { AiTemplateApi, AiImageApi } from '/@/api/ai/index';
import { AttachApi } from '/@/api/attach/index';
import { useAiImageTask } from '/@/views/template/composables/useAiImageTask';

const props = defineProps<{
    /** 当前 AI 会话 ID（换图接口参数；槽位图与演示图走不同接口） */
    sessionId: string;
    /** 对话框显示状态（v-model:visible，父组件据此拦截 Ctrl+S 等全局快捷键） */
    visible: boolean;
}>();

/** 应用成功后通知父组件刷新预览；isPreviewOnly=true 时为演示图（仅预览生效，无需刷新文件树） */
const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'applied', isPreviewOnly: boolean): void;
}>();

const aiApi = AiTemplateApi();
const attachApi = AttachApi();
const genTask = useAiImageTask();

const visible = computed({
    get: () => props.visible,
    set: (v: boolean) => emit('update:visible', v)
});
// 点选上下文（槽位图：slot/sectionId 来自预览页 data-ai-slot/data-ai-section 标记；
// 演示图：rawSrc 为点击图片的原样 src，改 _preview_data.json 仅预览生效）
const dialog = reactive({
    sectionId: '',
    slot: '',
    rawSrc: '',
    tab: 'library' as 'library' | 'generate' | 'upload',
    applying: false
});
// 附件库图片检索（typePath 为图片直显地址）
const attLib = reactive({
    keyword: '',
    page: 1,
    pageSize: 12,
    total: 0,
    list: [] as any[],
    loading: false
});
// AI 生图任务 UI 数据（任务状态机由 genTask 托管）
const imageGen = reactive({
    prompt: '',
    size: '1280*720',
    num: 2,
    results: [] as any[]
});
const pickUploadUrl = import.meta.env.VITE_API_URL + '/admin/attachment/upload';
const headers = { 'Authorization': Local.get('token') };

/**
 * 打开换图操作窗并加载附件库图片
 *
 * 槽位图传 sectionId/slot；演示图传 rawSrc（仅预览生效）
 */
const open = (sectionId: string, slot: string, rawSrc: string) => {
    dialog.sectionId = sectionId;
    dialog.slot = slot;
    dialog.rawSrc = rawSrc;
    dialog.tab = 'library';
    visible.value = true;
    resetImageGen();
    attLib.page = 1;
    loadAttImages();
};

/** 关闭对话框并停止生图轮询（抽屉关闭时由父组件调用兜底） */
const close = () => {
    visible.value = false;
    genTask.stopPolling();
};

/**
 * 应用换图：按图片来源分流
 * - 槽位图（有 sectionId/slot）：更新 _pagespec.json（spec 替换 → 校验 → 重渲染 → 持久化），正式生效
 * - 演示图（有 rawSrc）：更新 _preview_data.json 的 imageOverrides，仅预览生效
 *
 * attachmentId 三个来源归一：附件库点选 / AI 生成结果（已自动入库）/ 上传后入库
 */
const applyImageSlot = async (attachmentId: number) => {
    if (!props.sessionId) {
        ElMessage.warning('当前无 AI 调整会话');
        return;
    }
    if (!attachmentId) {
        ElMessage.warning('缺少附件 ID');
        return;
    }
    const isPreviewOnly = !!dialog.rawSrc && !dialog.slot;
    dialog.applying = true;
    try {
        const res: any = isPreviewOnly
            ? await aiApi.updatePreviewImage(props.sessionId, {
                imageUrl: dialog.rawSrc,
                attachmentId
            })
            : await aiApi.updateImageSlot(props.sessionId, {
                sectionId: dialog.sectionId,
                slot: dialog.slot,
                attachmentId
            });
        if (res.data) {
            ElMessage.success(isPreviewOnly ? '演示图片已更换（仅预览生效）' : '图片已更换');
            visible.value = false;
            genTask.stopPolling();
            emit('applied', isPreviewOnly);
        } else {
            ElMessage.error(res.msg || '更换图片失败');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '更换图片失败');
    } finally {
        dialog.applying = false;
    }
};

/**
 * 加载附件库图片（分页 + 文件名模糊搜索，只取 image 类型）
 */
const loadAttImages = async () => {
    attLib.loading = true;
    try {
        const res: any = await attachApi.getAttachList({
            page: attLib.page,
            pageSize: attLib.pageSize,
            fileType: 'image',
            fileName: attLib.keyword || undefined
        });
        attLib.list = res.data?.records || [];
        attLib.total = res.data?.total || 0;
    } catch (e: any) {
        ElMessage.error(e?.message || '加载附件库图片失败');
    } finally {
        attLib.loading = false;
    }
};

/** 附件库搜索（回到第一页） */
const searchAttImages = () => {
    attLib.page = 1;
    loadAttImages();
};

/** 重置 AI 生图表单（打开操作窗时） */
const resetImageGen = () => {
    genTask.reset();
    imageGen.prompt = '';
    imageGen.results = [];
};

/**
 * 提交 AI 生图任务（t2i 文生图）：提交即返回，轮询至完成后解析 results（url + attachmentId）
 */
const submitImageGen = async () => {
    const prompt = imageGen.prompt.trim();
    if (!prompt) {
        ElMessage.warning('请描述想要生成的图片');
        return;
    }
    imageGen.results = [];
    await genTask.submit({
        taskType: 't2i',
        prompt,
        size: imageGen.size,
        num: imageGen.num
    }, (t: any) => {
        imageGen.results = (t.results || []).filter((r: any) => r.attachmentId);
    }, '提交生图任务失败');
};

/** 重试失败的生图任务 */
const retryImageGen = () => genTask.retry();

/** 换图上传成功：入库成功后切到附件库 tab（最新上传排在最前），由用户点选应用 */
const onPickUploadSuccess = () => {
    ElMessage.success('上传成功，请在列表中点击刚上传的图片应用');
    dialog.tab = 'library';
    attLib.keyword = '';
    attLib.page = 1;
    loadAttImages();
};

const onPickUploadError = () => {
    ElMessage.error('上传失败');
};

defineExpose({ open, close });
</script>

<style lang="scss" scoped>
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
</style>
