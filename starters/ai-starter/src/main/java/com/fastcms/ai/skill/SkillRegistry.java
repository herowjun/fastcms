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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 注册中心：聚合全部 {@link SkillProvider}（磁盘文件源 + 插件源）的统一门面
 *
 * <p><b>内容只能来自文件</b>——本类没有任何注册内容的 API（无 register(prompt) 之类入口），
 * 技能详情规则只存在于 SKILL.md 文件中（磁盘目录或插件 jar），从结构上杜绝代码硬编码。</p>
 *
 * <p>与 PluginCapabilityRegistry 同构的机制：</p>
 * <ul>
 *     <li>惰性感知插件装卸：{@link #ensureFresh()}（1 秒节流）对比容器内 SkillProvider
 *     bean 集合，插件安装/卸载后下次访问即重建索引</li>
 *     <li>冲突处理：磁盘 file 源优先于插件源（用户本地覆盖的逃生通道）；
 *     插件之间 id 冲突 fail-fast（启动期多插件同 slug 时抛出，与能力注册中心一致）</li>
 *     <li>坏技能（error 非空）不进 L1 白名单，但保留在列表中供管理界面展示错误原因</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Component
public class SkillRegistry implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private static final long REFRESH_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private volatile Map<String, SkillDescriptor> skills = Map.of();

    private volatile Map<String, SkillProvider> providers = Map.of();

    private volatile ApplicationContext applicationContext;

    private volatile long lastCheckNanos = 0L;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 构造注入当前容器内全部 SkillProvider（core 的 file 源；插件运行期注册的由 ensureFresh 补充）
     */
    public SkillRegistry(List<SkillProvider> providers) {
        refresh(providers);
    }

    /**
     * 全部技能 L1 元数据（合并所有供给方，按 id 排序）
     */
    public List<SkillDescriptor> listSkills() {
        ensureFresh();
        return new ArrayList<>(skills.values());
    }

    /**
     * 按完整技能 id 查询 L1 元数据
     */
    public SkillDescriptor getSkill(String skillId) {
        ensureFresh();
        return skillId == null ? null : skills.get(skillId);
    }

    /**
     * 技能是否存在（智能体绑定校验用）
     */
    public boolean exists(String skillId) {
        ensureFresh();
        return skillId != null && skills.containsKey(skillId);
    }

    /**
     * 读取技能 L2 正文（SKILL.md frontmatter 之后的完整指令）
     *
     * @return 技能不存在或无效时返回 null
     */
    public String loadContent(String skillId) {
        ensureFresh();
        SkillDescriptor descriptor = skillId == null ? null : skills.get(skillId);
        if (descriptor == null) {
            return null;
        }
        SkillProvider provider = providers.get(providerKeyOf(descriptor));
        return provider == null ? null : provider.loadContent(skillId);
    }

    /**
     * 按 pluginId 过滤技能（插件资产清单用）
     */
    public List<SkillDescriptor> listSkillsByPlugin(String pluginId) {
        ensureFresh();
        return skills.values().stream()
                .filter(d -> pluginId != null && pluginId.equals(d.pluginId()))
                .sorted(java.util.Comparator.comparing(SkillDescriptor::id))
                .toList();
    }

    private String providerKeyOf(SkillDescriptor descriptor) {
        // file 源技能 id 无前缀，归属 file provider；插件技能归属其 providerId
        return descriptor.pluginId() == null ? SkillProvider.FILE_SOURCE : descriptor.pluginId();
    }

    private List<SkillProvider> collectProviders() {
        ApplicationContext context = this.applicationContext;
        if (context == null) {
            // 构造期上下文未注入：仅能拿到直接注册的 file 源（由子类/工厂场景兜底）
            return List.of();
        }
        try {
            return new ArrayList<>(context.getBeansOfType(SkillProvider.class).values());
        } catch (Exception e) {
            log.warn("收集 SkillProvider 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void ensureFresh() {
        ApplicationContext context = this.applicationContext;
        if (context == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCheckNanos < REFRESH_CHECK_INTERVAL_NANOS) {
            return;
        }
        lastCheckNanos = now;
        try {
            List<SkillProvider> current = collectProviders();
            Set<SkillProvider> beanSet = new HashSet<>(current);
            Set<SkillProvider> currentSet = new HashSet<>(providers.values());
            if (!beanSet.equals(currentSet)) {
                refresh(current);
            }
        } catch (Exception e) {
            // 容器刷新期（插件装卸进行中）的瞬时异常忽略，下次检查重试
        }
    }

    private synchronized void refresh(List<SkillProvider> providerList) {
        // file 源排最前（本地覆盖优先），插件源按 providerId 字典序稳定排列
        List<SkillProvider> ordered = providerList.stream()
                .sorted((a, b) -> {
                    boolean aFile = SkillProvider.FILE_SOURCE.equals(a.getProviderId());
                    boolean bFile = SkillProvider.FILE_SOURCE.equals(b.getProviderId());
                    if (aFile != bFile) {
                        return aFile ? -1 : 1;
                    }
                    return a.getProviderId().compareTo(b.getProviderId());
                })
                .toList();

        Map<String, SkillDescriptor> merged = new LinkedHashMap<>();
        Map<String, SkillProvider> providerIndex = new LinkedHashMap<>();

        for (SkillProvider provider : ordered) {
            String providerId = provider.getProviderId();
            for (SkillDescriptor descriptor : provider.listSkills()) {
                String skillId = descriptor.id();
                if (skillId == null || skillId.isBlank()) {
                    continue;
                }
                SkillDescriptor previous = merged.get(skillId);
                if (previous != null) {
                    boolean previousIsFile = previous.pluginId() == null;
                    if (previousIsFile) {
                        // file 源优先，插件源的同名技能被本地覆盖
                        log.info("技能 [{}] 被磁盘本地副本覆盖（插件 {} 提供）", skillId, providerId);
                        continue;
                    }
                    throw new IllegalStateException("技能 id 冲突: " + skillId
                            + "（插件 " + previous.pluginId() + " 与 " + providerId + "）");
                }
                merged.put(skillId, descriptor);
                providerIndex.put(providerKeyOf(descriptor), provider);
            }
        }

        this.providers = Map.copyOf(providerIndex);
        this.skills = Map.copyOf(merged);
        long fileCount = merged.values().stream().filter(d -> d.pluginId() == null).count();
        log.info("技能注册中心就绪：file 源 {} 个，插件源 {} 个（共 {} 个技能）",
                fileCount, merged.size() - fileCount, merged.size());
    }

}
