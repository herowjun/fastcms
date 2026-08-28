<template>
<div class="container">
    <el-card>
        <el-form :model="state.ruleForm" size="default" label-width="100px" :rules="state.rules" ref="myRefForm">
            <el-row :gutter="35">
                <el-col class="mb20">
                    <el-form-item label="标题" prop="title">
                        <el-input v-model="state.ruleForm.title" placeholder="请输入文章标题" clearable>
                            <template #append>
                                <el-button :loading="state.aiFieldLoading === 'title'" @click="onAiFieldBtn('title')">✨ AI</el-button>
                            </template>
                        </el-input>
                    </el-form-item>
                </el-col>
                <el-col class="mb20">
                    <el-form-item label="文章详情" prop="contentHtml">
                        <ckeditor ref="ckeditorRef" aiEnabled style="width:100%" v-model="state.ruleForm.contentHtml" @ai-rewrite="onEditorAiRewrite"></ckeditor>
                    </el-form-item>
                </el-col>
                <el-col class="mb20">
                    <el-form-item label="缩略图" prop="thumbnail">
                        <el-image
                            style="width: 100px; height: 100px"
                            :src="state.ruleForm.thumbnailUrl"
                            :fit="state.fit"
                            :preview-src-list="[state.ruleForm.thumbnailUrl]"
                            ></el-image>
                    </el-form-item>
                    <el-form-item>
                        <el-link type="primary" @click="onThumbnailDialogOpen">选择图片</el-link>
                    </el-form-item>
                </el-col>
                <el-col class="mb20">
                    <el-form-item label="文章摘要" prop="summary">
                        <el-input v-model="state.ruleForm.summary" type="textarea" :rows="2" placeholder="请输入文章摘要" clearable>
                            <template #append>
                                <el-button :loading="state.aiFieldLoading === 'summary'" @click="onAiFieldBtn('summary')">✨ AI</el-button>
                            </template>
                        </el-input>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="SEO关键词" prop="seoKeywords">
                        <el-input type="textarea" :rows="2" v-model="state.ruleForm.seoKeywords" placeholder="请输入seo关键词" clearable>
                            <template #append>
                                <el-button :loading="state.aiFieldLoading === 'seoKeywords'" @click="onAiFieldBtn('seoKeywords')">✨ AI</el-button>
                            </template>
                        </el-input>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="SEO描述" prop="seoDescription">
                        <el-input type="textarea" :rows="2" v-model="state.ruleForm.seoDescription" placeholder="请输入SEO描述" clearable>
                            <template #append>
                                <el-button :loading="state.aiFieldLoading === 'seoDescription'" @click="onAiFieldBtn('seoDescription')">✨ AI</el-button>
                            </template>
                        </el-input>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="排序" prop="sortNum">
                        <el-input v-model="state.ruleForm.sortNum" type="number" placeholder="请输入排序序号" clearable></el-input>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="外链" prop="outLink">
                        <el-input v-model="state.ruleForm.outLink" placeholder="请输入外链" clearable></el-input>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="模板" prop="suffix">
                        <el-input v-model="state.ruleForm.suffix" placeholder="请输入文章模板后缀" clearable></el-input>
                        <div class="sub-title">结合网站模板使用，不正确填写，访问页面会出现404</div>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="允许评论" prop="commentEnable">
                        <el-switch
                            v-model="state.ruleForm.commentEnable"
                            active-color="#13ce66">
                        </el-switch>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="分类" prop="categories">
                        <el-cascader ref="categoryCascader" @change="onCategoryChange" v-model="state.ruleForm.articleCategory" :options="state.categories" :props="{ multiple: true, label: 'label', value: 'id', children: 'children' }" collapse-tags clearable />
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="标签" prop="tags">
                        <el-select
                            v-model="state.ruleForm.articleTag"
                            class="w100"
                            multiple
                            filterable
                            allow-create
                            default-first-option
                            placeholder="可直接输入标签名称">
                            <el-option v-for="item in state.tags" :key="item.id" :label="item.tagName" :value="item.tagName"></el-option>
                        </el-select>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="附件" prop="attachTitle">
                        <el-input v-model="state.ruleForm.attachTitle" readonly></el-input>
                    </el-form-item>
                    <el-form-item>
                        <el-link type="primary" @click="onAttachlDialogOpen">选择附件</el-link>
                    </el-form-item>
                </el-col>
                <el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb20">
                    <el-form-item label="状态" prop="status">
                        <el-select v-model="state.ruleForm.status" placeholder="请选择状态" clearable class="w100">
                            <el-option label="发布" value="publish"></el-option>
                            <el-option label="草稿" value="draft"></el-option>
                        </el-select>
                    </el-form-item>
                </el-col>            
            </el-row>
            <el-row>
                <el-form-item>
                    <el-button type="primary" @click="onSubmit" size="default">保 存</el-button>
                    <el-button type="success" plain @click="onAiDrawerOpen" size="default">✨ AI 生成文章</el-button>
                    <el-button type="info" plain @click="onAiHistoryOpen" size="default" :disabled="!state.articleId && state.aiOpIds.length === 0">🕘 AI 记录</el-button>
                </el-form-item>
            </el-row>
        </el-form>
    </el-card>
    <AttachDialog ref="thumbnailDialogRef" @attachHandler="getSelectThumbnail" :fileType="state.fileType"/>
    <AttachDialog ref="attachDialogRef" @attachHandler="getSelectAttach"/>

    <!-- AI 助手抽屉（统一入口：生成文章/字段候选/划词改写的思考过程都在此流式展示） -->
    <el-drawer v-model="state.aiDrawerVisible" :title="aiDrawerTitle" size="40%" :close-on-click-modal="false">
        <!-- 思考过程（所有模式共用，流式展示） -->
        <div v-if="state.aiThinking || state.aiReasoning" class="ai-thinking-box">
            <div class="ai-thinking-header" @click="state.aiReasoningExpanded = !state.aiReasoningExpanded">
                <span class="ai-thinking-title">{{ state.aiThinking ? '正在思考…' : '已深度思考' }}</span>
                <span class="ai-thinking-arrow" :class="{ collapsed: !state.aiReasoningExpanded }">▸</span>
            </div>
            <div v-show="state.aiReasoningExpanded" class="ai-thinking-text">{{ formatReasoning(state.aiReasoning) }}</div>
        </div>

        <!-- 模式：生成文章 -->
        <div v-if="state.aiDrawerMode === 'generate'" class="ai-gen-panel">
            <el-input v-model="state.aiGen.topic" type="textarea" :rows="2" placeholder="文章主题，例如：Spring Boot 4 新特性解读"
                :disabled="state.aiGen.generating"></el-input>
            <el-input v-model="state.aiGen.keywords" placeholder="关键词（可选，逗号分隔）" style="margin-top:8px"
                :disabled="state.aiGen.generating"></el-input>
            <el-input v-model="state.aiGen.instruction" placeholder="补充要求（可选，如：面向初学者、1500字左右）" style="margin-top:8px"
                :disabled="state.aiGen.generating"></el-input>
            <div style="margin-top:10px;text-align:right">
                <el-button type="primary" :loading="state.aiGen.generating" :disabled="!state.aiGen.topic.trim()" @click="onAiGenerate">
                    {{ state.aiGen.generating ? '生成中…' : '生成' }}
                </el-button>
            </div>
            <div v-if="state.aiGen.output" class="ai-gen-output">
                <pre class="ai-gen-text">{{ state.aiGen.output }}</pre>
            </div>
            <div v-if="state.aiGen.result" class="ai-gen-result">
                <div style="margin-bottom:8px;color:#67c23a">✅ 生成完成，选择要应用的字段：</div>
                <div class="ai-gen-apply">
                    <el-button size="small" @click="applyAiResult('title')">应用标题</el-button>
                    <el-button size="small" @click="applyAiResult('summary')">应用摘要</el-button>
                    <el-button size="small" @click="applyAiResult('content')">应用正文</el-button>
                    <el-button size="small" @click="applyAiResult('seoKeywords')">应用SEO关键词</el-button>
                    <el-button size="small" @click="applyAiResult('seoDescription')">应用SEO描述</el-button>
                    <el-button size="small" type="success" @click="applyAiResult('all')">全部应用</el-button>
                </div>
            </div>
        </div>

        <!-- 模式：字段候选 -->
        <div v-else-if="state.aiDrawerMode === 'field'">
            <div v-if="state.aiFieldDialog.generating" v-loading="true" element-loading-text="AI 生成中"
                style="min-height:100px"></div>
            <template v-else-if="state.aiFieldDialog.candidates.length > 0">
                <div style="margin-bottom:8px;color:#67c23a">✅ 候选已生成，点击即应用：</div>
                <div class="ai-candidate-list">
                    <div v-for="(item, idx) in state.aiFieldDialog.candidates" :key="idx" class="ai-candidate-item" @click="applyAiCandidate(item)">
                        {{ item }}
                    </div>
                </div>
            </template>
        </div>

        <!-- 模式：划词改写（结果预览，确认后才替换编辑器选中内容） -->
        <div v-else-if="state.aiDrawerMode === 'rewrite'">
            <div v-if="state.aiRewriting" class="ai-rewrite-status">
                <el-icon class="is-loading"><ele-Loading /></el-icon>
                {{ opLabel(state.aiRewriteOp) }}中…
            </div>
            <template v-if="state.aiRewriteResult">
                <div class="ai-rewrite-section">
                    <div class="ai-op-label">原文</div>
                    <div class="ai-op-text original">{{ state.aiRewriteOriginal }}</div>
                </div>
                <div class="ai-rewrite-section">
                    <div class="ai-op-label">AI 结果</div>
                    <div class="ai-op-text result">{{ state.aiRewriteResult }}<span
                        v-if="state.aiRewriting" class="typing-cursor">▌</span></div>
                </div>
                <div v-if="!state.aiRewriting" class="ai-rewrite-actions">
                    <el-button @click="regenerateAiRewrite" :disabled="!state.aiRewritePayload">🔄 重新生成</el-button>
                    <el-button type="primary" @click="confirmAiRewrite">替换选中内容</el-button>
                    <el-button @click="cancelAiRewrite">放 弃</el-button>
                </div>
            </template>
        </div>
    </el-drawer>

    <!-- AI 操作历史抽屉 -->
    <el-drawer v-model="state.aiHistory.visible" title="AI 操作记录" size="50%">
        <div v-loading="state.aiHistory.loading">
            <el-empty v-if="!state.aiHistory.loading && state.aiHistory.list.length === 0"
                description="本文暂无 AI 操作记录（划词改写/扩写/润色/翻译后会记录在此）"></el-empty>
            <div v-for="op in state.aiHistory.list" :key="op.id" class="ai-op-item">
                <div class="ai-op-header">
                    <el-tag size="small" :type="opTagType(op.operation)">{{ opLabel(op.operation) }}</el-tag>
                    <span class="ai-op-time">{{ op.created }}</span>
                    <span v-if="op.model" class="ai-op-model">{{ op.model }}</span>
                </div>
                <div class="ai-op-body">
                    <div class="ai-op-section">
                        <div class="ai-op-label">原文</div>
                        <div class="ai-op-text original">{{ op.originalText }}</div>
                    </div>
                    <div class="ai-op-section">
                        <div class="ai-op-label">AI 结果</div>
                        <div class="ai-op-text result">{{ op.rewrittenText }}</div>
                    </div>
                    <div v-if="op.reasoning" class="ai-op-section">
                        <div class="ai-op-label reasoning-toggle" @click="op._reasoningExpanded = !op._reasoningExpanded">
                            思考过程 <span class="ai-op-arrow" :class="{ collapsed: !op._reasoningExpanded }">▸</span>
                        </div>
                        <div v-show="op._reasoningExpanded" class="ai-op-text reasoning">{{ formatReasoning(op.reasoning) }}</div>
                    </div>
                </div>
            </div>
        </div>
    </el-drawer>
