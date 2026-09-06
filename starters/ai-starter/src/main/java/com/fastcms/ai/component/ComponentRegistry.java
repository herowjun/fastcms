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

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 组件注册中心：聚合所有 {@link SectionComponentProvider}，对渲染引擎/AI 统一供给组件
 *
 * <p>注册中心是"组件从哪来"的终点抽象——内置包（classpath 资源）与插件包（PF4J Extension）
 * 在这里被拉平：引擎只认 {@code packId:componentId} 全名，不关心供给方式。</p>
 *
 * <p><b>插件动态装卸（1.2）</b>：插件组件包以 {@code @Extension} 实现 SectionComponentProvider，
 * 由 plugin-starter 的 ExtensionsRegister 注册为 Spring 单例（装卸时增删）。
 * 本类通过 {@link #ensureFresh()} 惰性感知容器内 provider 集合变化（1 秒节流），
 * 插件安装后下一次生成/渲染自动纳入新组件，卸载后自动剔除——无需事件耦合。</p>
 *
 * <p>AI 规划用的"组件菜单"由 {@link #buildManifest()} 生成——只含元数据，不含源码。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class ComponentRegistry implements ApplicationContextAware {

    /**
     * 惰性刷新检查节流间隔（纳秒）：ensureFresh 高频调用（渲染/校验循环内），
     * 实际容器扫描最多每秒一次
     */
    private static final long REFRESH_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private volatile Map<String, RegisteredComponent> components = Map.of();

    private volatile List<SectionComponentProvider> providers = List.of();

    private volatile ApplicationContext applicationContext;

    private volatile long lastCheckNanos = 0L;

    /**
     * 已注册组件：元数据 + 供给方（取 FTL 源码/包资产用）
     */
    public record RegisteredComponent(String fullId, ComponentDescriptor descriptor,
                                      SectionComponentProvider provider) {
    }

    public ComponentRegistry(List<SectionComponentProvider> providers) {
        refresh(providers);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 重建注册表（构造时调用；ensureFresh 检测到容器 provider 集合变化时重建）
     */
    public synchronized void refresh(List<SectionComponentProvider> providers) {
        Map<String, RegisteredComponent> map = new LinkedHashMap<>();
        for (SectionComponentProvider provider : providers) {
            for (ComponentDescriptor descriptor : provider.listComponents()) {
                String fullId = provider.getPackId() + ":" + descriptor.id();
                RegisteredComponent previous = map.put(fullId,
                        new RegisteredComponent(fullId, descriptor, provider));
                if (previous != null) {
                    throw new IllegalStateException("组件 id 冲突: " + fullId
                            + "（" + previous.provider().getPackId() + " 与 " + provider.getPackId() + "）");
                }
            }
        }
        this.providers = List.copyOf(providers);
        this.components = map;
    }

    /**
     * 惰性感知插件装卸：容器内 SectionComponentProvider bean 集合与当前注册集合不一致时重建。
     * 无 ApplicationContext（单元测试直接构造）时不做任何事。
     */
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
            Map<String, SectionComponentProvider> beans =
                    context.getBeansOfType(SectionComponentProvider.class);
            Set<SectionComponentProvider> beanSet = new HashSet<>(beans.values());
            Set<SectionComponentProvider> currentSet = new HashSet<>(providers);
            if (!beanSet.equals(currentSet)) {
                refresh(new ArrayList<>(beans.values()));
            }
        } catch (Exception e) {
            // 容器刷新期（插件装卸进行中）的瞬时异常忽略，下次检查重试
        }
    }

    public List<RegisteredComponent> listComponents() {
        ensureFresh();
        return new ArrayList<>(components.values());
    }

    public Optional<RegisteredComponent> find(String fullId) {
        ensureFresh();
        return Optional.ofNullable(components.get(fullId));
    }

    public ComponentDescriptor getDescriptor(String fullId) {
        ensureFresh();
        RegisteredComponent rc = components.get(fullId);
        return rc == null ? null : rc.descriptor();
    }

    /**
     * 取组件某个变体的 FTL 源码
     */
    public String getTemplateSource(String fullId, String variantId) {
        ensureFresh();
        RegisteredComponent rc = components.get(fullId);
        return rc == null ? null : rc.provider().getTemplateSource(rc.descriptor().id(), variantId);
    }

    /**
     * 找出最接近的组件 id（校验失败时给 AI 提示用）
     */
    public List<String> suggestSimilar(String wrongId) {
        ensureFresh();
        String target = wrongId == null ? "" : wrongId.toLowerCase();
        return components.keySet().stream()
                .filter(id -> id.toLowerCase().contains(target)
                        || target.contains(id.substring(id.indexOf(':') + 1).toLowerCase()))
                .limit(3)
                .toList();
    }

    /**
     * 构建 AI 规划用的组件菜单（纯文本，注入规划 prompt）
     *
     * <p>形态（每组件一行变体、一行槽位说明）：
     * <pre>
     * [tw:hero] 首屏 — 页面第一屏，承载主标题与行动按钮
     *   变体：split（左文右图）、centered（居中大标题）
     *   槽位：title*(string,≤24字)：主标题；subtitle(string,≤60字)：副标题
     * </pre>
     */
    public String buildManifest() {
        ensureFresh();
        StringBuilder sb = new StringBuilder("## 可用组件清单\n\n");
        for (RegisteredComponent rc : components.values()) {
            ComponentDescriptor d = rc.descriptor();
            sb.append("[").append(rc.fullId()).append("] ").append(d.name())
                    .append(" — ").append(d.description()).append("\n");
            if (d.variants() != null && !d.variants().isEmpty()) {
                sb.append("  变体：");
                for (ComponentVariant v : d.variants()) {
                    sb.append(v.id()).append("（").append(v.description()).append("）、");
                }
                sb.setLength(sb.length() - 1);
                sb.append("\n");
            }
            if (!d.safeSlots().isEmpty()) {
                sb.append("  槽位：");
                for (ComponentSlot slot : d.safeSlots()) {
                    sb.append(slot.name())
                            .append(slot.isRequired() ? "*" : "")
                            .append("(").append(slot.type());
                    if (slot.maxLength() != null) {
                        sb.append(",≤").append(slot.maxLength());
                    }
                    sb.append(")：").append(slot.desc()).append("；");
                }
                sb.setLength(sb.length() - 1);
                sb.append("\n");
            }
            if (d.safeAppliesTo() != null && !d.safeAppliesTo().isEmpty()) {
                sb.append("  适用页面：").append(String.join("/", d.safeAppliesTo())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

}
