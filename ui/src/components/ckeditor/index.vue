<script lang="ts" setup name ="fastcmsCkeditor">
import {onMounted, ref, watch, reactive} from "vue";

import connect from "./imgPlugin/connect";

// import ClassicEditor from '@ckeditor/ckeditor5-editor-classic/src/classiceditor';
import DecoupledEditor from '@ckeditor/ckeditor5-editor-decoupled/src/decouplededitor';
import Essentials from '@ckeditor/ckeditor5-essentials/src/essentials';
import UploadAdapter from '@ckeditor/ckeditor5-adapter-ckfinder/src/uploadadapter';
import Autoformat from '@ckeditor/ckeditor5-autoformat/src/autoformat';
import Bold from '@ckeditor/ckeditor5-basic-styles/src/bold';
import Code from '@ckeditor/ckeditor5-basic-styles/src/code';
import Italic from '@ckeditor/ckeditor5-basic-styles/src/italic';
import BlockQuote from '@ckeditor/ckeditor5-block-quote/src/blockquote';
import CKFinder from '@ckeditor/ckeditor5-ckfinder/src/ckfinder';
import EasyImage from '@ckeditor/ckeditor5-easy-image/src/easyimage';
import Heading from '@ckeditor/ckeditor5-heading/src/heading';
import Image from '@ckeditor/ckeditor5-image/src/image';
import ImageCaption from '@ckeditor/ckeditor5-image/src/imagecaption';
import ImageStyle from '@ckeditor/ckeditor5-image/src/imagestyle';
import ImageToolbar from '@ckeditor/ckeditor5-image/src/imagetoolbar';
import ImageUpload from '@ckeditor/ckeditor5-image/src/imageupload';
import ImageResize from '@ckeditor/ckeditor5-image/src/imageresize';
import ImageResizeEditing from '@ckeditor/ckeditor5-image/src/imageresize/imageresizeediting';
import ImageResizeHandles from '@ckeditor/ckeditor5-image/src/imageresize/imageresizehandles';
import Indent from '@ckeditor/ckeditor5-indent/src/indent';
import Link from '@ckeditor/ckeditor5-link/src/link';
import List from '@ckeditor/ckeditor5-list/src/list';
import MediaEmbed from '@ckeditor/ckeditor5-media-embed/src/mediaembed';
import Paragraph from '@ckeditor/ckeditor5-paragraph/src/paragraph';
import PasteFromOffice from '@ckeditor/ckeditor5-paste-from-office/src/pastefromoffice';
import Table from '@ckeditor/ckeditor5-table/src/table';
import TableToolbar from '@ckeditor/ckeditor5-table/src/tabletoolbar';
import TextTransformation from '@ckeditor/ckeditor5-typing/src/texttransformation';
import CloudServices from '@ckeditor/ckeditor5-cloud-services/src/cloudservices';
import Markdown from '@ckeditor/ckeditor5-markdown-gfm/src/markdown';

// import CKEditorInspector from '@ckeditor/ckeditor5-inspector/inspector.js';

import ImgCustom from './imgPlugin/main';
import VideoPlugin from './videoPlugin/main';
import videoConnect from './videoPlugin/connect';
import FastcmsTable from './tablePlugin/main';

import imgResWin from "./imgResWin.vue";
import videoResWin from "./videoResWin.vue";

const props = defineProps({
  isClient: {
      type: Boolean,
      default: false,
    },
    modelValue: {
      type: String
    },
    /**
     * 是否启用 AI 划词操作（改写/扩写/润色/翻译）
     * 启用后选中文本会出现 AI 浮动条，点击触发 ai-rewrite 事件，
     * AI 结果通过组件方法 replaceAiSelection(html) 写回选区
     */
    aiEnabled: {
      type: Boolean,
      default: false,
    }
});

const emit = defineEmits(["update:modelValue", "ai-rewrite"]);

let attachDialog = ref();
let videoDialog = ref();
let ckeditorDom = ref();
let editorExample: any = null;

// ===== AI 划词操作 =====
// 最近一次非折叠选区（以 path 保存，避免 LiveRange 失效），供 AI 改写后定位回写
let aiSelection: { startPath: any[]; endPath: any[] } | null = null;
const aiBar = reactive({
  visible: false,
  top: 0,
  left: 0,
  text: '',
});

/**
 * 选区变化：非折叠选中文本时显示 AI 浮动条（跟随浏览器原生选区矩形定位）
 */
