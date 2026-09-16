import { reactive, onUnmounted } from 'vue';
import { ElMessage } from 'element-plus';
import { AiImageApi } from '/@/api/ai/index';

/** AI 图片任务状态：提交 → 轮询 → 成功/失败 的公共状态机（UI 侧数据如 prompt/results 由调用方自理） */
export interface AiImageTaskState {
    taskId: any;
    status: '' | 'pending' | 'running' | 'success' | 'failed';
    error: string;
    submitting: boolean;
}

/**
 * AI 图片任务（文生图/修图共用）：提交即返回 + 3 秒轮询至终态。
 * 抽自 edit.vue 中两套几乎相同的提交/重试/轮询逻辑，轮询带防重入标志。
 */
export function useAiImageTask() {
    const aiImageApi = AiImageApi();
    const task = reactive<AiImageTaskState>({ taskId: null, status: '', error: '', submitting: false });
    let pollTimer: any = null;
    let polling = false;
    let successHandler: ((t: any) => void) | null = null;

    const stopPolling = () => {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    };

    const startPolling = () => {
        stopPolling();
        polling = false;
        pollTimer = setInterval(async () => {
            if (!task.taskId) {
                stopPolling();
                return;
            }
            // 防重入：上一轮请求未返回时跳过本轮，避免慢请求下轮询堆叠并发
            if (polling) return;
            polling = true;
            try {
                const res: any = await aiImageApi.getTask(task.taskId);
                const t = res.data;
                if (!t) return;
                task.status = t.status;
                if (t.status === 'success') {
                    stopPolling();
                    successHandler?.(t);
                } else if (t.status === 'failed') {
                    stopPolling();
                    task.error = t.error || '';
                }
            } catch (e) {
                // 单次轮询异常不打断（网络抖动等），下轮继续
            } finally {
                polling = false;
            }
        }, 3000);
    };

    /**
     * 提交任务：提交即返回，成功后自动开始轮询
     * @param params 生成参数（taskType/prompt 等）
     * @param onDone 轮询成功回调（入参为任务详情，含 results）
     * @param failMsg 提交失败提示文案
     */
    const submit = async (params: Record<string, any>, onDone: (t: any) => void, failMsg = '提交任务失败'): Promise<any> => {
        task.submitting = true;
        task.error = '';
        task.taskId = null;
        task.status = '';
        successHandler = onDone;
        try {
            const res: any = await aiImageApi.generate(params);
            if (!res.data) {
                ElMessage.error(res.msg || failMsg);
                return null;
            }
            task.taskId = res.data.id;
            task.status = res.data.status || 'pending';
            startPolling();
            return res.data;
        } catch (e: any) {
            ElMessage.error(e?.message || failMsg);
            return null;
        } finally {
            task.submitting = false;
        }
    };

    /** 重试失败任务 */
    const retry = async (): Promise<any> => {
        if (!task.taskId) return null;
        try {
            const res: any = await aiImageApi.retry(task.taskId);
            if (res.data) {
                task.taskId = res.data.id;
                task.status = res.data.status || 'pending';
                task.error = '';
                startPolling();
                return res.data;
            }
            ElMessage.error(res.msg || '重试失败');
            return null;
        } catch (e: any) {
            ElMessage.error(e?.message || '重试失败');
            return null;
        }
    };

    /** 重置轮询态（prompt/results/resultUrl 等 UI 数据由调用方自理） */
    const reset = () => {
        stopPolling();
        task.taskId = null;
        task.status = '';
        task.error = '';
        task.submitting = false;
    };

    onUnmounted(stopPolling);

    return { task, submit, retry, reset, stopPolling };
}
