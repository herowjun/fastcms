<template>
    <!-- 内联预览面板：手动编辑视图与 AI 工作台共用（Phase 0 抽取自原 AI 抽屉预览列）。
         URL/页面选项/空态文案由持有方注入（useAiPreview 已拼好），本组件只负责列头工具与舞台渲染；
         点选换图/选区钩子由持有方经 @frame-load + frameEl() 接通（withPickHooks 场景）。 -->
    <div class="preview-panel">
        <div class="preview-toolbar">
            <el-select :model-value="entry" size="small" filterable placeholder="选择预览页面"
                       @update:model-value="(v: string) => emit('update:entry', v)">
                <el-option v-for="p in pageOptions" :key="p" :value="p" :label="p" />
            </el-select>
            <el-select :model-value="viewport" size="small" class="viewport-select" title="预览视口"
                       @update:model-value="(v: any) => emit('update:viewport', v)">
                <el-option value="desktop" label="桌面" />
                <el-option value="tablet" label="平板 · 768px" />
                <el-option value="mobile" label="手机 · 375px" />
            </el-select>
            <!-- ⟳ 刷新：emit 由持有方走刷新键机制（?t= 变化驱动 iframe 重载） -->
            <el-button size="small" title="刷新预览" @click="emit('refresh')">
                <el-icon><ele-Refresh /></el-icon>
            </el-button>
            <el-button size="small" title="新窗口打开" @click="openInNewWindow">
                <el-icon><ele-FullScreen /></el-icon>
            </el-button>
        </div>
        <div class="preview-stage" :class="viewport">
            <div v-if="url" class="stage-frame" :class="viewport">
                <iframe ref="frameRef" :src="url" class="preview-frame" title="模板实时预览"
                        @load="emit('frame-load')"></iframe>
            </div>
            <!-- 预览空白占位：新会话生成中（尚无页面文件）或无可路由 HTML 时 -->
            <div v-else class="preview-empty">
                <el-empty :description="emptyTip" :image-size="80" />
            </div>
        </div>
    </div>
</template>

<script lang="ts" name="templatePreviewPanel" setup>
import { ref } from 'vue';

type Viewport = 'desktop' | 'tablet' | 'mobile';

const props = withDefaults(defineProps<{
    /** 当前预览页面（含模板目录前缀，预览后端会截掉） */
    entry?: string;
    /** 可选预览页面列表（文件树中的可路由 HTML） */
    pageOptions?: string[];
    /** 预览地址（持有方经 useAiPreview 拼接，含 ?t= 刷新键） */
    url?: string;
    /** 空态文案 */
    emptyTip?: string;
    /** 视口档位：桌面 / 平板 768 / 手机 375 */
    viewport?: Viewport;
}>(), {
    entry: '',
    pageOptions: () => [],
    url: '',
    emptyTip: '暂无可预览页面',
    viewport: 'desktop'
});

const emit = defineEmits<{
    (e: 'update:entry', v: string): void;
    (e: 'update:viewport', v: Viewport): void;
    (e: 'refresh'): void;
    (e: 'frame-load'): void;
}>();

const frameRef = ref<HTMLIFrameElement>();

/** 暴露 iframe 元素（AI 工作台点选钩子注入用） */
const frameEl = () => frameRef.value;

/** 直接重载当前 iframe（同源，contentWindow 可用；持有方也可走刷新键机制） */
const reload = () => {
    try {
        frameRef.value?.contentWindow?.location.reload();
    } catch {
        // 跨域等异常时静默忽略
    }
};

/** 新窗口打开当前预览 */
const openInNewWindow = () => {
    if (props.url) window.open(props.url, '_blank');
};

defineExpose({ frameEl, reload, openInNewWindow });
</script>

<style lang="scss" scoped>
.preview-panel {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    height: 100%;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    overflow: hidden;
    background: #fff;

    .preview-toolbar {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px;
        border-bottom: 1px solid var(--el-border-color-lighter);

        > .el-select:first-child {
            flex: 1;
            min-width: 0;
        }

        .viewport-select {
            width: 128px;
            flex-shrink: 0;
        }
    }

    // 视口舞台：非桌面档灰底居中缩放（对齐原型 .viewport-stage/.stage-frame）
    .preview-stage {
        flex: 1;
        min-height: 0;
        overflow: auto;
        background: var(--el-fill-color);
        display: flex;
        justify-content: center;
        align-items: flex-start;
        transition: background 0.2s;

        &.desktop {
            background: #fff;
            justify-content: stretch;

            .stage-frame {
                width: 100%;
                box-shadow: none;
            }
        }

        .stage-frame {
            background: #fff;
            height: 100%;
            box-shadow: 0 0 0 1px var(--el-border-color-lighter), 0 4px 18px rgba(0, 0, 0, 0.1);
            transition: width 0.25s ease;
            overflow: hidden;

            &.tablet {
                width: 768px;
            }

            &.mobile {
                width: 375px;
            }

            .preview-frame {
                width: 100%;
                height: 100%;
                border: 0;
                background: #fff;
                display: block;
            }
        }

        .preview-empty {
            width: 100%;
            height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
            background: var(--el-fill-color-lighter);
        }
    }
}
</style>
