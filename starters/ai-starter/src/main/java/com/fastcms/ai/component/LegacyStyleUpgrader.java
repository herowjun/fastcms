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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 旧模板「样式组件化」升级器：保留功能、焕新视觉
 *
 * <p>与已废弃的重写式升级（LegacyTemplateUpgrader，整站重建为组件化模板）不同，
 * 本升级器的语义是<strong>换皮不换骨</strong>：</p>
 *
 * <ul>
 *     <li>保留：全部 JS 引入与页面内联脚本、元素 id、JS 选择器依赖的 class（锚点）、
 *     FreeMarker 指令与数据绑定——原网站功能不受影响</li>
 *     <li>焕新：组件库 CSS（pack-tailwind-v4.css + tokens.css + site.css）追加引入，
 *     HTML 结构由 AI 语义化重组并追加 Tailwind utility class——视觉由组件库接管</li>
 * </ul>
 *
 * <p>流程分两段：</p>
 * <ol>
 *     <li>{@link #prepare}：确定性前置（不调 AI，秒级）——备份文本文件 → 扫描 JS 依赖锚点 →
 *     组件 CSS 落盘 → _layout.html 追加 CSS 引入（置于旧样式之后，级联覆盖）。
 *     产出一个升级计划文件 {@code _style_upgrade.json}（锚点清单 + 待改造页面批次 + 进度），
 *     兼作断点续传标记与幂等保护。</li>
 *     <li>AI 改造轮（服务层驱动，见 runStyleUpgradePipeline）：按批次把页面文件交给模型
 *     重组结构 + 追加 utility class，写盘前用 {@link #verifyAnchors} 校验锚点存活。</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class LegacyStyleUpgrader {

    private static final Logger log = LoggerFactory.getLogger(LegacyStyleUpgrader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 升级计划文件（兼断点续传标记）：存在且 pending 为空即升级完成
     */
    static final String UPGRADE_PLAN_FILE = "_style_upgrade.json";

    /**
     * 升级默认主色（提取旧 CSS 主色不可靠，统一默认科技蓝，升级后可对话更换）
     */
    private static final String DEFAULT_PRIMARY_COLOR = "#2563eb";

    /**
     * 升级默认风格预设
     */
    private static final String DEFAULT_STYLE_PRESET = "minimal";

    /**
     * JS 依赖锚点提取正则：jQuery id 选择器 / getElementById / jQuery class 选择器 /
     * querySelector(All) class 选择器 / Swiper 容器选择器（含插件以字符串传选择器的场景）
     */
    private static final Pattern[] ID_PATTERNS = {
            Pattern.compile("\\$\\(\\s*['\"]#([\\w-]+)['\"]"),
            Pattern.compile("getElementById\\(\\s*['\"]([\\w-]+)['\"]")
    };

    private static final Pattern[] CLASS_PATTERNS = {
            Pattern.compile("\\$\\(\\s*['\"]\\.([\\w-]+)['\"]"),
            Pattern.compile("querySelector(?:All)?\\(\\s*['\"]\\.([\\w-]+)['\"]"),
            Pattern.compile("new\\s+Swiper\\(\\s*['\"]\\.([\\w-]+)['\"]")
    };

    /**
     * 二进制资源扩展名（备份时仍然全量备份，仅信息记录用）
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf", "eot",
            "mp4", "webm", "mp3", "wav");

    /**
     * 升级计划
     *
     * @param anchors   JS 依赖锚点清单（"id:xxx" / "class:xxx"）
     * @param pageFiles 待 AI 改造的页面文件（相对路径，_layout.html 之外的顶层 html）
     * @param doneFiles 已完成改造的文件
     * @param backupDir 原文件备份目录（null = 本次为断点续传，未重新备份）
     */
    public record StyleUpgradePlan(List<String> anchors, List<String> pageFiles,
                                   List<String> doneFiles, Path backupDir) {
    }

    private final TokenEngine tokenEngine;

    private final List<SectionComponentProvider> providers;

    /**
     * 备份根目录（默认 ~/fastcms/template-backups，可配 fastcms.ai.template.backup-root）
     */
    private final Path backupRoot;

    public LegacyStyleUpgrader(TokenEngine tokenEngine,
                               List<SectionComponentProvider> providers,
                               @Value("${fastcms.ai.template.backup-root:}") String backupRootConfig) {
        this.tokenEngine = tokenEngine;
        this.providers = providers;
        this.backupRoot = (backupRootConfig == null || backupRootConfig.isBlank())
                ? Path.of(System.getProperty("user.home"), "fastcms", "template-backups")
                : Path.of(backupRootConfig);
    }

    /**
     * 是否为可升级的旧模板（有 html 页面且无 _pagespec.json），且升级未完成
     */
    public boolean isLegacy(Path workDir) {
        if (workDir == null || !Files.isDirectory(workDir)
                || Files.isRegularFile(workDir.resolve("_pagespec.json"))) {
            return false;
        }
        return isUpgradeCompleted(workDir) ? false : hasHtmlPage(workDir);
    }

    /**
     * 升级是否已完成（计划文件存在且 pending 为空）
     */
    public boolean isUpgradeCompleted(Path workDir) {
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return false;
        }
        JsonNode pending = plan.path("pending");
        return pending.isArray() && pending.isEmpty();
    }

    private boolean hasHtmlPage(Path workDir) {
        try (Stream<Path> stream = Files.walk(workDir)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase().endsWith(".html"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 确定性前置（幂等：计划文件已存在时读取并续传，不重复备份/CSS 落盘/改造 layout）
     *
     * @return 升级计划（锚点 + 待改造页面 + 进度）
     */
    public StyleUpgradePlan prepare(Path workDir, String templateName) throws IOException {
        JsonNode existing = readPlan(workDir);
        if (existing != null) {
            List<String> pending = readStringList(existing.path("pending"));
            if (!pending.isEmpty()) {
                sendLog(workDir, "检测到未完成的升级计划（剩 " + pending.size() + " 个文件），继续升级");
            }
            return new StyleUpgradePlan(readStringList(existing.path("anchors")), pending,
                    readStringList(existing.path("done")), null);
        }

        String name = templateName != null && !templateName.isBlank()
                ? templateName : workDir.getFileName().toString();

        // 1. 扫描 JS 依赖锚点（备份前扫，备份后文件一样，先后无影响）
        List<String> anchors = scanAnchors(workDir);

        // 2. 备份全部文本文件
        List<Path> textFiles = listTextFiles(workDir);
        Path backupDir = backupFiles(workDir, name, textFiles);

        // 3. 组件 CSS 落盘（pack-tailwind-v4.css / tokens.css / site.css）
        writeComponentCss(workDir);

        // 4. _layout.html 追加组件 CSS 引入（置于旧样式之后，级联覆盖；JS 引入不动）
        injectLayoutCss(workDir);

        // 5. 待改造页面清单：顶层 html，排除 _ 前缀（布局/宏已确定性处理）与 index 兜底
        List<String> pageFiles = listPageFiles(workDir);

        // 6. 写升级计划（断点续传 + 完成标记）
        writePlan(workDir, anchors, pageFiles, List.of(), backupDir);
        log.info("样式组件化升级前置完成: dir={}, anchors={}, pages={}, backup={}",
                workDir.getFileName(), anchors.size(), pageFiles.size(), backupDir);
        return new StyleUpgradePlan(anchors, pageFiles, List.of(), backupDir);
    }

    /**
     * 批次完成后更新计划进度（pending 移除已完成文件；路径做归一化，容忍 AI 输出 "./xxx" 或反斜杠）
     */
    public void markDone(Path workDir, List<String> doneInBatch) throws IOException {
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return;
        }
        List<String> pending = new ArrayList<>(readStringList(plan.path("pending")));
        List<String> done = new ArrayList<>(readStringList(plan.path("done")));
        List<String> normalized = new ArrayList<>();
        for (String path : doneInBatch) {
            String n = normalizeRelPath(path);
            if (!n.isEmpty() && !normalized.contains(n)) {
                normalized.add(n);
            }
        }
        pending.removeAll(normalized);
        done.addAll(normalized);
        writePlanRaw(workDir, readStringList(plan.path("anchors")), pending, done,
                plan.path("backupDir").isTextual() ? plan.path("backupDir").asString() : null);
    }

    /**
     * 读取计划中剩余的待改造文件（无计划返回空列表）
     *
     * <p>升级管线的驱动队列：每批完成后重读，只把 AI 真正返回并写盘的文件移出队列，
     * 防止"批次推进了但某个文件被 AI 漏掉"导致 pending 永不清空。</p>
     */
    public List<String> readPending(Path workDir) {
        JsonNode plan = readPlan(workDir);
        return plan == null ? List.of() : readStringList(plan.path("pending"));
    }

    /**
     * 升级状态信息（前端横幅区分"未升级"与"未完成续传"）
     *
     * @param upgradable   是否可升级（有 html 无 _pagespec.json 且升级未完成）
     * @param pendingCount 剩余待改造页面数
     * @param doneCount    已完成页面数
     * @param totalFiles   页面总数（pending + done；未开始时为 0）
     */
    public record UpgradeStatusInfo(boolean upgradable, int pendingCount, int doneCount, int totalFiles) {
    }

    /**
     * 查询升级状态（计划文件不存在时返回全零的未开始状态）
     */
    public UpgradeStatusInfo getStatus(Path workDir) {
        boolean upgradable = isLegacy(workDir);
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return new UpgradeStatusInfo(upgradable, 0, 0, 0);
        }
        List<String> pending = readStringList(plan.path("pending"));
        List<String> done = readStringList(plan.path("done"));
        return new UpgradeStatusInfo(upgradable, pending.size(), done.size(),
                pending.size() + done.size());
    }

    /**
     * 相对路径归一化：去空白、反斜杠转正斜杠、去开头的 ./
     */
    private String normalizeRelPath(String path) {
        if (path == null) {
            return "";
        }
        String s = path.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }

    /**
     * 锚点存活校验：旧内容中实际存在的锚点，新内容必须保留
     *
     * @return 缺失的锚点清单（空 = 全部保留）
     */
    public List<String> verifyAnchors(String oldContent, String newContent) {
        List<String> missing = new ArrayList<>();
        if (oldContent == null || newContent == null) {
            return missing;
        }
        for (Pattern p : ID_PATTERNS) {
            Matcher m = p.matcher(oldContent);
            while (m.find()) {
                String id = m.group(1);
                if (!missing.contains("id:" + id)
                        && oldContent.contains("id=\"" + id + "\"")
                        && !newContent.contains("id=\"" + id + "\"")) {
                    missing.add("id:" + id);
                }
            }
        }
        for (Pattern p : CLASS_PATTERNS) {
            Matcher m = p.matcher(oldContent);
            while (m.find()) {
                String cls = m.group(1);
                if (!missing.contains("class:" + cls)
                        && hasClassToken(oldContent, cls)
                        && !hasClassToken(newContent, cls)) {
                    missing.add("class:" + cls);
                }
            }
        }
        return missing;
    }

    // ==================== 锚点扫描 ====================

    /**
     * 扫描模板内全部 HTML 与 JS 文件中的选择器引用（id/class 锚点）
     */
    List<String> scanAnchors(Path workDir) throws IOException {
        Set<String> anchors = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(workDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".html") || name.endsWith(".js");
                    })
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            for (Pattern pattern : ID_PATTERNS) {
                                Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    anchors.add("id:" + m.group(1));
                                }
                            }
                            for (Pattern pattern : CLASS_PATTERNS) {
                                Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    anchors.add("class:" + m.group(1));
                                }
                            }
                        } catch (IOException e) {
                            log.warn("锚点扫描读取失败: {}", p, e);
                        }
                    });
        }
        return new ArrayList<>(anchors);
    }

    private boolean hasClassToken(String html, String cls) {
        return Pattern.compile("class=\\\"[^\\\"]*\\b" + Pattern.quote(cls) + "\\b[^\\\"]*\\\"")
                .matcher(html).find();
    }

    // ==================== CSS 与布局 ====================

    /**
     * 组件库 CSS 落盘：每个组件包一份 pack-{packId}.css + tokens.css + site.css
     * （与 PageSpecRenderer 产物同构，升级模板后续可对话微调视觉）
     */
    private void writeComponentCss(Path workDir) throws IOException {
        Path cssDir = workDir.resolve("static/css");
        Files.createDirectories(cssDir);
        for (SectionComponentProvider provider : providers) {
            byte[] packCss = provider.getPackAsset("static/pack.css");
            if (packCss != null) {
                Files.write(cssDir.resolve("pack-" + provider.getPackId() + ".css"), packCss);
            }
        }
        Files.writeString(cssDir.resolve("tokens.css"),
                tokenEngine.generateTokens(DEFAULT_PRIMARY_COLOR, DEFAULT_STYLE_PRESET),
                StandardCharsets.UTF_8);
        Files.writeString(cssDir.resolve("site.css"), SITE_CSS, StandardCharsets.UTF_8);
    }

    /**
     * _layout.html 追加组件 CSS 引入：置于旧 CSS 之后（级联覆盖，视觉以组件库为主），
     * 旧 CSS/JS 引入全部保留（swiper.min.css 等库样式是 JS 功能的一部分，不能丢）
     */
    private void injectLayoutCss(Path workDir) throws IOException {
        Path layout = workDir.resolve("_layout.html");
        if (!Files.isRegularFile(layout)) {
            log.warn("_layout.html 不存在，跳过 CSS 注入（页面可能各自内联样式）");
            return;
        }
        String content = Files.readString(layout, StandardCharsets.UTF_8);
        if (content.contains("tokens.css")) {
            return; // 幂等保护
        }
        StringBuilder inject = new StringBuilder();
        inject.append("<#-- 样式组件化升级：组件库 CSS（追加于旧样式之后，视觉以组件库为主） -->\n");
        inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/tokens.css\">\n");
        for (SectionComponentProvider provider : providers) {
            if (provider.getPackAsset("static/pack.css") != null) {
                inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/pack-")
                        .append(provider.getPackId()).append(".css\">\n");
            }
        }
        inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/site.css\">\n");
        int headIdx = content.lastIndexOf("</head>");
        if (headIdx < 0) {
            headIdx = 0;
        }
        String updated = new StringBuilder(content)
                .insert(headIdx, inject.toString()).toString();
        Files.writeString(layout, updated, StandardCharsets.UTF_8);
    }

    /**
     * 升级版正文排版样式（h1-h4/p/img/ul 的最小排版，与组件库 tokens 变量联动）
     */
    private static final String SITE_CSS = """
            /* 样式组件化升级：内容页正文排版（tokens.css 变量联动） */
            .fc-prose h1, .fc-prose h2, .fc-prose h3, .fc-prose h4 { font-weight: 600; line-height: 1.3; color: var(--color-text, #0f172a); }
            .fc-prose h1 { font-size: 1.75rem; margin: 1.2rem 0 .8rem; }
            .fc-prose h2 { font-size: 1.4rem; margin: 1.1rem 0 .7rem; }
            .fc-prose h3 { font-size: 1.2rem; margin: 1rem 0 .6rem; }
            .fc-prose p { margin: .7rem 0; line-height: 1.75; color: var(--color-text-secondary, #334155); }
            .fc-prose img { max-width: 100%; height: auto; border-radius: .5rem; }
            .fc-prose ul, .fc-prose ol { padding-left: 1.4rem; margin: .7rem 0; }
            .fc-prose a { color: var(--color-primary, #2563eb); }
            """;

    // ==================== 计划文件 ====================

    private JsonNode readPlan(Path workDir) {
        Path file = workDir.resolve(UPGRADE_PLAN_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("读取升级计划失败: {}", e.getMessage());
            return null;
        }
    }

    private void writePlan(Path workDir, List<String> anchors, List<String> pending,
                           List<String> done, Path backupDir) throws IOException {
        writePlanRaw(workDir, anchors, pending, done, backupDir == null ? null : backupDir.toString());
    }

    private void writePlanRaw(Path workDir, List<String> anchors, List<String> pending,
                              List<String> done, String backupDir) throws IOException {
        var root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.set("anchors", MAPPER.valueToTree(anchors));
        root.set("pending", MAPPER.valueToTree(pending));
        root.set("done", MAPPER.valueToTree(done));
        if (backupDir != null) {
            root.put("backupDir", backupDir);
        }
        Files.writeString(workDir.resolve(UPGRADE_PLAN_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    private List<String> readStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                if (n.isTextual()) {
                    list.add(n.asString());
                }
            });
        }
        return list;
    }

    // ==================== 文件清单与备份 ====================

    /**
     * 待 AI 改造页面：顶层 html（排除 _ 前缀的布局/宏文件——_layout 已确定性处理，
     * _articlePage 等宏文件改动风险高、分页逻辑必须原样保留，一并排除）
     */
    private List<String> listPageFiles(Path workDir) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(workDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".html"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .forEach(p -> files.add(p.getFileName().toString()));
        }
        files.sort(String::compareTo);
        return files;
    }

    private List<Path> listTextFiles(Path workDir) throws IOException {
        try (Stream<Path> stream = Files.walk(workDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isBinaryFile(p))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private Path backupFiles(Path workDir, String name, List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return null;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path backupDir = backupRoot.resolve(name + "_style_upgrade_backup_" + timestamp);
        for (Path file : files) {
            Path target = backupDir.resolve(workDir.relativize(file));
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("样式升级前已备份: {} 个文件 -> {}", files.size(), backupDir);
        return backupDir;
    }

    private boolean isBinaryFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return BINARY_EXTENSIONS.contains(ext);
    }

    /**
     * 前置阶段日志（无 SSE 通道时的兜底：仅本地日志，服务层负责推送给前端）
     */
    private void sendLog(Path workDir, String message) {
        log.info("样式组件化升级: {} - {}", workDir.getFileName(), message);
    }

}
