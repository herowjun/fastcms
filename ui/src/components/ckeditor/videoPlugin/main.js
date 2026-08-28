import Plugin from '@ckeditor/ckeditor5-core/src/plugin';
import VideoToolbarUI from './toolbar';
import { COMMAND_NAME_VIDEO } from './constant';

/**
 * 视频插件：基于 MediaEmbed 实现，工具栏按钮打开视频选择弹窗，
 * 选中后插入 media 模型元素，由 fastcms-video provider 渲染为 <video> 标签
 */
export default class VIDEO extends Plugin {
    static get requires() {
        return [ VideoToolbarUI ];
    }
    static get pluginName() {
        return COMMAND_NAME_VIDEO;
    }
}
