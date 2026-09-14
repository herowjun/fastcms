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

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * classpath 技能源基类：插件贡献 skill 的标准姿势
 *
 * <p>插件工程只需两步：</p>
 * <ol>
 *     <li>插件 jar 内按 Agent Skills 标准放资源：{@code src/main/resources/skills/<slug>/SKILL.md}</li>
 *     <li>写一个子类标注 {@link Extension @Extension} 并实现 {@link #getProviderId()}
 *         （返回插件 id），例如：</li>
 * </ol>
 * <pre>
 * {@code @Extension}
 * public class ArticleSkillsProvider extends ClasspathSkillProvider {
 *     public String getProviderId() { return "article-skills-plugin"; }
 * }
 * </pre>
 *
 * <p><b>关键实现细节</b>（沿用 TwPlusComponentPackProvider 的经验）：本类运行在插件类
 * 加载器中，资源解析必须显式绑定 {@code getClass().getClassLoader()}（插件 jar 内资源），
 * 禁止用默认类加载器（会解析到主应用 classpath 而找不到插件资源）。资源在构造时读取
 * 一次并缓存——插件 jar 运行期只读，实例随插件卸载销毁。</p>
 *
 * <p>skillId 为 {@code {providerId}/{目录名}}，目录名即 slug（小写字母数字连字符）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public abstract class ClasspathSkillProvider implements SkillProvider {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkillProvider.class);

    private static final String RESOURCE_PATTERN = "classpath*:skills/*/SKILL.md";

    private final List<SkillDescriptor> descriptors = new ArrayList<>();

    private final Map<String, String> contentCache = new ConcurrentHashMap<>();

    protected ClasspathSkillProvider() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        try {
            Resource[] resources = resolver.getResources(RESOURCE_PATTERN);
            for (Resource resource : resources) {
                String skillMd = new String(FileCopyUtils.copyToByteArray(resource.getInputStream()),
                        StandardCharsets.UTF_8);
                String slug = extractSlug(resource.getURL().getPath());
                String skillId = getProviderId() + "/" + slug;
                SkillMdParser.ParsedSkill parsed = SkillMdParser.parse(skillMd);
                if (parsed.valid()) {
                    descriptors.add(new SkillDescriptor(skillId, parsed.name(), parsed.description(),
                            parsed.version(), SkillDescriptor.SOURCE_PLUGIN, getProviderId(), false, null));
                    contentCache.put(skillId, parsed.content());
                } else {
                    log.warn("技能 [{}] SKILL.md 无效，已跳过: {}", skillId, parsed.error());
                    descriptors.add(new SkillDescriptor(skillId, parsed.name(), parsed.description(),
                            parsed.version(), SkillDescriptor.SOURCE_PLUGIN, getProviderId(), false, parsed.error()));
                }
            }
            descriptors.sort(Comparator.comparing(SkillDescriptor::id));
            log.info("插件技能包[{}]加载完成，共 {} 个技能", getProviderId(), descriptors.size());
        } catch (IOException e) {
            throw new IllegalStateException("插件技能包加载失败: " + getProviderId(), e);
        }
    }

    /**
     * 从资源 URL 提取技能 slug（skills/{slug}/SKILL.md 的中间段）
     */
    private String extractSlug(String resourcePath) {
        // 形如 .../skills/article-topic/SKILL.md（jar 内或文件系统路径均适用）
        int skillsIdx = resourcePath.lastIndexOf("skills/");
        String tail = resourcePath.substring(skillsIdx + "skills/".length());
        return tail.substring(0, tail.lastIndexOf('/'));
    }

    @Override
    public List<SkillDescriptor> listSkills() {
        return List.copyOf(descriptors);
    }

    @Override
    public String loadContent(String skillId) {
        return skillId == null ? null : contentCache.get(skillId);
    }

}