</div>
</template>

<script lang="ts" name="articleWrite" setup>
import { ref, reactive, onMounted, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRoute } from 'vue-router';
import AttachDialog from '/@/components/attach/index.vue';
import { ArticleApi } from '/@/api/article/index';
import { AiArticleApi } from '/@/api/ai/index';
import { Local } from '/@/utils/storage';
import qs from 'qs';
import CKEditor from "/@/components/ckeditor/index.vue";

const ckeditor = CKEditor;
const myRefForm = ref();
const articleApi = ArticleApi();
const aiApi = AiArticleApi();
const thumbnailDialogRef = ref();
const attachDialogRef = ref();
const ckeditorRef = ref();
const categoryCascader = ref();
const route = useRoute();
const state = reactive({
    fileType: "image",
    fit: "fill",
    row: null,
    isShowDialog: false,
    params: {},
    categories: [],
    tags: [] as any,
    ruleForm: {
        thumbnailUrl: '',
        articleTag: '',
        attachTitle: '',
        title: '',
        commentEnable: true,
        contentHtml: '',
        summary: '',
        seoKeywords: '',
        seoDescription: '',
        outLink: '',
        sortNum: 0,
        thumbnail: '',
        suffix: '',
        status: 'publish',
        articleCategory: [],
        attachId: null,
    },
    rules: {
        "title": { required: true, message: '请输入文章标题', trigger: 'blur' },
        "contentHtml": { required: true, message: '请输入文章详情', trigger: 'blur' },
        "thumbnail": { required: true, message: '请选择缩略图', trigger: 'blur' },
        "summary": { required: true, message: '请输入文章摘要', trigger: 'blur' },
        "seoKeywords": { required: true, message: '请输入SEO关键词', trigger: 'blur' },
        "seoDescription": { required: true, message: '请输入SEO描述', trigger: 'blur' },
        "status": { required: true, message: '请选择发布状态', trigger: 'blur' },
    },
    // ===== AI =====
    aiDrawerVisible: false,
    aiGen: {
        topic: '',
        keywords: '',
        instruction: '',
        generating: false,
        output: '',
        reasoning: '',
        result: null as any,
    },
    aiFieldLoading: '',
    aiFieldDialog: {
        field: '',
        candidates: [] as string[],
        generating: false,
    },
    // 各字段最近一次 AI 生成的候选（field -> candidates），供再次生成前回看选择
    aiFieldHistory: {} as Record<string, string[]>,
    aiRewriting: false,
    // 当前划词操作类型（rewrite/expand/polish/translate），供抽屉展示
    aiRewriteOp: '',
    // 最近一次划词请求参数缓存（供"重新生成"复用）
    aiRewritePayload: null as any,
    // 划词改写：原文与 AI 结果（抽屉内对比展示，用户确认后才替换编辑器）
    aiRewriteOriginal: '',
    aiRewriteResult: '',
    // 统一 AI 抽屉：模式（generate/field/rewrite）+ 思考过程
    aiDrawerMode: 'generate' as 'generate' | 'field' | 'rewrite',
    aiThinking: false,
    aiReasoning: '',
    aiReasoningExpanded: true,
    // 当前文章ID（编辑已有文章时存在，新建为空）
    articleId: '' as any,
    // 本次页面会话期间产生的 AI 操作记录ID（新建文章保存后绑定）
    aiOpIds: [] as number[],
    // AI 操作历史抽屉
    aiHistory: {
        visible: false,
        loading: false,
        list: [] as any[],
    },
});

