<template>
    <el-dialog v-model="visible"
               :title="dialog.view === 'history' ? '历史生成记录' : 'AI 新建模板'"
               :width="dialog.view === 'history' ? '680px' : '520px'" :close-on-click-modal="false">
        <el-form v-if="dialog.view === 'create'" ref="createFormRef" :model="dialog"
                 :rules="createFormRules" label-width="90px">
            <el-form-item label="模板目录名" prop="templateName">
                <el-input v-model="dialog.templateName" placeholder="英文目录名，以字母开头，如 my-company"
                          @input="onTemplateNameInput" />
            </el-form-item>
            <el-form-item label="需求描述" prop="requirement">
                <el-input v-model="dialog.requirement" type="textarea" :rows="4"
                          :placeholder="dialog.createMode === 'design' && dialog.importFile
                              ? '可选。补充说明站点定位（如：企业官网），AI 仿写时参考；与上传文件冲突时以文件为准'
                              : '描述模板需求，例如：企业官网模板，蓝色调，响应式设计'" />
            </el-form-item>
            <el-form-item label="生成模式">
                <el-radio-group v-model="dialog.createMode">
                    <!-- element-plus 2.3.x：radio 用 label 绑定值（value 属性为新版 API，此处不生效） -->
                    <el-radio label="pipeline">组件编排</el-radio>
                    <el-radio label="design">AI 自主设计</el-radio>
                </el-radio-group>
                <div class="mode-tip">{{ createModeTip }}</div>
            </el-form-item>
            <template v-if="dialog.createMode === 'design'">
                <!-- 参考文件（可选）：选择即切换为仿写链路——提交路由见 onCreateConfirm -->
                <el-form-item label="参考 HTML">
                    <el-upload accept=".html,.htm,.zip" :limit="1" :auto-upload="false"
                               :file-list="dialog.importFileList"
                               :on-change="onImportFileChange" :on-remove="onImportFileRemove"
                               :on-exceed="onImportFileExceed">
                        <el-button type="primary" plain>
                            <el-icon><ele-UploadFilled /></el-icon>选择 HTML / ZIP 文件
                        </el-button>
                        <template #tip>
                            <div class="el-upload__tip">可选。单个 .html：首页 1:1 保真、子页按其设计语言仿写；zip 站包：整站 1:1 迁移</div>
                        </template>
                    </el-upload>
                </el-form-item>
                <!-- 设计方向：仅无参考文件时可选（有文件时文件本身即方向） -->
                <el-form-item v-if="!dialog.importFile" label="设计方向">
                    <el-select v-model="dialog.designDirection" placeholder="AI 自选方向" clearable style="width: 100%">
                        <el-option v-for="d in designOptions.directions" :key="d.key"
                                   :label="d.name" :value="d.key">
                            <span>{{ d.name }}</span>
                            <span class="direction-summary">{{ d.summary }}</span>
                        </el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="确认方式">
                    <el-checkbox v-model="dialog.confirmAuto">审计通过后自动转化（跳过人工确认）</el-checkbox>
                </el-form-item>
            </template>
            <el-form-item label="移动端适配">
                <el-checkbox v-model="dialog.mobileAdaptive">生成响应式布局（多端断点 + 移动端汉堡菜单）</el-checkbox>
            </el-form-item>
        </el-form>
        <template v-else>
            <!-- 已应用/未应用分页签：未应用是活跃工作集（默认），已应用仅回看 -->
            <el-tabs v-model="dialog.historyTab">
                <el-tab-pane name="pending">
                    <template #label>
                        未应用<el-badge v-if="pendingSessions.length" :value="pendingSessions.length" type="warning" class="history-tab-badge" />
                    </template>
                    <el-table :data="pendingSessions" v-loading="dialog.historyLoading" stripe size="small"
                              max-height="420" highlight-current-row class="history-session-table" @row-click="onOpenHistorySession">
                        <el-table-column prop="templateName" label="模板目录" min-width="110" show-overflow-tooltip />
                        <el-table-column prop="requirement" label="需求描述" min-width="200" show-overflow-tooltip />
                        <el-table-column label="状态" width="86">
                            <template #default="scope">
                                <el-tag size="small" :type="sessionBadge(scope.row).type">{{ sessionBadge(scope.row).text }}</el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column label="创建时间" width="130">
                            <template #default="scope">{{ formatHistoryTime(scope.row.created) }}</template>
                        </el-table-column>
                        <el-table-column label="操作" width="70">
                            <template #default="scope">
                                <el-button size="small" text type="primary" @click.stop="onOpenHistorySession(scope.row)">打开</el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                    <el-empty v-if="!dialog.historyLoading && pendingSessions.length === 0"
                              description="暂无未应用的生成记录" :image-size="60" />
                </el-tab-pane>
                <el-tab-pane name="applied">
                    <template #label>
                        已应用<el-badge v-if="appliedSessions.length" :value="appliedSessions.length" type="success" class="history-tab-badge" />
                    </template>
                    <el-table :data="appliedSessions" v-loading="dialog.historyLoading" stripe size="small"
                              max-height="420" highlight-current-row class="history-session-table" @row-click="onOpenHistorySession">
                        <el-table-column prop="templateName" label="模板目录" min-width="110" show-overflow-tooltip />
                        <el-table-column prop="requirement" label="需求描述" min-width="200" show-overflow-tooltip />
                        <el-table-column label="状态" width="86">
                            <template #default="scope">
                                <el-tag size="small" :type="sessionBadge(scope.row).type">{{ sessionBadge(scope.row).text }}</el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column label="创建时间" width="130">
                            <template #default="scope">{{ formatHistoryTime(scope.row.created) }}</template>
                        </el-table-column>
                        <el-table-column label="操作" width="70">
                            <template #default="scope">
                                <el-button size="small" text type="primary" @click.stop="onOpenHistorySession(scope.row)">回看</el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                    <el-empty v-if="!dialog.historyLoading && appliedSessions.length === 0"
                              description="暂无已应用的生成记录" :image-size="60" />
                </el-tab-pane>
            </el-tabs>
            <div class="history-tip">点击记录或「打开 / 回看」进入 AI 工作台：未应用的可继续打磨、重新生成或应用；已应用的仅回看</div>
        </template>
        <template #footer>
            <template v-if="dialog.view === 'create'">
                <el-button text type="primary" @click="onShowHistory">
                    <el-icon><ele-Clock /></el-icon>历史生成记录
                </el-button>
                <el-button @click="visible = false">取 消</el-button>
                <el-button type="primary" :loading="dialog.loading" @click="onCreateConfirm">
                    开始生成
                </el-button>
            </template>
            <template v-else>
                <el-button @click="dialog.view = 'create'">返回新建</el-button>
                <el-button type="primary" @click="visible = false">关 闭</el-button>
            </template>
        </template>
    </el-dialog>
