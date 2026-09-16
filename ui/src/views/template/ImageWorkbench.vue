<template>
    <div v-if="visible" class="img-workbench" :style="height ? { height } : undefined">
        <div class="img-workbench-toolbar">
            <el-tag size="small" type="info">{{ filePath }}</el-tag>
            <div class="img-workbench-toolbar-actions">
                <el-button size="small" type="danger" plain :loading="deleting" title="删除该图片文件"
                           @click="onDeleteImageFile">
                    <el-icon><ele-Delete /></el-icon>删除
                </el-button>
                <el-button size="small" :loading="restoring" title="用 .bak 备份覆盖回当前图片（撤销已应用的修改）"
                           @click="restoreTemplateImage">
                    <el-icon><ele-RefreshLeft /></el-icon>恢复原图
                </el-button>
                <el-button size="small" title="关闭图片工作台，回到代码编辑器" @click="closeWorkbench">
                    <el-icon><ele-Close /></el-icon>关闭
                </el-button>
            </div>
        </div>
        <div class="img-compare">
            <div class="img-compare-pane">
                <div class="img-compare-label">原图</div>
                <div class="img-compare-body">
                    <img v-if="previewUrl" :src="previewUrl"
                         class="img-compare-el" title="点击新窗口查看原图" @click="openImageRaw" />
                </div>
            </div>
            <div class="img-compare-pane">
                <div class="img-compare-label">生成结果</div>
                <div class="img-compare-body">
                    <img v-if="imageEdit.resultUrl" :src="imageEdit.resultUrl"
                         class="img-compare-el" title="点击新窗口查看生成结果" @click="openResultRaw" />
                    <div v-else class="img-compare-empty">
                        提交修图要求后，生成结果将显示在此处与原图对比
                    </div>
                </div>
            </div>
        </div>
        <div class="img-edit-form">
            <el-input v-model="imageEdit.prompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                      placeholder="描述修图要求，例如：把背景换成浅蓝色，去掉右下角的水印" />
            <div class="img-edit-actions">
                <el-button type="primary" size="small" :loading="editTask.task.submitting"
                           :disabled="editTask.task.status === 'pending' || editTask.task.status === 'running'"
                           @click="submitTemplateImageEdit">
                    <el-icon><ele-MagicStick /></el-icon>AI 修图
                </el-button>
                <el-button v-if="imageEdit.resultUrl && editTask.task.status === 'success'"
                           type="success" size="small" :loading="imageEdit.applying"
                           title="将右侧生成结果写入模板文件（原件备份为 .bak）"
                           @click="applyImageEdit">
                    <el-icon><ele-Check /></el-icon>应用
                </el-button>
                <el-button v-if="editTask.task.taskId && editTask.task.status === 'failed'" size="small"
                           type="warning" @click="retryTemplateImageEdit">重试</el-button>
            </div>
            <div class="gen-status">
                <template v-if="editTask.task.status === 'pending' || editTask.task.status === 'running'">
                    <el-icon class="is-loading"><ele-Loading /></el-icon>
                    <span>AI 修图中（约 10~60 秒）...</span>
                </template>
                <template v-else-if="editTask.task.status === 'failed'">
                    <span class="gen-error">修图失败：{{ editTask.task.error || '未知错误' }}</span>
                </template>
                <span v-else-if="editTask.task.status === 'success'" class="gen-done">修图完成，请对比左右图片，满意后点击「应用」写入模板（原件备份为 .bak，可「恢复原图」撤销）</span>
            </div>
        </div>
    </div>
</template>

<script lang="ts" name="imageWorkbench" setup>
import { reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { TemplateApi } from '/@/api/template/index';
import { AiImageApi } from '/@/api/ai/index';
import { useAiImageTask } from '/@/views/template/composables/useAiImageTask';

const props = defineProps<{
    visible: boolean;
    /** 模板 ID（修图任务 sourceTemplateId 与图片/恢复/删除接口参数） */
    templateId: string;
    /** 图片文件路径（含模板目录前缀，与文件树一致） */
    filePath: string;
    /** 工作台高度（与代码编辑器等高，由父组件按视口自适应计算传入） */
    height?: string;
}>();

const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'refresh-tree'): void;
}>();

const templateApi = TemplateApi();
const aiImageApi = AiImageApi();
const editTask = useAiImageTask();

// 修图任务 UI 数据（任务状态机由 editTask 托管）
const imageEdit = reactive({
    prompt: '',
    resultUrl: '',
    applying: false
});

