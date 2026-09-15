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

import com.fastcms.ai.autoconfigure.FastcmsAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 磁盘技能库（file 源）：扫描 {@code fastcms.ai.skill.root}（默认 ~/fastcms/skills）
 *
 * <p>轻量自定义通道：用户把技能目录（含 SKILL.md）放进根目录即安装，记事本可改，
 * 与插件正式分发通道互补。技能库为小规模目录（个位数到几十个），刷新采用
 * "1 秒节流 + 全量重扫"——简单可靠，目录增删与文件修改即时可见，
 * 与 PluginCapabilityRegistry 的装卸感知同思路，不依赖 WatchService。</p>
 *
 * <p>skillId 为裸 slug（目录名）；与插件技能（{pluginId}/{slug}）天然不冲突。
 * 根目录不存在时自动创建；坏 SKILL.md 带错误标记进列表，不阻断启动。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Component
public class FileSkillRepository implements SkillProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSkillRepository.class);

    private static final long REFRESH_CHECK_INTERVAL_MILLIS = 1_000L;

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    private static final String SKILL_FILE = "SKILL.md";

    private final Path root;

    private final Map<String, CachedSkill> cache = new ConcurrentHashMap<>();

    private volatile long lastRefreshMillis = 0L;

    /**
     * 缓存条目：描述符 + 正文（刷新时整条重建，读取无锁）
     */
    private record CachedSkill(SkillDescriptor descriptor, String content) {
    }

    public FileSkillRepository(FastcmsAiProperties properties) {
        this.root = Path.of(properties.getSkill().getRoot());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("技能库目录创建失败: {}", root, e);
        }
        refresh();
    }

    @Override
    public String getProviderId() {
        return FILE_SOURCE;
    }

    @Override
    public List<SkillDescriptor> listSkills() {
        ensureFresh();
        return cache.values().stream()
                .map(CachedSkill::descriptor)
                .sorted(Comparator.comparing(SkillDescriptor::id))
                .toList();
    }

    @Override
    public String loadContent(String skillId) {
        ensureFresh();
        CachedSkill cached = skillId == null ? null : cache.get(skillId);
        return cached == null || cached.descriptor().error() != null ? null : cached.content();
    }

    /**
     * 根目录路径（管理端展示用）
     */
    public Path getRoot() {
        return root;
    }

    private void ensureFresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < REFRESH_CHECK_INTERVAL_MILLIS) {
            return;
        }
        synchronized (this) {
            if (now - lastRefreshMillis < REFRESH_CHECK_INTERVAL_MILLIS) {
                return;
            }
            lastRefreshMillis = now;
            refresh();
        }
    }

    private synchronized void refresh() {
        Map<String, CachedSkill> fresh = new java.util.LinkedHashMap<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory)
                        .map(dir -> dir.getFileName().toString())
                        .sorted()
                        .forEach(slug -> {
                            CachedSkill parsed = parseSlug(slug);
                            if (parsed != null) {
                                fresh.put(slug, parsed);
                            }
                        });
            } catch (IOException e) {
                log.warn("技能库目录扫描失败: {}", root, e);
                return;
            }
        }
        cache.clear();
        cache.putAll(fresh);
    }

    private CachedSkill parseSlug(String slug) {
        Path skillMd = root.resolve(slug).resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            // 目录存在但不是技能（用户放了无关文件），跳过不展示
            return null;
        }
        try {
            String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
            SkillMdParser.ParsedSkill parsed = SkillMdParser.parse(raw);
            if (!SLUG_PATTERN.matcher(slug).matches()) {
                return new CachedSkill(new SkillDescriptor(slug, parsed.name(), parsed.description(),
                        parsed.version(), SkillDescriptor.SOURCE_FILE, null, true,
                        "目录名不符合 slug 规范 ^[a-z0-9][a-z0-9-]{1,63}$"), parsed.content());
            }
            return new CachedSkill(new SkillDescriptor(slug, parsed.name(), parsed.description(),
                    parsed.version(), SkillDescriptor.SOURCE_FILE, null, true, parsed.error()),
                    parsed.content());
        } catch (IOException e) {
            return new CachedSkill(new SkillDescriptor(slug, null, null, null,
                    SkillDescriptor.SOURCE_FILE, null, true, "SKILL.md 读取失败: " + e.getMessage()), null);
        }
    }

}