</template>

<script lang="ts" name="createTemplateDialog" setup>
import { computed, nextTick, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { AiTemplateApi } from '/@/api/ai/index';

const props = defineProps<{
    /** 对话框显示状态（v-model:visible，父组件据此拦截 Ctrl+S 等全局快捷键） */
    visible: boolean;
}>();

/**
 * created：会话创建（含参考文件上传）成功后触发，父组件负责进入会话编辑视图
 * firstMessage 为抽屉打开后自动发送的首条消息（仿写链路触发编排器推进）
 */
const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'created', session: any, firstMessage: string): void;
    (e: 'open-session', row: any, sessions: any[]): void;
}>();

const aiApi = AiTemplateApi();

const visible = computed({
    get: () => props.visible,
    set: (v: boolean) => emit('update:visible', v)
});

// 对话框状态（create：新建表单；history：历史生成记录列表）
const dialog = reactive({
    view: 'create' as 'create' | 'history',
    // 历史记录页签（pending：未应用，默认；applied：已应用仅回看）
    historyTab: 'pending' as 'pending' | 'applied',
    // 状态徽章覆盖上下文（open 传入快照）：正在跑的会话显示「生成中」、本轮失败的显示「生成失败」；
    // 两者均为会话期瞬时态（后端无此状态值，不落库），刷新后回退为「待应用」
    runningId: '',
    failedId: '',
    templateName: '',
    requirement: '',
    // 是否适配移动端（默认开启：响应式布局 + 移动端汉堡菜单）
    mobileAdaptive: true,
    // 生成模式（UI 两项）：pipeline=组件编排（默认，兼容旧客户端不传字段的后端口径）；
    // design=AI 自主设计。'import' 不再是 UI 选项——自主设计 + 参考文件时提交路由改走 import 口径
    createMode: 'pipeline' as 'pipeline' | 'design',
    // 设计稿模式：设计方向 key（空 = AI 自选；选项来自后端方向资产库，不写死）
    designDirection: '',
    // 设计稿模式：审计通过后自动转化（跳过人工确认；关闭时审计通过停在确认卡片）
    confirmAuto: false,
    // 导入模式：待上传的 HTML/ZIP 文件（手动上传，创建会话后随 uploadReference 提交）
    importFile: null as File | null,
    importFileList: [] as any[],
    loading: false,
    // 历史生成型会话列表（未绑定 templateId，含已应用/未应用）
    sessions: [] as any[],
    historyLoading: false
});
// 设计稿模式选项（打开对话框时拉取）：directions=方向资产清单
const designOptions = reactive({
    loaded: false,
    directions: [] as any[]
});

