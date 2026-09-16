import { ElMessage, ElMessageBox } from 'element-plus';
import { Local, Session } from '/@/utils/storage';

/** 失败消息统一前缀（与后端 AiTemplateConstants.MSG_FAIL_PREFIX 对齐） */
export const FAIL_MSG_PREFIX = '生成失败：';

/**
 * AI 对话运行流 composable：单轮 SSE 发送 / 断线续看 / 显式停止的完整机制
 *
 * 核心抽象是 createRunContext（assistant 占位消息 + 事件分发 + 节流渲染）：
 * onSend（页面内主动发送）与 observeRunning（重开页面续看后台任务）共用同一套
 * 分发逻辑，续看回放的历史事件与实时事件视觉一致
 *
 * 页面状态（state/props/emit）通过 options 注入；loadSessionData 与 scrollToBottom
 * 以惰性 getter 传入（避免 setup 阶段的声明顺序/TDZ 问题）
 */
export function useAiRunStream(options: {
    /** 组件共享 reactive state（messages/chatting/statusText/abortController/lastSeq/stopping/files/upgrading/pending* 等） */
    state: any;
    /** AiTemplateApi 实例（chatUrl/streamUrl/runStatus/stopSession/listFiles/legacyStatus） */
    templateApi: any;
    /** 组件 props（session/currentFile/focusSection/focusElementHint） */
    getProps: () => any;
    /** 组件 emit（files-changed/file-written/switch-file） */
    emit: (...args: any[]) => void;
    /** 全量加载会话数据（终态兜底重载用；惰性 getter，规避 setup 声明顺序问题） */
    loadSessionData: () => any;
    /** 对话区滚底跟随（流式节流渲染时调用；惰性 getter） */
    scrollToBottom: () => void;
}) {
    const state = options.state;
    const templateApi = options.templateApi;
    const getProps = options.getProps;
    const emit = options.emit;
    const loadSessionData = options.loadSessionData;
    const scrollToBottom = options.scrollToBottom;

/**
 * 确认动作参数形状校验：模板 @click="onSend" 会把 MouseEvent 传进来
 * （opts 参数化后必须防事件对象误判为确认动作），只有携带字符串 confirmAction
 * 的对象才是设计稿确认调用（APPROVE/REJECT）
 */
const isConfirmOpts = (o: any): o is { confirmAction: string; input: string; displayText: string } => {
    return !!o && typeof o === 'object' && typeof o.confirmAction === 'string' && o.confirmAction;
};

const applyLegacyStatus = (data: any) => {
    if (data && typeof data === 'object') {
        state.legacyUpgradable = data.upgradable === true;
        state.legacyRefinable = data.refinable === true;
        state.legacyPending = data.pendingCount || 0;
        state.legacyDone = data.doneCount || 0;
        state.legacyTotal = data.totalFiles || 0;
        state.legacyRefreshCount = data.refreshCount || 0;
        state.legacyAdjustCount = data.adjustCount || 0;
    } else {
        // 旧版布尔返回或异常空值：仅控制横幅显隐
        state.legacyUpgradable = data === true;
        state.legacyRefinable = false;
        state.legacyPending = 0;
        state.legacyDone = 0;
        state.legacyTotal = 0;
        state.legacyRefreshCount = 0;
        state.legacyAdjustCount = 0;
    }
};

/**
 * 单轮流式处理上下文：assistant 占位消息 + 事件分发 + 节流渲染
 *
 * onSend（页面内主动发送）与 observeRunning（重开页面续看后台任务）共用——
 * 续看回放的历史事件（reasoning/message/file/progress/...）与实时事件走同一套
 * 分发逻辑，视觉与现场直播一致
 */
const createRunContext = (styleUpgrade: boolean, resume: boolean) => {
    // 累积 AI 响应文本（后端流式推送 message 事件为高频小增量，逐段拼接即打字机效果）
    let assistantContent = '';
    let reasoningContent = '';
    state.messages.push({
        role: 'assistant',
        content: '',
        reasoning: '',
        reasoningExpanded: true,
        progress: [] as any[],
        created: new Date().toISOString(),
    });
    const assistantIndex = state.messages.length - 1;

    // ===== 流式渲染节流 =====
    // SSE chunk 高频到达（推理模型长思考期间每秒可达上百个），若每个 chunk 都把
    // 全量 reasoning/content 写入响应式状态，会触发 renderReasoning 全量重算 +
    // v-html 整棵 DOM 子树销毁重建，分配速率远超 GC 回收（实测长思考轮次
    // Chrome 内存飙升 4GB+ 直至回复结束）。双管齐下：
    // - 攒批降频：STREAM_FLUSH_MS 内的增量合并为一次渲染
    // - 渲染窗口：流式期间 reasoning 仅渲染尾部窗口（思考面板滚动本就跟随尾部），
    //   单次渲染成本恒定；结束（done/error/停止/异常）时 flushStream 全量展示
    const STREAM_FLUSH_MS = 150;
    const REASONING_RENDER_WINDOW = 6000;
    // ===== 流式累积硬上限（前端防爆保险丝） =====
    // 后端思考失控保险丝（单轮 256KB）已从源头截断，此处为纵深防御：任何原因
    // （多轮累积、后端行为异常、旧版本实例）导致累积超限时丢弃后续增量并标注一次，
    // 保证 V8 字符串上限（~512MB）永不可达——失控会话实测案例中无界累积曾触发
    // "RangeError: Invalid string length" 使整个对话面板崩溃
    const REASONING_ACCUM_MAX = 1024 * 1024;
    const CONTENT_ACCUM_MAX = 4 * 1024 * 1024;
    let flushTimer: ReturnType<typeof setTimeout> | null = null;
    let lastFlushAt = 0;

    const applyStream = (full: boolean) => {
        const msg = state.messages[assistantIndex];
        if (!msg) return;
        msg.content = assistantContent;
        msg.reasoning = !full && reasoningContent.length > REASONING_RENDER_WINDOW
            ? '…（思考过长，流式期间仅显示尾部，完成后可查看全文）\n' + reasoningContent.slice(-REASONING_RENDER_WINDOW)
            : reasoningContent;
        scrollToBottom();
    };

    const scheduleFlush = () => {
        if (flushTimer !== null) return;
        const wait = Math.max(0, STREAM_FLUSH_MS - (Date.now() - lastFlushAt));
        flushTimer = setTimeout(() => {
            flushTimer = null;
            lastFlushAt = Date.now();
            applyStream(false);
        }, wait);
    };

    const flushStream = () => {
        if (flushTimer !== null) {
            clearTimeout(flushTimer);
            flushTimer = null;
        }
        lastFlushAt = 0;
        applyStream(true);
    };

    const finish = () => {
        // 结束路径统一收口：清掉挂起的节流定时器并把完整内容一次落库展示
        flushStream();
        state.abortController = null;
        state.chatting = false;
        state.statusText = '';
        state.upgrading = false;
    };

    const refreshFiles = () => {
        templateApi.listFiles(getProps().session.sessionId).then((res: any) => {
            if (res.data) state.files = res.data;
        }).catch(() => {});
    };

    /**
     * SSE file 事件实时更新文件列表（done 后 refreshFiles 会用服务器数据覆盖，
     * 此处只需保证流式过程中列表即时可见）
     */
    const upsertFile = (path: string, action: string) => {
        const idx = state.files.findIndex((f: any) => f.filePath === path);
        if (idx >= 0) {
            state.files.splice(idx, 1, { ...state.files[idx], action });
        } else {
            state.files.push({ filePath: path, action, content: '' });
        }
    };

    const handleDone = () => {
        const last = state.messages[assistantIndex];
        if (last && last.reasoning) {
            last.reasoningExpanded = false;
        }
        finish();
        refreshFiles();
        // 升级轮结束后刷新升级状态（升级完成则横幅消失；中断续传则横幅显示剩余进度）；
        // 续看模式下本轮任务类型未知，一并刷新（探测便宜，避免升级轮横幅滞留旧态）
        if ((styleUpgrade || resume) && getProps().session?.sessionId) {
            templateApi.legacyStatus(getProps().session.sessionId).then((res: any) => {
                applyLegacyStatus(res?.data);
            }).catch(() => {});
        }
        // AI 已写盘，通知父组件刷新文件树/编辑器
        emit('files-changed');
    };

    const handleError = (e: any) => {
        let msg = '生成失败';
        if (e.data) {
            try {
                const data = JSON.parse(e.data);
                msg = data.message || msg;
            } catch (err) {
                /* ignore */
            }
        }
        // 与后端落库逻辑一致：失败写入当前 assistant 占位消息，
        // 即时触发失败态 UI（红色消息 + 重新生成入口），无需刷新页面
        const last = state.messages[assistantIndex];
        if (last && !last.content) {
            last.content = FAIL_MSG_PREFIX + msg;
        }
        ElMessage.error(msg);
        finish();
        // 失败前可能已有部分文件落盘（如设计稿逐页产物）：文件表与父组件的
        // 会话文件树须与服务器对齐，否则下拉列表停留在失败前的旧快照
        refreshFiles();
        emit('files-changed');
        // 升级轮失败（如某文件改造解析失败中断）：刷新升级状态，
        // 横幅转为"继续升级"形态展示剩余进度（用户可一键续传）
        if (styleUpgrade && getProps().session?.sessionId) {
            templateApi.legacyStatus(getProps().session.sessionId).then((res: any) => {
                applyLegacyStatus(res?.data);
            }).catch(() => {});
        }
    };

    /**
     * 事件分发（实时事件与续看回放事件共用同一套逻辑）
     */
    const dispatch = (currentEvent: string, data: string) => {
        switch (currentEvent) {
            case 'message':
                // 累积上限防爆（reply 文本远达不到该量级，纯纵深防御）：超限丢弃增量并标注一次
                if (assistantContent.length < CONTENT_ACCUM_MAX) {
                    assistantContent += data;
                    if (assistantContent.length >= CONTENT_ACCUM_MAX) {
                        assistantContent += '\n…（内容超出展示上限，已停止接收后续流式内容）';
                    }
                }
                // 节流渲染：不直接写响应式状态（每 chunk 全量重渲会打爆内存），攒批 150ms
                scheduleFlush();
                break;
            case 'reasoning':
                // 累积上限防爆：超限丢弃增量并标注一次（后端保险丝正常时单轮最多 256KB）
                if (reasoningContent.length < REASONING_ACCUM_MAX) {
                    reasoningContent += data;
                    if (reasoningContent.length >= REASONING_ACCUM_MAX) {
                        reasoningContent += '\n…（思考过程超出展示上限，已停止接收；完整记录以对话历史为准）';
                    }
                }
                scheduleFlush();
                break;
            case 'file':
                // AI 每写完一个文件推送一次：实时更新文件列表 + 通知父组件（刷新实时预览）。
                // 阶段状态条不在此清除：由后端状态链负责（新 status 覆盖旧文案，结束时发空 status 清除）
                try {
                    const info = JSON.parse(data);
                    if (info.path) {
                        upsertFile(info.path, info.action || 'modify');
                        emit('file-written', info.path);
                    }
                } catch (err) {
                    /* 忽略格式异常的 file 事件 */
                }
                break;
            case 'switch-file':
                // 调整/升级对话流式期间，后端识别到 AI 正在处理的首个可路由 HTML 即推送：
                // 通知父组件把实时预览切到该页面（先看旧版本，写盘后经 file 事件刷新重载新内容）
                try {
                    const info = JSON.parse(data);
                    if (info.path) {
                        emit('switch-file', info.path);
                    }
                } catch (err) {
                    /* 忽略格式异常的 switch-file 事件 */
                }
                break;
            case 'confirm_request':
                // 设计稿先行模式等待人工确认：在当前 assistant 消息上挂确认卡片
                // （问题清单 + 预览链接 + 确认转化/驳回修改按钮），随 done 一起收口
                try {
                    const card = JSON.parse(data);
                    if (card.state === 'AWAITING_CONFIRM') {
                        state.messages[assistantIndex].confirmCard = {
                            issues: Array.isArray(card.issues) ? card.issues : [],
                            previewUrl: card.previewUrl || '',
                            confirmAuto: card.confirmAuto === true,
                        };
                        scrollToBottom();
                    }
                } catch (err) {
                    /* 忽略格式异常的 confirm_request 事件 */
                }
                break;
            case 'progress':
                // 分批流水线进度快照（全量文件清单及状态），更新 AI 消息内的进度卡
                try {
                    const info = JSON.parse(data);
                    if (info.files) {
                        state.messages[assistantIndex].progress = info.files;
                        scrollToBottom();
                    }
                } catch (err) {
                    /* 忽略格式异常的 progress 事件 */
                }
                break;
            case 'status':
                // 阶段性状态提示（如 reply 已流完、files 内容仍在传输），
                // 显示在输入区上方状态行，消除"回复已结束却长时间转圈"的假死观感
                if (data) {
                    state.statusText = data;
                }
                break;
            case 'usage':
                // 本轮 token 用量（done 之后到达）：挂到本条 assistant 消息，hover 底部展示
                try {
                    const u = JSON.parse(data);
                    state.messages[assistantIndex].promptTokens = u.promptTokens ?? 0;
                    state.messages[assistantIndex].completionTokens = u.completionTokens ?? 0;
                    state.messages[assistantIndex].totalTokens = u.totalTokens ?? 0;
                } catch (err) {
                    /* 忽略格式异常的 usage 事件 */
                }
                break;
            case 'done':
                // done 事件 data 未消费（终态消息已随流式渲染展示），无参收口
                handleDone();
                break;
            case 'error':
                handleError({ data });
                break;
            case 'run-status':
                // stream 端点探测响应（{"running":false} 后服务端随即 complete）：
                // 由流结束后的观察循环统一收口，此处无需处理
                break;
            default:
                break;
        }
    };

    return { assistantIndex, dispatch, finish };
};

/**
 * 消费 SSE 响应流：按行解析（event:/data:/id:），空行处分发事件
 *
 * id 字段为 RunChannel 分配的单调递增事件 seq（chat 提交流与 stream 续看流均携带）：
 * 记录进 state.lastSeq，断线重连时作为 stream 端点 since 参数增量回放（不重播已收事件）
 */
const consumeSse = async (resp: any, ctx: ReturnType<typeof createRunContext>) => {
    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    let currentEvent = 'message';
    let currentData: string[] = [];
    let sawEvent = false;

    // 逐块读取 SSE 流，按行解析（兼容 \r\n / \n）
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buf.indexOf('\n')) >= 0) {
            const raw = buf.slice(0, idx);
            buf = buf.slice(idx + 1);
            const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;

            if (line === '') {
                // 空行 = 事件结束
                if (sawEvent && currentData.length > 0) {
                    ctx.dispatch(currentEvent, currentData.join('\n'));
                }
                currentEvent = 'message';
                currentData = [];
                sawEvent = false;
            } else if (line.startsWith('event:')) {
                const name = line.slice(6).trim();
                if (name) {
                    currentEvent = name;
                    sawEvent = true;
                }
            } else if (line.startsWith('data:')) {
                currentData.push(line.slice(5).replace(/^ /, ''));
                sawEvent = true;
            } else if (line.startsWith('id:')) {
                // SSE id = 事件 seq（RunChannel 单调递增）：记录续连游标
                const id = Number(line.slice(3).trim());
                if (Number.isFinite(id) && id > state.lastSeq) {
                    state.lastSeq = id;
                }
            }
            // 其余字段（retry:/注释）忽略
        }
    }
};