//获取文章分类跟标签
const getCategoryList = () => {
    articleApi.getArticleCategoryList().then((res) => {
        state.categories = res.data;
    })
    articleApi.getArticleTagList().then(res => {
        res.data.forEach((item: any) => {
                state.tags.push(item);
        });
    })
}

const onSubmit = () => {
    myRefForm.value.validate((valid: any) => {
        if (valid) {
            categoryCascader.value.getCheckedNodes(true).map(item => {
                state.ruleForm.articleCategory.push(item.value);
            });
            let params = qs.stringify(state.ruleForm, {arrayFormat: 'repeat'});
            articleApi.addArticle(params).then((res) => {
                state.ruleForm.id = res.data;
                ElMessage.success("保存成功");
                // 新建文章首次保存：把本次会话期间的 AI 划词记录绑定到该文章
                if (!state.articleId && res.data && state.aiOpIds.length > 0) {
                    state.articleId = res.data;
                    aiApi.bindOps({ articleId: res.data, opIds: state.aiOpIds }).catch(() => { /* 绑定失败不影响保存 */ });
                }
            }).catch((res) => {
                ElMessage({showClose: true, message: res.message ? res.message : '系统错误' , type: 'error'});
            })
        }
    });
};

const getArticleInfo = (id: string) => {
    articleApi.getArticle(id).then((res) => {
        state.ruleForm = res.data;
    })
}

