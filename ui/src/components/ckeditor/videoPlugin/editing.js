import Plugin from '@ckeditor/ckeditor5-core/src/plugin';
import { toWidget } from '@ckeditor/ckeditor5-widget/src/utils';
import ResizeVideoCommand from './command';
import VideoAlignCommand from './alignCommand';
import { SCHEMA_NAME_VIDEO_BLOCK, COMMAND_NAME_RESIZE_VIDEO, COMMAND_NAME_ALIGN_VIDEO } from './constant';

/**
 * 视频部件 Editing：
 * - 注册 videoBlock 模型元素（$blockObject，与 imageBlock/media 同级语义）
 * - 编辑视图：<figure class="video"><video/></figure> widget，可选中、可拖动、可挂缩放手柄
 * - 数据输出：裸 <video controls playsinline src style="width:x%"/>，前台模板直接播放
 * - 数据加载（upcast）兼容两种历史格式：
 *   1. 旧 MediaEmbed 数据：<figure class="media"><div data-oembed-url="..."><video .../></div></figure>
 *   2. 新数据/外部粘贴：裸 <video src="..."/>
 */
export default class VideoEditing extends Plugin {
	static get pluginName() {
		return 'VideoEditing';
	}

	init() {
		const editor = this.editor;
		const conversion = editor.conversion;
		const schema = editor.model.schema;

		schema.register(SCHEMA_NAME_VIDEO_BLOCK, {
			inheritAllFrom: '$blockObject',
			allowAttributes: ['src', 'resizedWidth', 'videoAlignment']
		});

		editor.commands.add(COMMAND_NAME_RESIZE_VIDEO, new ResizeVideoCommand(editor));
		editor.commands.add(COMMAND_NAME_ALIGN_VIDEO, new VideoAlignCommand(editor));

		this._defineDataDowncast(conversion);
		this._defineEditingDowncast(conversion);
		this._defineWidthConverters(conversion);
		this._defineAlignConverters(conversion);
		this._defineUpcast(conversion);
	}

	// ===== 模型 -> 数据：videoBlock => <video controls playsinline src="..."/> =====
	_defineDataDowncast(conversion) {
		conversion.for('dataDowncast').elementToStructure({
			model: SCHEMA_NAME_VIDEO_BLOCK,
			view: (modelElement, { writer }) => {
				return writer.createEmptyElement('video', {
					controls: '',
					playsinline: '',
					src: modelElement.getAttribute('src')
				});
			}
		});
	}

	// ===== 模型 -> 编辑视图：videoBlock => <figure class="video"><video/></figure> widget =====
	_defineEditingDowncast(conversion) {
		conversion.for('editingDowncast').elementToStructure({
			model: SCHEMA_NAME_VIDEO_BLOCK,
			view: (modelElement, { writer }) => {
				const figure = writer.createContainerElement('figure', { class: 'video' });
				// preload=metadata 确保元数据加载，宽高与 loadedmetadata 事件可依赖
				const video = writer.createEmptyElement('video', {
					controls: '',
					playsinline: '',
					preload: 'metadata',
					src: modelElement.getAttribute('src')
				});
				writer.insert(writer.createPositionAt(figure, 0), video);
				return toVideoWidget(figure, writer, '视频部件');
			}
		});
	}

