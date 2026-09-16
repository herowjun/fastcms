import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { isRoutableHtml } from '/@/views/template/composables/useAiPreview';

/**
 * 预览 iframe 点选钩子 composable：统一管理向预览文档注入的三类捕获监听——
 * - 换图模式：全部图片高亮可点选（槽位图 data-ai-slot / 演示图 img），点击打开换图对话框
 * - 选区模式：组件区块（data-ai-section-root）hover 高亮，点击锁定为 AI 对话目标
 * - 链接拦截：普通模式下预览内链接点击联动页面下拉（状态先行、单次加载）
 *
 * 两个点选模式互斥；iframe 因刷新键重载后由 onPreviewFrameLoad 向新文档重新注入。
 * 页面状态（会话/预览入口）通过 getter 注入，对话框打开通过回调交还页面处理。
 */
export function usePreviewIframeHooks(options: {
    /** 预览 iframe 元素（同源，可直接操作 contentDocument） */
    getFrame: () => HTMLIFrameElement | undefined;
    /** 是否存在 AI 会话（点选模式开启前置校验） */
    hasSession: () => boolean;
    /** 会话是否只读（已应用会话仅回看，禁止写会话的点选交互） */
    isSessionReadonly: () => boolean;
    /** 会话编辑视图开关（决定预览路由前缀，反解 entry 用） */
    isSessionView: () => boolean;
    /** 当前 AI 会话（会话视图下拼接预览路由用） */
    getSession: () => any;
    /** 当前正式模板 ID（非会话视图下拼接预览路由用） */
    getLoadedTemplateId: () => string;
    /** 当前预览入口页面 */
    getPreviewEntry: () => string;
    /** 切换预览入口页面（链接导航/iframe 地址同步） */
    setPreviewEntry: (entry: string) => void;
    /** 刷新预览（链接点击命中的是当前页面时触发重载） */
    refreshPreview: () => void;
    /** 打开换图对话框（sectionId/slot 为空串时表示演示图换图） */
    onOpenImagePick: (sectionId: string, slot: string, rawSrc: string) => void;
}) {
    // ===== 预览页点选换图 =====
    // 换图模式：开启后向预览 iframe 注入点选钩子（全部图片边框高亮可点选，
    // 槽位图 data-ai-slot 主色粗虚线、演示图浅色细虚线，点击弹操作窗）
    const pickMode = ref(false);
    // ===== 预览页选区修改 =====
    // 选区模式：开启后向预览 iframe 注入区块钩子（hover 高亮 data-ai-section-root 区块，点击锁定为 AI 对话目标）
    const sectionMode = ref(false);
    // 已锁定的选中区块（AI 对话聚焦目标，随 chat 请求发送 focusSectionId）：
    // elementHint 为点选时命中的具体元素描述（如 标题「散养土鸡蛋」），做元素级语义提示
    const selectedSection = ref<{ sectionId: string; elementHint: string } | null>(null);

    /** 选区模式注入的样式/类名常量（与 iframe 文档内约定一致） */
    const SECTION_STYLE_ID = '__ai_section_select_style__';
    const SECTION_SELECTED_CLASS = '__ai_section_selected__';

    /**
     * 切换换图模式：开启后预览 iframe 中全部图片边框高亮可点选，关闭时移除高亮
     */
    const toggleImagePickMode = () => {
        pickMode.value = !pickMode.value;
        if (pickMode.value && !options.hasSession()) {
            ElMessage.warning('当前无 AI 会话，请先打开 AI 调整');
            pickMode.value = false;
            return;
        }
        // 已应用的生成会话仅回看：沙箱目录与正式目录已分叉，换图改沙箱不生效
        if (pickMode.value && options.isSessionReadonly()) {
            ElMessage.warning('该会话已应用，仅支持回看；如需调整请在正式模板上使用「AI 调整」');
            pickMode.value = false;
            return;
        }
        if (pickMode.value && sectionMode.value) {
            // 两个点选模式互斥
            sectionMode.value = false;
            applySectionSelectHooks();
        }
        applyImagePickHooks();
        if (pickMode.value) {
            ElMessage.info('换图模式已开启：点击预览页中高亮虚线的图片即可更换');
        }
    };

    /**
     * 切换选区模式：开启后预览 iframe 中的组件区块（data-ai-section-root 根标记）
     * hover 高亮，点击锁定为 AI 对话目标（后续对话只修改该区块）
     *
     * 调整模式与会话编辑视图均可用：组件化模板走后端定位（调整=提示词约束路线 B，
     * 会话=PageSpec 往返），非组件化模板点选时提示不可用
     */
    const toggleSectionSelectMode = () => {
        sectionMode.value = !sectionMode.value;
        if (sectionMode.value && !options.hasSession()) {
            ElMessage.warning('当前无 AI 会话，请先打开 AI 调整');
            sectionMode.value = false;
            return;
        }
        // 已应用的生成会话仅回看：不允许锁定选区发起对话
        if (sectionMode.value && options.isSessionReadonly()) {
            ElMessage.warning('该会话已应用，仅支持回看；如需调整请在正式模板上使用「AI 调整」');
            sectionMode.value = false;
            return;
        }
        if (sectionMode.value && pickMode.value) {
            // 两个点选模式互斥
            pickMode.value = false;
            applyImagePickHooks();
        }
        applySectionSelectHooks();
        if (sectionMode.value) {
            ElMessage.info('选区模式已开启：点击预览页中的区块，锁定后在右侧对话中描述修改需求');
        }
    };

    /**
     * 注入/移除点选钩子（同源 iframe 可直接操作 contentDocument）
     */
    const applyImagePickHooks = () => {
        const doc: Document | null | undefined = options.getFrame()?.contentDocument;
        if (!doc) return;
        const styleId = '__ai_pick_style__';
        const existing = doc.getElementById(styleId);
        if (pickMode.value) {
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
        if (!pickMode.value) return;
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
            options.onOpenImagePick(sectionId, slot, '');
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
        options.onOpenImagePick('', '', rawSrc);
    };

    /**
     * 注入/移除选区钩子：hover 高亮样式 + click 捕获监听 + 已选中区块的持续高亮
     */
    const applySectionSelectHooks = () => {
        const doc: Document | null | undefined = options.getFrame()?.contentDocument;
        if (!doc) return;
        const existing = doc.getElementById(SECTION_STYLE_ID);
        if (sectionMode.value) {
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
        if (!sectionMode.value) return;
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
        selectedSection.value = { sectionId, elementHint: buildElementHint(target, rootEl) };
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
        const doc = options.getFrame()?.contentDocument;
        if (!doc) return;
        doc.querySelectorAll('.' + SECTION_SELECTED_CLASS).forEach((el) => el.classList.remove(SECTION_SELECTED_CLASS));
        if (!selectedSection.value) return;
        const root = doc.querySelector(`[data-ai-section-root="${CSS.escape(selectedSection.value.sectionId)}"]`);
        root?.classList.add(SECTION_SELECTED_CLASS);
    };

    /**
     * 清除选区锁定（标签 ✕ / ESC）
     */
    const clearSelectedSection = () => {
        selectedSection.value = null;
        markSelectedSection();
    };

    /**
     * 从预览路由路径反解页面 entry（与预览地址构造互逆）：
     * 会话视图 /ai/template/preview/{sessionId}/{templateName}/{entry}，
     * 调整模式 /template/preview/{templateId}/{entry}（entry 含模板目录前缀）
     */
    const extractPreviewEntry = (pathname: string): string => {
        try {
            if (options.isSessionView()) {
                const sess = options.getSession();
                if (!sess?.sessionId || !sess.templateName) return '';
                const prefix = '/ai/template/preview/' + sess.sessionId + '/' + sess.templateName + '/';
                if (!pathname.startsWith(prefix)) return '';
                return decodeURIComponent(pathname.substring(prefix.length));
            }
            const templateId = options.getLoadedTemplateId();
            if (!templateId) return '';
            const prefix = '/template/preview/' + templateId + '/';
            if (!pathname.startsWith(prefix)) return '';
            return decodeURIComponent(pathname.substring(prefix.length));
        } catch {
            return '';
        }
    };

    /**
     * 预览内链接点击拦截（普通模式）：点击菜单/链接时先把页面下拉切到目标页，
     * 再由预览地址驱动 iframe 导航——状态先行、单次加载，无二次刷新
     */
    const onPreviewLinkClick = (e: Event) => {
        // 换图/选区模式的捕获处理器已 preventDefault 全部点击，跳过避免干扰
        if (pickMode.value || sectionMode.value || e.defaultPrevented) return;
        const anchor = (e.target as HTMLElement)?.closest?.('a') as HTMLAnchorElement | null;
        if (!anchor) return;
        const win = options.getFrame()?.contentWindow;
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
        if (entry !== options.getPreviewEntry()) {
            options.setPreviewEntry(entry);
        } else {
            options.refreshPreview();
        }
    };

    /** 向预览 iframe 文档注册链接导航监听（每次导航文档重建，随 load 重新挂载） */
    const applyPreviewLinkHook = () => {
        const doc: Document | null | undefined = options.getFrame()?.contentDocument;
        doc?.addEventListener('click', onPreviewLinkClick, true);
    };

    /** load 兜底：非点击导航（JS 跳转等）后按 iframe 实际地址同步页面下拉 */
    const syncPreviewEntryFromIframe = () => {
        const win = options.getFrame()?.contentWindow;
        if (!win) return;
        try {
            const entry = extractPreviewEntry(win.location.pathname);
            if (entry && isRoutableHtml(entry) && entry !== options.getPreviewEntry()) {
                options.setPreviewEntry(entry);
            }
        } catch {
            // 读不到 iframe 地址时忽略（不联动）
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

    /** 抽屉关闭时重置全部点选状态（退出换图/选区模式 + 清除选区锁定） */
    const resetModes = () => {
        pickMode.value = false;
        sectionMode.value = false;
        clearSelectedSection();
    };

    return {
        pickMode, sectionMode, selectedSection,
        toggleImagePickMode, toggleSectionSelectMode,
        clearSelectedSection, resetModes, onPreviewFrameLoad
    };
}
