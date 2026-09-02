import Plugin from '@ckeditor/ckeditor5-core/src/plugin';
import ButtonView from '@ckeditor/ckeditor5-ui/src/button/buttonview';
import DropdownButtonView from '@ckeditor/ckeditor5-ui/src/dropdown/button/dropdownbuttonview';
import { createDropdown, addListToDropdown } from '@ckeditor/ckeditor5-ui/src/dropdown/utils';
import Model from '@ckeditor/ckeditor5-ui/src/model';
import Collection from '@ckeditor/ckeditor5-utils/src/collection';
import WidgetToolbarRepository from '@ckeditor/ckeditor5-widget/src/widgettoolbarrepository';
import alignLeftIcon from '@ckeditor/ckeditor5-core/theme/icons/align-left.svg';
import alignCenterIcon from '@ckeditor/ckeditor5-core/theme/icons/align-center.svg';
import alignRightIcon from '@ckeditor/ckeditor5-core/theme/icons/align-right.svg';
import objectSizeFull from '@ckeditor/ckeditor5-core/theme/icons/object-size-full.svg';
import { COMMAND_NAME_ALIGN_VIDEO, COMMAND_NAME_RESIZE_VIDEO } from './constant';

/**
 * 视频布局工具条：选中视频部件时在上方出现的浮动工具条（与图片的样式工具条同机制），
 * 提供：
 * - 靠左 / 居中 / 靠右：切换 videoAlignment 属性
 * - 尺寸下拉（25% / 50% / 75% / 原始尺寸）：切换 resizedWidth 属性
 */
const ALIGN_BUTTONS = [
    { value: 'left', label: '视频靠左', icon: alignLeftIcon },
    { value: 'center', label: '视频居中', icon: alignCenterIcon },
    { value: 'right', label: '视频靠右', icon: alignRightIcon }
];

const RESIZE_OPTIONS = [
    { label: '25%', value: '25%' },
    { label: '50%', value: '50%' },
    { label: '75%', value: '75%' },
    { label: '原始尺寸', value: null }
];

export default class VideoAlignToolbar extends Plugin {
    static get pluginName() {
        return 'VideoAlignToolbar';
    }

    init() {
        const editor = this.editor;
        for (const { value, label, icon } of ALIGN_BUTTONS) {
            editor.ui.componentFactory.add(`videoAlign:${value}`, locale => {
                const view = new ButtonView(locale);
                const command = editor.commands.get(COMMAND_NAME_ALIGN_VIDEO);
                view.set({
                    label,
                    icon,
                    tooltip: true,
                    isToggleable: true
                });
                view.bind('isEnabled').to(command, 'isEnabled');
                view.bind('isOn').to(command, 'value', v => v === value);
                this.listenTo(view, 'execute', () => {
                    editor.execute(COMMAND_NAME_ALIGN_VIDEO, { value });
                    editor.editing.view.focus();
                });
                return view;
            });
        }
        this._registerVideoResizeDropdown();
    }

    /**
     * 尺寸预设下拉（与图片 imageResize 下拉同交互）：
     * 选中项高亮当前宽度，原始尺寸即清除 resizedWidth 恢复 100% 宽
     */
    _registerVideoResizeDropdown() {
        const editor = this.editor;
        editor.ui.componentFactory.add('videoResize', locale => {
            const command = editor.commands.get(COMMAND_NAME_RESIZE_VIDEO);
            const dropdownView = createDropdown(locale, DropdownButtonView);
            const dropdownButton = dropdownView.buttonView;
            dropdownButton.set({
                label: '尺寸',
                tooltip: '视频尺寸',
                icon: objectSizeFull,
                isToggleable: true,
                withText: true
            });
            // isEnabled 必须绑在 dropdownView 上——createDropdown 内部已把
            // buttonView.isEnabled 绑到 dropdownView，对 buttonView 二次 bind 会抛
            // observable-bind-rebind，导致工具条初始化中断后永远不再显示
            dropdownView.bind('isEnabled').to(command, 'isEnabled');
            // 按钮文案跟随当前宽度（如 50%），无宽度时显示"尺寸"
            dropdownButton.bind('label').to(command, 'value', v => v || '尺寸');
            addListToDropdown(dropdownView, () => this._getResizeListItemDefinitions(command), {
                ariaLabel: '视频尺寸列表',
                role: 'menu'
            });
            this.listenTo(dropdownView, 'execute', evt => {
                editor.execute(COMMAND_NAME_RESIZE_VIDEO, { width: evt.source.commandValue });
                editor.editing.view.focus();
            });
            return dropdownView;
        });
    }

    _getResizeListItemDefinitions(command) {
        const itemDefinitions = new Collection();
        for (const option of RESIZE_OPTIONS) {
            const definition = {
                type: 'button',
                model: new Model({
                    commandValue: option.value,
                    label: option.label,
                    role: 'menuitemradio',
                    withText: true
                })
            };
            definition.model.bind('isOn').to(command, 'value', v => (v || null) === option.value);
            itemDefinitions.add(definition);
        }
        return itemDefinitions;
    }

    afterInit() {
        const editor = this.editor;
        const widgetToolbarRepository = editor.plugins.get(WidgetToolbarRepository);
        widgetToolbarRepository.register('videoAlign', {
            ariaLabel: '视频布局工具条',
            items: [
                'videoAlign:left',
                'videoAlign:center',
                'videoAlign:right',
                '|',
                'videoResize'
            ],
            getRelatedElement: getSelectedVideoWidget
        });
    }
}

/**
 * 当前选中的是否为视频 widget（编辑视图的 figure.video）
 */
function getSelectedVideoWidget(selection) {
    const viewElement = selection.getSelectedElement();
    if (viewElement && viewElement.getCustomProperty('video')) {
        return viewElement;
    }
    return null;
}
