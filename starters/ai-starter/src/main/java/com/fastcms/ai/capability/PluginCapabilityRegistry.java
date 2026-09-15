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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 插件能力注册中心：聚合所有 {@link CapabilityProvider}，对 prompt / AI Tool / 渲染器统一供给能力契约
 *
 * <p>与 {@code ComponentRegistry} 同构：</p>
 * <ul>
 *     <li>构造时收集容器内全部 provider（core bean + 插件 @Extension bean）</li>
 *     <li>{@link #ensureFresh()} 惰性感知插件装卸（1 秒节流），插件卸载后能力自动剔除，
 *         AI 不会再看到已卸载插件的能力，也不会生成调用它的死代码</li>
 *     <li>L1 摘要（{@link #buildManifest()}）常驻 system prompt，每能力 2 行，token 可控</li>
 *     <li>L2 详情（{@link #buildCapabilityDetail(String)}）由 {@code get_capability_detail}
 *         AI Tool 按需拉取，仅注入当轮</li>
 * </ul>
 *
 * <p><b>可用性三态</b>（L1 摘要直接标注）：业务能力声明 {@code requiresChannel}（如
 * "payment:*"）时，注册中心校验是否存在匹配的渠道能力；无匹配时标注
 * missing-channel，AI 会提示用户先安装支付渠道插件。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class PluginCapabilityRegistry implements ApplicationContextAware {

    private static final long REFRESH_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private volatile Map<String, RegisteredCapability> capabilities = Map.of();

    private volatile List<CapabilityProvider> providers = List.of();

    private volatile ApplicationContext applicationContext;

    private volatile long lastCheckNanos = 0L;

    /**
     * 已注册能力：描述符 + 供给方（读 snippet 源码用）
     */
    public record RegisteredCapability(String capabilityId, CapabilityDescriptor descriptor,
                                       CapabilityProvider provider) {

        public String pluginId() {
            return provider == null ? null : provider.getPluginId();
        }

        public boolean gated() {
            String pluginId = pluginId();
            return pluginId != null && !pluginId.isBlank() && !descriptor().isCore();
        }
    }

    /**
     * 能力可用性三态
     */
    public enum Availability {
        READY("ready"),
        MISSING_CHANNEL("missing-channel");

        private final String label;

        Availability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public PluginCapabilityRegistry(List<CapabilityProvider> providers) {
        refresh(providers);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public synchronized void refresh(List<CapabilityProvider> providers) {
        Map<String, RegisteredCapability> map = new LinkedHashMap<>();
        for (CapabilityProvider provider : providers) {
            for (CapabilityDescriptor descriptor : provider.listCapabilities()) {
                String id = descriptor.capabilityId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                RegisteredCapability previous = map.put(id, new RegisteredCapability(id, descriptor, provider));
                if (previous != null) {
                    throw new IllegalStateException("能力 id 冲突: " + id
                            + "（" + previous.pluginId() + " 与 " + provider.getPluginId() + "）");
                }
            }
        }
        this.providers = List.copyOf(providers);
        this.capabilities = map;
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
            Map<String, CapabilityProvider> beans = context.getBeansOfType(CapabilityProvider.class);
            Set<CapabilityProvider> beanSet = new HashSet<>(beans.values());
            Set<CapabilityProvider> currentSet = new HashSet<>(providers);
            if (!beanSet.equals(currentSet)) {
                refresh(new ArrayList<>(beans.values()));
            }
        } catch (Exception e) {
            // 容器刷新期（插件装卸进行中）的瞬时异常忽略，下次检查重试
        }
    }

    public List<RegisteredCapability> listCapabilities() {
        ensureFresh();
        return new ArrayList<>(capabilities.values());
    }

    public Optional<RegisteredCapability> find(String capabilityId) {
        ensureFresh();
        return Optional.ofNullable(capabilities.get(capabilityId));
    }

    /**
     * 读取 snippet 源码（含 {{PARAM}} 占位符）
     */
    public String getSnippetSource(String capabilityId, String snippetId) {
        ensureFresh();
        RegisteredCapability rc = capabilities.get(capabilityId);
        return rc == null ? null : rc.provider().getSnippetSource(capabilityId, snippetId);
    }

    /**
     * 业务能力（requiresChannel）的渠道依赖是否满足
     */
    public Availability availabilityOf(RegisteredCapability rc) {
        ensureFresh();
        String requires = rc.descriptor().requiresChannel();
        if (requires == null || requires.isBlank()) {
            return Availability.READY;
        }
        for (RegisteredCapability candidate : capabilities.values()) {
            if (matchesChannel(candidate.descriptor().capabilityId(), requires)) {
                return Availability.READY;
            }
        }
        return Availability.MISSING_CHANNEL;
    }

    /**
     * 渠道依赖模式匹配："payment:*" 匹配 "payment:wxpay" / "payment:alipay"
     */
    private boolean matchesChannel(String capabilityId, String pattern) {
        if (capabilityId == null || pattern == null) {
            return false;
        }
        if (pattern.endsWith(":*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return capabilityId.startsWith(prefix);
        }
        return pattern.equals(capabilityId);
    }

    /**
     * 已注册能力声明的全部端点路径前缀（渲染期路径校验白名单）
     */
    public Set<String> registeredEndpointPrefixes() {
        ensureFresh();
        Set<String> prefixes = new java.util.LinkedHashSet<>();
        for (RegisteredCapability rc : capabilities.values()) {
            for (CapabilityEndpoint endpoint : rc.descriptor().safeEndpoints()) {
                prefixes.add(endpoint.pathPrefix());
            }
        }
        return prefixes;
    }

    /**
     * L1 摘要清单（常驻 system prompt）：每能力 2 行，含可用性标注
     *
     * <p>无已注册能力时返回空串（prompt 不出现该段）。</p>
     */
    public String buildManifest() {
        ensureFresh();
        if (capabilities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (RegisteredCapability rc : capabilities.values()) {
            CapabilityDescriptor d = rc.descriptor();
            Availability availability = availabilityOf(rc);
            sb.append("[").append(d.capabilityId()).append("] ").append(d.name())
                    .append(" — ").append(d.description());
            if (d.requiresChannel() != null && !d.requiresChannel().isBlank()) {
                sb.append("（需搭配 ").append(d.requiresChannel()).append(" 渠道）");
            }
            sb.append(" (").append(availability.label()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * L2 详细契约（get_capability_detail Tool 按需拉取）：端点契约 + 认证流 + 前端流程 + snippet 清单
     *
     * @return 能力不存在时返回提示文本（AI 可据此纠正 capabilityId）
     */
    public String buildCapabilityDetail(String capabilityId) {
        ensureFresh();
        RegisteredCapability rc = capabilities.get(capabilityId);
        if (rc == null) {
            return "能力不存在: " + capabilityId + "。可用能力: "
                    + String.join(", ", capabilities.keySet());
        }
        CapabilityDescriptor d = rc.descriptor();
        StringBuilder sb = new StringBuilder();
        sb.append("能力: ").append(d.capabilityId()).append("（").append(d.name()).append("）\n");
        if (d.description() != null) {
            sb.append("描述: ").append(d.description()).append("\n");
        }
        sb.append("可用性: ").append(availabilityOf(rc).label()).append("\n");
        if (rc.gated()) {
            sb.append("提供插件: ").append(rc.pluginId()).append("\n");
        }

        if (!d.safeAuthFlow().isEmpty()) {
            sb.append("\n## 认证流\n");
            d.safeAuthFlow().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }

        if (!d.safeEndpoints().isEmpty()) {
            sb.append("\n## 接口契约（AI 写 JS 的唯一依据，禁止编造路径与字段）\n");
            for (CapabilityEndpoint e : d.safeEndpoints()) {
                sb.append("- ").append(e.method()).append(" ").append(e.path());
                sb.append("  [").append(e.id()).append("]");
                sb.append(e.requiresAuth() ? " 需登录" : " 免登录").append("\n");
                if (e.contentType() != null) {
                    sb.append("  请求体: ").append(e.contentType()).append("\n");
                }
                if (e.params() != null && !e.params().isEmpty()) {
                    e.params().forEach((k, v) -> sb.append("  参数 ").append(k).append(": ").append(v).append("\n"));
                }
                if (e.response() != null && !e.response().isEmpty()) {
                    e.response().forEach((k, v) -> sb.append("  响应 ").append(k).append(": ").append(v).append("\n"));
                }
                if (e.usageHint() != null && !e.usageHint().isBlank()) {
                    sb.append("  调用提示: ").append(e.usageHint()).append("\n");
                }
            }
        }

        if (!d.safeFrontendFlow().isEmpty()) {
            sb.append("\n## 前端交互流程\n");
            for (String step : d.safeFrontendFlow()) {
                sb.append("- ").append(step).append("\n");
            }
        }

        if (!d.safeCompositionEvents().isEmpty()) {
            sb.append("\n## 组合事件协议（渠道/业务能力跨 snippet 联动，事件挂 document）\n");
            d.safeCompositionEvents().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }

        if (!d.safeErrorHandling().isEmpty()) {
            sb.append("\n## 异常处理\n");
            d.safeErrorHandling().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }

        if (!d.safeSnippets().isEmpty()) {
            sb.append("\n## 官方 snippet（优先引用，质量有保证；custom-html 手写仅作兜底）\n");
            for (CapabilitySnippet snippet : d.safeSnippets()) {
                sb.append("- snippetId: ").append(snippet.id()).append(" — ").append(snippet.description()).append("\n");
                if (!snippet.safeParams().isEmpty()) {
                    sb.append("  参数: ").append(String.join(", ", snippet.safeParams())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 检测文本（模板 HTML）中是否已集成某能力（旧模板无 PageSpec 时的兜底识别）
     *
     * @return 命中的能力 id 清单
     */
    public List<String> detectIntegrated(String html) {
        ensureFresh();
        List<String> hits = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return hits;
        }
        for (RegisteredCapability rc : capabilities.values()) {
            for (String pattern : rc.descriptor().safeDetectionPatterns()) {
                try {
                    if (Pattern.compile(pattern).matcher(html).find()) {
                        hits.add(rc.capabilityId());
                        break;
                    }
                } catch (PatternSyntaxException ignored) {
                    // 声明文件里的非法正则跳过（不影响其余能力识别）
                }
            }
        }
        return hits;
    }

}
