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

/**
 * 文件补丁（Patch 形态输出协议的元素）
 *
 * <p>用于「调整轮 / 渲染修复轮」：模型只输出<b>变更片段</b>而非整个文件，
 * 由 {@code FilePatchApplier} 在磁盘上做 search/replace 后落盘。</p>
 *
 * <p><b>为什么需要</b>：整文件回写模式下，改一行也要重发整个文件（20KB 模板 ≈ 5000+ token
 * 输出，按 48 tok/s 的 decode 吞吐就是 100s+ 纯等待）。补丁形态改一行只输出几百字节。</p>
 *
 * <p><b>约束</b>：{@code search} 必须在目标文件中<b>唯一匹配</b>（多处匹配视为歧义，拒绝应用），
 * 以免误改；{@code replace} 为替换后的新内容（空串 = 删除该片段）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiTemplatePatchDto {

    /**
     * 目标文件相对路径（相对模板根目录，如 {@code index.html}、{@code static/css/base.css}）
     */
    private String path;

    /**
     * 被替换的原文片段（须在文件中唯一出现）
     */
    private String search;

    /**
     * 替换后的新内容（空串 = 删除 search 片段）
     */
    private String replace;

    public AiTemplatePatchDto() {
    }

    public AiTemplatePatchDto(String path, String search, String replace) {
        this.path = path;
        this.search = search;
        this.replace = replace;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public String getReplace() {
        return replace;
    }

    public void setReplace(String replace) {
        this.replace = replace;
    }

    @Override
    public String toString() {
        return "AiTemplatePatchDto{path='" + path + "', search="
                + (search == null ? "null" : search.length() + " chars")
                + ", replace=" + (replace == null ? "null" : replace.length() + " chars") + '}';
    }
}