/**
 * 终态兜底重载：流结束但未收到 done/error（任务在断连窗口内被停止/异常终止）。
 * 终态消息（停止原因含进度明细 / 失败原因）由后端在 complete 连接前先行落库，
 * 重载消息与文件即与真实终态对齐（复用 loadSessionData 的全量恢复逻辑）
 */
const reloadRunTerminal = async () => {
    if (!getProps().session?.sessionId) return;
    try {
        await loadSessionData();
    } catch (e) {
        console.error(e);
    } finally {
        emit('files-changed');
    }
};

/**
 * 观察循环：探测运行态 → 连接 stream 端点（回放 since 之后事件 + 实时续接）；
 * 流结束仍未终态则指数退避重连（1s/2s/4s…上限 30s，共 8 次）。
 * 终态判定：done/error 事件（dispatch 后 chatting=false）或探测 running=false
 * （任务已结束，终态消息已落库 → 重载收口）
 */
const observeLoop = async (ctx: ReturnType<typeof createRunContext>) => {
    const sessionId = getProps().session?.sessionId;
    if (!sessionId) {
        ctx.finish();
        return;
    }
    for (let attempt = 0; attempt < 8; attempt++) {
        if (!state.chatting) return; // 已收到 done/error 终态
        // 探测运行态：探测请求本身失败时保守视为仍在跑，靠 stream 连接结果判定
        let running = true;
        try {
            const res: any = await templateApi.runStatus(sessionId);
            running = res?.data?.running === true;
        } catch (e) {
            /* 探测失败不阻断：继续走 stream 连接 */
        }
        if (!running) {
            // 任务已在断连窗口内结束（终态消息已落库）：重载收口
            ctx.finish();
            await reloadRunTerminal();
            return;
        }
        // 连接 stream 端点：回放 since 之后的历史事件 + 实时续接
        const controller = new AbortController();
        state.abortController = controller;
        try {
            const token = Local.get('token') as string | undefined;
            const resp = await fetch(templateApi.streamUrl(sessionId, state.lastSeq), {
                headers: token ? { Authorization: 'Bearer ' + token } : {},
                signal: controller.signal,
            });
            if (!resp.ok || !resp.body) {
                // 连接被拒（会话已删除/权限变化）：终止观察
                ctx.finish();
                return;
            }
            await consumeSse(resp, ctx);
            if (!state.chatting) return; // done/error 已收口
            // 流结束但未终态（服务端断开/任务仍在跑）：探测后重连
        } catch (e: any) {
            if (e?.name === 'AbortError') {
                // 会话切换/组件卸载触发的主动断开：静默收口（后台任务不受影响）
                ctx.finish();
                return;
            }
            console.error(e);
        }
        // 网络异常/未终态断开：指数退避后重连
        await new Promise((r) => setTimeout(r, Math.min(30000, 1000 * Math.pow(2, attempt))));
    }
    // 重连次数用尽（长时间断网）：收口并提示（任务仍在后台跑，重开页面可再次续看）
    ctx.finish();
    ElMessage.info('连接已断开，任务仍在后台处理，重新打开会话可继续查看进度');
    await reloadRunTerminal();
};