// const onEditorReady = (editor) => {
//     console.log(editor);
// };

//打开缩略图弹出框
const onThumbnailDialogOpen = () => {
    thumbnailDialogRef.value.openDialog(1);
};

//打开附件弹出框
const onAttachlDialogOpen = () => {
    attachDialogRef.value.openDialog(1);
};

//获取弹出框选中的图片
const getSelectThumbnail = (value) => {
    state.ruleForm.thumbnail = value[0].filePath;
    state.ruleForm.thumbnailUrl = value[0].path;
};

//获取弹出框选中的附件
const getSelectAttach = (value) => {
    state.ruleForm.attachId = value[0].id;
    state.ruleForm.attachTitle = value[0].fileName;
};

const onCategoryChange = () => {
	// const p = categoryCascader.value.getCheckedNodes();
	// console.log("====p:" + p[0].value)
    // categoryCascader.value.getCheckedNodes(true).map(item => {
    //     state.ruleForm.articleCategory.push(item.value);
    // });
};

// ==================== AI 能力（无状态：每次请求携带完整上下文） ====================

/**
 * 通用 SSE POST 消费：fetch + ReadableStream 解析 text/event-stream
 * handlers: { onMessage(delta), onReasoning(delta), onDone(data), onError(msg) }
 */
const ssePost = async (url: string, body: object, handlers: any) => {
    const token = Local.get('token') as string | undefined;
    const resp = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: 'Bearer ' + token } : {})
        },
        body: JSON.stringify(body)
    });
    if (!resp.ok || !resp.body) {
        let msg = '请求失败（' + resp.status + '）';
        try {
            const errRes = await resp.json();
            if (errRes && errRes.msg) msg = errRes.msg;
        } catch (e) { /* ignore */ }
        handlers.onError(msg);
        return;
    }
    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    let currentEvent = 'message';
    let currentData: string[] = [];
    const dispatch = () => {
        if (currentData.length === 0) { currentEvent = 'message'; return; }
        const data = currentData.join('\n');
        switch (currentEvent) {
            case 'message': handlers.onMessage?.(data); break;
            case 'reasoning': handlers.onReasoning?.(data); break;
            case 'done': handlers.onDone?.(data); break;
            case 'error': handlers.onError?.(data); break;
        }
        currentEvent = 'message';
        currentData = [];
    };
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split(/\r?\n/);
        buf = lines.pop() || '';
        for (const line of lines) {
            if (line.startsWith('event:')) {
                dispatch();
                currentEvent = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                currentData.push(line.slice(5).replace(/^ /, ''));
            } else if (line === '') {
                dispatch();
            }
        }
    }
    dispatch();
};

