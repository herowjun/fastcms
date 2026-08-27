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
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * 扫描 Spring 容器中所有 bean 的 @AiTool 注解方法，注册到 {@link AiToolRegistry}
 *
 * <p><b>设计说明</b>：fastcms 的插件机制会把插件里 @Component 标注的类注册成 Spring bean
 * （见 plugin-starter 的 ComponentRegister），所以本类只需扫描 Spring 容器即可覆盖
 * 主工程和所有插件中的 @AiTool 工具，不需要专门对接 PF4J 生命周期。</p>
 *
 * <p><b>第一步骨架说明</b>：当前实现"扫描 + 注册到 Registry"，但 Registry 还没对接到
 * Spring AI 的 ToolCallback。后续步骤会把 Registry 里的工具转换为 ToolCallback
 * 注册到 ChatClient。</p>
 *
 * <p>使用 {@link SmartInitializingSingleton} 而不是 ApplicationListener，确保所有
 * 单例 bean（包括插件注册的 bean）创建完成后才扫描。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiToolRegister implements SmartInitializingSingleton, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegister.class);

    private final AiToolRegistry aiToolRegistry;
    private ApplicationContext applicationContext;

    public AiToolRegister(AiToolRegistry aiToolRegistry) {
        this.aiToolRegistry = aiToolRegistry;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int count = 0;
        for (Map.Entry<String, Object> entry : applicationContext.getBeansOfType(Object.class).entrySet()) {
            Object bean = entry.getValue();
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getMethods()) {
                AiTool annotation = AnnotationUtils.findAnnotation(method, AiTool.class);
                if (annotation != null) {
                    try {
                        aiToolRegistry.register(bean, method, annotation);
                        count++;
                    } catch (Exception e) {
                        log.error("注册 AI 工具失败：{}#{}", targetClass.getSimpleName(), method.getName(), e);
                    }
                }
            }
        }
        log.info("AI 工具扫描完成，共注册 {} 个工具", count);
    }

}
