import Observer from '@ckeditor/ckeditor5-engine/src/view/observer/observer';

/**
 * 视频加载观察器：监听编辑区内 <video> 的 loadedmetadata 事件（元数据加载完成，
 * 可获取宽高），触发 videoLoaded 事件，供缩放手柄插件挂载 resizer。
 * 仿照官方 ImageLoadObserver 实现（load 事件不会在 video 元素上触发，
 * 故监听 loadedmetadata）。
 */
export default class VideoLoadObserver extends Observer {
	observe(domRoot) {
		this.listenTo(domRoot, 'loadedmetadata', (event, domEvent) => {
			const domElement = domEvent.target;
			if (this.checkShouldIgnoreEventFromTarget(domElement)) {
				return;
			}
			if (domElement.tagName == 'VIDEO') {
				this._fireEvents(domEvent);
			}
			// 使用捕获阶段提升性能（与 ImageLoadObserver 一致）
		}, { useCapture: true });
	}

	stopObserving(domRoot) {
		this.stopListening(domRoot);
	}

	_fireEvents(domEvent) {
		if (this.isEnabled) {
			this.document.fire('layoutChanged');
			this.document.fire('videoLoaded', domEvent);
		}
	}
}
