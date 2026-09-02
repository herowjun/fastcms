import Plugin from '@ckeditor/ckeditor5-core/src/plugin';
import VideoToolbarUI from './toolbar';
import VideoEditing from './editing';
import VideoResizeHandles from './resizeHandles';
import VideoAlignToolbar from './alignToolbar';
import { COMMAND_NAME_VIDEO } from './constant';

/**
 * 视频插件：
 * - VideoEditing：注册 videoBlock 模型（widget 化，可选中、可拖动）
 * - VideoResizeHandles：四角拖拽缩放手柄（宽度持久化到 video 标签 style）
 * - VideoAlignToolbar：选中视频时上方的浮动工具条（靠左/居中/靠右）
 * - VideoToolbarUI：工具栏按钮，打开视频选择弹窗
 */
export default class VIDEO extends Plugin {
    static get requires() {
        return [ VideoEditing, VideoResizeHandles, VideoAlignToolbar, VideoToolbarUI ];
    }
    static get pluginName() {
        return COMMAND_NAME_VIDEO;
    }
}
