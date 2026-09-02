import Command from '@ckeditor/ckeditor5-core/src/command';
import { SCHEMA_NAME_VIDEO_BLOCK } from './constant';

/**
 * 视频对齐命令：设置 videoBlock 的 videoAlignment 属性（left/center/right）
 * 与图片的 imageStyle 对齐语义一致，供选中视频时上方的浮动工具条调用
 */
export default class VideoAlignCommand extends Command {

    refresh() {
        const element = this.editor.model.document.selection.getSelectedElement();
        this.isEnabled = !!element && element.is('element', SCHEMA_NAME_VIDEO_BLOCK);
        this.value = this.isEnabled ? (element.getAttribute('videoAlignment') || null) : null;
    }

    execute(options) {
        const { value } = options;
        const model = this.editor.model;
        model.change(writer => {
            const element = model.document.selection.getSelectedElement();
            if (!element || !element.is('element', SCHEMA_NAME_VIDEO_BLOCK)) {
                return;
            }
            if (value) {
                writer.setAttribute('videoAlignment', value, element);
            } else {
                writer.removeAttribute('videoAlignment', element);
            }
        });
    }
}
