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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 旧模板确定性升级器：直写 HTML 模板 → 组件化模板（不经 AI）
 *
 * <p>设计动机：旧管线 prompt 强制 AI 生成 _preview_data.json，站点名/副标题等
 * 内容资产本就是结构化数据；主色/风格可用默认值。迁移不需要"理解"，只需要
 * 确定性转换——不调模型、秒级完成、结果可复现。AI 的价值后移到升级后的
 * 微调（补 feature-grid 文案、换风格等，走组件化会话闭环）。</p>
 *
 * <p>流程（先备份后写入，任何失败都不破坏原目录）：</p>
 * <ol>
 *     <li>识别：目录有 .html 且无 _pagespec.json（组件化模板的标志物）</li>
 *     <li>提取内容资产：_preview_data.json 的 seo.website_title / website_sub_title
 *         （兜底 _template.properties 的 template.name，UTF-8 读取）</li>
 *     <li>构建 PageSpec：navbar + hero + article-list + footer（默认主色与 minimal 风格）</li>
 *     <li>校验 + 渲染前备份：旧文本文件复制到同级 _legacy_backup_时间戳 目录</li>
 *     <li>渲染 + 清理：渲染产物落盘，旧文本文件删除（二进制资源保留：可能是用户素材）</li>
 *     <li>预览数据回写：保留旧 _preview_data.json（菜单/文章 mock 数据，预览质量依赖它）</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class LegacyTemplateUpgrader {

    private static final Logger log = LoggerFactory.getLogger(LegacyTemplateUpgrader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 组件化模板标志物（PageSpecRenderer 的落盘事实源）
     */
    private static final String SPEC_FILE = "_pagespec.json";

    /**
     * 旧管线的内容资产文件（站点名/菜单/文章 mock）
     */
    private static final String PREVIEW_DATA_FILE = "_preview_data.json";

    /**
     * 旧模板注册信息文件（siteName 兜底来源）
     */
    private static final String TEMPLATE_PROPERTIES_FILE = "_template.properties";

    /**
     * 升级默认主色（科技蓝，升级后可通过 AI 微调更换）
     */
    private static final String DEFAULT_PRIMARY_COLOR = "#2563eb";

    /**
     * 升级默认风格预设（通用最小风格）
     */
    private static final String DEFAULT_STYLE_PRESET = "minimal";

    /**
     * 二进制资源扩展名（清理时保留：删了无法从组件库恢复）
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf", "eot",
            "mp4", "webm", "mp3", "wav");

    /**
     * 升级结果
     *
     * @param siteName      提取到的站点名（未提取到时为目录名）
     * @param writtenFiles  渲染落盘的文件相对路径
     * @param removedFiles  清理删除的旧文件相对路径
     * @param backupDir     旧文件备份目录（null = 无需备份）
     */
    public record UpgradeResult(String siteName, List<String> writtenFiles,
                                List<String> removedFiles, Path backupDir) {
    }

    private final PageSpecValidator validator;

    private final PageSpecRenderer renderer;

    public LegacyTemplateUpgrader(PageSpecValidator validator, PageSpecRenderer renderer) {
        this.validator = validator;
        this.renderer = renderer;
    }

    /**
     * 是否为可升级的旧模板（有 html 页面且无 _pagespec.json）
     */
    public boolean isLegacy(Path workDir) {
        if (workDir == null || !Files.isDirectory(workDir)
                || Files.isRegularFile(workDir.resolve(SPEC_FILE))) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(workDir)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase().endsWith(".html"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 执行升级（幂等保护：已组件化时抛异常）
     *
     * @param workDir      模板工作目录
     * @param templateName 模板目录名（空时取 workDir 目录名）
     */
    public UpgradeResult upgrade(Path workDir, String templateName) throws IOException {
        if (!isLegacy(workDir)) {
            throw new IllegalArgumentException("当前模板不是可升级的旧模板（可能已组件化或无页面文件）");
        }
        String name = templateName != null && !templateName.isBlank()
                ? templateName : workDir.getFileName().toString();

        // 1. 提取内容资产
        JsonNode previewData = readPreviewData(workDir);
        String siteName = extractSiteName(workDir, previewData, name);
        String subTitle = extractSubTitle(previewData);

        // 2. 构建 PageSpec（升级版编排：导航 + 首屏 + 最新文章 + 页脚）
        PageSpec spec = buildSpec(name, siteName, subTitle);
        List<String> errors = validator.validate(spec);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("升级 PageSpec 校验失败: " + String.join("；", errors));
        }

        // 3. 备份旧文本文件（渲染前执行；渲染失败时原目录不受影响，备份目录无害）
        List<Path> legacyTextFiles = listLegacyTextFiles(workDir);
        Path backupDir = backupLegacyFiles(workDir, legacyTextFiles);

        // 4. 保留旧预览数据（菜单/文章 mock，渲染器会重写该文件，渲染后回写）
        byte[] previewDataBytes = previewData != null
                ? Files.readAllBytes(workDir.resolve(PREVIEW_DATA_FILE)) : null;

        // 5. 渲染
        PageSpecRenderer.RenderResult renderResult = renderer.render(spec, workDir);

        // 6. 回写旧预览数据（覆盖渲染器的最小版，保住菜单/文章 mock；无旧数据时保留渲染器输出）
        if (previewDataBytes != null) {
            Files.write(workDir.resolve(PREVIEW_DATA_FILE), previewDataBytes);
        }

        // 7. 清理旧文本文件（渲染产物之外的；二进制资源保留）
        List<String> removed = cleanupLegacyFiles(workDir, renderResult.writtenFiles());

        log.info("旧模板升级完成: dir={}, siteName={}, written={}, removed={}, backup={}",
                workDir.getFileName(), siteName, renderResult.writtenFiles().size(), removed.size(), backupDir);
        return new UpgradeResult(siteName, renderResult.writtenFiles(), removed, backupDir);
    }

    // ==================== 内容资产提取 ====================

    private JsonNode readPreviewData(Path workDir) {
        Path file = workDir.resolve(PREVIEW_DATA_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("解析 {} 失败，站点名回退 _template.properties: {}", PREVIEW_DATA_FILE, e.getMessage());
            return null;
        }
    }

    private String extractSiteName(Path workDir, JsonNode previewData, String fallback) {
        if (previewData != null) {
            JsonNode title = previewData.path("seo").path("website_title");
            if (title.isTextual() && !title.asString().isBlank()) {
                return title.asString().trim();
            }
        }
        // 兜底：_template.properties 的 template.name（UTF-8 手读，历史教训：
        // Properties.load(InputStream) 固定 ISO-8859-1 会把中文读成乱码）
        Path propFile = workDir.resolve(TEMPLATE_PROPERTIES_FILE);
        if (Files.isRegularFile(propFile)) {
            try {
                for (String line : Files.readAllLines(propFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("template.name=")) {
                        String value = trimmed.substring("template.name=".length()).trim();
                        if (!value.isEmpty()) {
                            return value;
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("读取 {} 失败: {}", TEMPLATE_PROPERTIES_FILE, e.getMessage());
            }
        }
        return fallback;
    }

    private String extractSubTitle(JsonNode previewData) {
        if (previewData != null) {
            JsonNode sub = previewData.path("seo").path("website_sub_title");
            if (sub.isTextual() && !sub.asString().isBlank()) {
                return sub.asString().trim();
            }
        }
        return null;
    }

    // ==================== PageSpec 构建 ====================

    /**
     * 构建升级版 PageSpec
     *
     * <p>编排取舍：只放有数据来源的组件——navbar/footer 的品牌与菜单（菜单运行期
     * 走 menuTag，预览走 _preview_data.json mock）、hero 的标题/副标题（SEO 资产）、
     * article-list（运行期走 articleListTag）。feature-grid 这类需要创作文案的
     * 组件不放——那是 AI 微调的活，确定性升级不硬造。</p>
     */
    private PageSpec buildSpec(String templateName, String siteName, String subTitle) {
        java.util.Map<String, Object> navData = java.util.Map.of("brand", siteName);
        java.util.Map<String, Object> footerData = java.util.Map.of("brand", siteName);

        java.util.Map<String, Object> heroData = new java.util.LinkedHashMap<>();
        heroData.put("title", siteName);
        if (subTitle != null) {
            heroData.put("subtitle", subTitle);
        }
        heroData.put("ctaLabel", "浏览内容");
        heroData.put("ctaHref", "/article/category/1");

        java.util.Map<String, Object> articleData = new java.util.LinkedHashMap<>();
        articleData.put("title", "最新动态");
        articleData.put("count", 6);

        List<SectionSpec> indexSections = List.of(
                new SectionSpec("nav", "tw:navbar", "sticky", navData),
                new SectionSpec("hero", "tw:hero", "centered", heroData),
                new SectionSpec("articles", "tw:article-list", "cards", articleData),
                new SectionSpec("footer", "tw:footer", "simple", footerData));
        List<SectionSpec> frameSections = List.of(
                new SectionSpec("nav", "tw:navbar", "sticky", navData),
                new SectionSpec("footer", "tw:footer", "simple", footerData));

        return new PageSpec(
                PageSpec.SPEC_VERSION,
                BuiltinTailwindPackProvider.FOUNDATION,
                templateName,
                siteName,
                null,
                DEFAULT_STYLE_PRESET,
                DEFAULT_PRIMARY_COLOR,
                java.util.Map.of(
                        PageSpec.PAGE_INDEX, new PageSpecPage(indexSections),
                        PageSpec.PAGE_ARTICLE_LIST, new PageSpecPage(frameSections),
                        PageSpec.PAGE_ARTICLE, new PageSpecPage(frameSections),
                        PageSpec.PAGE_PAGE, new PageSpecPage(frameSections)));
    }

    // ==================== 备份与清理 ====================

    /**
     * 列出将被清理的旧文本文件（排除二进制资源）
     */
    private List<Path> listLegacyTextFiles(Path workDir) throws IOException {
        try (Stream<Path> stream = Files.walk(workDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isBinaryFile(p))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * 备份旧文本文件到同级目录 {@code <模板名>_legacy_backup_<时间戳>}
     *
     * @return 备份目录；无文件可备份时返回 null
     */
    private Path backupLegacyFiles(Path workDir, List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return null;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path backupDir = workDir.resolveSibling(
                workDir.getFileName() + "_legacy_backup_" + timestamp);
        for (Path file : files) {
            Path target = backupDir.resolve(workDir.relativize(file));
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("旧模板文件已备份: {} 个文件 -> {}", files.size(), backupDir);
        return backupDir;
    }

    /**
     * 清理旧文本文件（渲染产物之外的；二进制资源保留在原位）
     */
    private List<String> cleanupLegacyFiles(Path workDir, List<String> keepFiles) {
        Set<String> keep = new HashSet<>(keepFiles);
        List<String> removed = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workDir)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = workDir.relativize(p).toString().replaceAll("\\\\", "/");
                        return !keep.contains(rel) && !isBinaryFile(p);
                    })
                    .collect(java.util.stream.Collectors.toList());
            for (Path p : candidates) {
                String rel = workDir.relativize(p).toString().replaceAll("\\\\", "/");
                if (Files.deleteIfExists(p)) {
                    removed.add(rel);
                }
            }
        } catch (IOException e) {
            log.warn("旧模板清理扫描失败: {}", workDir, e);
        }
        return removed;
    }

    private boolean isBinaryFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf((char) 46);
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return BINARY_EXTENSIONS.contains(ext);
    }

}