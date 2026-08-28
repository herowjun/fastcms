import Plugin from "@ckeditor/ckeditor5-core/src/plugin";
import ButtonView from "@ckeditor/ckeditor5-ui/src/button/buttonview";
import ButtonIcon from "./videoIcon.svg?ckeditor";
import { COMMAND_NAME_VIDEO, COMMAND_LABEL_VIDEO } from "./constant";
import connect from "./connect";

export default class VideoToolbarUI extends Plugin {
    init() {
        this.createToolbarButton();
    }
    createToolbarButton() {
        const editor = this.editor;
        // 保存编辑器实例引用，供视频选择弹窗插入媒体元素使用
        connect.editorObj = editor;
        editor.ui.componentFactory.add(COMMAND_NAME_VIDEO, (locale) => {
            const view = new ButtonView(locale);
            view.set({
                label: COMMAND_LABEL_VIDEO,
                tooltip: true,
                icon: ButtonIcon,
                class: "toolbar_button_video_extend",
            });
            // 点击按钮打开视频选择弹窗
            this.listenTo(view, "execute", () => {
                connect.dialogObj && connect.dialogObj.openDialog(5);
            });
            return view;
        });
    }
}