// 预览状态：url 带刷新键，应用/恢复原图后重载左侧原图
const previewUrl = ref('');
const previewKey = ref(0);
const restoring = ref(false);
const deleting = ref(false);

/** 关闭工作台，回到代码编辑器 */
const closeWorkbench = () => emit('update:visible', false);

/** 重置修图任务状态（keepPrompt：应用/恢复后保留提示词便于继续微调） */
const resetImageEditState = (keepPrompt = false) => {
    editTask.reset();
    if (!keepPrompt) imageEdit.prompt = '';
    imageEdit.resultUrl = '';
    imageEdit.applying = false;
};

/**
 * 图片预览地址：复用预览路由的静态文件分支，filePath 含模板目录前缀，后端会截掉前缀解析；
 * 逐段编码（保留 / 分隔符，避免 %2F 被 Tomcat 拒绝）
 */
const buildImageUrl = (fp: string) => {
    const encodedPath = fp.split('/').map(encodeURIComponent).join('/');
    return '/template/preview/' + encodeURIComponent(props.templateId) + '/' + encodedPath + '?t=' + previewKey.value;
};

const refreshImagePreview = () => {
    previewKey.value = Date.now();
    previewUrl.value = buildImageUrl(props.filePath);
};

/** 新窗口查看原图 */
const openImageRaw = () => {
    if (previewUrl.value) window.open(previewUrl.value, '_blank');
};

/** 新窗口查看生成结果图 */
const openResultRaw = () => {
    if (imageEdit.resultUrl) window.open(imageEdit.resultUrl, '_blank');
};

/** 打开/切换图片时重载左侧原图并重置修图任务态 */
watch(() => [props.visible, props.filePath] as const, ([vis]: readonly [boolean, string]) => {
    if (!vis) {
        // 关闭即停止修图轮询（后台任务继续执行，未应用的结果不会写入模板）
        editTask.stopPolling();
        return;
    }
    previewKey.value = Date.now();
    previewUrl.value = buildImageUrl(props.filePath);
    resetImageEditState();
});

/** 提交模板图片 AI 修图任务（结果先存附件库展示对比，用户应用后才回写） */
const submitTemplateImageEdit = async () => {
    const prompt = imageEdit.prompt.trim();
    if (!prompt) {
        ElMessage.warning('请描述修图要求');
        return;
    }
    if (!props.templateId || !props.filePath) {
        ElMessage.warning('缺少模板或图片文件信息');
        return;
    }
    imageEdit.resultUrl = '';
    await editTask.submit({
        taskType: 'edit',
        prompt,
        num: 1,
        sourceTemplateId: props.templateId,
        sourceFilePath: props.filePath
    }, (t: any) => {
        // 取第一张结果图展示在右侧（不回写模板，等用户点「应用」）
        const results = (t.results || []).filter((r: any) => r.url);
        imageEdit.resultUrl = results.length > 0 ? results[0].url : '';
        if (!imageEdit.resultUrl) {
            ElMessage.warning('修图完成但未返回结果图，请重试');
        }
    }, '提交修图任务失败');
};

/** 重试失败的修图任务 */
const retryTemplateImageEdit = () => editTask.retry();

/** 应用修图结果：回写模板文件（后端先备份原图 .bak），成功后刷新左侧原图并可继续修图 */
const applyImageEdit = async () => {
    if (!editTask.task.taskId) {
        ElMessage.warning('暂无可应用的修图结果');
        return;
    }
    imageEdit.applying = true;
    try {
        const res: any = await aiImageApi.apply(editTask.task.taskId);
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
        imageEdit.applying = false;
    }
};

/** 恢复 AI 修图前的原图（.bak 备份覆盖回原路径），同时清掉未应用的生成结果 */
const restoreTemplateImage = () => {
    if (!props.filePath) return;
    restoring.value = true;
    templateApi.restoreImage(props.filePath, props.templateId || undefined).then((res: any) => {
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
        restoring.value = false;
    });
};

/** 删除图片文件 */
const onDeleteImageFile = () => {
    if (!props.filePath) return;
    ElMessageBox.confirm('此操作将永久删除[' + props.filePath + ']文件, 是否继续?', '提示', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
    }).then(() => {
        deleting.value = true;
        templateApi.delTemplateFile(props.filePath, props.templateId || undefined).then(() => {
            ElMessage.success('删除成功');
            closeWorkbench();
            emit('refresh-tree');
        }).catch((res: any) => {
            ElMessage.error(res?.message || '删除失败');
        }).finally(() => {
            deleting.value = false;
        });
    }).catch(() => {});
};
</script>

<style lang="scss" scoped>
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
</style>