const createFormRef = ref();

/**
 * 新建模板表单校验规则（rules 化：错误就地显示在表单项下方，替代提交时的弹窗提示）
 * requirement 必填性随参考文件动态变化：仿写链路（design + 参考文件）下需求描述可选
 */
const createFormRules = computed(() => ({
    templateName: [
        { required: true, message: '请输入模板目录名', trigger: 'blur' },
        { pattern: /^[a-zA-Z][a-zA-Z0-9_-]*$/, message: '必须以英文字母开头，只能包含字母、数字、下划线、横线', trigger: 'blur' }
    ],
    requirement: [
        { required: !(dialog.createMode === 'design' && !!dialog.importFile), message: '请输入需求描述', trigger: 'blur' }
    ]
}));

/** 生成模式提示：随选择与参考文件切换（文案与后端默认开关口径一致） */
const createModeTip = computed(() => {
    if (dialog.createMode === 'design') {
        return dialog.importFile
            ? '已选参考文件：AI 以其为权威仿写——单个 HTML 首页 1:1 保真、子页继承其设计语言；zip 整站 1:1 迁移'
            : 'AI 自主设计整页视觉：灵活度高、效果上限高，但耗时与 token 成本约为组件模式的 2~3 倍。可上传参考 HTML 让 AI 照其仿写';
    }
    return '组件拼装 + 定向润色：快、稳、省 token，由组件数量决定丰富度（默认模式）';
});

/**
 * 拉取设计稿先行模式的方向清单（打开新建对话框时调用）。
 * 模式选项始终显示（不受后端 feature 开关控制）；此处仅拉方向资产清单，
 * 拉取失败不阻断对话框——方向下拉留空，管线模式与"AI 自选"均不受影响。
 */
const loadDesignOptions = async () => {
    try {
        const res = await aiApi.designOptions();
        if (res.data) {
            designOptions.directions = Array.isArray(res.data.directions) ? res.data.directions : [];
            designOptions.loaded = true;
        }
    } catch (e) {
        // 忽略：方向下拉留空（AI 自选仍可用），不打扰用户
    }
};

/**
 * 打开 AI 新建模板对话框（generate 模式）
 *
 * @param view 落地页签：create=新建表单（默认）；history=历史生成记录（工具条「历史记录」入口）
 * @param badgeCtx 状态徽章覆盖上下文（打开时快照）：runningId=正在生成的会话，failedId=本轮失败的会话
 */
const open = (view: 'create' | 'history' = 'create', badgeCtx?: { runningId?: string; failedId?: string }) => {
    dialog.runningId = badgeCtx?.runningId || '';
    dialog.failedId = badgeCtx?.failedId || '';
    if (view === 'history') {
        onShowHistory();
        visible.value = true;
        return;
    }
    dialog.view = 'create';
    dialog.templateName = '';
    dialog.requirement = '';
    dialog.mobileAdaptive = true;
    // 设计模式三字段每次重置（避免上次选择残留：默认管线、方向 AI 自选、审计通过自动转化）
    dialog.createMode = 'pipeline';
    dialog.designDirection = '';
    dialog.confirmAuto = true;
    // 参考文件每次重置（残留会让用户误以为已选择新文件）
    dialog.importFile = null;
    dialog.importFileList = [];
    visible.value = true;
    // 清掉上次打开时未通过校验的红字（el-dialog 关闭不销毁内容，错误状态会残留）
    nextTick(() => createFormRef.value?.clearValidate());
    loadDesignOptions();
};

/** 参考文件选择（自主设计模式，手动上传暂存待创建会话后提交） */
const onImportFileChange = (file: any) => {
    dialog.importFile = (file && file.raw) || null;
    dialog.importFileList = file ? [{ name: file.name }] : [];
};

/** 参考文件移除 */
const onImportFileRemove = () => {
    dialog.importFile = null;
    dialog.importFileList = [];
};

/** 参考文件 limit=1 下重复选择 → 替换既有文件（el-upload 不自动替换，手动接管） */
const onImportFileExceed = (files: any[]) => {
    const file = files && files[0];
    if (!file) return;
    dialog.importFile = file;
    dialog.importFileList = [{ name: file.name }];
};

/**
 * 查看历史生成记录：加载生成型会话（未绑定 templateId），
 * 已应用/未应用全部显示，点击记录可回到抽屉继续处理
 */
