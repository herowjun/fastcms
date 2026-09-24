<template>
    <el-dialog v-model="visible" title="AI 新建模板" width="520px" :close-on-click-modal="false">
        <el-form ref="createFormRef" :model="dialog"
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
                <!-- 人工确认策略由后端按会话类型决定（自由设计会话设计稿需人工确认，导入会话全自动），前端不提供开关 -->
            </template>
            <el-form-item label="移动端适配">
                <el-checkbox v-model="dialog.mobileAdaptive">生成响应式布局（多端断点 + 移动端汉堡菜单）</el-checkbox>
            </el-form-item>
        </el-form>
        <!-- 历史生成记录视图已删除：由父页面左侧常驻工作面板（WorkObjectPanel）的未应用/已应用页签接管 -->
        <template #footer>
            <el-button @click="visible = false">取 消</el-button>
            <el-button type="primary" :loading="dialog.loading" @click="onCreateConfirm">
                开始生成
            </el-button>
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
}>();

const aiApi = AiTemplateApi();

const visible = computed({
    get: () => props.visible,
    set: (v: boolean) => emit('update:visible', v)
});

// 对话框状态（新建表单；历史生成记录视图已删除，由左侧工作面板 WorkObjectPanel 接管）
const dialog = reactive({
    templateName: '',
    requirement: '',
    // 是否适配移动端（默认开启：响应式布局 + 移动端汉堡菜单）
    mobileAdaptive: true,
    // 生成模式（UI 两项）：pipeline=组件编排（默认，兼容旧客户端不传字段的后端口径）；
    // design=AI 自主设计。'import' 不再是 UI 选项——自主设计 + 参考文件时提交路由改走 import 口径
    createMode: 'pipeline' as 'pipeline' | 'design',
    // 设计稿模式：设计方向 key（空 = AI 自选；选项来自后端方向资产库，不写死）
    designDirection: '',
    // 导入模式：待上传的 HTML/ZIP 文件（手动上传，创建会话后随 uploadReference 提交）
    importFile: null as File | null,
    importFileList: [] as any[],
    loading: false
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
 * 打开 AI 新建模板对话框（新建表单视图；每次打开重置全部字段，避免上次选择残留）
 */
const open = () => {
    dialog.templateName = '';
    dialog.requirement = '';
    dialog.mobileAdaptive = true;
    // 设计模式字段每次重置（默认管线、方向 AI 自选；确认策略由后端按会话类型决定）
    dialog.createMode = 'pipeline';
    dialog.designDirection = '';
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
            designDirection: (!withReference && dialog.designDirection) || undefined
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