	// ===== resizedWidth 属性 -> style="width:x%"（数据侧写在 video 上，编辑侧写在 figure 上）=====
	_defineWidthConverters(conversion) {
		const applyStyle = (viewWriter, viewElement, newValue) => {
			if (newValue !== null) {
				viewWriter.setStyle('width', newValue, viewElement);
				viewWriter.addClass('video_resized', viewElement);
			} else {
				viewWriter.removeStyle('width', viewElement);
				viewWriter.removeClass('video_resized', viewElement);
			}
		};

		// 数据管道：宽度写到 video 标签 style，前台按此宽度展示（数据侧无需 class）
		conversion.for('dataDowncast').add(dispatcher => {
			dispatcher.on(`attribute:resizedWidth:${SCHEMA_NAME_VIDEO_BLOCK}`, (evt, data, conversionApi) => {
				if (!conversionApi.consumable.consume(data.item, evt.name)) {
					return;
				}
				const viewVideo = conversionApi.mapper.toViewElement(data.item);
				if (!viewVideo) {
					return;
				}
				if (data.attributeNewValue !== null) {
					conversionApi.writer.setStyle('width', data.attributeNewValue, viewVideo);
				} else {
					conversionApi.writer.removeStyle('width', viewVideo);
				}
			});
		});

		// 编辑管道：宽度写到外层 figure（与图片 figure.image 行为一致）
		conversion.for('editingDowncast').add(dispatcher => {
			dispatcher.on(`attribute:resizedWidth:${SCHEMA_NAME_VIDEO_BLOCK}`, (evt, data, conversionApi) => {
				if (!conversionApi.consumable.consume(data.item, evt.name)) {
					return;
				}
				const viewFigure = conversionApi.mapper.toViewElement(data.item);
				if (!viewFigure) {
					return;
				}
				applyStyle(conversionApi.writer, viewFigure, data.attributeNewValue);
			});
		});
	}

	// ===== videoAlignment 属性 -> 对齐样式/class =====
	_defineAlignConverters(conversion) {
		// 编辑管道：外层 figure 挂 video-align-left/center/right class，CSS 控制浮动/居中
		conversion.for('editingDowncast').add(dispatcher => {
			dispatcher.on(`attribute:videoAlignment:${SCHEMA_NAME_VIDEO_BLOCK}`, (evt, data, conversionApi) => {
				if (!conversionApi.consumable.consume(data.item, evt.name)) {
					return;
				}
				const viewFigure = conversionApi.mapper.toViewElement(data.item);
				if (!viewFigure) {
					return;
				}
				const writer = conversionApi.writer;
				writer.removeClass(ALIGN_CLASSES, viewFigure);
				if (data.attributeNewValue) {
					writer.addClass('video-align-' + data.attributeNewValue, viewFigure);
				}
			});
		});

		// 数据管道：对齐写成 video 标签内联样式，前台模板无需任何 CSS 即可生效
		conversion.for('dataDowncast').add(dispatcher => {
			dispatcher.on(`attribute:videoAlignment:${SCHEMA_NAME_VIDEO_BLOCK}`, (evt, data, conversionApi) => {
				if (!conversionApi.consumable.consume(data.item, evt.name)) {
					return;
				}
				const viewVideo = conversionApi.mapper.toViewElement(data.item);
				if (!viewVideo) {
					return;
				}
				const writer = conversionApi.writer;
				const value = data.attributeNewValue;
				writer.removeStyle('float', viewVideo);
				writer.removeStyle('margin-left', viewVideo);
				writer.removeStyle('margin-right', viewVideo);
				if (value === 'left' || value === 'right') {
					writer.setStyle('float', value, viewVideo);
					writer.setStyle(value === 'left' ? 'margin-right' : 'margin-left', '1em', viewVideo);
				} else if (value === 'center') {
					writer.setStyle('margin-left', 'auto', viewVideo);
					writer.setStyle('margin-right', 'auto', viewVideo);
				}
			});
		});
	}

