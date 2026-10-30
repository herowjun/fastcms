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

import java.util.List;

/**
 * 调整轮意图分类（B2 · 思考档位按问题类型分流）
 *
 * <p>同一套「调整」链路里其实混着两类差异很大的任务：</p>
 * <ul>
 *     <li><b>诊断类</b>（「导航为什么不居中」「这个按钮为什么不生效」）——答案空间有限、
 *         不需要改文件，只需要定位原因；深度思考纯属浪费，关掉思考即可。</li>
 *     <li><b>改动类</b>（「把主色改成蓝色」「加一个页脚」）——需要权衡与创作，
 *         保留低档思考。</li>
 * </ul>
 *
 * <p>分类用<b>轻量关键词启发式</b>，不额外调用模型（多一次调用的成本与延迟
 * 远大于本项收益）。判定顺序：先看改动动词（命中即改动），再看诊断词（命中即诊断），
 * 都不命中默认按<b>改动</b>处理——关思考的答错风险高于多花几秒。</p>
 *
 * <p>注意：这是「场景默认档位」层的分流。用户在后台「模型管理」里显式配置了
 * {@code reasoning_effort} 时，以用户配置为准（见 {@code AiReasoningSupport.resolve}）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public enum AdjustIntent {

    /**
     * 诊断类：定位问题、解释原因，一般不改文件
     */
    DIAGNOSTIC,

    /**
     * 改动类：明确要求修改模板
     */
    CHANGE;

    /**
     * 强改动动词：语义无歧义，命中即判定为改动类
     *
     * <p>放在第一层，保证「把不居中的改成居中」这类混合语句仍按改动处理。</p>
     */
    private static final List<String> STRONG_CHANGE_KEYWORDS = List.of(
            "改成", "换成", "改为", "调整为", "变成", "设为", "设置为", "修改", "调整",
            "增加", "添加", "新增", "加入", "删除", "去掉", "移除", "清掉", "替换",
            "重写", "重新生成", "重新做", "重做", "移动", "挪到", "放大", "缩小",
            "加个", "加一个", "补一个", "做一个", "做一");

    /**
     * 歧义动词：既可能是改动，也可能只是在描述现象
     *
     * <p>用<b>否定感知</b>匹配——紧邻否定词（不/没/无/未/别/莫）的出现不算改动：</p>
     * <ul>
     *     <li>「这个按钮<b>不显示</b>」→ 显示前有「不」→ 不算改动 → 诊断类</li>
     *     <li>「帮我<b>显示出来</b>」→ 显示前无否定 → 算改动</li>
     *     <li>「图片<b>没显示出来</b>」→ 算诊断类</li>
     * </ul>
     */
    private static final List<String> AMBIGUOUS_CHANGE_KEYWORDS = List.of(
            "显示", "展示", "居中", "对齐", "排序", "隐藏", "生效", "响应", "出现");

    /**
     * 否定标记（紧邻歧义动词之前时，该出现不计为改动）
     */
    private static final String NEGATION_MARKERS = "不没无未莫别勿";

    /**
     * 强诊断词：明确的问原因 / 问状态 / 要解释
     *
     * <p>放在第三层，优先于弱改动词——否则「为什么配色不对」会被误判为改动。</p>
     */
    private static final List<String> STRONG_DIAGNOSTIC_KEYWORDS = List.of(
            "为什么", "为何", "原因", "怎么回事", "咋回事", "什么问题", "问题在哪",
            "不生效", "没生效", "没反应", "不显示", "没显示", "没有显示", "不见了",
            "不居中", "没居中", "不对齐", "错位", "变形", "溢出", "是不是", "是否",
            "能不能", "可不可以", "有没有", "解释", "说明一下", "分析一下", "解释下",
            "讲讲", "建议", "什么区别", "区别是", "什么原理", "原理是", "怎么才能", "如何才能");

    /**
     * 弱改动词：语义基本是改动，但排在诊断词之后
     *
     * <p>「为什么不优化配色」这类句子里，「优化/配色」命中，但「为什么」应优先判为诊断。</p>
     */
    private static final List<String> WEAK_CHANGE_KEYWORDS = List.of(
            "优化", "换色", "改色", "配色", "布局", "圆角", "阴影", "精简", "扁平化",
            "扩大", "扩展", "改一下", "改下");

    /**
     * 分类调整请求的意图
     *
     * @param userInput 用户本轮输入（可空）
     * @return 意图（输入为空或无法判断时返回 {@link #CHANGE}）
     */
    public static AdjustIntent classify(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return CHANGE;
        }
        String text = userInput.toLowerCase();
        // 第一层：强改动动词（无歧义）
        for (String keyword : STRONG_CHANGE_KEYWORDS) {
            if (text.contains(keyword)) {
                return CHANGE;
            }
        }
        // 第二层：歧义动词（否定感知）
        if (hasUnnegatedOccurrence(text, AMBIGUOUS_CHANGE_KEYWORDS)) {
            return CHANGE;
        }
        // 第三层：强诊断词
        for (String keyword : STRONG_DIAGNOSTIC_KEYWORDS) {
            if (text.contains(keyword)) {
                return DIAGNOSTIC;
            }
        }
        // 第四层：弱改动词
        for (String keyword : WEAK_CHANGE_KEYWORDS) {
            if (text.contains(keyword)) {
                return CHANGE;
            }
        }
        return CHANGE;
    }

    /**
     * 是否存在未被否定词修饰的目标词出现
     *
     * @param text     已转小写的输入
     * @param keywords 目标词列表
     * @return true = 至少有一处出现不是被否定修饰的
     */
    private static boolean hasUnnegatedOccurrence(String text, List<String> keywords) {
        for (String keyword : keywords) {
            int idx = text.indexOf(keyword);
            while (idx >= 0) {
                boolean negated = idx > 0 && NEGATION_MARKERS.indexOf(text.charAt(idx - 1)) >= 0;
                if (!negated) {
                    return true;
                }
                idx = text.indexOf(keyword, idx + 1);
            }
        }
        return false;
    }
}
