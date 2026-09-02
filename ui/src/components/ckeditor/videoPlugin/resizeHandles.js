import Plugin from '@ckeditor/ckeditor5-core/src/plugin';
import WidgetResize from '@ckeditor/ckeditor5-widget/src/widgetresize';
import VideoLoadObserver from './videoLoadObserver';
import { COMMAND_NAME_RESIZE_VIDEO } from './constant';

const RESIZABLE_VIDEO_CSS_SELECTOR = 'figure.video.ck-widget > video';
const RESIZED_VIDEO_CLASS = 'video_resized';

/**
 * 视频缩放手柄：仿照官方 ImageResizeHandles 实现。
 * 视频元数据加载完成后，为对应 widget 挂载四角拖拽缩放手柄，
 * 拖拽实时预览，松手提交宽度到模型（resizedWidth 属性）。
 */
export default class VideoResizeHandles extends Plugin {
	static get requires() {
		return [WidgetResize];
	}

	static get pluginName() {
		return 'VideoResizeHandles';
	}

	init() {
		const editor = this.editor;
		const editingView = editor.editing.view;
		const command = editor.commands.get(COMMAND_NAME_RESIZE_VIDEO);

		editingView.addObserver(VideoLoadObserver);

		this.bind('isEnabled').to(command);

		this.listenTo(editingView.document, 'videoLoaded', (evt, domEvent) => {
			// 只处理视频部件内的 video（figure.video widget 的直接子元素）
			if (!domEvent.target.matches(RESIZABLE_VIDEO_CSS_SELECTOR)) {
				return;
			}
			const domConverter = editingView.domConverter;
			const videoView = domConverter.domToView(domEvent.target);
			const widgetView = videoView && videoView.parent;
			if (!widgetView) {
				return;
			}

			let resizer = editor.plugins.get(WidgetResize).getResizerByViewElement(widgetView);
			if (resizer) {
				// 已有手柄（如视频 src 变更后重新加载），重绘即可
				resizer.redraw();
				return;
			}

			const mapper = editor.editing.mapper;
			const videoModel = mapper.toModelElement(widgetView);
			if (!videoModel) {
				return;
			}

			resizer = editor.plugins.get(WidgetResize).attachTo({
				unit: editor.config.get('video.resizeUnit') || '%',
				modelElement: videoModel,
				viewElement: widgetView,
				editor,
				getHandleHost(domWidgetElement) {
					return domWidgetElement.querySelector('video');
				},
				getResizeHost() {
					return domConverter.mapViewToDom(mapper.toViewElement(videoModel));
				},
				// 视频为块级居中部件，左右手柄对称缩放
				isCentered() {
					return true;
				},
				onCommit(newValue) {
					// 先移除预览 class，避免命令执行失败时残留视觉状态
					editingView.change(writer => {
						writer.removeClass(RESIZED_VIDEO_CLASS, widgetView);
					});
					editor.execute(COMMAND_NAME_RESIZE_VIDEO, { width: newValue });
				}
			});

			resizer.on('updateSize', () => {
				if (!widgetView.hasClass(RESIZED_VIDEO_CLASS)) {
					editingView.change(writer => {
						writer.addClass(RESIZED_VIDEO_CLASS, widgetView);
					});
				}
				if (widgetView.getStyle('height')) {
					editingView.change(writer => {
						writer.removeStyle('height', widgetView);
					});
				}
			});

			resizer.bind('isEnabled').to(this);
		});
	}
}
