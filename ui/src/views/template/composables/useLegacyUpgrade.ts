import { ElMessageBox } from 'element-plus';

/**
 * 旧模板样式组件化升级 / 焕新动作 composable
 *
 * 三个动作均走标准 chat SSE 流（styleUpgrade 标志）：确认对话框收集参数后
 * 预填输入框 + 置 pending* 标志，再委托注入的 onSend 发出
 */
export function useLegacyUpgrade(options: {
    /** 组件共享 reactive state（chatting/legacyTotal/legacyDone/legacyPending/legacyAdjustCount/upgrading/inputText/pending*） */
    state: any;
    /** 组件 props（session） */
    getProps: () => any;
    /** 发送入口（useAiRunStream 返回的 onSend） */
    onSend: (rawOpts?: any) => any;
}) {
    const state = options.state;
    const getProps = options.getProps;
    const onSend = options.onSend;

/**
 * 旧模板「样式组件化升级」（AI 改造管线）
 *
 * 流程：确认后走 chat SSE（styleUpgrade 标志）：后端确定性前置（备份/锚点扫描/组件 CSS 引入）
 * → AI 分批改造页面（保留 id/JS 锚点/脚本/FreeMarker，追加 utility class）→ 写盘前锚点校验
 * → 渲染校验。进度在对话流实时展示；中断后再次发起从断点续传。
 */
const onUpgradeLegacy = () => {
    if (!getProps().session?.sessionId || state.chatting) return;
    const resuming = state.legacyTotal > 0 && state.legacyDone > 0;
    ElMessageBox.confirm(
        resuming
            ? `上次样式组件化升级未完成（已完成 ${state.legacyDone}/${state.legacyTotal} 个页面）。继续升级将从剩余 ${state.legacyPending} 个页面断点续传：保留全部 JS 功能、元素 id 与脚本，引入组件库 CSS 焕新页面视觉。是否继续？`
            : '将对此模板执行样式组件化升级：保留全部 JS 功能、元素 id 与脚本，引入组件库 CSS 焕新页面视觉。原文件自动备份，改造过程由 AI 分批完成（可在对话中看到进度，中断后可续传）。是否继续？',
        resuming ? '继续样式组件化升级' : '样式组件化升级',
        { confirmButtonText: resuming ? '继续升级' : '开始升级', cancelButtonText: '取 消', type: 'warning' }
    ).then(() => {
        state.upgrading = true;
        // 走标准 chat 流：输入框填入升级指令并携带 styleUpgrade 标志发送
        state.inputText = resuming
            ? '继续样式组件化升级（从剩余页面断点续传，保留网站功能，焕新页面视觉）'
            : '开始样式组件化升级（保留网站功能，焕新页面视觉）';
        state.pendingStyleUpgrade = true;
        onSend();
    }).catch(() => {});
};

/**
 * 「智能焕新」（升级完成后的精准重刷，默认推荐）
 *
 * 先由 AI 评估各页面的方向耦合度，判定最小重做集合（必含 _layout.html 公共布局，
 * 通常还有首页等含 hero/深色区的页面）；未选中的页面保留当前版本，经 tokens.css
 * 变量自动换肤。耗时可降到全量的 1/3 左右。评估失败自动回退全量焕新。
 * 对话框内可填写对当前样式的具体意见（如「配色太暗」），AI 优先定向修正这些问题。
 */
const onDeepRefresh = () => {
    if (!getProps().session?.sessionId || state.chatting) return;
    // 对话修改整合提示（后端 adjustCount 驱动）：让用户放心焕新不会丢掉微调劳动
    const adjustNote = state.legacyAdjustCount > 0
        ? `\n\n注意：焕新前你已做过 ${state.legacyAdjustCount} 轮对话微调，本轮焕新会把它们整合进底稿基线（原版本自动另存备份），不会丢失。`
        : '';
    ElMessageBox.prompt(
        '智能焕新：AI 先评估哪些页面与新设计方向强耦合（公共布局/首页等），只重做这些页面；其余页面保留并通过主题变量自动换肤，速度快、消耗少。评估失败时自动回退全量焕新。JS 功能与元素 id 仍全部保留，原备份不变。\n\n可选：填写对当前样式的具体意见（如「配色太暗」「卡片太密」），AI 将优先修正这些问题' + adjustNote,
        '智能焕新',
        {
            confirmButtonText: '开始焕新',
            cancelButtonText: '取 消',
            type: 'warning',
            inputType: 'textarea',
            inputPlaceholder: '对当前样式的具体意见（可空，不填则按轮换设计方向焕新）',
            inputValue: ''
        }
    ).then(({ value }: any) => {
        state.upgrading = true;
        // 走标准 chat 流：携带 styleUpgrade + deepRefresh 标志（后端先做范围评估再重置计划）
        state.inputText = '智能焕新样式组件化（AI 评估范围，重做方向耦合页面含 _layout.html，其余保留换肤，重写组件样式库，保留网站功能）';
        state.pendingStyleUpgrade = true;
        state.pendingDeepRefresh = true;
        state.pendingFullRefresh = false;
        state.pendingFeedback = (value || '').trim();
        onSend();
    }).catch(() => {});
};

/**
 * 「全量焕新」（整体换设计方向的兜底选项）
 *
 * 跳过范围评估，全部计划页面（含 _layout.html）恢复原始备份底稿重新改造一轮。
 * 适合对整体风格彻底不满意、想整套换设计方向的场景。同样支持填写具体意见定向修正。
 */
const onFullRefresh = () => {
    if (!getProps().session?.sessionId || state.chatting) return;
    // 对话修改整合提示（后端 adjustCount 驱动）：让用户放心焕新不会丢掉微调劳动
    const adjustNote = state.legacyAdjustCount > 0
        ? `\n\n注意：焕新前你已做过 ${state.legacyAdjustCount} 轮对话微调，本轮焕新会把它们整合进底稿基线（原版本自动另存备份），不会丢失。`
        : '';
    ElMessageBox.prompt(
        '全量焕新：跳过范围评估，全部页面（含 _layout.html 公共布局）恢复原始底稿、以新的设计方向整体重新改造，并重写组件样式库。耗时与 token 消耗为智能焕新的数倍，建议先试智能焕新。是否继续？\n\n可选：填写对当前样式的具体意见（如「配色太暗」「布局太乱」），AI 将优先修正这些问题' + adjustNote,
        '全量焕新',
        {
            confirmButtonText: '开始全量焕新',
            cancelButtonText: '取 消',
            type: 'warning',
            inputType: 'textarea',
            inputPlaceholder: '对当前样式的具体意见（可空，不填则按轮换设计方向焕新）',
            inputValue: ''
        }
    ).then(({ value }: any) => {
        state.upgrading = true;
        state.inputText = '全量焕新样式组件化（重置计划，全部页面含 _layout.html 重新改造，重写组件样式库，保留网站功能）';
        state.pendingStyleUpgrade = true;
        state.pendingDeepRefresh = true;
        state.pendingFullRefresh = true;
        state.pendingFeedback = (value || '').trim();
        onSend();
    }).catch(() => {});
};

    return { onUpgradeLegacy, onDeepRefresh, onFullRefresh };
}
