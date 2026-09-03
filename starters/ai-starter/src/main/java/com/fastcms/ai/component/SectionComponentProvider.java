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
package com.fastcms.ai.component;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * 组件包扩展点：所有 section 组件供给方实现此接口
 *
 * <p>组件化 AI 模板生成的核心抽象——"组件从哪来"被本接口隔离：</p>
 * <ul>
 *     <li>首期：内置组件包 {@code BuiltinTailwindPackProvider} 以 classpath 资源目录实现（Spring bean）</li>
 *     <li>未来：商业组件包以 PF4J 插件实现（{@code @Extension} 注册），
 *         {@link ComponentRegistry} 与渲染引擎零改动，只认本接口</li>
 * </ul>
 *
 * <p>组件包的组成：每个组件一个目录，含 {@code component.json}（元数据，给 AI 看的"菜单"）
 * 与 {@code variants/*.ftl}（FreeMarker 片段，渲染引擎取用），外加地基 CSS 资产（pack.css）。</p>
 *
 * <p><b>组件 FTL 契约</b>：只产出 {@code <section>} 片段；槽位数据从模板变量 {@code comp} 读取
 * （如 {@code ${(comp.title)!''}}），一律空值防御；不得出现 {@code html/head/body}；
 * 不得依赖外部 JS（交互用 CSS-only 方案实现）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface SectionComponentProvider extends ExtensionPoint {

    /**
     * 包标识，作为组件 id 的命名空间前缀（{@code packId:componentId}），防多包冲突
     */
    String getPackId();

    /**
     * 本包的 CSS 地基（如 "tailwind-v4"），决定 TokenEngine 生成 tokens.css 的变量契约
     */
    String getFoundation();

    /**
     * 本包全部组件的元数据（给 AI 的"菜单"，不含源码）
     */
    List<ComponentDescriptor> listComponents();

    /**
     * 取某个组件某个变体的 FreeMarker 源码（渲染引擎调用，AI 永远不接触）
     *
     * @param componentId 组件 id（不含包前缀）
     * @param variantId   变体 id
     * @return FTL 源码，组件或变体不存在时返回 null
     */
    String getTemplateSource(String componentId, String variantId);

    /**
     * 取组件包的地基静态资产（如 {@code "static/pack.css"}），渲染引擎复制进生成的模板目录
     *
     * @param assetPath 相对包根的资产路径
     * @return 资产内容，不存在时返回 null
     */
    byte[] getPackAsset(String assetPath);

}
