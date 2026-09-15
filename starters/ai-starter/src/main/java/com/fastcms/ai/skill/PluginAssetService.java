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
package com.fastcms.ai.skill;

import com.fastcms.ai.capability.PluginCapabilityRegistry;
import com.fastcms.ai.component.ComponentDescriptor;
import com.fastcms.ai.component.ComponentVariant;
import com.fastcms.ai.component.SectionComponentProvider;
import com.fastcms.ai.tool.AiToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件资产聚合：按 pluginId 汇总插件贡献的各类 AI 资产，并推断分类标签
 *
 * <p>插件是统一资产容器（skill / AI 工具 / 组件包 / 能力描述可任意组合），
 * 本类是插件管理"资产视图"的数据源：</p>
 * <ul>
 *     <li><b>标签自动推断</b>（零配置，按实际资产打标）：skill / capability / tool /
 *     component / payment（声明了 payment:* 渠道依赖的能力时）</li>
 *     <li><b>资产清单</b>：skills 精确按 pluginId；capabilities 按注册中心 pluginId；
 *     tools / components 按插件类加载器归属判定（无 pluginId 元数据，类加载器是
 *     插件边界的事实标准，插件卸载后自动消失）</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Component
public class PluginAssetService implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(PluginAssetService.class);

    public static final String TAG_SKILL = "skill";
    public static final String TAG_CAPABILITY = "capability";
    public static final String TAG_TOOL = "tool";
    public static final String TAG_COMPONENT = "component";
    public static final String TAG_PAYMENT = "payment";

    private static final String PAYMENT_CHANNEL_PREFIX = "payment:";

    private final SkillRegistry skillRegistry;

    private final PluginCapabilityRegistry capabilityRegistry;

    private final AiToolRegistry aiToolRegistry;

    private volatile ApplicationContext applicationContext;

    public PluginAssetService(SkillRegistry skillRegistry,
                              PluginCapabilityRegistry capabilityRegistry,
                              AiToolRegistry aiToolRegistry) {
        this.skillRegistry = skillRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.aiToolRegistry = aiToolRegistry;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 插件资产总览（插件详情抽屉数据源）
     *
     * @param pluginId 插件 id
     * @param pluginClassLoader 插件类加载器（tools/components 归属判定用；可空）
     */
    public PluginAssets getPluginAssets(String pluginId, ClassLoader pluginClassLoader) {
        List<SkillDescriptor> skills = skillRegistry.listSkillsByPlugin(pluginId);
        List<CapabilityBrief> capabilities = listCapabilities(pluginId);
        List<ToolBrief> tools = listTools(pluginClassLoader);
        List<ComponentBrief> components = listComponents(pluginClassLoader);

        List<String> tags = new ArrayList<>();
        if (!skills.isEmpty()) {
            tags.add(TAG_SKILL);
        }
        if (!capabilities.isEmpty()) {
            tags.add(TAG_CAPABILITY);
        }
        if (!tools.isEmpty()) {
            tags.add(TAG_TOOL);
        }
        if (!components.isEmpty()) {
            tags.add(TAG_COMPONENT);
        }
        boolean hasPayment = capabilities.stream()
                .anyMatch(c -> c.requiresChannel() != null && c.requiresChannel().startsWith(PAYMENT_CHANNEL_PREFIX));
        if (hasPayment) {
            tags.add(TAG_PAYMENT);
        }
        return new PluginAssets(pluginId, tags, skills, capabilities, tools, components);
    }

    private List<CapabilityBrief> listCapabilities(String pluginId) {
        List<CapabilityBrief> result = new ArrayList<>();
        try {
            capabilityRegistry.listCapabilities().stream()
                    .filter(rc -> pluginId != null && pluginId.equals(rc.pluginId()))
                    .forEach(rc -> result.add(new CapabilityBrief(
                            rc.descriptor().capabilityId(),
                            rc.descriptor().name(),
                            rc.descriptor().description(),
                            rc.descriptor().requiresChannel())));
        } catch (Exception e) {
            log.warn("聚合插件 [{}] 能力清单失败: {}", pluginId, e.getMessage());
        }
        return result;
    }

    private List<ToolBrief> listTools(ClassLoader pluginClassLoader) {
        List<ToolBrief> result = new ArrayList<>();
        if (pluginClassLoader == null) {
            return result;
        }
        aiToolRegistry.getToolDescriptors().forEach((name, descriptor) -> {
            Object target = descriptor.getTarget();
            if (target == null) {
                return;
            }
            Class<?> targetClass = target.getClass();
            try {
                targetClass = AopUtils.getTargetClass(target);
            } catch (Exception ignored) {
            }
            if (pluginClassLoader.equals(targetClass.getClassLoader())) {
                result.add(new ToolBrief(name, descriptor.getDescription()));
            }
        });
        return result;
    }

    private List<ComponentBrief> listComponents(ClassLoader pluginClassLoader) {
        List<ComponentBrief> result = new ArrayList<>();
        ApplicationContext context = this.applicationContext;
        if (context == null || pluginClassLoader == null) {
            return result;
        }
        try {
            context.getBeansOfType(SectionComponentProvider.class).values().stream()
                    .filter(provider -> {
                        try {
                            return pluginClassLoader.equals(AopUtils.getTargetClass(provider).getClassLoader());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(provider -> {
                        List<ComponentDescriptor> descriptors = provider.listComponents();
                        List<ComponentDetail> details = descriptors.stream()
                                .map(d -> new ComponentDetail(d.id(), d.name(), d.description(), d.category(),
                                        d.safeAppliesTo(), toVariantBriefs(d)))
                                .toList();
                        result.add(new ComponentBrief(provider.getPackId(), provider.getFoundation(),
                                descriptors.size(), details));
                    });
        } catch (Exception e) {
            log.warn("聚合插件组件包失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 插件资产总览 VO
     *
     * @param pluginId 插件 id
     * @param tags 自动推断的分类标签
     * @param skills 贡献的技能（L1 元数据）
     * @param capabilities 贡献的业务能力
     * @param tools 贡献的 AI 工具
     * @param components 贡献的组件包
     */
    public record PluginAssets(String pluginId, List<String> tags,
                               List<SkillDescriptor> skills,
                               List<CapabilityBrief> capabilities,
                               List<ToolBrief> tools,
                               List<ComponentBrief> components) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pluginId", pluginId);
            map.put("tags", tags);
            map.put("skills", skills);
            map.put("capabilities", capabilities);
            map.put("tools", tools);
            map.put("components", components);
            return map;
        }
    }

    /**
     * 能力摘要（L1）
     */
    public record CapabilityBrief(String capabilityId, String name, String description, String requiresChannel) {
    }

    /**
     * 工具摘要
     */
    public record ToolBrief(String name, String description) {
    }

    private static List<ComponentVariantBrief> toVariantBriefs(ComponentDescriptor descriptor) {
        if (descriptor.variants() == null) {
            return List.of();
        }
        return descriptor.variants().stream()
                .map(v -> new ComponentVariantBrief(v.id(), v.description()))
                .toList();
    }

    /**
     * 组件包摘要（含每个组件的元数据明细，插件资产抽屉展示用）
     */
    public record ComponentBrief(String packId, String foundation, int componentCount,
                                 List<ComponentDetail> components) {
    }

    /**
     * 组件明细（L1：名称/描述/分类/适用页面/变体）
     */
    public record ComponentDetail(String id, String name, String description, String category,
                                  List<String> appliesTo, List<ComponentVariantBrief> variants) {
    }

    /**
     * 组件变体摘要
     */
    public record ComponentVariantBrief(String id, String description) {
    }

}
