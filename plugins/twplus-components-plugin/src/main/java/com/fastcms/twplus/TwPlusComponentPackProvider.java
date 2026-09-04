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
package com.fastcms.twplus;

import com.fastcms.ai.component.ComponentDescriptor;
import com.fastcms.ai.component.SectionComponentProvider;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
 * twx 组件包（插件供给）：内容区组件扩充
 *
 * <p>首批 10 个组件：page-hero / content-section / stats / logo-cloud / testimonial /
 * team-grid / gallery / faq / cta-banner / contact。与内置包（tw）同地基（tailwind-v4）
 * 共存——同一模板可混用两包组件，渲染引擎按包分别落盘 pack-tw.css 与 pack-twx.css。</p>
 *
 * <p>资源布局（打入插件 jar）：</p>
 * <pre>
 * components/twx/
 * ├── page-hero/component.json + variants/*.ftl
 * ├── ...
 * └── static/pack.css      ← 本包独立地基 CSS（自定义 twx-* 组件类，CSS 变量取自 tokens.css）
 * </pre>
 *
 * <p><b>与内置包的关键差异</b>：本类运行在插件类加载器中，资源解析必须显式绑定
 * {@code getClass().getClassLoader()}（插件 jar 内资源），禁止用默认类加载器
 * （会解析到主应用 classpath 而找不到插件资源）。组件样式采用自定义
 * {@code twx-*} 类（见 pack.css 的 {@code @layer components}），不依赖内置包的
 * 工具类集合，包级自治。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Extension
public class TwPlusComponentPackProvider implements SectionComponentProvider {

    private static final Logger log = LoggerFactory.getLogger(TwPlusComponentPackProvider.class);

    public static final String PACK_ID = "twx";
    public static final String FOUNDATION = "tailwind-v4";

    private static final String BASE_PATH = "components/" + PACK_ID;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ComponentDescriptor> descriptors = new ArrayList<>();

    /**
     * FTL 源码缓存（插件 jar 内资源读一次缓存；插件卸载时本实例随之销毁）
     */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public TwPlusComponentPackProvider() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        try {
            Resource[] resources = resolver.getResources(
                    "classpath*:" + BASE_PATH + "/*/component.json");
            for (Resource resource : resources) {
                String json = new String(FileCopyUtils.copyToByteArray(resource.getInputStream()),
                        StandardCharsets.UTF_8);
                descriptors.add(MAPPER.readValue(json, ComponentDescriptor.class));
            }
            descriptors.sort(Comparator.comparing(ComponentDescriptor::id));
            log.info("插件组件包[{}]加载完成，共 {} 个组件", PACK_ID, descriptors.size());
        } catch (IOException e) {
            throw new IllegalStateException("插件组件包加载失败: " + BASE_PATH, e);
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
        try {
            Resource resource = new PathMatchingResourcePatternResolver(getClass().getClassLoader())
                    .getResource("classpath:" + path);
            if (!resource.exists()) {
                return null;
            }
            return FileCopyUtils.copyToByteArray(resource.getInputStream());
        } catch (IOException e) {
            log.warn("读取插件组件包资产失败: {}", path, e);
            return null;
        }
    }

    private String readResource(String path) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver(getClass().getClassLoader())
                    .getResource("classpath:" + path);
            if (!resource.exists()) {
                log.warn("插件组件模板不存在: {}", path);
                return null;
            }
            return new String(FileCopyUtils.copyToByteArray(resource.getInputStream()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取插件组件模板失败: {}", path, e);
            return null;
        }
    }

}