/**
 * 统一 AI 抽屉标题
 */
const aiDrawerTitle = computed(() => {
    switch (state.aiDrawerMode) {
        case 'field': return 'AI 候选：' + fieldLabel(state.aiFieldDialog.field);
        case 'rewrite': return 'AI ' + opLabel(state.aiRewriteOp || 'rewrite');
        default: return 'AI 生成文章草稿';
    }
});

/**
 * 思考过程文本格式化：模型输出是一整段，按句末标点与分点编号断行提升可读性
 */
const formatReasoning = (text: string): string => {
    if (!text) return '';
    return text
        // 句末标点后换行
        .replace(/([。？！；])/g, '$1\n')
        // 分点/编号/提示词前换行（如"候选："、"1."、"首先："）
        .replace(/(?<!\n)(候选[：:]|首先[：:]|其次[：:]|然后[：:]|最后[：:]|步骤[：:]|\d+\s*[\.、)）])/g, '\n$1')
        // 合并多余空行
        .replace(/\n{2,}/g, '\n')
        .trim();
};

/**
 * 打开统一 AI 抽屉（指定模式），重置思考过程状态
 */
const openAiDrawer = (mode: 'generate' | 'field' | 'rewrite') => {
    state.aiDrawerMode = mode;
    state.aiThinking = false;
    state.aiReasoning = '';
    state.aiReasoningExpanded = true;
    state.aiDrawerVisible = true;
};

const onAiDrawerOpen = () => {
    state.aiGen.output = '';
    state.aiGen.result = null;
    state.aiGen.generating = false;
    openAiDrawer('generate');
};

/**
 * AI 全文生成：流式接收说明文本，done 后解析结构化结果
 */
