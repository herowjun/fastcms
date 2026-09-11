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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主应用内置能力供给方：声明 core:* 能力（订单 / 扫码支付等主应用统一接口）
 *
 * <p><b>设计动机</b>：主应用接口（/fastcms/api/client/...）与插件接口统一建模为能力，
 * 不做特殊通道——渲染期路径校验白名单、AI 端点契约查询对所有能力一视同仁。
 * 支付渠道插件（wechat-pay-plugin 等）只激活 platform 参数，真正的接口在主应用，
 * 由渠道能力声明组合本类提供的 core 能力并给出完整前端流程。</p>
 *
 * <p>声明文件位于 ai-starter classpath：{@code capabilities/core/*.json}。
 * path 字段写前端可见真实路径（含 context-path /fastcms），与
 * {@code OrderApi} / {@code PaymentApi} 的实际行为对齐。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class CoreCapabilityProvider implements CapabilityProvider {

    private static final Logger log = LoggerFactory.getLogger(CoreCapabilityProvider.class);

    private static final String BASE_PATH = "capabilities/core";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<CapabilityDescriptor> descriptors = new ArrayList<>();

    private final Map<String, String> snippetCache = new ConcurrentHashMap<>();

    public CoreCapabilityProvider() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:" + BASE_PATH + "/*.json");
            for (Resource resource : resources) {
                String json = new String(FileCopyUtils.copyToByteArray(resource.getInputStream()),
                        StandardCharsets.UTF_8);
                descriptors.add(MAPPER.readValue(json, CapabilityDescriptor.class));
            }
            descriptors.sort(Comparator.comparing(CapabilityDescriptor::capabilityId));
            log.info("内置能力[core]加载完成，共 {} 个能力", descriptors.size());
        } catch (IOException e) {
            throw new IllegalStateException("内置能力加载失败: " + BASE_PATH, e);
        }
    }

    @Override
    public String getPluginId() {
        // core 能力来自主应用，恒可用，不参与 hasPlugin 门控
        return null;
    }

    @Override
    public List<CapabilityDescriptor> listCapabilities() {
        return descriptors;
    }

    @Override
    public String getSnippetSource(String capabilityId, String snippetId) {
        // core 能力暂无官方 snippet（组合流程由渠道能力声明），后续可按需补充
        return null;
    }
}
