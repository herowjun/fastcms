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
package com.fastcms.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式文件路径扫描器：从逐步到达的响应 JSON 流中增量提取 files 数组的 path 字段
 *
 * <p>AI 响应的 files 字段（完整文件内容）可持续流式传输数分钟，期间前端无法感知
 * 正在处理哪个文件。本扫描器与 {@link ReplyStreamExtractor} 并行工作，
 * 每当一个文件的 {@code "path":"xxx"} 键值对在流中完整出现，即上报该路径，
 * 供调用方推送"正在生成/修改 xxx"的阶段性状态事件。</p>
 *
 * <p>匹配模式要求 path 键位于对象成员位置（前面是 { 或 ,），
 * 避免 content 字符串内的转义引号 {\@code \"path\"} 造成误报
 * （JSON 字符串内的引号必带反斜杠，与 {@code [{,]\s*"} 冲突）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class FileProgressScanner {

    /**
     * path 键值对（对象成员位置 + 转义安全取值）
     */
    private static final Pattern PATH_PATTERN =
            Pattern.compile("[{,]\\s*\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * 已匹配文本之后的扫描位置（含未完成的尾部，防模式被 chunk 切断）
     */
    private final StringBuilder buf = new StringBuilder();

    private int scanPos;

    /**
     * 喂入新 chunk，返回本 chunk 中新完整出现的文件路径（可能为空列表，绝不返回 null）
     *
     * <p>路径按出现顺序返回（与 files 数组顺序一致），已上报的不会重复上报。</p>
     */
    public List<String> feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Collections.emptyList();
        }
        buf.append(chunk);
        List<String> newPaths = null;
        Matcher m = PATH_PATTERN.matcher(buf);
        m.region(scanPos, buf.length());
        while (m.find()) {
            if (newPaths == null) {
                newPaths = new ArrayList<>();
            }
            newPaths.add(unescape(m.group(1)));
            scanPos = m.end();
        }
        // 已扫描区域可以丢弃，仅保留最后 64 字符（覆盖 "path":"value" 尾部被切断的场景）
        if (buf.length() > 64) {
            int keepFrom = Math.max(scanPos, buf.length() - 64);
            buf.delete(0, keepFrom);
            scanPos = Math.max(0, scanPos - keepFrom);
        }
        return newPaths == null ? Collections.emptyList() : newPaths;
    }

    /**
     * 还原 JSON 字符串转义（路径中常见 / 转义，其余按字面处理即可）
     */
    private String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
