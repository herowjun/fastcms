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
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * 幽灵工具解析器：模型拼错工具名时的优雅降级（不崩流，回流纠错提示）
 *
 * <p><b>问题背景</b>：Spring AI 2.0.1 的 {@code DefaultToolCallingManager} 对模型调用的
 * 未注册工具名直接抛 {@code IllegalStateException: No ToolCallback found for tool name: X}，
 * 异常穿透整条流式链路，数分钟的生成任务全部作废（实测：Qwen3.6-27B 把
 * {@code search_template_files} 幻觉成单数 {@code search_template_file}，247 秒的
 * 样式升级轮崩掉、模板零改动）。</p>
 *
 * <p><b>解法</b>：本包装器把委托 resolver 解析不到的名字映射为一个"幽灵回调"——
 * 其 {@code call()} 不执行任何动作，而是返回一段纠错提示（引导模型对照系统提示中的
 * 工具定义重试；resolver 层拿不到当前请求挂载的工具清单，无法在提示中列出）。
 * 该文本作为工具结果回流给模型，模型在下一轮自我纠正。
 * 需配合 {@code DefaultToolCallingManager.builder().resolutionFallbackEnabled(true)}
 * 使用（fallback 关闭时请求外名字根本不进 resolver，直接抛异常）。</p>
 *
 * <p><b>边界</b>：只影响"模型调用未注册工具名"这一路径；请求内正常工具的解析
 * （options 中的 toolCallbacks 优先）不受影响。幽灵回调计入工具调用次数
 * （manager 的 maxCallsPerTool/maxTotalToolCalls 默认 40/150），模型反复拼错也不会无限消耗。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class GhostToolCallbackResolver implements ToolCallbackResolver {

    private final ToolCallbackResolver delegate;

    public GhostToolCallbackResolver(ToolCallbackResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolCallback resolve(String toolName) {
        ToolCallback resolved = delegate.resolve(toolName);
        return resolved != null ? resolved : ghost(toolName);
    }

    /**
     * 构造幽灵回调：definition 名与请求名一致（manager 按名回填结果不报错），
     * call() 返回纠错提示（含可用工具清单，引导模型下一轮用准确名称重试）
     */
    static ToolCallback ghost(String requestedName) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(requestedName)
                .description("占位：请求的工具名未注册（模型输出名与注册名不一致）")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "工具调用失败：名为「" + requestedName + "」的工具不存在。"
                        + "可能是工具名称拼写有误（注意单复数、下划线与大小写）。"
                        + "请检查系统提示中的工具定义，使用准确的工具名重试；"
                        + "若任务不需要该工具，请基于已有信息直接继续。";
            }
        };
    }
}
