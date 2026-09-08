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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式 PageSpec 页面进度扫描器：从逐步到达的 PageSpec JSON 流中识别已规划的页面数
 *
 * <p>组件化管线的 AI 输出是 PageSpec 页面规划（大模板可持续数分钟），期间文件尚不存在、
 * 无法像 files 直出管线那样逐文件推送"正在生成 xxx"。本扫描器与 {@link ReplyStreamExtractor}
 * 并行工作，每当一个页面的 {@code "sections"} 键在流中完整出现即计为一页，配合累计接收量
 * 形成"已识别 N 个页面 / 已接收 X KB"的心跳状态，消除长时间等待的假死观感。</p>
 *
 * <p>锚点说明：{@code PageSpecPage} 恰有一个 sections 字段，SectionSpec / SiteContentSpec
 * 等其余结构没有 sections 键，对象成员位置的 {@code "sections"} 计数即页面数
 * （组件 data 文案中出现该字样的概率极低，可忽略）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class PageSpecProgressScanner {

    /**
     * 页面对象的 sections 键（对象成员位置）
     */
    private static final Pattern SECTIONS_PATTERN = Pattern.compile("[{,]\\s*\"sections\"");

    /**
     * 心跳推送的字节阈值：跨过阈值（或识别出新页面）时返回 true
     */
    private static final int PUSH_CHAR_THRESHOLD = 8192;

    /**
     * 已匹配文本之后的扫描位置（含未完成的尾部，防模式被 chunk 切断）
     */
    private final StringBuilder buf = new StringBuilder();

    private int scanPos;

    private int totalPages;

    private long receivedChars;

    private long lastPushedChars;

    /**
     * 喂入新 chunk（仅 reply 流完后的 PageSpec 部分），本次是否值得推送心跳：
     * 识别出新页面 或 距上次推送累计接收量跨过阈值
     */
    public boolean feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        buf.append(chunk);
        receivedChars += chunk.length();
        boolean newPage = false;
        Matcher m = SECTIONS_PATTERN.matcher(buf);
        m.region(scanPos, buf.length());
        while (m.find()) {
            totalPages++;
            newPage = true;
            scanPos = m.end();
        }
        // 已扫描区域可以丢弃，仅保留尾部 32 字符（覆盖 "sections" 模式被切断的场景）
        if (buf.length() > 32) {
            int keepFrom = Math.max(scanPos, buf.length() - 32);
            buf.delete(0, keepFrom);
            scanPos = Math.max(0, scanPos - keepFrom);
        }
        if (newPage || receivedChars - lastPushedChars >= PUSH_CHAR_THRESHOLD) {
            lastPushedChars = receivedChars;
            return true;
        }
        return false;
    }

    public int totalPages() {
        return totalPages;
    }

    public long receivedChars() {
        return receivedChars;
    }

    /**
     * 重置计数（截断重试 / 修复轮从零开始计）
     */
    public void reset() {
        buf.setLength(0);
        scanPos = 0;
        totalPages = 0;
        receivedChars = 0;
        lastPushedChars = 0;
    }
}
