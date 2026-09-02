import { SCHEMA_NAME_VIDEO_BLOCK } from './constant';

/**
 * 插入视频部件到编辑器（与官方 insertMedia 行为一致）：
 * 使用 insertObject 自动寻找最佳插入位置，插入后选中视频部件，
 * 便于立即拖拽缩放。插入操作进入默认批次，可撤销/重做。
 */
export default function insertVideo(model, src) {
	if (!src) {
		return;
	}
	model.change(writer => {
		const videoElement = writer.createElement(SCHEMA_NAME_VIDEO_BLOCK, { src });
		model.insertObject(videoElement, null, null, {
			setSelection: 'on',
			findOptimalPosition: 'auto'
		});
	});
}
