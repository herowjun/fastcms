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
 * 内置 Tailwind v4 组件包（首批 5 个组件：navbar / hero / feature-grid / article-list / footer）
 *
 * <p>以 ai-starter classpath 资源目录 {@code components/tw/} 供给，与本接口解耦：
 * 将来商业化组件包以 PF4J 插件实现同一接口即可并存，引擎零改动。</p>
 *
 * <p>资源布局（打包进 ai-starter jar）：</p>
 * <pre>
 * components/tw/
 * ├── navbar/component.json + variants/*.ftl
 * ├── hero/component.json + variants/*.ftl
 * ├── ...
 * └── static/pack.css        ← Tailwind CLI 预编译的地基 CSS（发版组件包时构建一次）
 * </pre>
 *
 * <p>历史教训：资源读取统一 UTF-8 显式解码（禁止依赖平台默认编码），
 * component.json 用 Jackson 3 解析（{@code tools.jackson.*}）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class BuiltinTailwindPackProvider implements SectionComponentProvider {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTailwindPackProvider.class);

    public static final String PACK_ID = "tw";
    public static final String FOUNDATION = "tailwind-v4";

    private static final String BASE_PATH = "components/" + PACK_ID;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ComponentDescriptor> descriptors = new ArrayList<>();

    /**
     * FTL 源码缓存（classpath 资源在 jar 内，读一次缓存，重复渲染零 IO 开销）
     */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public BuiltinTailwindPackProvider() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(
                    "classpath*:" + BASE_PATH + "/*/component.json");
            for (Resource resource : resources) {
                String json = new String(FileCopyUtils.copyToByteArray(resource.getInputStream()),
                        StandardCharsets.UTF_8);
                descriptors.add(MAPPER.readValue(json, ComponentDescriptor.class));
            }
            descriptors.sort(Comparator.comparing(ComponentDescriptor::id));
            log.info("内置组件包[{}]加载完成，共 {} 个组件", PACK_ID, descriptors.size());
        } catch (IOException e) {
            throw new IllegalStateException("内置组件包加载失败: " + BASE_PATH, e);
        }
    }

    @Override
    public String getPackId() {
        return PACK_ID;
    }

    @Override
    public String getFoundation() {
        return FOUNDATION;
    }

    @Override
    public List<ComponentDescriptor> listComponents() {
        return descriptors;
    }

    @Override
    public String getTemplateSource(String componentId, String variantId) {
        if (componentId == null || variantId == null) {
            return null;
        }
        String path = BASE_PATH + "/" + componentId + "/variants/" + variantId + ".ftl";
        return templateCache.computeIfAbsent(path, k -> readResource(path));
    }

    @Override
    public byte[] getPackAsset(String assetPath) {
        if (assetPath == null) {
            return null;
        }
        String path = BASE_PATH + "/" + assetPath;
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + path);
        try {
            if (!resource.exists()) {
                return null;
            }
            return FileCopyUtils.copyToByteArray(resource.getInputStream());
        } catch (IOException e) {
            log.warn("读取组件包资产失败: {}", path, e);
            return null;
        }
    }

    private String readResource(String path) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:" + path);
            if (!resource.exists()) {
                log.warn("组件模板不存在: {}", path);
                return null;
            }
            return new String(FileCopyUtils.copyToByteArray(resource.getInputStream()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取组件模板失败: {}", path, e);
            return null;
        }
    }

}