const onShowHistory = async () => {
    dialog.view = 'history';
    // 默认展示未应用页签（活跃工作集），每次打开重置避免上次停留页签造成误导
    dialog.historyTab = 'pending';
    dialog.historyLoading = true;
    try {
        const res = await aiApi.listSessions();
        dialog.sessions = (res.data || []).filter((s: any) => !s.templateId);
    } catch (e: any) {
        ElMessage.error(e?.message || '加载历史记录失败');
    } finally {
        dialog.historyLoading = false;
    }
};

/** 历史记录按状态分流：未应用（可继续打磨/应用）与已应用（仅回看） */
const pendingSessions = computed(() => dialog.sessions.filter((s: any) => s.status !== 'applied'));
const appliedSessions = computed(() => dialog.sessions.filter((s: any) => s.status === 'applied'));

/**
 * 状态徽章四态：生成中（蓝）/ 生成失败（红）为会话期瞬时态（内存覆盖，open 时快照）；
 * 待应用（黄）/ 已应用（绿）按落库 status 映射
 */
const sessionBadge = (row: any): { text: string; type: 'primary' | 'danger' | 'warning' | 'success' } => {
    if (row.sessionId && row.sessionId === dialog.runningId) return { text: '生成中', type: 'primary' };
    if (row.sessionId && row.sessionId === dialog.failedId) return { text: '生成失败', type: 'danger' };
    if (row.status === 'applied') return { text: '已应用', type: 'success' };
    return { text: '待应用', type: 'warning' };
};

/**
 * 打开历史生成会话：通知父组件进入会话编辑视图恢复会话（不自动发送消息）；
 * 未应用的可继续对话打磨，已应用的只读回看（预览照常显示）
 */
const onOpenHistorySession = (row: any) => {
    emit('open-session', row, dialog.sessions);
    // 会话已进入工作台恢复，对话框完成使命即关闭
    visible.value = false;
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
    dialog.templateName = (val || '').replace(/[^a-zA-Z0-9_-]/g, '');
};

/**
 * 确认新建模板：创建生成型会话并通知父组件自动发送首条需求。
 * 自主设计模式带参考文件：创建会话（createMode=design）→ 上传 HTML（同步 ingest，
 * 后端归一为 import 血统）→ 自动发送消息触发转化（单文件 landing 起步 DESIGNING，AI 仿写子页；zip 起步 CONVERTING）
 */
const onCreateConfirm = async () => {
    // rules 校验（错误就地显示在表单项下方），不通过则中断提交
    try {
        await createFormRef.value?.validate();
    } catch {
        return;
    }
    const name = dialog.templateName.trim();
    const requirement = dialog.requirement.trim();
    // 自主设计 + 参考文件 = 仿写链路（上传后由后端归一血统）；无文件 = 纯 AI 自主设计
    const withReference = dialog.createMode === 'design' && !!dialog.importFile;
    dialog.loading = true;
    try {
        // 模式只传 pipeline/design 两种；带参考文件时方向不传（文件本身即方向）
        const res = await aiApi.createSession({
            templateName: name, requirement, mobileAdaptive: dialog.mobileAdaptive,
            createMode: dialog.createMode,
            designDirection: (!withReference && dialog.designDirection) || undefined,
            confirmAuto: dialog.confirmAuto === true
        });
        if (!res.data) {
            ElMessage.error(res.msg || '创建会话失败');
            return;
        }
        if (withReference) {
            // 上传参考文件（同步完成解压 + 归一化 + plan.json 落盘，秒级；后端归一为 import 血统）
            const impRes = await aiApi.uploadReference(res.data.sessionId, dialog.importFile as File);
            if (!impRes.data) {
                ElMessage.error(impRes.msg || '导入失败');
                return;
            }
            const report = impRes.data;
            ElMessage.success(`导入完成：${report.pageCount} 个页面 / ${report.assetCount} 个资产文件，开始转化…`);
        }
        visible.value = false;
        // 首条消息：仿写链路触发编排器推进（单文件 landing DESIGNING 起步 / zip CONVERTING 起步）；生成模式发送需求
        emit('created', res.data, withReference ? '开始转化导入的模板页面' : requirement);
    } catch (e: any) {
        ElMessage.error(e?.message || '创建会话失败');
    } finally {
        dialog.loading = false;
    }
};

defineExpose({ open });
</script>

<style lang="scss" scoped>
// 历史生成记录列表（el-dialog teleport 到 body，插槽内容仍带组件 scoped 属性，可正常匹配）
.history-session-table {
    cursor: pointer;
}

// 页签 label 内 badge：与文字垂直居中、间距收紧
.history-tab-badge {
    margin-left: 6px;
    vertical-align: 2px;

    :deep(.el-badge__content) {
        position: relative;
        transform: none;
    }
}

.history-tip {
    margin-top: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
}
</style>
