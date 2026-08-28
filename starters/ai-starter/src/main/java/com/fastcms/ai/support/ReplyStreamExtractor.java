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

/**
 * 流式 reply 提取器：从逐步到达的 JSON 文本流中增量提取 reply 字段的自然语言内容
 *
 * <p>AI 的响应是 JSON 对象（如 {"reply":"...","files":[...]} 或
 * {"reply":"...","title":"...","content":"..."}），直接把原始 JSON 推给前端
 * 会显示一大坨结构化文本。本提取器维护一个轻量状态机：
 * <ol>
 *     <li>等待出现 "reply" 键（限制扫描窗口，无 reply 键的响应不会无限累积缓冲）</li>
 *     <li>进入 reply 字符串后逐字符解码（处理换行、制表、引号、反斜杠及 unicode 码点转义）</li>
 *     <li>遇到未转义的闭引号即结束</li>
 * </ol>
 * 每次 {@link #feed(String)} 返回本次新确定的解码文本增量，调用方通过 SSE 推送实现打字机效果。</p>
 *
 * <p>模板生成/调整与文章生成等 AI 场景共用。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class ReplyStreamExtractor {

    private final StringBuilder buf = new StringBuilder();
    private final StringBuilder decoded = new StringBuilder();
    /** buf 中已解码扫描到的位置 */
    private int scanPos;
    /** decoded 已推送（返回给调用方）的长度 */
    private int emittedLen;
    private boolean replyStarted;
    private boolean finished;

    /**
     * 喂入新 chunk，返回本次新确定的 reply 文本增量（可能为空字符串）
     */
    public String feed(String chunk) {
        if (finished) {
            return "";
        }
        buf.append(chunk);

        if (!replyStarted) {
            if (!tryLocateReply()) {
                return "";
            }
        }

        // 从 scanPos 增量解码 reply 字符串
        int n = buf.length();
        while (scanPos < n) {
            char c = buf.charAt(scanPos);
            if (c == '\\') {
                if (scanPos + 1 >= n) {
                    break; // 转义序列不完整，等待下一个 chunk
                }
                char next = buf.charAt(scanPos + 1);
                if (next == 'u') {
                    if (scanPos + 6 > n) {
                        break; // unicode 码点转义不完整，等待下一个 chunk
                    }
                    String hex = buf.substring(scanPos + 2, scanPos + 6);
                    try {
                        decoded.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        decoded.append("\\u"); // 非法 unicode 转义，按字面处理
                    }
                    scanPos += 6;
                } else {
                    switch (next) {
                        case 'n' -> decoded.append('\n');
                        case 't' -> decoded.append('\t');
                        case 'r' -> decoded.append('\r');
                        case 'b' -> decoded.append('\b');
                        case 'f' -> decoded.append('\f');
                        case '"', '\\', '/' -> decoded.append(next);
                        default -> decoded.append('\\').append(next); // 未知转义按字面
                    }
                    scanPos += 2;
                }
            } else if (c == '"') {
                // 未转义的闭引号：reply 结束
                finished = true;
                break;
            } else {
                decoded.append(c);
                scanPos++;
            }
        }

        String delta = decoded.substring(emittedLen);
        emittedLen = decoded.length();
        return delta;
    }

    /**
     * 是否推送过 reply 增量（用于结束后判断是否需要兜底补推）
     */
    public boolean wasEmitted() {
        return emittedLen > 0;
    }

    /**
     * 尝试在缓冲区中定位 "reply" 键及其字符串值起点
     *
     * @return true 表示已进入 reply 字符串；false 表示尚未找到（继续等待）
     */
    private boolean tryLocateReply() {
        int keyIdx = buf.indexOf("\"reply\"");
        if (keyIdx < 0) {
            // 未找到：滚动保留尾部 8 字符（覆盖 "reply" 键名被 chunk 切断的情况），
            // 旧数组格式等无 reply 键的响应不会造成缓冲无限增长
            if (buf.length() > 8) {
                buf.delete(0, buf.length() - 8);
            }
            return false;
        }
        // 跳过键名后的空白找冒号
        int i = keyIdx + "\"reply\"".length();
        int n = buf.length();
        while (i < n && Character.isWhitespace(buf.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return false; // 等待冒号
        }
        if (buf.charAt(i) != ':') {
            // "reply" 出现在字符串值内的巧合场景，放弃流式提取
            finished = true;
            return false;
        }
        i++;
        while (i < n && Character.isWhitespace(buf.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return false; // 等待值的开引号
        }
        if (buf.charAt(i) != '"') {
            // reply 不是字符串（异常情况），放弃流式提取
            finished = true;
            return false;
        }
        scanPos = i + 1;
        replyStarted = true;
        return true;
    }
}
