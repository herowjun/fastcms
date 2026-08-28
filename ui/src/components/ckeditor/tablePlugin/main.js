import Plugin from "@ckeditor/ckeditor5-core/src/plugin";
import { createDropdown } from "@ckeditor/ckeditor5-ui/src/dropdown/utils";
import InsertTableView from "@ckeditor/ckeditor5-table/src/ui/inserttableview.js";
import ButtonIcon from "./tableIcon.svg?ckeditor";

/**
 * 表格插件：复用官方 insertTable 下拉的网格选择视图（InsertTableView），
 * 打开时保持官方默认选中 1x1，用户悬停/键盘选择目标尺寸。
 */
export default class FastcmsTable extends Plugin {
    static get pluginName() {
        return 'FastcmsTable';
    }
    init() {
        const editor = this.editor;
        editor.ui.componentFactory.add('fastcmsInsertTable', (locale) => {
            const command = editor.commands.get('insertTable');
            const dropdownView = createDropdown(locale);
            dropdownView.bind('isEnabled').to(command);
            dropdownView.buttonView.set({
                icon: ButtonIcon,
                label: '插入表格',
                tooltip: true
            });
            let insertTableView;
            dropdownView.on('change:isOpen', () => {
                if (!dropdownView.isOpen) return;
                if (!insertTableView) {
                    insertTableView = new InsertTableView(locale);
                    dropdownView.panelView.children.add(insertTableView);
                    insertTableView.delegate('execute').to(dropdownView);
                    dropdownView.on('execute', () => {
                        // 官方 insertTable 内部用 model.insertObject，非折叠选区时会把选中内容整体替换为表格。
                        // CMS 场景下插表格不应销毁用户已选中的正文，这里先把选区折叠到起点再插入。
                        const selection = editor.model.document.selection;
                        if (!selection.isCollapsed) {
                            const position = selection.getFirstPosition();
                            editor.model.change(writer => writer.setSelection(position));
                        }
                        editor.execute('insertTable', { rows: insertTableView.rows, columns: insertTableView.columns });
                        editor.editing.view.focus();
                    });
                }
            });
            return dropdownView;
        });
    }
}