	// ===== 数据 -> 模型（upcast）：兼容新格式与 MediaEmbed 历史格式 =====
	_defineUpcast(conversion) {
		// 场景 1（历史数据）：<figure class="media">...<video src/>...</figure>
		// 以最高优先级接管，避免 MediaEmbed 把附件视频降级为不可缩放的 media 元素
		conversion.for('upcast').add(dispatcher => {
			dispatcher.on('element:figure', (evt, data, conversionApi) => {
				const viewFigure = data.viewItem;
				if (!viewFigure.hasClass('media')) {
					return;
				}
				const viewVideo = findVideoElement(viewFigure);
				if (!viewVideo || !viewVideo.getAttribute('src')) {
					// 无视频子元素（如 oembed/iframe 外部媒体），交给 MediaEmbed 处理
					return;
				}
				if (!conversionApi.consumable.test(viewFigure, { name: true, classes: 'media' })) {
					return;
				}
				const videoBlock = conversionApi.writer.createElement(SCHEMA_NAME_VIDEO_BLOCK, collectAttributes(viewVideo));
				// safeInsert 接收模型元素，内部完成 schema 拆分与插入
				if (!conversionApi.safeInsert(videoBlock, data.modelCursor)) {
					return;
				}
				// 消费整个 figure 与内部 video，内部其余节点（div 包装层等）不再遍历
				conversionApi.consumable.consume(viewFigure, { name: true, classes: 'media' });
				conversionApi.consumable.consume(viewVideo, { name: true });
				conversionApi.updateConversionResult(videoBlock, data);
			}, { priority: 'highest' });
		});

		// 场景 2（新数据/粘贴）：裸 <video src="..."/>
		conversion.for('upcast').add(dispatcher => {
			dispatcher.on('element:video', (evt, data, conversionApi) => {
				const viewVideo = data.viewItem;
				const src = viewVideo.getAttribute('src');
				if (!src) {
					return;
				}
				if (!conversionApi.consumable.test(viewVideo, { name: true })) {
					return;
				}
				const videoBlock = conversionApi.writer.createElement(SCHEMA_NAME_VIDEO_BLOCK, collectAttributes(viewVideo));
				if (!conversionApi.safeInsert(videoBlock, data.modelCursor)) {
					return;
				}
				conversionApi.consumable.consume(viewVideo, { name: true });
				conversionApi.updateConversionResult(videoBlock, data);
			}, { priority: 'highest' });
		});
	}
}

const ALIGN_CLASSES = ['video-align-left', 'video-align-center', 'video-align-right'];

/**
 * figure -> video widget 包装，附加自定义属性便于识别
 */
function toVideoWidget(viewFigure, writer, label) {
	writer.setCustomProperty('video', true, viewFigure);
	return toWidget(viewFigure, writer, { label });
}

/**
 * 深度查找第一个带 src 的 video 后代元素
 * （历史数据结构为 figure.media > div[data-oembed-url] > video）
 */
function findVideoElement(viewElement) {
	for (const child of viewElement.getChildren()) {
		if (child.is('element', 'video') && child.getAttribute('src')) {
			return child;
		}
		if (child.is('element')) {
			const found = findVideoElement(child);
			if (found) {
				return found;
			}
		}
	}
	return null;
}

/**
 * 从 view video 元素提取模型属性（src、resizedWidth、videoAlignment）
 */
function collectAttributes(viewVideo) {
	const attrs = { src: viewVideo.getAttribute('src') };
	const width = parseWidth(viewVideo);
	if (width) {
		attrs.resizedWidth = width;
	}
	const alignment = parseAlignment(viewVideo);
	if (alignment) {
		attrs.videoAlignment = alignment;
	}
	return attrs;
}

/**
 * 解析 style 中的百分比宽度；100% 视为原始尺寸返回 null；
 * 其他单位（px 等）不做持久化（跟随容器自适应更稳妥）
 */
function parseWidth(viewVideo) {
	const width = viewVideo.getStyle('width');
	if (!width) {
		return null;
	}
	const match = width.match(/^(\d+(?:\.\d+)?)%$/);
	if (!match) {
		return null;
	}
	if (parseFloat(match[1]) >= 100) {
		return null;
	}
	return match[1] + '%';
}

/**
 * 解析 style 中的对齐方式（与数据侧输出格式对应）：
 * float:left/right -> left/right；margin-left:auto 或 margin:0 auto -> center
 */
function parseAlignment(viewVideo) {
	const float = viewVideo.getStyle('float');
	if (float === 'left' || float === 'right') {
		return float;
	}
	const margin = viewVideo.getStyle('margin');
	if (margin && /auto/.test(margin)) {
		return 'center';
	}
	const marginLeft = viewVideo.getStyle('margin-left');
	const marginRight = viewVideo.getStyle('margin-right');
	if (marginLeft === 'auto' || marginRight === 'auto') {
		return 'center';
	}
	return null;
}
