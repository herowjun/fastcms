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

import com.fastcms.plugin.FastcmsPluginManager;
import com.fastcms.plugin.register.AbstractPluginRegister;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;

/**
 * 插件 AI 工具注册器：跟随插件安装/卸载生命周期注册与清理 @AiTool 工具
 *
 * <p><b>背景</b>：{@link AiToolRegister}（SmartInitializingSingleton）只在应用启动时扫描一次
 * Spring 容器。运行时安装的插件其 @AiTool 方法永远不会被扫描到；卸载的插件其工具会残留在
 * {@link AiToolRegistry} 中，模型调用时目标类加载器已卸载，抛出诡异异常。</p>
 *
 * <p><b>挂接方式</b>：本类注册为 Spring bean 后，FastcmsPluginManager 启动时会收集容器中的
 * PluginRegister 并追加到 CompoundPluginRegister 注册链末尾——
 * 注册顺序晚于 ComponentRegister（插件 bean 已就绪），
 * 卸载顺序早于 ComponentRegister（bean 销毁前先摘除工具）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiToolPluginRegister extends AbstractPluginRegister {

    private static final Logger log = LoggerFactory.getLogger(AiToolPluginRegister.class);

    private final AiToolRegistry aiToolRegistry;

    public AiToolPluginRegister(FastcmsPluginManager pluginManager, AiToolRegistry aiToolRegistry) {
        super(pluginManager);
        this.aiToolRegistry = aiToolRegistry;
    }

    @Override
    public void registry(String pluginId) throws Exception {
        PluginWrapper plugin = getPlugin(pluginId);
        if (plugin == null || plugin.getPluginClassLoader() == null) {
            return;
        }
        ClassLoader classLoader = plugin.getPluginClassLoader();
        int count = 0;
        // 扫描 Spring 容器中由该插件类加载器加载的 bean（ComponentRegister 已注册进容器），
        // 与 AiToolRegister 的启动扫描逻辑保持一致（AopUtils 取用户类 + AnnotationUtils 找注解）
        for (Object bean : getApplicationContext().getBeansOfType(Object.class).values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (targetClass.getClassLoader() != classLoader) {
                continue;
            }
            for (Method method : targetClass.getMethods()) {
                AiTool annotation = AnnotationUtils.findAnnotation(method, AiTool.class);
                if (annotation == null) {
                    continue;
                }
                // 应用启动时 AiToolRegister 已扫过一遍（含启动前已加载的插件）：同一实例同一方法跳过，
                // 避免 unregisterByTarget 之外再叠加"工具被覆盖"告警噪音
                String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
                AiToolRegistry.ToolDescriptor existing = aiToolRegistry.getToolDescriptors().get(toolName);
                if (existing != null && existing.getTarget() == bean && method.equals(existing.getMethod())) {
                    continue;
                }
                try {
                    aiToolRegistry.register(bean, method, annotation);
                    count++;
                } catch (Exception e) {
                    log.error("插件 AI 工具注册失败: pluginId={}, method={}", pluginId, method.getName(), e);
                }
            }
        }
        if (count > 0) {
            log.info("插件 AI 工具注册完成: pluginId={}, count={}", pluginId, count);
        }
    }

    @Override
    public void unRegistry(String pluginId) throws Exception {
        PluginWrapper plugin = getPlugin(pluginId);
        if (plugin == null) {
            return;
        }
        // 按插件类加载器批量摘除工具，不依赖 bean 实例引用（此时 bean 可能已被销毁）
        aiToolRegistry.unregisterByClassLoader(plugin.getPluginClassLoader());
    }

}
