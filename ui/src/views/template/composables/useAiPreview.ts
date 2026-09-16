import { computed, reactive } from 'vue';

/**
 * 判断是否为可路由的 HTML 文件（非 _ 开头的布局/宏文件）
 */
export const isRoutableHtml = (file: string) =>
    !!file && file.toLowerCase().endsWith('.html') && !file.split('/').pop()!.startsWith('_');

/**
 * AI 实时预览 composable：管理预览入口页面、刷新键与预览地址的拼接。
 * 树数据与会话上下文由页面通过 getter 注入（会话编辑视图走会话预览路由与会话目录树，
 * 其余走正式模板路由与正式模板树）。
 */
export function useAiPreview(options: {
    /** 会话编辑视图开关（true 时预览走会话路由与会话目录树） */
    getSessionView: () => boolean;
    /** 当前 AI 会话（会话视图下拼接预览路由用） */
    getCurrentSession: () => any;
    /** 当前正式模板 ID（非会话视图下拼接预览路由用） */
    getLoadedTemplateId: () => string;
    /** 编辑器当前打开文件（非会话视图下优先作为预览入口） */
    getCurrEditFile: () => string;
    /** 当前生效的文件树节点（会话视图=会话工作目录树，否则=正式模板树） */
    getTreeNodes: () => any[];
    /** 会话工作目录树（判断空树引导文案） */
    getSessionTreeNodes: () => any[];
}) {
    const preview = reactive({
        // 当前预览页面（含模板目录前缀，预览后端会截掉）
        entry: '',
        // 刷新键（AI 写盘/手动刷新/入口切换）变化即强制 iframe 重载
        key: 0
    });

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
        walk(options.getTreeNodes());
        return result;
    });

    /**
     * 实时预览 iframe 地址：刷新键变化（AI 写盘/手动刷新）即重载
     *
     * 会话编辑视图走会话预览路由（按 sessionId 定位工作目录），
     * 文件路径含模板目录前缀，后端 AiTemplatePreviewController 会截掉
     */
    const aiPreviewUrl = computed(() => {
        if (options.getSessionView()) {
            const sess = options.getCurrentSession();
            if (!sess?.sessionId || !sess.templateName || !preview.entry) return '';
            return '/ai/template/preview/' + sess.sessionId + '/' + sess.templateName + '/' + preview.entry + '?t=' + preview.key;
        }
        const templateId = options.getLoadedTemplateId();
        if (!templateId || !preview.entry) return '';
        return '/template/preview/' + templateId + '/' + preview.entry + '?t=' + preview.key;
    });

    /**
     * 预览空白占位文案：会话尚无任何文件（新会话生成中）显示引导提示，
     * 其余（树已就绪但无可路由 HTML）用通用提示
     */
    const previewEmptyTip = computed(() => {
        if (options.getSessionView() && (options.getSessionTreeNodes() || []).length === 0) {
            return 'AI 正在生成文件，首个页面完成后将在此显示实时预览';
        }
        return '暂无可预览页面';
    });

    /**
     * 初始化实时预览入口：优先当前编辑的可路由 HTML（仅非会话视图），其次首页（index.html），
     * 最后取第一个可选页
     *
     * 文件树路径含模板目录前缀（如 my-company/index.html），首页匹配须按后缀而非全等，
     * 否则永远回退到文件树第一个页面（字母序在前，如 article.html）
     */
    const initEntry = () => {
        const currOptions = previewPageOptions.value;
        const currEditFile = options.getCurrEditFile();
        // 会话编辑视图预览的是会话工作目录，主编辑器当前文件（正式模板）不适用
        if (!options.getSessionView() && isRoutableHtml(currEditFile) && currOptions.includes(currEditFile)) {
            preview.entry = currEditFile;
        } else {
            const indexEntry = currOptions.find((p: string) => p === 'index.html' || p.endsWith('/index.html'));
            preview.entry = indexEntry || (currOptions.length > 0 ? currOptions[0] : '');
        }
        preview.key = Date.now();
    };

    /** 手动刷新预览（刷新键变化驱动 iframe 重载） */
    const refresh = () => {
        preview.key = Date.now();
    };

    /** 新窗口打开当前预览 */
    const openInNewWindow = () => {
        if (aiPreviewUrl.value) window.open(aiPreviewUrl.value, '_blank');
    };

    return { preview, previewPageOptions, aiPreviewUrl, previewEmptyTip, initEntry, refresh, openInNewWindow };
}