const onAiSelectionChange = () => {
  if (!props.aiEnabled || !editorExample) return;
  const selection = editorExample.model.document.selection;
  if (selection.isCollapsed) {
    aiBar.visible = false;
    return;
  }
  const domSelection = window.getSelection();
  if (!domSelection || domSelection.rangeCount === 0 || domSelection.isCollapsed) {
    aiBar.visible = false;
    return;
  }
  const rect = domSelection.getRangeAt(0).getBoundingClientRect();
  const containerRect = ckeditorDom.value?.getBoundingClientRect();
  if (!rect || !containerRect || rect.width === 0) {
    aiBar.visible = false;
    return;
  }
  // 保存模型层选区起止位置路径（getRanges() 是 generator，必须 Array.from 取值）
  const ranges = Array.from(selection.getRanges());
  if (!ranges || ranges.length === 0) {
    aiBar.visible = false;
    return;
  }
  aiSelection = { startPath: [...ranges[0].start.path], endPath: [...ranges[0].end.path] };
  aiBar.text = domSelection.toString();
  aiBar.top = rect.top - containerRect.top - 38;
  aiBar.left = Math.max(0, rect.left - containerRect.left);
  aiBar.visible = true;
};

/**
 * 取选中文本的前后文（各截取 maxLen 字符，纯文本），供 AI 改写时保持文风一致
 */
const getAiContext = (): string => {
  if (!editorExample || !aiSelection) return '';
  try {
    const model = editorExample.model;
    const root = model.document.getRoot();
    const start = model.createPositionFromPath(root, aiSelection.startPath);
    const end = model.createPositionFromPath(root, aiSelection.endPath);
    const allText = (editorExample.data.get() || '')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    const selected = aiBar.text;
    if (!selected) return '';
    const selStart = allText.indexOf(selected);
    if (selStart < 0) return '';
    const before = allText.slice(Math.max(0, selStart - 500), selStart);
    const after = allText.slice(selStart + selected.length, selStart + selected.length + 500);
    return JSON.stringify({ before: before.slice(-300), after: after.slice(0, 300) });
  } catch (e) {
    return '';
  }
};

/**
 * 点击浮动条按钮：把选中内容交给父组件走 AI
 * mousedown.prevent 避免焦点移出编辑器导致选区丢失
 */
const onAiOperation = (operation: string) => {
  if (!editorExample || !aiSelection) return;
  // 直接使用浮动条显示时保存的选中文本（mousedown.prevent 保证选区未变）
  const selectedText = aiBar.text;
  const context = getAiContext();
  aiBar.visible = false;
  emit('ai-rewrite', { operation, text: selectedText, context });
};

/**
 * 对外暴露：把 AI 改写结果一次性写回划词选区（替换原选中内容，作为流式失败的回退）
 */
const replaceAiSelection = (html: string) => {
  if (!editorExample || !aiSelection) return;
  try {
    const model = editorExample.model;
    const root = model.document.getRoot();
    const start = model.createPositionFromPath(root, aiSelection.startPath);
    const end = model.createPositionFromPath(root, aiSelection.endPath);
    const range = model.createRange(start, end);
    const viewFragment = editorExample.data.processor.toView(html);
    const modelFragment = editorExample.data.toModel(viewFragment);
    model.change((writer: any) => {
      writer.setSelection(range);
      model.insertContent(modelFragment);
    });
  } catch (e) {
    console.error('AI 结果写回编辑器失败', e);
  }
  aiSelection = null;
};

defineExpose({ replaceAiSelection });

const state = reactive({
  isClient: props.isClient || false
})
const editorConfig = reactive({
    language: {
        ui: 'zh-cn'
    },
    // CKEditor 5 v42+ 要求声明许可：FastCMS 为开源项目（LGPL-3.0），走 GPL 2+ 免费通道
    licenseKey: 'GPL',
    plugins: [
        Essentials,
        UploadAdapter,
        Autoformat,
        Bold,
        Code,
        Italic,
        BlockQuote,
        CKFinder,
        CloudServices,
        EasyImage,
        Heading,
        Image,
        ImageCaption,
        ImageStyle,
        ImageToolbar,
        ImageUpload,
        ImageResize,
        ImageResizeEditing,
        ImageResizeHandles,
        Indent,
        Link,
        List,
        MediaEmbed,
        // Paragraph,
        PasteFromOffice,
        Table,
        TableToolbar,
        TextTransformation,
        // Markdown,
        ImgCustom,
        VideoPlugin,
        FastcmsTable
    ],
    toolbar: [
        'heading',
        '|',
        'bold',
        'italic',
        'link',
        'bulletedList',
        'numberedList',
        '|',
        'outdent',
        'indent',
        '|',
        ImgCustom.pluginName,
        VideoPlugin.pluginName,
        // Markdown.pluginName,
        'blockQuote',
        'fastcmsInsertTable',
        'undo',
        'redo',
    ],
    // 本地视频：附件 URL（/attachment/yyyyMMdd/uuid.mp4，可带域名）渲染为 video 标签，
    // previewsInData 保证源码数据中输出真实 video 标签供前台模板播放
    mediaEmbed: {
        previewsInData: true,
        extraProviders: [
            {
                name: 'fastcms-video',
                url: /^(?:https?:\/\/[^\/]+)?\/attachment\/.+\.(?:mp4|webm|ogg|mov|avi|wmv|mpeg|rmvb|m4v|flv)$/i,
                html: (match: any) => `<video controls playsinline style="width:100%;aspect-ratio:16/9;background:#000" src="${match[0]}"></video>`
            }
        ]
    },
    image: {
      styles: ["alignLeft", "alignCenter", "alignRight"],
      resizeOptions: [
        {
          name: "imageResize:original",
          label: "Original",
          value: null,
        },
        {
          name: "imageResize:25",
          label: "25%",
          value: "25",
        },
        {
          name: "imageResize:50",
          label: "50%",
          value: "50",
        },
        {
          name: "imageResize:75",
          label: "75%",
          value: "75",
        },
      ],
      toolbar: [
        'imageStyle:inline',
        'imageStyle:block',
        'imageStyle:side',
        "|",
        "imageStyle:alignLeft",
        "imageStyle:alignCenter",
        "imageStyle:alignRight",
        "|",
        "imageResize",
        "imageTextAlternative",
        'toggleImageCaption',
      ]
    },
    table: {
      contentToolbar: [
        'tableColumn',
        'tableRow',
        'mergeTableCells'
      ]
    },
  })
