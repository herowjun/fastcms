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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AI 工具注册中心
 *
 * <p>主工程或插件通过 {@link #register} 注册 @AiTool 标注的方法，
 * ChatClient 调用工具时通过 {@link #getToolDescriptors} 拿到所有可用工具。</p>
 *
 * <p><b>线程安全</b>：使用 synchronized Map，支持插件热插拔时并发注册/注销。</p>
 *
 * <p><b>第一步骨架说明</b>：本类只维护工具的元数据（name、description、方法、目标对象），
 * 不直接对接 Spring AI 的 ToolCallback 接口。后续步骤会在 AiToolRegister 里把
 * 这些元数据转换为 Spring AI 的 {@code ToolCallback} 注册到 ChatClient。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

    /**
     * 工具描述符集合，key = 工具名（默认为方法名）
     */
    private final Map<String, ToolDescriptor> toolDescriptors = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 注册一个 @AiTool 方法
     *
     * @param target      方法所在的对象实例（如果是静态方法或类方法可传 null/Class）
     * @param method      被标注的方法
     * @param annotation  方法上的 @AiTool 注解
     */
    public void register(Object target, Method method, AiTool annotation) {
        String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
        ToolDescriptor descriptor = new ToolDescriptor(toolName, annotation.description(), target, method, annotation.returnDirect());
        ToolDescriptor previous = toolDescriptors.put(toolName, descriptor);
        if (previous != null) {
            log.warn("AI 工具 [{}] 被覆盖：旧={} 新={}", toolName, previous, descriptor);
        } else {
            log.info("AI 工具注册：{} ({})", toolName, annotation.description());
        }
    }

    /**
     * 注销一个工具
     *
     * @param toolName 工具名
     * @return 被移除的描述符，不存在则返回 null
     */
    public ToolDescriptor unregister(String toolName) {
        ToolDescriptor removed = toolDescriptors.remove(toolName);
        if (removed != null) {
            log.info("AI 工具注销：{}", toolName);
        }
        return removed;
    }

    /**
     * 注销某个目标对象注册的所有工具（插件卸载时调用）
     *
     * @param target 工具所在的对象实例
     */
    public void unregisterByTarget(Object target) {
        toolDescriptors.entrySet().removeIf(entry -> Objects.equals(entry.getValue().getTarget(), target));
    }

    /**
     * 获取所有已注册工具的描述符
     */
    public Map<String, ToolDescriptor> getToolDescriptors() {
        synchronized (toolDescriptors) {
            return new LinkedHashMap<>(toolDescriptors);
        }
    }

    /**
     * 工具描述符
     */
    public static class ToolDescriptor {
        private final String name;
        private final String description;
        private final Object target;
        private final Method method;
        private final boolean returnDirect;

        public ToolDescriptor(String name, String description, Object target, Method method, boolean returnDirect) {
            this.name = name;
            this.description = description;
            this.target = target;
            this.method = method;
            this.returnDirect = returnDirect;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Object getTarget() { return target; }
        public Method getMethod() { return method; }
        public boolean isReturnDirect() { return returnDirect; }

        @Override
        public String toString() {
            return "ToolDescriptor{name='" + name + '\'' +
                    ", description='" + description + '\'' +
                    ", method=" + method.getDeclaringClass().getSimpleName() + "#" + method.getName() +
                    ", returnDirect=" + returnDirect +
                    '}';
        }
    }

}