/**
 * 续看后台任务：页面重开/刷新时探测到任务运行中（loadSessionData 调用）——
 * 回放 since 之后全部历史事件（含已发生的思考过程）到新 assistant 占位消息，
 * 再实时续接。与 onSend 共用 createRunContext，视觉与现场直播一致
 */
const observeRunning = async (since: number) => {
    if (!getProps().session?.sessionId || state.chatting) return;
    state.chatting = true;
    state.lastSeq = since;
    const ctx = createRunContext(false, true);
    await observeLoop(ctx);
};

const onSend = async (rawOpts?: any) => {
    const opts = isConfirmOpts(rawOpts) ? rawOpts : null;
    // 普通对话需有输入；确认动作（APPROVE 空输入）由卡片按钮触发，绕过空输入检查
    if ((!opts && !state.inputText.trim()) || !getProps().session?.sessionId) return;

    // 新一轮对话回到自动跟随模式
    userScrolledUp.value = false;

    // 先把用户输入加入消息列表（UI 即时反馈；确认动作用自然语言展示，历史可读）
    state.messages.push({
        role: 'user',
        content: opts ? opts.displayText : state.inputText,
        created: new Date().toISOString(),
    });

    const userInput = opts ? opts.input : state.inputText;
    const confirmAction = opts ? opts.confirmAction : '';
    const styleUpgrade = state.pendingStyleUpgrade;
    const deepRefresh = state.pendingDeepRefresh;
    const fullRefresh = state.pendingFullRefresh;
    const feedback = state.pendingFeedback;
    state.inputText = '';
    state.chatting = true;
    // 新任务 = 新事件序列（RunChannel seq 从 1 重新计数）：续连游标归零
    state.lastSeq = 0;

    if (state.abortController) {
        state.abortController.abort();
    }
    const controller = new AbortController();
    state.abortController = controller;

    // 本轮流式上下文（占位消息 + 事件分发 + 节流渲染）
    const ctx = createRunContext(styleUpgrade, false);

    try {
        const token = Local.get('token') as string | undefined;
        const resp = await fetch(templateApi.chatUrl(getProps().session.sessionId), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                ...(token ? { Authorization: 'Bearer ' + token } : {})
            },
            body: JSON.stringify({
                input: userInput,
                currentFile: getProps().currentFile || '',
                focusSectionId: getProps().focusSection || '',
                focusElementHint: getProps().focusElementHint || '',
                styleUpgrade,
                deepRefresh,
                fullRefresh,
                // 焕新意见（可空）：后端注入焕新提示词做定向修正，未填时按轮换方向焕新
                feedback: feedback || '',
                fullInject: state.fullInject === true,
                // 设计稿先行模式确认动作（APPROVE/REJECT，可空）：仅 design 会话的后端消费，
                // 管线会话后端忽略（isDesignMode 前置判断），旧后端无此字段也不受影响
                confirmAction: confirmAction || undefined
            }),
            signal: controller.signal
        });
        // 请求已发出，升级标志复位（下一轮普通对话不带该标志）
        state.pendingStyleUpgrade = false;
        state.pendingDeepRefresh = false;
        state.pendingFullRefresh = false;
        state.pendingFeedback = '';

        if (!resp.ok || !resp.body) {
            let msg = '请求失败（' + resp.status + '）';
            try {
                const errRes = await resp.json();
                if (errRes && errRes.msg) msg = errRes.msg;
            } catch (err) {
                /* ignore */
            }
            // HTTP 层失败同样写入占位消息触发失败态 UI
            const last = state.messages[ctx.assistantIndex];
            if (last && !last.content) {
                last.content = FAIL_MSG_PREFIX + msg;
            }
            // 401 = 登录过期/已在别处登录：AI 对话走原生 fetch，axios 的 401 拦截器不生效，
            // 此处清缓存 + 弹窗提示 + 跳转管理后台入口（/fastcms → SPA 检测无 token 自动进登录页）
            if (resp.status === 401) {
                Session.clear();
                Local.clear();
                ctx.finish();
                ElMessageBox.alert('你已被登出，请重新登录', '提示', {})
                    .then(() => { window.location.href = '/fastcms'; })
                    .catch(() => {});
                return;
            }
            ElMessage.error(msg);
            ctx.finish();
            return;
        }

        await consumeSse(resp, ctx);
        // 流结束但后端未发 done/error：任务在后台仍可能运行（连接中断/另一端停止/
        // 服务端静默终止）——转观察循环：仍在跑则增量续连，已结束则重载终态消息收口
        if (state.chatting) {
            await observeLoop(ctx);
        }
    } catch (e: any) {
        if (e?.name === 'AbortError') {
            // 会话切换/组件卸载触发的主动断开（停止不再走本地 abort）：静默收口
            ctx.finish();
        } else {
            console.error(e);
            // 连接异常但任务与连接已解耦（断网/代理断开）：任务大概率仍在后台跑——
            // 转 stream 端点续连；确认无运行任务时由观察循环重载终态收口
            await observeLoop(ctx);
        }
    }
};

/**
 * 显式停止：调用 stop 端点（断开连接不取消任务——停止只能显式点击）。
 * 停止后任务线程优雅中断并落一条"已停止"消息（含进度明细 + 本轮思考过程），
 * 随后服务端 complete 流连接 → 前端流结束兜底自动重载终态消息；本地不主动
 * abort（abort 会跳过该重载路径，丢失停止反馈）
 */
const onStop = async () => {
    if (!getProps().session?.sessionId || state.stopping || !state.chatting) return;
    state.stopping = true;
    try {
        await templateApi.stopSession(getProps().session.sessionId);
    } catch (e: any) {
        console.error(e);
        ElMessage.error('停止请求失败：' + (e?.message || '网络错误'));
        // 停止请求失败兜底：仅断开本地连接（任务仍在后台跑，重开页面可续看）
        if (state.abortController) {
            state.abortController.abort();
            state.abortController = null;
        }
        state.chatting = false;
    } finally {
        state.stopping = false;
    }
};

    return { onSend, onStop, observeRunning, applyLegacyStatus };
}

