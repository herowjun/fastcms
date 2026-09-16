import { reactive } from 'vue';
import { TemplateApi } from '/@/api/template/index';
import { AiTemplateApi } from '/@/api/ai/index';

/**
 * 模板文件树 composable：统一管理两棵文件的加载与查找——
 * - 正式模板目录树（主编辑界面文件树）
 * - AI 会话工作目录树（仅服务抽屉左侧预览的页面下拉）
 *
 * 编辑器联动（默认文件打开、内容加载、dirty 检查）与页面状态强耦合，
 * 由调用方通过 onReady 回调接入，composable 只负责树数据本身。
 */
export function useTemplateFileTree() {
    const templateApi = TemplateApi();
    const aiApi = AiTemplateApi();

    const tree = reactive({
        loading: false,
        data: [] as any[],
        defaultProps: {
            children: 'children',
            label: 'label',
            filePath: 'filePath'
        },
        // 默认展开的节点（第一层）
        expandedKeys: [] as string[],
        // 会话工作目录文件树（仅服务抽屉左侧预览页面下拉，与主页面文件树无关）
        sessionData: [] as any[]
    });

    /**
     * 递归查找 index.html 节点（含模板目录前缀，如 my-company/index.html）
     */
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

    /**
     * 加载正式模板文件树（主编辑界面只加载正式模板目录）
     *
     * @param templateId 模板 ID（空 = 后端取激活模板）
     * @param onReady 树就绪回调（仅首次进入/切换模板时传，供调用方打开默认文件；
     *                AI 写盘后的树刷新不传，避免重置用户正在编辑的文件）
     * @returns 加载 Promise（供调用方在树就绪后初始化预览入口）
     */
    const load = (templateId: string | undefined, onReady?: () => void) => {
        tree.loading = true;
        return templateApi.getTemplateFileTree(templateId || undefined).then((res: any) => {
            tree.data = res.data;
            // 默认展开第一层（顶层节点）
            tree.expandedKeys = (res.data || []).map((n: any) => n.filePath);
            if (onReady) {
                onReady();
            }
        }).finally(() => {
            tree.loading = false;
        });
    };

    /**
     * 加载会话工作目录文件树（仅用于抽屉左侧预览的页面下拉，与主编辑界面无关）
     */
    const loadSession = (sessionId: string | undefined) => {
        if (!sessionId) return Promise.resolve();
        return aiApi.getSessionFileTree(sessionId).then((res: any) => {
            tree.sessionData = res.data || [];
        }).catch(() => {
            tree.sessionData = [];
        });
    };

    /** 清空会话文件树（退出会话视图/关闭抽屉时） */
    const clearSession = () => {
        tree.sessionData = [];
    };

    return { tree, findIndexNode, load, loadSession, clearSession };
}