onMounted(() => {
  connect.dialogObj = attachDialog.value;
  videoConnect.dialogObj = videoDialog.value;
  const ckeditorDiv = ckeditorDom.value;
  DecoupledEditor.create(ckeditorDiv.querySelector('.CKEditorContent'), editorConfig)
  .then((editor: any) => {
    const toolbar = ckeditorDiv.querySelector(".CKEditorToolbar");
    toolbar && (toolbar.innerHTML = "");
    setTimeout(() => toolbar.appendChild( editor.ui.view.toolbar.element ), 0);
    editorExample = editor;
    editor.setData(props.modelValue);
    // 用 change:data（仅数据变更，不含选区变化）触发同步，避免选区操作产生冗余 emit
    editor.model.document.on("change:data", function() {
      emit("update:modelValue", editor.getData());
    });
    // AI 划词：选区变化时定位浮动条
    editor.model.document.selection.on("change:range", () => {
      setTimeout(onAiSelectionChange, 0);
    });
    // CKEditorInspector.attach(editor); // CKEditor 调试器
  })
  .catch((error: any) => {
    console.log(error);
  });
});

// 比较式同步：外部值与编辑器当前内容一致时跳过，不一致才回写。
// 取代原 isPrint 标志位机制——插入表格等操作会连续触发多次 change 事件，
// 标志位在 watch 异步消费与多次 emit 之间存在竞态，可能用旧值整体覆盖编辑器内容
watch(() => props.modelValue, val => {
  if (editorExample && val !== editorExample.getData()) {
    editorExample.setData(val);
  }
})

</script>

<template>
  <div class="CKEditor" ref="ckeditorDom">
    <div class="CKEditorBox">
      <div class="CKEditorToolbar"></div>
      <div class="CKEditorContent"></div>
    </div>
    <!-- AI 划词浮动条 -->
    <div v-if="props.aiEnabled && aiBar.visible" class="ck-ai-bar" :style="{ top: aiBar.top + 'px', left: aiBar.left + 'px' }">
      <button type="button" @mousedown.prevent @click="onAiOperation('rewrite')">✍️ 改写</button>
      <button type="button" @mousedown.prevent @click="onAiOperation('expand')">📈 扩写</button>
      <button type="button" @mousedown.prevent @click="onAiOperation('polish')">✨ 润色</button>
      <button type="button" @mousedown.prevent @click="onAiOperation('translate')">🌍 翻译</button>
    </div>
    <imgResWin ref="attachDialog" :isClient=state.isClient />
    <videoResWin ref="videoDialog" :isClient=state.isClient />
  </div>
</template>

<style>
  .ck.ck-content.CKEditorContent {
    border: #bbb 1px solid;
  }
  .CKEditor { position: relative; }
  .ck-ai-bar {
    position: absolute;
    z-index: 1000;
    display: flex;
    gap: 2px;
    padding: 4px;
    background: #fff;
    border: 1px solid #dcdfe6;
    border-radius: 6px;
    box-shadow: 0 2px 12px rgba(0,0,0,.12);
  }
  .ck-ai-bar button {
    border: none;
    background: #f5f7fa;
    color: #409eff;
    font-size: 12px;
    padding: 4px 8px;
    border-radius: 4px;
    cursor: pointer;
    white-space: nowrap;
  }
  .ck-ai-bar button:hover {
    background: #409eff;
    color: #fff;
  }
</style>
