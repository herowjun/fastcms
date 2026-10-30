/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0,
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * http://www.jd.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.support;

/**
 * 补丁片段匹配器（行尾符无关）
 *
 * <p><b>为什么需要这一层</b>：模型输出的 {@code search} 片段几乎总是 LF 换行，
 * 而磁盘上的模板文件（尤其 Windows 项目 / 从网页导入的 HTML）可能是 CRLF 换行。
 * 逐字 {@code indexOf} 匹配会 100% 失败 → 补丁被拒 → 回退整文件回写 →
 * 补丁协议的收益（输出量降 1~2 个数量级）完全归零。</p>
 *
 * <p><b>2026-10-28 活体实测</b>（真实端点 Qwen3.6-27B + 真实 {@code FilePatchApplier}）：
 * 模型按补丁协议输出了 461 字符（对比全文 22681 字符，输出量降 49 倍），
 * 但同一个响应里：LF 文件 {@code _preview_data.json} 的补丁<b>成功</b>、
 * CRLF 文件 {@code index.html} 的补丁<b>失败</b>——根因就是换行符差异。</p>
 *
 * <p><b>匹配口径</b>：</p>
 * <ol>
 *   <li>先做<b>逐字</b>匹配（零成本、覆盖绝大多数情况）；</li>
 *   <li>未命中再把双方换行统一为 LF 后匹配——这是模型输出与磁盘文件不一致的
 *       主要来源（CRLF vs LF、以及模型偶尔输出 {@code \r\n}）；</li>
 *   <li>两种口径都要求<b>唯一命中</b>，多处命中一律判歧义拒绝
 *       （避免改错地方），保持与原实现一致的安全口径。</li>
 * </ol>
 *
 * <p><b>为什么只归一化换行、不归一化空白</b>：缩进差异属于「模型重排了格式」，
 * 归一化空白后匹配到的位置可能与模型意图不符（模型想改 A 处、实际匹配到 B 处），
 * 属于静默改错内容，比直接失败更危险。缩进不一致时应由模型自己保证
 * search 与注入内容一致——而注入内容与磁盘内容同源（同一份 CRLF），
 * 只有换行符会在「模型重打一遍」时丢失。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public final class PatchTextMatcher {

    /**
     * 匹配结果
     *
     * @param start      命中起点（在<b>原始</b> content 中的下标）
     * @param length     命中长度（对应<b>原始</b> content 中的长度）
     * @param normalized 是否走了换行归一化口径（用于日志与测试断言）
     */
    public record Match(int start, int length, boolean normalized) {
    }

    /** 匹配失败 */
    public static final Match NOT_FOUND = new Match(-1, 0, false);

    /**
     * 多处命中（歧义）
     */
    public static final Match AMBIGUOUS = new Match(-2, 0, false);

    private PatchTextMatcher() {
    }

    /**
     * 在 content 中定位 search（行尾符无关、唯一命中）
     *
     * @param content 文件原文
     * @param search  模型给出的待替换片段
     * @return 命中位置；{@link #NOT_FOUND} = 未找到；{@link #AMBIGUOUS} = 多处命中
     */
    public static Match find(String content, String search) {
        if (content == null || search == null || search.isEmpty()) {
            return NOT_FOUND;
        }
        // 1) 逐字匹配（绝大多数情况，零成本）
        int first = content.indexOf(search);
        if (first >= 0) {
            if (content.indexOf(search, first + 1) >= 0) {
                return AMBIGUOUS;
            }
            return new Match(first, search.length(), false);
        }
        // 2) 换行归一化匹配（CRLF vs LF，两个方向都要覆盖：
        //    模型输出 LF + 磁盘 CRLF；模型输出 CRLF + 磁盘 LF）
        Normalized normalized = normalize(content);
        String normSearch = normalizeNewlines(search);
        if (normSearch.isEmpty()) {
            return NOT_FOUND;
        }
        if (normalized.text().equals(content) && normSearch.equals(search)) {
            // 双方都没有 CR → 归一化不会改变任何东西 → 必然匹配不上
            return NOT_FOUND;
        }
        int nFirst = normalized.text().indexOf(normSearch);
        if (nFirst < 0) {
            return NOT_FOUND;
        }
        if (normalized.text().indexOf(normSearch, nFirst + 1) >= 0) {
            return AMBIGUOUS;
        }
        // 把归一化坐标映射回原始坐标。
        // 归一化下标 k 对应原文区间 [starts[k], ends[k])，
        // 命中区间 [nFirst, nFirst+len) → 原文区间 [starts[nFirst], ends[nFirst+len-1])
        int origStart = normalized.starts()[nFirst];
        int origEnd = normalized.ends()[nFirst + normSearch.length() - 1];
        if (origEnd < origStart) {
            return NOT_FOUND;
        }
        return new Match(origStart, origEnd - origStart, true);
    }

    /**
     * 按「文件原有换行风格」归一化替换内容
     *
     * <p>模型输出的替换片段是 LF；文件是 CRLF 时直接拼进去会产生混合行尾
     * （部分行 CRLF、部分行 LF），后续再做补丁匹配又会引入新的不一致。
     * 这里把替换内容统一转成文件的主导行尾风格。</p>
     *
     * @param replace 模型给出的替换内容
     * @param content 文件原文（用于判断行尾风格）
     * @return 行尾风格与文件一致的替换内容
     */
    public static String alignNewlines(String replace, String content) {
        if (replace == null || replace.indexOf('\n') < 0 || content == null) {
            return replace;
        }
        boolean crlf = content.contains("\r\n");
        boolean lfOnly = content.indexOf('\n') >= 0 && content.indexOf("\r\n") < 0;
        if (!crlf) {
            // 文件是 LF（或无换行）→ 保持 LF
            return replace;
        }
        if (lfOnly) {
            return replace;
        }
        // 文件含 CRLF（可能混合）→ 统一转 CRLF
        return replace.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    /**
     * 换行归一化：{@code \r\n} → {@code \n}（单独 {@code \r} → {@code \n}）
     */
    public static String normalizeNewlines(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 归一化文本 + 原文区间映射
     *
     * @param text   归一化后的文本
     * @param starts 每个归一化字符在原文中的<b>起始</b>下标
     * @param ends   每个归一化字符在原文中的<b>结束</b>下标（不含，exclusive）
     */
    private record Normalized(String text, int[] starts, int[] ends) {
    }

    /**
     * 构建归一化文本与原文区间映射
     *
     * <p>归一化规则：{@code \r\n} → {@code \n}（长度减 1）、单独 {@code \r} → {@code \n}（长度不变）。
     * 逐字符记录「归一化字符 ↔ 原文区间」，避免手工算术出错。</p>
     */
    private static Normalized normalize(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        int[] starts = new int[content.length() + 1];
        int[] ends = new int[content.length() + 1];
        int normLen = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            starts[normLen] = i;
            if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                sb.append('\n');
                ends[normLen] = i + 2;
                normLen++;
                i++;
            } else if (c == '\r') {
                sb.append('\n');
                ends[normLen] = i + 1;
                normLen++;
            } else {
                sb.append(c);
                ends[normLen] = i + 1;
                normLen++;
            }
        }
        int[] exactStarts = new int[normLen];
        int[] exactEnds = new int[normLen];
        System.arraycopy(starts, 0, exactStarts, 0, normLen);
        System.arraycopy(ends, 0, exactEnds, 0, normLen);
        return new Normalized(sb.toString(), exactStarts, exactEnds);
    }
}
