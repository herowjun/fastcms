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
package com.fastcms.ai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 {@link AiToolRegistry} 中的 @AiTool 工具桥接为 Spring AI 的 {@link ToolCallback}
 *
 * <p>ChatClient 通过 {@code defaultTools(callbacks)} 挂载后，模型可在对话中
 * 自主决定调用这些工具（查询文章、搜索内容等只读能力）。</p>
 *
 * <p><b>调用时机</b>：必须在 {@link AiToolRegister} 的
 * {@code afterSingletonsInstantiated()}（所有单例 bean 就绪、@AiTool 扫描完成）之后
 * 调用 {@link #getToolCallbacks()}。各 AI 场景服务在用户请求时构建 ChatClient，
 * 天然满足该时序。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiToolCallbackProvider {

    private final AiToolRegistry registry;

    public AiToolCallbackProvider(AiToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 获取当前已注册工具对应的 ToolCallback 数组（每次调用现算，规模小无性能问题）
     *
     * @return 无工具时返回空数组（ChatClient.defaultTools 接受空数组）
     */
    public ToolCallback[] getToolCallbacks() {
        Map<String, AiToolRegistry.ToolDescriptor> descriptors = registry.getToolDescriptors();
        List<ToolCallback> callbacks = new ArrayList<>(descriptors.size());
        for (AiToolRegistry.ToolDescriptor descriptor : descriptors.values()) {
            try {
                callbacks.add(toCallback(descriptor));
            } catch (Exception e) {
                // 单个工具桥接失败不影响其他工具（如方法签名含无法生成 schema 的类型）
                throw new IllegalStateException("AI 工具桥接失败: " + descriptor.getName(), e);
            }
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    private ToolCallback toCallback(AiToolRegistry.ToolDescriptor descriptor) {
        Method method = descriptor.getMethod();
        String inputSchema = JsonSchemaGenerator.generateForMethodInput(method);
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name(descriptor.getName())
                .description(descriptor.getDescription() == null ? "" : descriptor.getDescription())
                .inputSchema(inputSchema)
                .build();
        return MethodToolCallback.builder()
                .toolDefinition(toolDefinition)
                .toolMethod(method)
                .toolObject(descriptor.getTarget())
                .build();
    }
}