const onAiGenerate = async () => {
    if (!state.aiGen.topic.trim() || state.aiGen.generating) return;
    state.aiGen.generating = true;
    state.aiGen.output = '';
    state.aiGen.result = null;
    state.aiThinking = true;
    state.aiReasoning = '';
    try {
        await ssePost(aiApi.generateUrl(), {
            topic: state.aiGen.topic,
            keywords: state.aiGen.keywords,
            instruction: state.aiGen.instruction,
            articleId: state.articleId || undefined,
        }, {
            onMessage: (delta: string) => { state.aiGen.output += delta; },
            onReasoning: (delta: string) => { state.aiReasoning += delta; },
            onDone: (data: string) => {
                state.aiThinking = false;
                try {
                    const parsed = JSON.parse(data);
                    if (parsed.logId) state.aiOpIds.push(parsed.logId);
                    state.aiGen.result = parsed.content ? parsed : null;
                    if (!state.aiGen.result) ElMessage.warning('AI 未返回正文，请重试');
                } catch (e) {
                    ElMessage.warning('结果解析失败，请重试');
                }
            },
            onError: (msg: string) => { state.aiThinking = false; ElMessage.error(msg || '生成失败'); },
        });
    } catch (e: any) {
        ElMessage.error(e?.message || '生成失败');
    } finally {
        state.aiGen.generating = false;
        state.aiThinking = false;
    }
};

/**
 * 应用 AI 生成结果到表单（content 映射 contentHtml）
 */
const applyAiResult = (field: string) => {
    const result = state.aiGen.result;
    if (!result) return;
    const apply = (f: string) => {
        if (!result[f]) return;
        if (f === 'content') {
            state.ruleForm.contentHtml = result.content;
        } else {
            (state.ruleForm as any)[f] = result[f];
        }
    };
    if (field === 'all') {
        ['title', 'summary', 'content', 'seoKeywords', 'seoDescription'].forEach(apply);
    } else {
        apply(field);
    }
    ElMessage.success('已应用');
};

/**
 * 单字段候选（标题/摘要/SEO）
 * 非首次生成时先展示上次候选，由用户决定重新生成还是直接选用
 */
const onAiFieldBtn = async (field: string) => {
    if (state.aiFieldLoading) return;
    if (!state.ruleForm.contentHtml) {
        ElMessage.warning('请先填写正文再生成' + fieldLabel(field));
        return;
    }
    // 非首次：先展示上次候选，询问是否重新生成
    const prevCandidates = state.aiFieldHistory[field];
    if (prevCandidates && prevCandidates.length > 0) {
        try {
            await ElMessageBox.confirm(
                '上一次 AI 生成的候选还保留着，是否重新生成？\n（"取消"则回看上次候选直接选用，不消耗 AI 额度）',
                'AI ' + fieldLabel(field),
                { confirmButtonText: '重新生成', cancelButtonText: '查看上次候选', type: 'info', distinguishCancelAndClose: true }
            );
        } catch (action) {
            if (action === 'cancel') {
                // 回看上次候选：打开抽屉展示，不发起 AI 请求
                state.aiFieldDialog.field = field;
                state.aiFieldDialog.candidates = [...prevCandidates];
                state.aiFieldDialog.generating = false;
                openAiDrawer('field');
                return;
            }
            return; // 右上角关闭：什么都不做
        }
    }
    state.aiFieldLoading = field;
    // 打开统一 AI 抽屉（field 模式），流式展示思考过程
    state.aiFieldDialog.field = field;
    state.aiFieldDialog.candidates = [];
    state.aiFieldDialog.generating = true;
    openAiDrawer('field');
    state.aiThinking = true;
    try {
        await ssePost(aiApi.fieldUrl(), {
            field,
            content: state.ruleForm.contentHtml,
            title: state.ruleForm.title,
            articleId: state.articleId || undefined,
        }, {
            onReasoning: (delta: string) => { state.aiReasoning += delta; },
            onDone: (data: string) => {
                state.aiThinking = false;
                try {
                    const parsed = JSON.parse(data);
                    if (parsed.logId) state.aiOpIds.push(parsed.logId);
                    state.aiFieldDialog.candidates = parsed.candidates || [];
                    // 记住本次候选，供下次回看
                    if (state.aiFieldDialog.candidates.length > 0) {
                        state.aiFieldHistory[field] = [...state.aiFieldDialog.candidates];
                    }
                } catch (e) {
                    ElMessage.warning('结果解析失败，请重试');
                }
            },
            onError: (msg: string) => {
                state.aiThinking = false;
                ElMessage.error(msg || 'AI 处理失败');
            },
        });
        if (state.aiFieldDialog.candidates.length === 0) {
            ElMessage.warning('AI 未返回候选');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '生成失败');
    } finally {
        state.aiFieldDialog.generating = false;
        state.aiFieldLoading = '';
        state.aiThinking = false;
    }
};

