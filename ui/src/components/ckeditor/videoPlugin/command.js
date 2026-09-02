import Command from '@ckeditor/ckeditor5-core/src/command';
import { SCHEMA_NAME_VIDEO_BLOCK } from './constant';

/**
 * 视频缩放命令：把拖拽得到的宽度写入模型的 resizedWidth 属性。
 * width 为 null 时恢复原始尺寸（100% 宽）。
 * value 暴露当前宽度（如 "50%"），供尺寸下拉按钮显示与选中态判断。
 */
export default class ResizeVideoCommand extends Command {
	refresh() {
		const element = this.editor.model.document.selection.getSelectedElement();
		this.isEnabled = !!(element && element.is('element', SCHEMA_NAME_VIDEO_BLOCK));
		this.value = this.isEnabled ? (element.getAttribute('resizedWidth') || null) : null;
	}

	execute({ width } = {}) {
		const model = this.editor.model;
		const videoBlock = model.document.selection.getSelectedElement();
		if (!videoBlock || !videoBlock.is('element', SCHEMA_NAME_VIDEO_BLOCK)) {
			return;
		}
		model.change(writer => {
			if (width) {
				writer.setAttribute('resizedWidth', width, videoBlock);
			} else {
				writer.removeAttribute('resizedWidth', videoBlock);
			}
		});
	}
}
