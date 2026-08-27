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

import java.util.Objects;

/**
 * AI 响应解析出的单个文件描述
 *
 * <p>对应 AI 返回 JSON 数组中的一个元素：
 * <pre>{ "path": "index.html", "content": "...", "action": "create" }</pre>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiTemplateFileDto {

    /**
     * 文件相对路径，如 {@code index.html}、{@code static/css/base.css}
     */
    private String path;

    /**
     * 文件完整内容（action=delete 时可为 null）
     */
    private String content;

    /**
     * 操作类型：create / modify / delete
     */
    private String action;

    public AiTemplateFileDto() {
    }

    public AiTemplateFileDto(String path, String content, String action) {
        this.path = path;
        this.content = content;
        this.action = action;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    @Override
    public String toString() {
        return "AiTemplateFileDto{path='" + path + "', action='" + action + "', contentLength="
                + (content == null ? 0 : content.length()) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AiTemplateFileDto that)) return false;
        return Objects.equals(path, that.path)
                && Objects.equals(content, that.content)
                && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, content, action);
    }

}