const fieldLabel = (field: string) => {
    const map: any = {
        title: '标题', summary: '摘要', seoKeywords: 'SEO关键词', seoDescription: 'SEO描述',
    };
    return map[field] || field;
};

/**
 * AI 操作历史：打开抽屉并加载记录（按文章维度，倒序）
 */
const onAiHistoryOpen = () => {
    if (!state.articleId) {
        ElMessage.warning('新建文章保存后才能查看历史记录');
        return;
    }
    state.aiHistory.visible = true;
    state.aiHistory.loading = true;
    state.aiHistory.list = [];
    aiApi.listOps(state.articleId).then((res: any) => {
        state.aiHistory.list = (res.data || []).map((op: any) => ({ ...op, _reasoningExpanded: false }));
    }).catch((e: any) => {
        ElMessage.error(e?.message || '加载 AI 记录失败');
    }).finally(() => {
        state.aiHistory.loading = false;
    });
};

const opLabel = (operation: string) => {
    const map: any = {
        rewrite: '改写', expand: '扩写', polish: '润色', translate: '翻译', generate: '生成文章',
        field_title: '标题候选', field_summary: '摘要候选',
        field_seoKeywords: 'SEO关键词候选', field_seoDescription: 'SEO描述候选',
    };
    return map[operation] || operation;
};

const opTagType = (operation: string) => {
    const map: any = {
        rewrite: 'primary', expand: 'success', polish: 'warning', translate: 'info',
        generate: 'danger', field_title: 'primary', field_summary: 'success',
        field_seoKeywords: 'warning', field_seoDescription: 'info',
    };
    return map[operation] || 'info';
};

/**
 * 应用字段候选
 */
const applyAiCandidate = (item: string) => {
    (state.ruleForm as any)[state.aiFieldDialog.field] = item;
    state.aiDrawerVisible = false;
    ElMessage.success('已应用' + fieldLabel(state.aiFieldDialog.field));
};

/**
 * 编辑器划词 AI：改写/扩写/润色/翻译
 * 结果先在抽屉中与原文对比展示，用户点"替换选中内容"后才写回编辑器
 */
const onEditorAiRewrite = (payload: any) => {
    // 缓存本次请求参数（含上下文），供"重新生成"复用
    state.aiRewritePayload = payload;
    doAiRewrite(payload);
};

/**
 * 执行划词 AI 请求（重新生成也走这里）
 */
const doAiRewrite = async (payload: any) => {
    if (state.aiRewriting) {
        ElMessage.warning('AI 正在处理上一次请求');
        return;
    }
    // 自动打开统一 AI 抽屉（rewrite 模式），思考过程在抽屉中流式展示
    state.aiRewriteOp = payload.operation;
    openAiDrawer('rewrite');
    state.aiRewriting = true;
    state.aiThinking = true;
    state.aiRewriteOriginal = payload.text;
    state.aiRewriteResult = '';
    let result = '';
    try {
        await ssePost(aiApi.rewriteUrl(), {
            text: payload.text,
            operation: payload.operation,
            instruction: payload.instruction,
            context: payload.context,
            articleTitle: state.ruleForm.title,
            articleId: state.articleId || undefined,
        }, {
            onReasoning: (delta: string) => { state.aiReasoning += delta; },
            onMessage: (delta: string) => { result += delta; state.aiRewriteResult += delta; },
            onDone: (data: string) => {
                state.aiThinking = false;
                state.aiReasoningExpanded = false; // 折叠思考，突出结果对比
                // done 数据为 JSON：{ content, logId }，解析失败回退为纯文本
                try {
                    const parsed = JSON.parse(data);
                    result = parsed.content || result;
                    if (parsed.logId) state.aiOpIds.push(parsed.logId);
                } catch (e) {
                    result = data || result;
                }
                state.aiRewriteResult = result;
            },
            onError: (msg: string) => { ElMessage.error(msg || 'AI 处理失败'); },
        });
        if (!result) {
            ElMessage.warning('AI 未返回结果');
        }
    } catch (e: any) {
        ElMessage.error(e?.message || 'AI 处理失败');
    } finally {
        state.aiRewriting = false;
        state.aiThinking = false;
    }
};

