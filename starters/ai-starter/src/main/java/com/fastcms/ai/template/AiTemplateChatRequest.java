/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * http://www.xjd2020.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template;

import java.io.Serializable;

/**
 * AI 模板生成 SSE 流式对话请求
 *
 * <p>对话接口改为 POST 后，用户输入通过请求体传递，
 * 不再受 EventSource 仅支持 GET / query 参数长度限制。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiTemplateChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户输入（需求描述 / 微调需求）
     */
    private String input;

    /**
     * 用户当前正在编辑/预览的文件路径（含模板目录前缀，如 xjd2022/index.html）。
     * 调整型会话注入提示词，让 AI 聚焦用户当前关注的页面。
     */
    private String currentFile;

    /**
     * 预览页点选的目标区块 sectionId（可空；来自 iframe 的 data-ai-section 标记）。
     * 组件化会话微调时注入该 section 的 spec 片段，AI 只修改该区块。
     */
    private String focusSectionId;

    /**
     * 用户点选区块时命中的具体元素描述（可空；如 "标题「散养土鸡蛋」"）。
     * 元素级语义提示：让 AI 知道用户聚焦区块内的哪个元素，修改更有针对性。
     */
    private String focusElementHint;

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getCurrentFile() {
        return currentFile;
    }

    public void setCurrentFile(String currentFile) {
        this.currentFile = currentFile;
    }

    public String getFocusSectionId() {
        return focusSectionId;
    }

    public void setFocusSectionId(String focusSectionId) {
        this.focusSectionId = focusSectionId;
    }

    public String getFocusElementHint() {
        return focusElementHint;
    }

    public void setFocusElementHint(String focusElementHint) {
        this.focusElementHint = focusElementHint;
    }

}
