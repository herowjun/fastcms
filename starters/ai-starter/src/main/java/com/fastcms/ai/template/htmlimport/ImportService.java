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
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template.htmlimport;

import com.fastcms.ai.autoconfigure.FastcmsAiProperties;
import com.fastcms.ai.service.IAiTemplateFileService;
import com.fastcms.ai.service.IAiTemplateMessageService;
import com.fastcms.ai.template.AiTemplateConstants;
import com.fastcms.ai.template.design.DesignSseSink;
import com.fastcms.ai.template.design.MockupConverter;
import com.fastcms.ai.template.design.MockupOrchestrator;
import com.fastcms.entity.AiTemplateSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HTML 导入 ingest 主流程（确定性，零 AI，见 doc/wiki/html-import-to-template-design.md §3/§5）
 *
 * <p>职责：解压（防 slip）→ 文件分类 → pageKey 推导 → 逐页归一化（{@link HtmlNormalizer}）
 * → 资产归位接线 → 写 {@code design/*.html} + {@code plan.json}（state=CONVERTING，
 * 供 orchestrator 断点续传）+ {@code import-meta.json}（转化后接线依据）。</p>
 *
 * <p><b>触发拓扑（§5.4）</b>：本服务同步完成 ingest（秒级）即返回，转化由既有 chatStream
 * 驱动（编排器从 CONVERTING 起步复用 {@link MockupConverter} 五步转化）；
 * 转化完成后由 {@link #postConvertWiring} 注入导入站外部 CSS 到 layout head 并注册资产文件。</p>
 *
 * <p><b>安全（§7）</b>：zip slip（解压路径 normalize 后校验在 staging 内）、扩展名白名单、
 * 包大小与文件数上限（可配置 {@code fastcms.ai.template.import.*}）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 导入资产在模板静态目录下的命名空间（与模板自产 static/css 等隔离，防覆盖） */
    static final String ASSET_DIR = "static/import";
    /** 转化后 CSS 接线注入 _layout.html 的幂等标记 */
    private static final String CSS_INJECT_MARKER = "fastcms-import-css";
    /** 一次性解压暂存目录（ingest 结束即清理） */
    private static final String STAGING_DIR = "import-src";

    private final FastcmsAiProperties aiProperties;
    private final IAiTemplateMessageService messageService;
    private final IAiTemplateFileService fileService;

    public ImportService(FastcmsAiProperties aiProperties,
                         IAiTemplateMessageService messageService,
                         IAiTemplateFileService fileService) {
        this.aiProperties = aiProperties;
        this.messageService = messageService;
        this.fileService = fileService;
    }

    /**
     * 导入结果（前端展示 + 触发转化的依据）
     *
     * @param pageCount  导入页面数
     * @param assetCount 归位资产文件数（css/js/图片/字体）
     * @param notes      显式报告（首页缺失/文件冲突/降级标注等，绝不静默）
     */
    public record ImportResult(int pageCount, int assetCount, List<String> notes) {
    }

    /** import-meta.json（design/ 下，转化后接线与诊断依据） */
    private record ImportMeta(String version, String source, List<String> css, List<String> js,
                              List<String> pages, List<String> notes) {
    }

    // ==================== ingest 主入口 ====================

    /**
     * 导入 HTML（单文件或 zip 站包）：同步完成解压 + 归一化 + plan.json 落盘（CONVERTING），
     * 返回报告；随后由前端走既有 chat 触发转化（编排器 CONVERTING 起步）。
     *
     * <p>幂等：重复调用按新一次导入处理（清掉上一轮导入产物后重建）。</p>
     */
    public ImportResult importHtml(AiTemplateSession session, Path workDir, MultipartFile file) throws IOException {
        if (!AiTemplateConstants.isImportMode(session)) {
            throw new IllegalArgumentException("该会话不是 HTML 导入模式，无法导入");
        }
        if (StringUtils.hasText(session.getTemplateId())) {
            throw new IllegalArgumentException("导入仅支持新建模板会话");
        }
        FastcmsAiProperties.Template.Import cfg = aiProperties.getTemplate().getImportConfig();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 HTML 文件或 zip 站包");
        }
        if (file.getSize() > cfg.getMaxZipSize()) {
            throw new IllegalArgumentException("文件超过大小上限（" + humanSize(cfg.getMaxZipSize()) + "）");
        }

        Path staging = workDir.resolve(STAGING_DIR);
        deleteDirectory(staging);
        Files.createDirectories(staging);

        List<String> htmlFiles = new ArrayList<>();
        Set<String> assetFiles = new LinkedHashSet<>();
        try {
            if (isZip(file)) {
                extractZip(file, staging, cfg);
                classify(staging, staging, htmlFiles, assetFiles);
            } else {
                String name = sanitizeFileName(file.getOriginalFilename());
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".html")) {
                    // zip 嗅探已排除压缩包：此处必须真是 HTML 才有意义（§4.3 非法输入不猜）
                    throw new IllegalArgumentException("仅支持 .html 文件或 zip 包（当前文件不是合法 HTML）");
                }
                Path target = staging.resolve(name);
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                htmlFiles.add(name);
            }
            if (htmlFiles.isEmpty()) {
                throw new IllegalArgumentException("压缩包内未找到 HTML 文件");
            }
            return doIngest(session, workDir, staging, htmlFiles, assetFiles, file.getOriginalFilename());
        } finally {
            deleteDirectory(staging);
        }
    }

    /** ingest 核心：pageKey 推导 → 归一化落盘 → 资产归位 → plan.json / import-meta.json */
    private ImportResult doIngest(AiTemplateSession session, Path workDir, Path staging,
                                  List<String> htmlFiles, Set<String> assetFiles,
                                  String sourceName) throws IOException {
        List<String> notes = new ArrayList<>();
        PageKeyResolver.Resolution resolution = PageKeyResolver.resolve(htmlFiles);
        notes.addAll(resolution.notes());

        // 清掉上一轮导入/转化产物（幂等重入：design、导入资产、转化生成的模板文件）
        cleanupPreviousArtifacts(workDir);
        Path designDir = workDir.resolve("design");
        Files.createDirectories(designDir);

        // 资产先归位（归一化引用重写依据文件集合）
        Path assetTarget = workDir.resolve(ASSET_DIR);
        if (!assetFiles.isEmpty()) {
            Files.createDirectories(assetTarget);
        }
        List<String> cssFiles = new ArrayList<>();
        List<String> jsFiles = new ArrayList<>();
        for (String rel : assetFiles) {
            Path src = staging.resolve(rel);
            Path target = assetTarget.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            String lower = rel.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".css")) {
                cssFiles.add(rel);
            } else if (lower.endsWith(".js")) {
                jsFiles.add(rel);
            }
        }

        // 逐页归一化
        List<MockupOrchestrator.PageState> pageStates = new ArrayList<>();
        List<MockupConverter.SectionMapping> mappings = new ArrayList<>();
        List<String> pageSummaries = new ArrayList<>();
        for (PageKeyResolver.ResolvedPage page : resolution.pages()) {
            String html = Files.readString(staging.resolve(page.sourceRelPath()), StandardCharsets.UTF_8);
            String htmlDir = parentDirOf(page.sourceRelPath());
            HtmlNormalizer.AssetUrlRewriter rewriter = rewriterFor(htmlDir, assetFiles);
            HtmlNormalizer.NormalizeResult result = HtmlNormalizer.normalize(page.pageKey(), html, rewriter);

            Files.writeString(designDir.resolve(page.name() + ".html"),
                    result.html(), StandardCharsets.UTF_8);
            pageStates.add(new MockupOrchestrator.PageState(page.name(),
                    StringUtils.hasText(result.title()) ? result.title() : page.name(),
                    "design/" + page.name() + ".html", "done", page.pageKey()));
            pageSummaries.add(page.name() + "（" + page.pageKey() + "，形态 " + result.form()
                    + "，" + result.sectionCount() + " 区块）");
            for (String note : result.notes()) {
                notes.add("[" + page.name() + "] " + note);
            }

            // c 形态确定性映射（§4.2 页型闭环，零 AI）。
            // nav/footer 为全站公共块（converter 仅从首页 anchor 切分，idx=-1/-2），只在首页生成映射；
            // b/a 形态首页的 nav/footer 留给 AI 映射（Tailwind 系组件映射是设计意图）
            boolean firstPage = resolution.pages().get(0) == page;
            if (result.form() == HtmlNormalizer.Form.C) {
                if (firstPage && result.navPresent()) {
                    mappings.add(new MockupConverter.SectionMapping(page.name(), -1,
                            "tw:navbar", "sticky", 1.0, "导入归一化：导航→导航组件", "import"));
                }
                if (firstPage && result.footerPresent()) {
                    mappings.add(new MockupConverter.SectionMapping(page.name(), -2,
                            "tw:footer", "simple", 1.0, "导入归一化：页脚→页脚组件", "import"));
                }
                for (int i = 0; i < result.sectionCount(); i++) {
                    boolean isBody = result.contentBodyIdx() != null && result.contentBodyIdx() == i;
                    mappings.add(new MockupConverter.SectionMapping(page.name(), i,
                            isBody ? MockupConverter.MAP_CONTENT_BODY : MockupConverter.MAP_CUSTOM,
                            null, 1.0,
                            isBody ? "导入归一化：内容主体→CMS 正文（文章可流入）" : "导入归一化：保真 custom",
                            "import"));
                }
            }
        }

        // plan.json（state=CONVERTING 起步，pages 全 done、c 形态 mappingCache 就绪；b/a 形态 AI 映射）
        MockupOrchestrator.DesignPlan plan = new MockupOrchestrator.DesignPlan(
                1, MockupOrchestrator.STATE_CONVERTING, null,
                pageStates, mappings, List.of(), null, 0,
                List.of("IMPORT: " + sourceName + "，" + pageStates.size() + " 页 / "
                        + assetFiles.size() + " 资产 / " + mappings.size() + " 确定性映射"));
        Files.writeString(designDir.resolve("plan.json"),
                JSON_MAPPER.writeValueAsString(plan), StandardCharsets.UTF_8);

        // import-meta.json（转化后接线依据：css 注入 layout；js 仅报告不注入）
        Files.writeString(designDir.resolve("import-meta.json"), JSON_MAPPER.writeValueAsString(
                        new ImportMeta("1", sourceName, cssFiles, jsFiles, pageSummaries, notes)),
                StandardCharsets.UTF_8);

        // 导入动作落消息表（历史可追溯；转化由下一次 chat 驱动）
        try {
            messageService.saveMessage(session.getSessionId(), AiTemplateConstants.ROLE_USER,
                    "【导入】" + sourceName + "（" + pageStates.size() + " 个页面）");
        } catch (Exception e) {
            log.warn("导入动作消息落库失败（不影响导入）: sessionId={}", session.getSessionId(), e);
        }
        log.info("HTML 导入 ingest 完成: sessionId={}, pages={}, assets={}, mappings={}",
                session.getSessionId(), pageStates.size(), assetFiles.size(), mappings.size());
        return new ImportResult(pageStates.size(), assetFiles.size(), notes);
    }

    // ==================== 转化后接线（编排器 CONVERTING 收尾调用） ====================

    /**
     * 转化完成后接线（§5.2 规则 3）：导入站外部 CSS 注入 _layout.html head（幂等），
     * 静态资产文件 + 改动的 layout 注册进会话文件表（apply 全目录拷贝，注册供前端文件树）。
     */
    public void postConvertWiring(AiTemplateSession session, Path workDir, DesignSseSink sse) {
        try {
            Path metaFile = workDir.resolve("design").resolve("import-meta.json");
            if (!Files.isRegularFile(metaFile)) {
                return;
            }
            ImportMeta meta = JSON_MAPPER.readValue(
                    Files.readString(metaFile, StandardCharsets.UTF_8), ImportMeta.class);

            // ① layout head 注入 css link（custom 区块的 class 依赖外部样式，须随 layout 生效）
            Path layout = workDir.resolve(AiTemplateConstants.FILE_LAYOUT);
            if (Files.isRegularFile(layout) && meta.css() != null && !meta.css().isEmpty()) {
                String content = Files.readString(layout, StandardCharsets.UTF_8);
                if (!content.contains(CSS_INJECT_MARKER)) {
                    StringBuilder inject = new StringBuilder();
                    inject.append("<#-- ").append(CSS_INJECT_MARKER)
                            .append(": 导入站外部样式（ingest 接线） -->\n");
                    for (String css : meta.css()) {
                        inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/import/")
                                .append(css).append("\">\n");
                    }
                    int headIdx = content.lastIndexOf("</head>");
                    content = headIdx >= 0
                            ? new StringBuilder(content).insert(headIdx, inject.toString()).toString()
                            : inject + content;
                    Files.writeString(layout, content, StandardCharsets.UTF_8);
                    registerFile(session, workDir, sse, AiTemplateConstants.FILE_LAYOUT);
                    sse.send(AiTemplateConstants.SSE_EVENT_MESSAGE,
                            "已接入导入站外部样式 " + meta.css().size() + " 个（随模板布局全局生效）\n");
                }
            }

            // ② 资产文件注册（前端文件树 + 应用前可见）
            try (var stream = Files.walk(workDir.resolve(ASSET_DIR))) {
                stream.filter(Files::isRegularFile).forEach(p ->
                        registerFile(session, workDir, sse,
                                workDir.relativize(p).normalize().toString().replace('\\', '/')));
            }
        } catch (Exception e) {
            // 接线失败不阻塞转化收尾（custom 区块样式可能缺失，但模板主体完整），显式播报
            log.error("导入转化后接线失败: sessionId={}", session.getSessionId(), e);
            Map<String, String> err = new LinkedHashMap<>();
            err.put("message", "导入资产接线失败（" + e.getMessage() + "），模板可能缺少外部样式");
            sse.send(AiTemplateConstants.SSE_EVENT_ERROR, JSON_MAPPER.writeValueAsString(err));
        }
    }

    private void registerFile(AiTemplateSession session, Path workDir, DesignSseSink sse, String relPath) {
        try {
            String content = Files.readString(workDir.resolve(relPath), StandardCharsets.UTF_8);
            fileService.saveOrUpdateFile(session.getSessionId(), relPath, content,
                    AiTemplateConstants.ACTION_CREATE);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("path", relPath);
            data.put("action", AiTemplateConstants.ACTION_CREATE);
            sse.send(AiTemplateConstants.SSE_EVENT_FILE, JSON_MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            log.warn("导入产物注册失败: sessionId={}, path={}", session.getSessionId(), relPath, e);
        }
    }

    // ==================== zip 处理（防 slip + 白名单 + 上限） ====================

    /** magic number 嗅探（PK\x03\x04 / PK\x05\x06），防伪装扩展名 */
    private static boolean isZip(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(4);
            return head.length >= 4 && head[0] == 'P' && head[1] == 'K'
                    && (head[2] == 3 || head[2] == 5 || head[2] == 7)
                    && (head[3] == 4 || head[3] == 6 || head[3] == 8);
        }
    }

    private void extractZip(MultipartFile file, Path staging,
                            FastcmsAiProperties.Template.Import cfg) throws IOException {
        int count = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isJunkEntry(name)) {
                    continue;
                }
                if (++count > cfg.getMaxFiles()) {
                    throw new IllegalArgumentException("压缩包文件数超过上限 " + cfg.getMaxFiles());
                }
                String ext = extensionOf(name);
                if (!cfg.getAllowExts().contains(ext)) {
                    throw new IllegalArgumentException("不支持的文件类型: " + name + "（白名单外）");
                }
                Path target = staging.resolve(name).normalize();
                if (!target.startsWith(staging)) {
                    throw new IllegalArgumentException("非法的压缩包路径（zip slip 拦截）: " + name);
                }
                Files.createDirectories(target.getParent());
                long written = 0;
                try (OutputStream os = Files.newOutputStream(target)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        written += n;
                        if (written > cfg.getMaxZipSize()) {
                            throw new IllegalArgumentException("解压后单文件超过大小上限（"
                                    + humanSize(cfg.getMaxZipSize()) + "）: " + name);
                        }
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** 递归分类：html → 页面清单；其余白名单内文件 → 资产清单（相对 zip 根） */
    private void classify(Path root, Path dir, List<String> htmlFiles, Set<String> assetFiles) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    classify(root, child, htmlFiles, assetFiles);
                } else {
                    String rel = root.relativize(child).normalize().toString().replace('\\', '/');
                    String lower = rel.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                        htmlFiles.add(rel);
                    } else {
                        assetFiles.add(rel);
                    }
                }
            }
        }
    }

    private static boolean isJunkEntry(String name) {
        if (name.contains("__MACOSX") || name.contains(".DS_Store")) {
            return true;
        }
        for (String segment : name.replace('\\', '/').split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFileName(String original) {
        if (!StringUtils.hasText(original)) {
            return null;
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^\\w.\\-]", "_");
        return name.isBlank() ? null : name;
    }

    // ==================== 资产引用重写 ====================

    /**
     * 构造该页的引用重写器：原始 href/src → zip 相对路径（按该页所在目录解析），
     * 命中资产集合则重写为 ${ctx()}/import/...（custom 区块经 FreeMarker 渲染时解析）
     */
    private static HtmlNormalizer.AssetUrlRewriter rewriterFor(String htmlDir, Set<String> assets) {
        return raw -> {
            if (!StringUtils.hasText(raw) || assets.isEmpty()) {
                return raw;
            }
            String trimmed = raw.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("//")
                    || lower.startsWith("data:") || lower.startsWith("mailto:") || lower.startsWith("tel:")
                    || lower.startsWith("javascript:") || trimmed.startsWith("#")
                    || trimmed.startsWith("${")) {
                return raw;
            }
            // 去查询串/锚点后解析
            String clean = trimmed;
            int cut = Integer.MAX_VALUE;
            int q = clean.indexOf('?');
            if (q >= 0) {
                cut = q;
            }
            int h = clean.indexOf('#');
            if (h >= 0 && h < cut) {
                cut = h;
            }
            if (cut != Integer.MAX_VALUE) {
                clean = clean.substring(0, cut);
            }
            if (clean.isBlank()) {
                return raw;
            }
            String resolved = clean.startsWith("/")
                    ? resolveRel("", clean.substring(1))
                    : resolveRel(htmlDir, clean);
            if (resolved != null && assets.contains(resolved)) {
                return "${ctx()}/import/" + resolved;
            }
            return raw;
        };
    }

    /** 相对路径解析（zip 内语义：/ 分隔，.. 回退不出根） */
    private static String resolveRel(String baseDir, String ref) {
        List<String> stack = new ArrayList<>();
        if (StringUtils.hasText(baseDir)) {
            for (String seg : baseDir.split("/")) {
                if (StringUtils.hasText(seg)) {
                    stack.add(seg);
                }
            }
        }
        for (String seg : ref.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (stack.isEmpty()) {
                    return null; // 逃逸 zip 根：不可信，放弃
                }
                stack.remove(stack.size() - 1);
            } else {
                stack.add(seg);
            }
        }
        return stack.isEmpty() ? null : String.join("/", stack);
    }

    private static String parentDirOf(String relPath) {
        String normalized = relPath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "";
    }

    // ==================== 清理 ====================

    /** 重导入/重新转化前清掉上一轮产物（design、导入资产、转化生成的模板文件与静态目录） */
    private void cleanupPreviousArtifacts(Path workDir) {
        try (var stream = Files.list(workDir)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if ("design".equals(name) || STAGING_DIR.equals(name) || "static".equals(name)) {
                    deleteDirectory(child);
                } else if (Files.isRegularFile(child)
                        && (name.endsWith(".html") || name.startsWith("_"))) {
                    Files.deleteIfExists(child);
                }
            }
        } catch (IOException e) {
            log.warn("导入前清理旧产物失败（继续导入，旧文件可能残留）: workDir={}", workDir, e);
        }
    }

    private static void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 删除失败保留（孤儿文件不影响主流程）
                    }
                });
            }
        } catch (IOException e) {
            log.warn("目录删除失败: {}", path, e);
        }
    }

    private static String humanSize(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + "MB" : (bytes / 1024) + "KB";
    }
}
