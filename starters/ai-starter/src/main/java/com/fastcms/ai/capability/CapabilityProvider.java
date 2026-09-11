/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.capability;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * 插件能力供给方扩展点：向 AI 暴露插件能力的接口契约与参考实现
 *
 * <p><b>双注册机制</b>（与 SectionComponentProvider 一致）：</p>
 * <ul>
 *     <li>主应用内置能力：ai-starter 内实现本接口的 Spring bean（如 {@link CoreCapabilityProvider}）</li>
 *     <li>插件能力：插件工程以 {@code @Extension} 实现本接口，由 plugin-starter 的
 *         ExtensionsRegister 注册为 Spring 单例（装卸时增删），registry 惰性感知自动纳入/剔除</li>
 * </ul>
 *
 * <p><b>插件侧约定</b>：插件在 {@code src/main/resources/plugin-capabilities.json} 声明能力，
 * Provider 解析该文件并提供 snippet 源码读取。声明文件路径固定，
 * {@code pluginId} 与 PF4J 插件 id 一致（渲染期 {@code <@hasPlugin>} 门控依赖此映射）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface CapabilityProvider extends ExtensionPoint {

    /**
     * 声明文件固定路径（classpath 下）：插件能力的唯一声明入口
     */
    String CAPABILITY_DECLARATION_PATH = "plugin-capabilities.json";

    /**
     * 供给方对应的插件 id（core 能力返回 null 或空，表示不门控）
     */
    String getPluginId();

    /**
     * 本供给方声明的全部能力
     */
    List<CapabilityDescriptor> listCapabilities();

    /**
     * 读取 snippet 源码（渲染期物化进模板目录用）
     *
     * @param capabilityId 能力 id
     * @param snippetId    snippet id
     * @return snippet 源码（含 {@code {{PARAM}}} 占位符），不存在返回 null
     */
    String getSnippetSource(String capabilityId, String snippetId);
}