/**
 * 重新生成：同上下文再次请求（结果不满意时换一版）
 */
const regenerateAiRewrite = () => {
    if (!state.aiRewritePayload || state.aiRewriting) return;
    doAiRewrite(state.aiRewritePayload);
};

/**
 * 确认替换：把 AI 结果写入编辑器划词选区
 */
const confirmAiRewrite = () => {
    if (!state.aiRewriteResult) return;
    ckeditorRef.value?.replaceAiSelection(state.aiRewriteResult.replace(/\r?\n/g, '<br>'));
    state.aiDrawerVisible = false;
    ElMessage.success('AI 已替换选中内容');
};

/**
 * 放弃本次改写结果
 */
const cancelAiRewrite = () => {
    state.aiDrawerVisible = false;
};

onMounted(() => {
    state.params = route;
    getCategoryList();
    let articleId = state.params.query.id;
    if(articleId) {
        state.articleId = articleId;
        getArticleInfo(articleId);
    }
});

</script>

<style lang="scss">
/* ===== AI 字段候选列表 ===== */
.ai-candidate-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
.ai-candidate-item {
    padding: 8px 12px;
    border: 1px solid #ebeef5;
    border-radius: 4px;
    background: #fff;
    cursor: pointer; /* 鼠标移入显示点击手势 */
    transition: all 0.15s;
    line-height: 1.5;
    &:hover {
        border-color: #409eff;
        background: #ecf5ff;
        color: #409eff;
    }
}

.ck-content { height:500px; }
.ai-rewrite-status {
    display: flex;
    align-items: center;
    gap: 8px;
    color: #409eff;
    padding: 4px 0 12px;
}
.ai-rewrite-section {
    margin-bottom: 10px;
}
.ai-rewrite-actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 4px;
}
.typing-cursor {
    color: #409eff;
    animation: blink 1s infinite;
}
@keyframes blink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
}
.ai-thinking-box {
    width: 100%;
    margin-bottom: 12px;
    border: 1px solid #e4e7ed;
    border-radius: 4px;
    background: #fafafa;
    font-size: 13px;
}
.ai-thinking-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 12px;
    cursor: pointer;
    user-select: none;
}
.ai-thinking-title {
    color: #409eff;
}
.ai-thinking-arrow {
    color: #909399;
    transition: transform 0.2s;
    &.collapsed { transform: rotate(-90deg); }
}
.ai-thinking-text {
    max-height: 320px;
    overflow-y: auto;
    padding: 4px 12px 8px;
    color: #909399;
    white-space: pre-wrap;
    line-height: 1.6;
    border-top: 1px dashed #e4e7ed;
}

/* ===== AI 操作历史抽屉 ===== */
.ai-op-item {
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 10px 14px;
    margin-bottom: 12px;
}
.ai-op-header {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 8px;
}
.ai-op-time {
    color: #909399;
    font-size: 12px;
}
.ai-op-model {
    color: #c0c4cc;
    font-size: 12px;
}
.ai-op-section {
    margin-bottom: 6px;
}
.ai-op-label {
    font-size: 12px;
    color: #909399;
    margin-bottom: 2px;
}
.ai-op-text {
    font-size: 13px;
    line-height: 1.6;
    padding: 6px 10px;
    border-radius: 4px;
    white-space: pre-wrap;
    word-break: break-all;
}
.ai-op-text.original {
    background: #fafafa;
    color: #606266;
    max-height: 100px;
    overflow-y: auto;
}
.ai-op-text.result {
    background: #f0f9eb;
    color: #303133;
    max-height: 150px;
    overflow-y: auto;
}
.ai-op-text.reasoning {
    background: #fafafa;
    color: #909399;
    max-height: 180px;
    overflow-y: auto;
}
.reasoning-toggle {
    cursor: pointer;
    user-select: none;
}
.ai-op-arrow {
    display: inline-block;
    color: #c0c4cc;
    transition: transform 0.2s;
    &.collapsed { transform: rotate(-90deg); }
}
</style>