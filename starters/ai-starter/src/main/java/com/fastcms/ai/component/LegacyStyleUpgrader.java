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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
     * @param anchors           JS 依赖锚点清单（"id:xxx" / "class:xxx"）
     * @param pageFiles         待 AI 改造的页面文件（相对路径，_layout.html 之外的顶层 html）
     * @param doneFiles         已完成改造的文件
     * @param backupDir         原文件备份目录（null = 本次为断点续传，未重新备份）
     * @param refreshCount      已完成的深度焕新轮次
     * @param sectionDowngrades 区块级恢复失败、降级为整文件重做的文件清单（问题1c：
     *                          一次性执行结果，供服务层 SSE 实况播报与收尾摘要；
     *                          空清单 = 无降级）
     * @param baselineRebuilt   深度焕新基线重建是否成功（问题①：当前版本快照为新备份）。
     *                          false = 快照失败沿用旧基线，重做页面的对话微调会被覆盖——
     *                          服务层据此分支播报，不得在失败时宣称"微调不会丢失"；
     *                          首次升级路径（prepare）无重建语义，恒为 true
     */
    public record StyleUpgradePlan(List<String> anchors, List<String> pageFiles,
                                   List<String> doneFiles, Path backupDir, int refreshCount,
                                   List<String> sectionDowngrades, boolean baselineRebuilt) {

        /**
         * 兼容旧调用点（无焕新轮次语义，视为第 0 轮即首次升级）
         */
        public StyleUpgradePlan(List<String> anchors, List<String> pageFiles,
                                List<String> doneFiles, Path backupDir) {
            this(anchors, pageFiles, doneFiles, backupDir, 0, List.of(), true);
        }

        /**
         * 兼容旧调用点（无区块级降级语义；首次升级无重建语义，基线视为就位）
         */
        public StyleUpgradePlan(List<String> anchors, List<String> pageFiles,
                                List<String> doneFiles, Path backupDir, int refreshCount) {
            this(anchors, pageFiles, doneFiles, backupDir, refreshCount, List.of(), true);
        }
    }

    /**
     * 智能焕新范围（P2-2 区块级）：范围评估的产物，驱动 restartPlan 的差异化恢复。
     *
     * @param fullFiles  整文件重做清单（恢复完整旧版底稿重新改造）
     * @param sectionRedo 区块级重做清单（文件 → 1-based 顶层 section 序号）：
     *                    仅把这些区块恢复为旧版底稿，其余区块保留当前版本——
     *                    「方向耦合强的小区块重做 + 通用内容区块保留」的最小重做单元
     */
    public record RefreshScope(List<String> fullFiles, Map<String, List<Integer>> sectionRedo) {

        public RefreshScope {
            fullFiles = fullFiles == null ? List.of() : List.copyOf(fullFiles);
            sectionRedo = sectionRedo == null ? Map.of() : Map.copyOf(sectionRedo);
        }

        /**
         * 全部涉及文件（整文件 + 区块级的并集）
         */
        public List<String> allFiles() {
            List<String> all = new ArrayList<>(fullFiles);
            sectionRedo.keySet().forEach(f -> {
                if (!all.contains(f)) {
                    all.add(f);
                }
            });
            return all;
        }

        public boolean isEmpty() {
            return fullFiles.isEmpty() && sectionRedo.isEmpty();
        }
    }

    /**
     * 焕新「上一轮」上下文（迭代记忆）：每轮升级收尾时落盘到计划文件 lastRound 字段，
     * 下一轮焕新回喂给 AI——多轮焕新从「失忆重抽」变为「有记忆的定向精修」。
     *
     * @param direction       上一轮使用的设计方向名（如「现代商务风」或反馈命中方向）
     * @param structureDigest 上一轮产物的结构指纹摘要（文件 → hero/栅格/卡片/节奏 4 行统计），
     *                        焕新时在恢复备份底稿<strong>之前</strong>对磁盘当前版本实时重建
     *                        （两轮之间用户可能对话微调过，实时摘要才准确），此处存储值作兜底
     * @param userFeedback    上一轮焕新时用户填写的意见原文（可空）
     * @param auditIssues     上一轮收尾审计发现的问题摘要（file:type 列表，clean 时为空）
     * @param fixPatches      上一轮功能修复补丁（P1 治 R4：文件 → 功能维度 diff，
     *                        恢复备份底稿后这些修复会丢失，须回喂重落实）
     */
    public record LastRoundInfo(String direction, Map<String, String> structureDigest,
                                String userFeedback, List<String> auditIssues,
                                Map<String, FileFixPatches> fixPatches) {

        /**
         * 兼容旧调用点（无功能补丁语义）
         */
        public LastRoundInfo(String direction, Map<String, String> structureDigest,
                             String userFeedback, List<String> auditIssues) {
            this(direction, structureDigest, userFeedback, auditIssues, Map.of());
        }
    }

    /**
     * 单文件功能修复补丁（P1 治 R4）：「备份原始稿 vs 当前产物」的功能维度 diff。
     * 这些修复不改变视觉、只改变功能正确性——焕新恢复备份底稿后会静默丢失，
     * 下一轮必须回喂提示词并做写盘校验。
     *
     * @param macroDefaults 产物独有的宏签名（带参数默认值，如
     *                      {@code menuChildren children=[] currentUri=""}——叶子数据
     *                      null 时无兜底会整站渲染 500）
     * @param addedAnchors  产物新增的 JS 依赖锚点（"id:xxx" / "class:xxx"，锚点修复轮
     *                      追加的补全；备份中已有的锚点由既有锚点校验保护，不重复记录）
     * @param scriptDiffs   产物新增/修改的脚本块摘要（JS 初始化参数修正等，仅提示词回喂，
     *                      不做写盘校验——脚本差异无法确定性断言）
     */
    public record FileFixPatches(List<String> macroDefaults, List<String> addedAnchors,
                                 List<String> scriptDiffs) {
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

    /**
     * 是否存在升级计划（无论完成与否）——深度焕新的准入条件：
     * 只对升级过的模板重刷，防止误伤从未升级的组件化模板
     */
    public boolean hasUpgradePlan(Path workDir) {
        return readPlan(workDir) != null;
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
                    readStringList(existing.path("done")), null,
                    existing.path("refreshCount").isInt() ? existing.path("refreshCount").asInt() : 0);
        }

        String name = templateName != null && !templateName.isBlank()
                ? templateName : workDir.getFileName().toString();

        // 1. 扫描 JS 依赖锚点（备份前扫，备份后文件一样，先后无影响）
        List<String> anchors = scanAnchors(workDir);

        // 2. 备份全部文本文件
        List<Path> textFiles = listTextFiles(workDir);
        Path backupDir = backupFiles(workDir, name, textFiles);

        // 3. 组件 CSS 落盘（pack-tailwind-v4.css / tokens.css / site.css）
        writeComponentCss(workDir, null);

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
     * 深度焕新（重刷）：升级已完成或中断后，把<strong>全部</strong>改造文件（含 _layout.html）
     * 重新置为 pending，从当前状态再改造一轮。
     *
     * <p>与 {@link #prepare} 的区别：</p>
     * <ul>
     *     <li>不重新备份——保留计划中的原始备份目录（那才是"焕新前"的干净基线，
     *     再备份会把已改造状态当作基线，回滚语义被破坏）</li>
     *     <li>重写组件 CSS（tokens/upgrade/pack 可能随代码版本升级而增强）并刷新
     *     _layout.html 的注入块（清除历史版本注入的失效 link，如 site.css/旧 pack 命名）</li>
     *     <li>锚点清单沿用原计划（JS 文件在改造中不变，锚点集稳定）</li>
     * </ul>
     *
     * <p>无既有计划时等价于首次 {@link #prepare}。</p>
     */
    public StyleUpgradePlan restartPlan(Path workDir, String templateName) throws IOException {
        return restartPlan(workDir, templateName, (RefreshScope) null, null);
    }

    /**
     * 深度焕新（范围感知，无方向覆写）：见 {@link #restartPlan(Path, String, RefreshScope, String)}
     */
    public StyleUpgradePlan restartPlan(Path workDir, String templateName,
                                        List<String> scopeFiles) throws IOException {
        return restartPlan(workDir, templateName,
                scopeFiles == null || scopeFiles.isEmpty()
                        ? null : new RefreshScope(scopeFiles, Map.of()),
                null);
    }

    /**
     * 深度焕新（范围感知）：见 {@link #restartPlan(Path, String, RefreshScope, String)}
     */
    public StyleUpgradePlan restartPlan(Path workDir, String templateName,
                                        List<String> scopeFiles, String directionKey) throws IOException {
        return restartPlan(workDir, templateName,
                scopeFiles == null || scopeFiles.isEmpty()
                        ? null : new RefreshScope(scopeFiles, Map.of()),
                directionKey);
    }

    /**
     * 深度焕新（范围感知 + 区块级）：scope 为空 = 全量焕新（全部计划文件恢复备份底稿重做）；
     * 非空 = 智能焕新——{@link RefreshScope#fullFiles()} 整文件恢复重做，
     * {@link RefreshScope#sectionRedo()} 仅恢复指定区块的旧版底稿（其余区块保留当前版本，
     * 区块边界截取为确定性操作；截取失败降级整文件恢复——硬保证，管线不中断）。
     *
     * <p>智能焕新的意义：焕新的视觉身份 80% 由 _layout.html（头尾/导航/全局色）与 index.html
     * （hero/分区节奏）承载，其余内容页多为方向无关的通用卡片布局；全量重做 14 个页面
     * 耗时数倍却产出雷同。范围由 AI 规划调用判定（见服务层），此处只负责执行。</p>
     *
     * <p><strong>基线重建（问题2a）</strong>：焕新先把当前正式目录（含对话调整与历史功能修复）
     * 快照为新备份目录，重做页面恢复的底稿从「原始旧稿」变为「当前版本」——对话微调成果
     * 不再被覆盖丢失。历史备份目录（含首次升级前原始旧稿）保留在磁盘不删，可手动找回；
     * fixPatches 以新基线 diff 自然为空（底稿已含修复），回喂机制自动退化，自洽。</p>
     *
     * <p><strong>方向否决记录（问题4）</strong>：焕新触发 = 用户否决上一轮方向（满意就不会
     * 再焕新），lastRound.direction 反查资产键追加进 rejectedDirections，轮换池跳过被否决方向。</p>
     *
     * @param scope        智能焕新范围（null/空 = 全量）
     * @param directionKey 焕新方向资产键（P0-3；可空）——轮换方向命中时按资产
     *                     {@code tokensOverride}（主色/风格预设）覆写 tokens.css，
     *                     保留页面与新做页面同步确定性换肤；空/未知键用升级默认 tokens
     */
    public StyleUpgradePlan restartPlan(Path workDir, String templateName,
                                        RefreshScope scope, String directionKey) throws IOException {
        JsonNode existing = readPlan(workDir);
        if (existing == null) {
            return prepare(workDir, templateName);
        }
        List<String> anchors = readStringList(existing.path("anchors"));
        String backupDir = existing.path("backupDir").isTextual()
                ? existing.path("backupDir").asString() : null;
        int refreshCount = existing.path("refreshCount").isInt()
                ? existing.path("refreshCount").asInt() : 0;

        // 问题4：否决方向记录（在基线重建覆盖 lastRound 语义前，先从旧计划解析）
        JsonNode lastRoundNode = existing.path("lastRound").isObject()
                ? existing.path("lastRound") : null;
        Set<String> rejectedDirections = readRejectedDirections(workDir);
        if (lastRoundNode != null && lastRoundNode.path("direction").isTextual()) {
            String rejectedKey = DesignDirectionLibrary.nameToKey(
                    lastRoundNode.path("direction").asString());
            if (rejectedKey != null && !rejectedDirections.contains(rejectedKey)) {
                rejectedDirections.add(rejectedKey);
            }
        }

        // 问题2a：基线重建——当前正式目录快照为新备份（时间戳目录，历史备份不删不覆盖）。
        // 快照失败（磁盘异常等）降级沿用旧基线，焕新流程不中断；baselineRebuilt 回传
        // 重建结果（问题①），服务层据此分支播报——失败时不得宣称"微调不会丢失"
        String effectiveBackupDir = backupDir;
        boolean baselineRebuilt = false;
        String name = templateName != null && !templateName.isBlank()
                ? templateName : workDir.getFileName().toString();
        try {
            Path rebased = backupFiles(workDir, name, listTextFiles(workDir));
            if (rebased != null) {
                String oldBackupDir = backupDir;
                effectiveBackupDir = rebased.toString();
                baselineRebuilt = true;
                log.info("深度焕新重建基线: 新基线={}, 旧基线(保留)={}", effectiveBackupDir, oldBackupDir);
            }
        } catch (Exception e) {
            log.warn("深度焕新基线重建失败，沿用旧基线（对话调整可能被覆盖）: {}", e.getMessage());
        }
        backupDir = effectiveBackupDir;

        // 计划全集 = pending + done（焕新前一轮的改造范围）
        List<String> plannedFiles = new ArrayList<>(readStringList(existing.path("pending")));
        for (String d : readStringList(existing.path("done"))) {
            if (!plannedFiles.contains(d)) {
                plannedFiles.add(d);
            }
        }

        // 范围解析：整文件清单 + 区块级清单（P2-2）；_layout.html 是站点门面，
        // 任何焕新都必须整文件重做（区块级条目中的 _layout 强制提升为整文件）
        boolean smart = scope != null && !scope.isEmpty();
        List<String> fullScope = new ArrayList<>(scope == null ? List.of() : scope.fullFiles());
        Map<String, List<Integer>> sectionScope = scope == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(scope.sectionRedo());
        if (!smart) {
            fullScope = new ArrayList<>(plannedFiles);
            sectionScope.clear();
        } else {
            if (sectionScope.remove("_layout.html") != null
                    && !fullScope.contains("_layout.html")) {
                log.warn("区块级范围包含 _layout.html，已提升为整文件重做（站点门面）");
            }
            if (plannedFiles.contains("_layout.html") && !fullScope.contains("_layout.html")) {
                fullScope.add("_layout.html");
            }
            // 范围裁剪：与计划全集求交（容忍 AI 输出越界路径）
            java.util.Set<String> fullSet = new java.util.HashSet<>();
            for (String s : fullScope) {
                String n = normalizeRelPath(s);
                if (!n.isEmpty()) {
                    fullSet.add(n);
                }
            }
            fullScope = plannedFiles.stream().filter(fullSet::contains).toList();
            Map<String, List<Integer>> trimmed = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> e : sectionScope.entrySet()) {
                String n = normalizeRelPath(e.getKey());
                if (!n.isEmpty() && plannedFiles.contains(n)) {
                    trimmed.put(n, e.getValue());
                }
            }
            sectionScope = trimmed;
            if (fullScope.isEmpty() && sectionScope.isEmpty()) {
                log.warn("智能焕新范围与计划文件无交集，回退全量焕新");
                smart = false;
                fullScope = new ArrayList<>(plannedFiles);
            }
        }

        // 涉及文件全集（重做范围）：整文件 + 区块级并集
        List<String> redesignScope = new ArrayList<>(fullScope);
        sectionScope.keySet().forEach(f -> {
            if (!redesignScope.contains(f)) {
                redesignScope.add(f);
            }
        });

        // 从备份恢复底稿——整文件恢复 + 区块级拼接恢复（仅重做范围；
        // 智能焕新保留其余页面的已升级版本）。若跳过恢复，重置进 pending 的是
        // "已升级版"文件，AI 只能原样核对输出（无变化空转）
        // 问题1c：区块级降级记录到 sectionDowngrades——服务层据此 SSE 实况播报，
        // 降级不再是只有日志可见的静默行为
        int restored = restorePagesFromBackup(workDir, backupDir, fullScope);
        List<String> sectionDowngrades = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : sectionScope.entrySet()) {
            if (restoreSectionsFromBackup(workDir, backupDir, e.getKey(), e.getValue())) {
                restored++;
            } else if (restorePagesFromBackup(workDir, backupDir, List.of(e.getKey())) > 0) {
                log.warn("区块级恢复失败已降级整文件恢复: {}", e.getKey());
                sectionDowngrades.add(e.getKey());
                restored++;
            }
        }
        if (restored == 0) {
            log.warn("深度焕新未能从备份恢复原文件（backup 缺失或计划为空），将基于当前版本重改造");
        }

        // 组件 CSS 重写 + layout 注入块刷新（兼容历史版本注入的旧文件名）；
        // tokens.css 按本轮方向资产覆写（P0-3：保留页面经变量自动换肤到新方向）
        writeComponentCss(workDir, DesignDirectionLibrary.get(directionKey));
        refreshLayoutCss(workDir);

        // 新计划：pending = 重做范围；done = 保留的已完成页面（智能焕新时非空）。
        // lastRound 透传保留——上一轮上下文在收尾 updatePlanLastRound 时才更新为本轮信息。
        // adjustCount 清零（问题2b：焕新即把对话修改整合进新基线，计数重新开始）
        List<String> newDone = smart
                ? plannedFiles.stream().filter(f -> !redesignScope.contains(f)).toList()
                : List.of();
        JsonNode lastRound = existing.path("lastRound").isObject()
                ? existing.path("lastRound") : null;
        writePlanRaw(workDir, anchors, redesignScope, newDone, backupDir,
                refreshCount + 1, 0, List.copyOf(rejectedDirections), lastRound);
        log.info("样式组件化升级深度焕新: dir={}, mode={}, redesign={}, sectionRedo={}, keep={}, restored={}, refresh={}, backup={}, rejected={}, downgrades={}",
                workDir.getFileName(), smart ? "smart" : "full",
                redesignScope.size(), sectionScope.size(), newDone.size(), restored,
                refreshCount + 1, backupDir, rejectedDirections, sectionDowngrades);
        return new StyleUpgradePlan(anchors, redesignScope, newDone,
                backupDir == null ? null : Path.of(backupDir), refreshCount + 1,
                List.copyOf(sectionDowngrades), baselineRebuilt);
    }

    /**
     * 读取计划文件全集（pending + done）——智能焕新规划调用的候选范围
     */
    public List<String> listPlannedFiles(Path workDir) throws IOException {
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return List.of();
        }
        List<String> all = new ArrayList<>(readStringList(plan.path("pending")));
        for (String d : readStringList(plan.path("done"))) {
            if (!all.contains(d)) {
                all.add(d);
            }
        }
        return all;
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
        // lastRound/adjustCount/rejectedDirections 原样透传：每批进度更新不丢上下文
        //（收尾 updatePlanLastRound 才更新 lastRound；计数与否决集合只在 restartPlan/incr 变更）
        writePlanRaw(workDir, readStringList(plan.path("anchors")), pending, done,
                plan.path("backupDir").isTextual() ? plan.path("backupDir").asString() : null,
                plan.path("refreshCount").isInt() ? plan.path("refreshCount").asInt() : 0,
                plan.path("adjustCount").isInt() ? plan.path("adjustCount").asInt() : 0,
                readStringList(plan.path("rejectedDirections")),
                plan.path("lastRound").isObject() ? plan.path("lastRound") : null);
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
     * @param refinable    升级已完成且可深度焕新（计划存在 + pending 清空 + 有改造记录；
     *                     此刻 upgradable 为 false，前端需靠本字段展示"深度焕新"入口）
     * @param refreshCount 已完成的深度焕新轮次
     * @param adjustCount  上次升级/焕新后的对话修改轮数（问题2b：前端焕新确认框据此
     *                     提示"N 轮微调将整合进新基线"；焕新时清零重新计数）
     */
    public record UpgradeStatusInfo(boolean upgradable, int pendingCount, int doneCount,
                                    int totalFiles, boolean refinable, int refreshCount,
                                    int adjustCount) {

        /**
         * 兼容旧调用点（无焕新轮次与对话修改计数）
         */
        public UpgradeStatusInfo(boolean upgradable, int pendingCount, int doneCount,
                                 int totalFiles, boolean refinable) {
            this(upgradable, pendingCount, doneCount, totalFiles, refinable, 0, 0);
        }
    }

    /**
     * 查询升级状态（计划文件不存在时返回全零的未开始状态）
     */
    public UpgradeStatusInfo getStatus(Path workDir) {
        boolean upgradable = isLegacy(workDir);
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return new UpgradeStatusInfo(upgradable, 0, 0, 0, false);
        }
        List<String> pending = readStringList(plan.path("pending"));
        List<String> done = readStringList(plan.path("done"));
        boolean refinable = pending.isEmpty() && !done.isEmpty();
        return new UpgradeStatusInfo(upgradable, pending.size(), done.size(),
                pending.size() + done.size(), refinable,
                plan.path("refreshCount").isInt() ? plan.path("refreshCount").asInt() : 0,
                plan.path("adjustCount").isInt() ? plan.path("adjustCount").asInt() : 0);
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

    // ==================== 视觉审计 ====================

    /**
     * 审计问题（文件 + 类型 + 详情，服务层转译为 AI 修复提示）
     *
     * <p>类型：</p>
     * <ul>
     *     <li>{@code missing_class}：HTML 中的 class 在全部 CSS 中无定义 → 样式静默失效（布局乱的直接原因）</li>
     *     <li>{@code undefined_var}：var(--x) 引用的 CSS 变量未定义 → 颜色/圆角回退失效</li>
     *     <li>{@code legacy_css_residue}：_layout.html 仍引入旧站皮肤 CSS → 旧样式与 utility 双重作用、视觉冲突</li>
     *     <li>{@code page_without_utilities}：已完成改造的页面没有任何 utility class → 疑似漏改造</li>
     *     <li>{@code section_count_drift}：改造稿顶层 <section> 数与备份基线不一致 → AI 擅自增删区块，
     *     后续区块级焕新的恢复对位会错位（问题1b；提示词设计规范第 8 条硬约束的审计兜底）</li>
     *     <li>{@code layout_language_mix}：同站版式语言混血（卡片化页与去卡片化页并存、深浅 hero 并存）
     *     → 智能焕新重做页与保留页风格割裂（P2-1）</li>
     * </ul>
     */
    public record AuditIssue(String file, String type, String detail) {
    }

    /**
     * 审计报告（clean = 无问题，不触发修复轮）
     */
    public record UpgradeAuditReport(boolean clean, List<AuditIssue> issues, int auditedFiles) {
    }

    /**
     * 审计问题数上限（防修复提示词爆炸；超限截断并提示剩余数）
     */
    private static final int MAX_AUDIT_ISSUES = 80;

    /**
     * JS 行为库 class 前缀/名单（样式由配套库 CSS 提供，或为纯行为标记，不参与缺失判定）
     */
    private static final Set<String> LIB_CLASS_PREFIXES = Set.of(
            "swiper", "fancybox", "wow", "animated", "slick", "layui", "el-", "van-", "ant-", "js-");

    /**
     * _layout.html 中允许保留的功能性 CSS（文件名包含即豁免：轮播/动画/字体图标库）；
     * 组件库 CSS（tokens/pack-/upgrade/site）与外部 http 链接同样豁免
     */
    private static final List<String> FUNCTIONAL_CSS_TOKENS = List.of(
            "tokens.css", "upgrade.css", "pack-", "site.css", "swiper", "animate", "font", "icon");

    /**
     * utility class 识别模式（判定页面是否真正被改造过）
     */
    private static final Pattern UTILITY_CLASS_PATTERN = Pattern.compile(
            "^(m|p|mt|mr|mb|ml|mx|my|pt|pr|pb|pl|px|py|gap|gap-x|gap-y|w|h|max-w|min-w|max-h|min-h)-.+"
                    + "|^(sm|md|lg|hover):.+"
                    + "|^(flex|grid|hidden|block|inline-block|contents)$"
                    + "|^(text|bg|rounded|shadow|border|font|leading|tracking|object|overflow|items|justify|self|col|order|space|aspect|z)-.+"
                    + "|^line-clamp-.+|^prose$|^container$");

    /**
     * HTML class 属性提取（含单双引号形态）
     */
    private static final Pattern CLASS_ATTR_PATTERN =
            Pattern.compile("class\\s*=\\s*\"([^\"]*)\"|class\\s*=\\s*'([^']*)'");

    /**
     * CSS 变量引用提取：var(--xxx)
     */
    private static final Pattern VAR_REF_PATTERN = Pattern.compile("var\\(\\s*(--?[\\w-]+)");

    /**
     * CSS 变量定义提取：--xxx:
     */
    private static final Pattern VAR_DEF_PATTERN = Pattern.compile("(--[\\w-]+)\\s*:");

    /**
     * link 标签提取（_layout.html 旧 CSS 残留检查）
     */
    private static final Pattern LINK_CSS_PATTERN =
            Pattern.compile("<link[^>]+href\\s*=\\s*[\"']([^\"']+\\.css)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * AI 改造损伤特征：字符串拼接伪装指令（${''}${'#'}{if x}...${'#'}{/if}）。
     * FreeMarker 语法合法（字符串插值），解析校验拦不住；渲染后把 "#{if...}"
     * 文本垃圾打进 class 属性，导航高亮等条件样式全部静默失效
     */
    private static final Pattern ESCAPED_DIRECTIVE_PATTERN = Pattern.compile("\\$\\{''\\}\\$\\{'#'\\}");

    /**
     * 宏定义提取：组1=宏名，组2=参数串（macro_arg_null_risk 检查用）
     */
    private static final Pattern MACRO_DEF_PATTERN = Pattern.compile("<#macro\\s+(\\w+)\\s*([^>]*)>");

    /**
     * 宏调用提取（自闭合与成对形态通吃）：组1=宏名，组2=参数串
     */
    private static final Pattern MACRO_CALL_PATTERN = Pattern.compile("<@(\\w+)([^>]*?)/?>");

    /**
     * 宏调用实参中数据树字段提取：组1=形参名，组2=实参表达式（如 children=item.children）。
     * .children 是菜单/分类树的标准子级字段，叶子节点该值为 null
     */
    private static final Pattern MACRO_CHILDREN_ARG_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*([\\w.()]+\\.children)\\b");

    /**
     * 升级后视觉审计：把"页面丑/乱"翻译成 AI 可读的结构化问题清单。
     *
     * <p>纯静态分析（不渲染）：扫描改造后的 html，比对 static/css 下全部 CSS 的
     * 类选择器与变量定义，找出静默失效的样式；检查 _layout.html 旧 CSS 残留；
     * 检查疑似漏改造页面。服务层拿报告构建修复提示（buildAuditFixPrompt），
     * 修复后复审，闭环直到 clean 或修复轮上限。</p>
     */
    public UpgradeAuditReport auditUpgrade(Path workDir) throws IOException {
        List<String> auditFiles = listPageFiles(workDir);
        if (auditFiles.isEmpty()) {
            return new UpgradeAuditReport(true, List.of(), 0);
        }
        JsonNode plan = readPlan(workDir);
        Set<String> anchorClasses = new java.util.HashSet<>();
        if (plan != null) {
            for (String a : readStringList(plan.path("anchors"))) {
                if (a.startsWith("class:")) {
                    anchorClasses.add(a.substring("class:".length()));
                }
            }
        }
        Set<String> doneFiles = new java.util.HashSet<>(plan == null
                ? List.of() : readStringList(plan.path("done")));
        // 问题1b：section_count_drift 比对的基线目录（计划不存在/无备份 = 首次升级前，不比对）
        String backupDirStr = plan == null || !plan.path("backupDir").isTextual()
                ? null : plan.path("backupDir").asString();

        // 1. 汇总全部 CSS：类选择器进 blob（边界匹配），变量定义进集合
        String cssBlob = loadCssBlob(workDir);
        Set<String> definedVars = new java.util.HashSet<>();
        Matcher varDef = VAR_DEF_PATTERN.matcher(cssBlob);
        while (varDef.find()) {
            definedVars.add(varDef.group(1));
        }

        List<AuditIssue> issues = new ArrayList<>();
        // P2-1：跨页版式语言特征收集（layout_language_mix 混血检测；
        // 仅真正改造过的页面参与——utility=0 的漏改造页由 page_without_utilities 单独报）
        List<PageLayoutLang> langs = new ArrayList<>();
        int truncated = 0;
        for (String rel : auditFiles) {
            Path file = workDir.resolve(rel).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // 1) missing_class：class token 无任何 CSS 定义（锚点 class 与库 class 豁免）
            Set<String> missed = new java.util.LinkedHashSet<>();
            int utilityCount = 0;
            Matcher cm = CLASS_ATTR_PATTERN.matcher(content);
            while (cm.find()) {
                String attr = cm.group(1) != null ? cm.group(1) : cm.group(2);
                if (attr == null) {
                    continue;
                }
                for (String token : attr.trim().split("\\s+")) {
                    if (token.isEmpty() || token.indexOf('$') >= 0 || token.indexOf('<') >= 0
                            || token.indexOf('@') >= 0 || token.indexOf('{') >= 0
                            || anchorClasses.contains(token) || isLibClass(token)) {
                        continue;
                    }
                    if (UTILITY_CLASS_PATTERN.matcher(token).find()) {
                        utilityCount++;
                    }
                    if (!cssDefinesClass(cssBlob, token)) {
                        missed.add(token);
                    }
                }
            }
            for (String cls : missed) {
                if (issues.size() + truncated >= MAX_AUDIT_ISSUES) {
                    truncated++;
                } else {
                    issues.add(new AuditIssue(rel, "missing_class",
                            "class \"" + cls + "\" 在所有 CSS 中均未定义，元素样式失效"));
                }
            }

            // P2-1：版式语言特征收集（_layout.html 为宏片段、无页面区块，不参与）
            if (!"_layout.html".equals(rel) && utilityCount > 0) {
                PageLayoutLang lang = analyzeLayoutLanguage(rel, content);
                if (lang.sections() > 0) {
                    langs.add(lang);
                }
            }

            // 2) undefined_var：var(--x) 引用未定义变量
            Set<String> undefinedVars = new java.util.LinkedHashSet<>();
            Matcher vm = VAR_REF_PATTERN.matcher(content);
            while (vm.find()) {
                String v = vm.group(1);
                if (!v.startsWith("--")) {
                    v = "--" + v;
                }
                if (!definedVars.contains(v)) {
                    undefinedVars.add(v);
                }
            }
            for (String v : undefinedVars) {
                if (issues.size() + truncated >= MAX_AUDIT_ISSUES) {
                    truncated++;
                } else {
                    issues.add(new AuditIssue(rel, "undefined_var",
                            "CSS 变量 " + v + " 未定义，样式回退失效"));
                }
            }

            // 3) page_without_utilities：已完成改造但无任何 utility class
            if (doneFiles.contains(rel) && utilityCount == 0
                    && issues.size() + truncated < MAX_AUDIT_ISSUES) {
                issues.add(new AuditIssue(rel, "page_without_utilities",
                        "页面没有任何 utility class，疑似未被改造，仍是旧样式"));
            }

            // 3b) section_count_drift（问题1b）：改造稿顶层 <section> 数与基线漂移——
            //     设计规范第 8 条硬约束（顶层区块数与旧稿一致，是区块级焕新对位正确性的
            //     根本保证），AI 擅自增删区块会让后续焕新的区块级恢复错位。基线无
            //     <section>（首次升级的旧站稿不用语义标签）或备份缺失时不比对，避免误报。
            if (backupDirStr != null && doneFiles.contains(rel)
                    && !"_layout.html".equals(rel)
                    && issues.size() + truncated < MAX_AUDIT_ISSUES) {
                try {
                    Path baseline = Path.of(backupDirStr).resolve(rel).normalize();
                    if (Files.isRegularFile(baseline)) {
                        int baseCount = locateTopLevelSections(
                                Files.readString(baseline, StandardCharsets.UTF_8)).size();
                        if (baseCount > 0) {
                            int curCount = locateTopLevelSections(content).size();
                            if (curCount != baseCount) {
                                issues.add(new AuditIssue(rel, "section_count_drift",
                                        "顶层 <section> 区块数 " + curCount + " 与基线 " + baseCount
                                                + " 不一致（AI 擅自增删了区块，后续区块级焕新的"
                                                + "恢复对位会错位），以基线的内容区块划分增补或合并"));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("section_count_drift 基线读取失败，跳过该文件: {}", rel);
                }
            }

            // 4) escaped_directive：字符串拼接伪装指令（AI 损伤特征，语法校验拦不住）
            int escapedCount = 0;
            Matcher edm = ESCAPED_DIRECTIVE_PATTERN.matcher(content);
            while (edm.find()) {
                escapedCount++;
            }
            if (escapedCount > 0 && issues.size() + truncated < MAX_AUDIT_ISSUES) {
                issues.add(new AuditIssue(rel, "escaped_directive",
                        "发现 " + escapedCount + " 处 ${''}${'#'}{...} 字符串拼接伪装指令"
                                + "（渲染后输出字面文本而非条件判断，高亮/条件样式全部失效），"
                                + "必须改写为真正的 <#if x>...</#if> 指令"));
            }

            // 5) macro_arg_null_risk：宏调用把可能为 null 的数据树子级（*.children）
            //    传给无默认值的宏参数——叶子数据（如无子菜单的菜单项）触发整站渲染 500。
            //    只核对同文件内定义的宏（跨文件宏定义无法静态得知），菜单树宏均在 _layout.html
            java.util.Map<String, String> macroParams = new java.util.HashMap<>();
            Matcher mdef = MACRO_DEF_PATTERN.matcher(content);
            while (mdef.find()) {
                macroParams.put(mdef.group(1), mdef.group(2) == null ? "" : mdef.group(2));
            }
            if (!macroParams.isEmpty()) {
                Matcher mcall = MACRO_CALL_PATTERN.matcher(content);
                while (mcall.find()) {
                    String macroName = mcall.group(1);
                    String defParams = macroParams.get(macroName);
                    if (defParams == null) {
                        continue;
                    }
                    Matcher marg = MACRO_CHILDREN_ARG_PATTERN.matcher(
                            mcall.group(2) == null ? "" : mcall.group(2));
                    while (marg.find() && issues.size() + truncated < MAX_AUDIT_ISSUES) {
                        // 调用处已判空豁免：同文件出现 "表达式??" 或 "表达式?size"
                        //（如 <#if child.children?? && child.children?size gt 0> 包裹调用）则视为安全
                        String expr = marg.group(2);
                        boolean guarded = content.contains(expr + "??")
                                || content.contains(expr + "?size");
                        if (!guarded && macroParamLacksDefault(defParams, marg.group(1))) {
                            issues.add(new AuditIssue(rel, "macro_arg_null_risk",
                                    "宏 <@" + macroName + " " + marg.group(1) + "=" + marg.group(2)
                                            + "> 的参数 " + marg.group(1)
                                            + " 无默认值，叶子数据该字段为 null 时页面直接 500；"
                                            + "给宏参数补默认值（" + marg.group(1)
                                            + "=[]）或调用处判空"));
                        }
                    }
                }
            }
        }

        // 7) layout_language_mix（P2-1）：同站版式语言混血检测（跨页比对，确定性零 token）。
        //    智能焕新「重做页落新方向 vs 保留页留旧方向」的割裂感翻译成 AI 可修复问题——
        //    每个冲突页面单独出一条 issue（修复轮按 file 聚合注入涉事文件）
        appendLayoutLanguageIssues(issues, langs);

        // 6) legacy_css_residue：_layout.html 旧站皮肤 CSS 未移除
        Path layout = workDir.resolve("_layout.html");
        if (Files.isRegularFile(layout) && auditFiles.contains("_layout.html")) {
            String layoutContent = Files.readString(layout, StandardCharsets.UTF_8);
            Matcher lm = LINK_CSS_PATTERN.matcher(layoutContent);
            while (lm.find()) {
                String href = lm.group(1);
                if (href.startsWith("http://") || href.startsWith("https://")
                        || href.startsWith("//")) {
                    continue; // 外链（google fonts 等）豁免
                }
                String lower = href.toLowerCase();
                boolean functional = FUNCTIONAL_CSS_TOKENS.stream().anyMatch(lower::contains);
                if (!functional && issues.size() + truncated < MAX_AUDIT_ISSUES) {
                    issues.add(new AuditIssue("_layout.html", "legacy_css_residue",
                            "旧站 CSS \"" + href + "\" 仍在引入，与组件库 utility 冲突"));
                }
            }
        }

        if (truncated > 0) {
            issues.add(new AuditIssue("*", "truncated", "另有 " + truncated + " 个同类问题未列出"));
        }
        log.info("视觉审计完成: dir={}, files={}, issues={}", workDir.getFileName(),
                auditFiles.size(), issues.size());
        return new UpgradeAuditReport(issues.isEmpty(), issues, auditFiles.size());
    }

    /**
     * 单页版式语言特征向量（P2-1 layout_language_mix 审计用）：卡片化程度 / 去卡片化信号 /
     * hero 明暗语言，跨页比对判定「混血」
     *
     * @param sections       顶层 section 区块数
     * @param cardBlocks     卡片化区块数（rounded-(lg|xl|2xl|3xl) 且带 shadow 或 border 的组合）
     * @param decardSignals  去卡片化信号数（border-t 分隔线区块数 + text-6xl~9xl 超大标题数）
     * @param heroState      首屏 hero 语言：0=无 hero 特征，1=浅色 hero，2=深色/渐变 hero
     */
    private record PageLayoutLang(String file, int sections, int cardBlocks,
                                  int decardSignals, int heroState) {

        /** 卡片化页：区块过半用圆角卡片（≥60%） */
        boolean carded() {
            return sections >= 2 && cardBlocks * 10 >= sections * 6;
        }

        /** 去卡片化页：卡片占比 ≤30% 且分隔线/超大标题信号 ≥2（杂志编辑式排版） */
        boolean decarded() {
            return sections >= 2 && cardBlocks * 10 <= sections * 3 && decardSignals >= 2;
        }
    }

    /** 卡片圆角特征（区块内出现即视为卡片候选） */
    private static final Pattern CARD_ROUNDED_PATTERN =
            Pattern.compile("rounded-(lg|xl|2xl|3xl)\\b");

    /** border 作为完整 class token（排除 border-t 等变体） */
    private static final Pattern CARD_BORDER_PATTERN =
            Pattern.compile("\\bborder(?=[\\s\"'])");

    /** 超大标题（去卡片化信号，text-6xl~9xl） */
    private static final Pattern HUGE_TEXT_PATTERN =
            Pattern.compile("text-(6xl|7xl|8xl|9xl)\\b");

    /** hero 形态判定：大标题 / 全屏高 / hero|banner 命名（命中才算 hero，普通内容首区块不算） */
    private static final Pattern HERO_LIKE_PATTERN =
            Pattern.compile("text-(4xl|5xl|6xl|7xl|8xl)\\b|min-h-screen|\\bh-screen\\b"
                    + "|class\\s*=\\s*[\"'][^\"']*(hero|banner|jumbotron)", Pattern.CASE_INSENSITIVE);

    /** 深色/渐变 hero 特征（与浅色 hero 互斥判定） */
    private static final Pattern DARK_HERO_PATTERN =
            Pattern.compile("bg-(slate|gray|zinc|neutral|stone)-(800|900)\\b|bg-black\\b"
                    + "|bg-primary-(700|800|900)\\b|bg-gradient-to-|from-primary-|from-slate-8|from-gray-8");

    /**
     * 单页版式语言特征提取（确定性，零 token）
     */
    private PageLayoutLang analyzeLayoutLanguage(String rel, String content) {
        List<int[]> spans = locateTopLevelSections(content);
        if (spans.isEmpty()) {
            return new PageLayoutLang(rel, 0, 0, 0, 0);
        }
        int cardBlocks = 0;
        int decardSignals = 0;
        for (int[] span : spans) {
            String block = content.substring(span[0], span[1]);
            if (CARD_ROUNDED_PATTERN.matcher(block).find()
                    && (block.contains("shadow-") || CARD_BORDER_PATTERN.matcher(block).find())) {
                cardBlocks++;
            }
            if (block.contains("border-t")) {
                decardSignals++;
            }
            Matcher huge = HUGE_TEXT_PATTERN.matcher(block);
            while (huge.find()) {
                decardSignals++;
            }
        }
        int heroState = heroLanguageOf(content.substring(spans.get(0)[0], spans.get(0)[1]));
        return new PageLayoutLang(rel, spans.size(), cardBlocks, decardSignals, heroState);
    }

    /**
     * 首屏区块的 hero 明暗语言：非 hero 形态返回 0；深色/渐变返回 2；其余（浅色）返回 1
     */
    private static int heroLanguageOf(String firstBlock) {
        if (!HERO_LIKE_PATTERN.matcher(firstBlock).find()) {
            return 0;
        }
        return DARK_HERO_PATTERN.matcher(firstBlock).find() ? 2 : 1;
    }

    /**
     * 跨页混血判定与 issue 产出（P2-1）：两种互斥语言并存时，每个冲突页面单独出
     * {@code layout_language_mix} issue（修复轮按 file 聚合注入涉事文件；方向语言
     * 的统一基准由修复提示词注入——auditUpgrade 不依赖提示词层）
     *
     * <ul>
     *     <li>卡片化页（≥60% 区块用卡片）与去卡片化页（分隔线 + 超大标题）并存</li>
     *     <li>深色/渐变 hero 页与浅色 hero 页并存</li>
     * </ul>
     */
    private void appendLayoutLanguageIssues(List<AuditIssue> issues, List<PageLayoutLang> langs) {
        if (langs.size() < 2) {
            return;
        }
        List<PageLayoutLang> carded = langs.stream().filter(PageLayoutLang::carded).toList();
        List<PageLayoutLang> decarded = langs.stream().filter(PageLayoutLang::decarded).toList();
        List<PageLayoutLang> darkHero = langs.stream().filter(l -> l.heroState() == 2).toList();
        List<PageLayoutLang> lightHero = langs.stream().filter(l -> l.heroState() == 1).toList();
        boolean cardConflict = !carded.isEmpty() && !decarded.isEmpty();
        boolean heroConflict = !darkHero.isEmpty() && !lightHero.isEmpty();
        if (!cardConflict && !heroConflict) {
            return;
        }
        for (PageLayoutLang lang : langs) {
            if (issues.size() >= MAX_AUDIT_ISSUES) {
                break;
            }
            List<String> conflicts = new ArrayList<>();
            if (cardConflict && (lang.carded() || lang.decarded())) {
                boolean selfCarded = lang.carded();
                List<String> otherFiles = (selfCarded ? decarded : carded)
                        .stream().map(PageLayoutLang::file).toList();
                conflicts.add("本页为「" + (selfCarded ? "卡片化" : "去卡片化") + "」风格（"
                        + lang.cardBlocks() + "/" + lang.sections() + " 个区块用圆角卡片+阴影，"
                        + "去卡片化信号 " + lang.decardSignals() + "），与站内 " + otherFiles
                        + " 的「" + (selfCarded ? "去卡片化：border-t 分隔线+超大标题" : "卡片化：圆角卡片+阴影") + "」并存");
            }
            if (heroConflict && lang.heroState() > 0) {
                boolean selfDark = lang.heroState() == 2;
                List<String> otherFiles = (selfDark ? lightHero : darkHero)
                        .stream().map(PageLayoutLang::file).toList();
                conflicts.add("本页首屏为「" + (selfDark ? "深色/渐变" : "浅色") + "」hero，与站内 "
                        + otherFiles + " 的「" + (selfDark ? "浅色" : "深色/渐变") + "」hero 不一致");
            }
            if (!conflicts.isEmpty()) {
                issues.add(new AuditIssue(lang.file(), "layout_language_mix",
                        "全站版式语言混血（焕新重做页与保留页风格割裂的典型特征）："
                                + String.join("；", conflicts)
                                + "。需统一为同一版式语言（hero 明暗、卡片化程度一致）"));
            }
        }
    }

    /**
     * 判断宏形参是否无默认值：参数串中"裸出现"（前后是空白/边界，而非 xxx= 形态）。
     * 如 "children currentUri" 中 children 无默认值；"children=[] currentUri=\"\"" 中均有默认值
     */
    private static boolean macroParamLacksDefault(String params, String paramName) {
        return Pattern.compile("(^|\\s)" + Pattern.quote(paramName) + "(\\s|$)")
                .matcher(params).find();
    }

    /**
     * 汇总 static/css 下全部 CSS 内容（去注释），供类选择器/变量存在性匹配
     */
    private String loadCssBlob(Path workDir) throws IOException {
        StringBuilder blob = new StringBuilder(256 * 1024);
        Path cssDir = workDir.resolve("static/css");
        if (Files.isDirectory(cssDir)) {
            List<Path> cssFiles;
            try (Stream<Path> stream = Files.list(cssDir)) {
                cssFiles = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".css"))
                        .collect(java.util.stream.Collectors.toList());
            }
            for (Path css : cssFiles) {
                try {
                    String content = Files.readString(css, StandardCharsets.UTF_8);
                    blob.append(content.replaceAll("/\\*[\\s\\S]*?\\*/", "")).append('\n');
                } catch (IOException e) {
                    log.warn("审计读取 CSS 失败: {}", css, e);
                }
            }
        }
        return blob.toString();
    }

    /**
     * CSS 是否定义了指定 class（blob 中查找 ".转义名" 且后继字符不是类名字符，
     * 防止 .flex 误匹配 .flex-col；转义 : / . % ! @ 等特殊字符与生成器输出一致）
     */
    private boolean cssDefinesClass(String cssBlob, String cls) {
        String esc = cls.replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("/", "\\/")
                .replace(".", "\\.")
                .replace("%", "\\%")
                .replace("!", "\\!")
                .replace("@", "\\@");
        String needle = "." + esc;
        int idx = cssBlob.indexOf(needle);
        while (idx >= 0) {
            int after = idx + needle.length();
            if (after >= cssBlob.length()) {
                return true;
            }
            char c = cssBlob.charAt(after);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
                return true;
            }
            idx = cssBlob.indexOf(needle, idx + 1);
        }
        return false;
    }

    /**
     * 是否为 JS 行为库 class（swiper/fancybox 等，样式来自配套库或纯行为标记）
     */
    private boolean isLibClass(String cls) {
        String lower = cls.toLowerCase();
        return LIB_CLASS_PREFIXES.stream().anyMatch(lower::startsWith);
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
     * 升级管线 CSS 落盘（三件套）：
     * <ul>
     *     <li>tokens.css：TokenEngine 产物 + <strong>语义别名</strong>（--color-primary/--color-text-primary 等，
     *     AI 改造中高频引用但引擎不直接生成的变量，缺失会导致全站配色静默失效）</li>
     *     <li>pack-{packId}.css：组件库包资产（原样拷贝）</li>
     *     <li>upgrade.css：<strong>升级专用 utility 体系</strong>（运行时生成）——preflight + Tailwind 兼容
     *     utility 全集 + 响应式断点（sm/md/lg）+ hover 子集 + .prose 正文排版 + line-clamp。
     *     组件库的 pack.css 只含组件模板用到的最小类子集（约百个），AI 自由改写页面会用到大量
     *     pack 中不存在的 utility（grid-cols-N、mb-N、md: 前缀等）——没有这份文件，AI 写的类全部失效，布局必乱</li>
     * </ul>
     */
    private void writeComponentCss(Path workDir,
                                   DesignDirectionLibrary.DesignDirectionAsset direction) throws IOException {
        Path cssDir = workDir.resolve("static/css");
        Files.createDirectories(cssDir);
        for (SectionComponentProvider provider : providers) {
            byte[] packCss = provider.getPackAsset("static/pack.css");
            if (packCss != null) {
                Files.write(cssDir.resolve("pack-" + provider.getPackId() + ".css"), packCss);
            }
        }
        // P0-3: 方向 tokensOverride——焕新轮换方向由资产确定主色/风格预设（确定性换肤）。
        // 主色为空（反馈定向方向按 D3 决策不动主色 / 首次升级 / 未知键）时优先继承
        // 现有 tokens.css 的主色（焕新场景用户/AI 已定调的主色不应被定向修正重置为默认色），
        // 无现有 tokens（首次升级）才用升级默认色
        String primaryColor = direction != null && direction.primaryColor() != null
                && !direction.primaryColor().isBlank()
                ? direction.primaryColor() : inheritPrimaryColor(cssDir);
        String stylePreset = direction != null && direction.stylePreset() != null
                && !direction.stylePreset().isBlank()
                ? direction.stylePreset() : DEFAULT_STYLE_PRESET;
        Files.writeString(cssDir.resolve("tokens.css"),
                tokenEngine.generateTokens(primaryColor, stylePreset) + "\n" + TOKEN_ALIASES,
                StandardCharsets.UTF_8);
        Files.writeString(cssDir.resolve("upgrade.css"), generateUpgradeCss(), StandardCharsets.UTF_8);
    }

    /**
     * 继承现有 tokens.css 的主色（D3 决策配套，问题5）：反馈定向方向（提亮/疏朗/强对比/统一）
     * 不配置 primaryColor——定向修正只动风格维度，用户已定调的主色必须原样保留。
     * 从现有 tokens.css 提取 {@code --color-primary-600}（TokenEngine 输出的主档变量）；
     * 无现有 tokens 或提取失败（格式异常）回退升级默认色。
     */
    private String inheritPrimaryColor(Path cssDir) {
        try {
            Path tokens = cssDir.resolve("tokens.css");
            if (Files.isRegularFile(tokens)) {
                Matcher m = Pattern.compile(
                        "--color-primary-600\\s*:\\s*(#[0-9a-fA-F]{3,8})").matcher(
                        Files.readString(tokens, StandardCharsets.UTF_8));
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            log.debug("继承现有主色失败，回退默认色: {}", e.getMessage());
        }
        return DEFAULT_PRIMARY_COLOR;
    }

    /**
     * _layout.html 注入升级 CSS 引入（tokens → pack → upgrade）。
     *
     * <p>注入位置在旧样式之后（若旧 CSS 仍在，级联覆盖）；旧站 CSS（base.css/m.css）的移除
     * 不在这里做——由 AI 改造轮重写 _layout.html 时按契约处理（识别库 CSS 保留、旧站 CSS 删除）。</p>
     */
    private void injectLayoutCss(Path workDir) throws IOException {
        Path layout = workDir.resolve("_layout.html");
        if (!Files.isRegularFile(layout)) {
            log.warn("_layout.html 不存在，跳过 CSS 注入（页面可能各自内联样式）");
            return;
        }
        String content = Files.readString(layout, StandardCharsets.UTF_8);
        if (content.contains("upgrade.css")) {
            return; // 幂等保护
        }
        Files.writeString(layout, insertCssLinks(content), StandardCharsets.UTF_8);
    }

    /**
     * 深度焕新时刷新注入块：先清除历史版本注入的 link（含已废弃命名 pack-tw.css/site.css）
     * 与标记注释行，再按当前版本重新注入。旧站自身 CSS（base.css 等）不动——那些由 AI 改造轮处理。
     */
    private void refreshLayoutCss(Path workDir) throws IOException {
        Path layout = workDir.resolve("_layout.html");
        if (!Files.isRegularFile(layout)) {
            return;
        }
        String content = Files.readString(layout, StandardCharsets.UTF_8);
        // 移除标记注释行 + 组件库 CSS link 行（tokens/pack-*/upgrade/site，历史版本命名一并覆盖）
        content = content.replaceAll("(?m)^<#-- 样式组件化升级.*\\R?", "");
        content = content.replaceAll(
                "(?m)^<link rel=\"stylesheet\" href=\"\\$\\{ctx\\(\\)\\}/css/(tokens|upgrade|site|pack-[^\"]*)\\.css\">\\R?", "");
        Files.writeString(layout, insertCssLinks(content), StandardCharsets.UTF_8);
    }

    /**
     * 在 </head> 前（无则文件头）插入组件库 CSS 引入块，返回新内容
     */
    private String insertCssLinks(String content) {
        StringBuilder inject = new StringBuilder();
        inject.append("<#-- 样式组件化升级：组件库 CSS + utility 体系 -->\n");
        inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/tokens.css\">\n");
        for (SectionComponentProvider provider : providers) {
            if (provider.getPackAsset("static/pack.css") != null) {
                inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/pack-")
                        .append(provider.getPackId()).append(".css\">\n");
            }
        }
        inject.append("<link rel=\"stylesheet\" href=\"${ctx()}/css/upgrade.css\">\n");
        int headIdx = content.lastIndexOf("</head>");
        if (headIdx < 0) {
            headIdx = 0;
        }
        return new StringBuilder(content).insert(headIdx, inject.toString()).toString();
    }

    /**
     * tokens.css 追加的语义别名：升级改造轮 AI 高频引用的语义变量
     * （提示词举例引用 + AI 自然偏好），指向 TokenEngine 生成的真实色阶/字面量
     */
    private static final String TOKEN_ALIASES = """
            /* 语义别名（样式组件化升级追加）：指向上方真实色阶 */
            :root {
              --color-primary: var(--color-primary-600);
              --color-primary-dark: var(--color-primary-700);
              --color-primary-light: var(--color-primary-100);
              --color-text-primary: #0f172a;
              --color-text-secondary: #475569;
              --color-text-muted: #94a3b8;
              --color-bg: #ffffff;
              --color-bg-secondary: #f8fafc;
              --color-border: #e2e8f0;
              --color-danger: #dc2626;
              --color-danger-light: #fee2e2;
              --color-success: #16a34a;
              --color-warning: #d97706;
            }
            """;

    /**
     * 语义别名变量块（tokens.css 追加段）——设计稿转化段复用：
     * 转化产物 tokens.css = TokenEngine 色阶 + 本别名块 + 设计稿 :root --c-* 原值
     * （doc/wiki/ai-template-two-mode-design.md §5.1 Step 1 tokens 提取），
     * 与升级管线 tokens.css 构成口径一致
     */
    public String tokenAliases() {
        return TOKEN_ALIASES;
    }

    // ==================== upgrade.css 运行时生成器 ====================

    /**
     * Tailwind 间距/尺寸刻度（0.25rem 步进体系）
     */
    private static final double[] SPACING_SCALE = {0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 14, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 96};

    /**
     * slate/gray 色板（Tailwind v3 默认值）
     */
    private static final String[][] SLATE = {
            {"50", "#f8fafc"}, {"100", "#f1f5f9"}, {"200", "#e2e8f0"}, {"300", "#cbd5e1"},
            {"400", "#94a3b8"}, {"500", "#64748b"}, {"600", "#475569"}, {"700", "#334155"},
            {"800", "#1e293b"}, {"900", "#0f172a"}};

    private static final String[][] GRAY = {
            {"50", "#f9fafb"}, {"100", "#f3f4f6"}, {"200", "#e5e7eb"}, {"300", "#d1d5db"},
            {"400", "#9ca3af"}, {"500", "#6b7280"}, {"600", "#4b5563"}, {"700", "#374151"},
            {"800", "#1f2937"}, {"900", "#111827"}};

    /**
     * 生成升级专用 upgrade.css：preflight + utility 全集 + 响应式断点 + hover 子集 + prose 排写
     *
     * <p><b>public 供设计稿转化段复用</b>（doc/wiki/ai-template-two-mode-design.md §5.1 Step 3）：
     * 设计稿 custom 区块携带任意 Tailwind 类，pack.css 固定子集覆盖不了，
     * 与升级管线共用同一份运行时 utility 体系（单一实现，口径一致）。</p>
     */
    public String generateUpgradeCss() {
        List<String[]> core = new ArrayList<>();
        appendSpacingUtilities(core);
        appendLayoutUtilities(core);
        appendColorUtilities(core);
        appendTypographyUtilities(core);
        appendBorderShadowUtilities(core);
        appendEffectUtilities(core);

        StringBuilder css = new StringBuilder(160 * 1024);
        css.append("/* 样式组件化升级 utility 体系（运行时生成，Tailwind 兼容语法） */\n\n");
        // 1. preflight（重置默认样式，让 utility 完全接管视觉）
        css.append(PREFLIGHT_CSS).append('\n');
        // 2. 基础 utility
        for (String[] u : core) {
            css.append('.').append(u[0]).append(" { ").append(u[1]).append(" }\n");
        }
        // 2.1 子项间距/分隔线（含组合选择器，必须平铺输出而非 .class{prop} 形态）
        appendCombinatorUtilities(css);
        // 3. 响应式断点（sm/md/lg，布局关键类的前缀版本）
        appendResponsiveBlock(css, "sm", "(min-width: 640px)", core);
        appendResponsiveBlock(css, "md", "(min-width: 768px)", core);
        appendResponsiveBlock(css, "lg", "(min-width: 1024px)", core);
        // 4. hover 子集（交互态，手写常用）
        css.append(HOVER_CSS).append('\n');
        // 5. 正文排版 + 行数截断
        css.append(PROSE_CSS).append('\n');
        return css.toString();
    }

    /**
     * 子项间距（space-x/y-*）与分隔线（divide-*）：选择器含组合器，平铺输出
     */
    private void appendCombinatorUtilities(StringBuilder css) {
        css.append(".divide-y > :not([hidden]) ~ :not([hidden]) { border-top-width:1px; border-bottom-width:0 }\n");
        css.append(".divide-x > :not([hidden]) ~ :not([hidden]) { border-right-width:1px; border-left-width:0 }\n");
        for (double s : SPACING_SCALE) {
            String rem = (s * 0.25) + "rem";
            css.append(".space-x-").append(spacingClassName(s))
                    .append(" > :not([hidden]) ~ :not([hidden]) { margin-left:").append(rem).append(" }\n");
            css.append(".space-y-").append(spacingClassName(s))
                    .append(" > :not([hidden]) ~ :not([hidden]) { margin-top:").append(rem).append(" }\n");
        }
    }

    private void appendSpacingUtilities(List<String[]> r) {
        String[] boxDirs = {"m", "mt", "mr", "mb", "ml", "mx", "my", "p", "pt", "pr", "pb", "pl", "px", "py",
                "gap", "gap-x", "gap-y"};
        for (double s : SPACING_SCALE) {
            String name = spacingClassName(s);
            String rem = (s * 0.25) + "rem";
            for (String dir : boxDirs) {
                boolean margin = dir.startsWith("m");
                String cls = dir + "-" + name;
                String prop;
                switch (dir) {
                    case "m" -> prop = "margin";
                    case "mt" -> prop = "margin-top";
                    case "mr" -> prop = "margin-right";
                    case "mb" -> prop = "margin-bottom";
                    case "ml" -> prop = "margin-left";
                    case "mx" -> { r.add(new String[]{cls, "margin-left:" + rem + ";margin-right:" + rem}); continue; }
                    case "my" -> { r.add(new String[]{cls, "margin-top:" + rem + ";margin-bottom:" + rem}); continue; }
                    case "p" -> prop = "padding";
                    case "pt" -> prop = "padding-top";
                    case "pr" -> prop = "padding-right";
                    case "pb" -> prop = "padding-bottom";
                    case "pl" -> prop = "padding-left";
                    case "px" -> { r.add(new String[]{cls, "padding-left:" + rem + ";padding-right:" + rem}); continue; }
                    case "py" -> { r.add(new String[]{cls, "padding-top:" + rem + ";padding-bottom:" + rem}); continue; }
                    case "gap" -> prop = "gap";
                    case "gap-x" -> prop = "column-gap";
                    default -> prop = "row-gap";
                }
                r.add(new String[]{cls, prop + ":" + rem});
                // 负 margin（AI 常用 -mt-2 等偏移）
                if (margin) {
                    r.add(new String[]{"-" + cls, prop + ":-" + rem});
                }
            }
        }
        r.add(new String[]{"m-0", "margin:0"});
        r.add(new String[]{"p-0", "padding:0"});
        r.add(new String[]{"m-auto", "margin:auto"});
        r.add(new String[]{"mx-auto", "margin-left:auto;margin-right:auto"});
        r.add(new String[]{"my-auto", "margin-top:auto;margin-bottom:auto"});
        r.add(new String[]{"mt-auto", "margin-top:auto"});
        r.add(new String[]{"ml-auto", "margin-left:auto"});
        r.add(new String[]{"mr-auto", "margin-right:auto"});
    }

    private String spacingClassName(double s) {
        return s == Math.floor(s) ? String.valueOf((long) s) : String.valueOf(s);
    }

    private void appendLayoutUtilities(List<String[]> r) {
        // display
        String[][] displays = {{"block", "display:block"}, {"inline-block", "display:inline-block"},
                {"inline", "display:inline"}, {"flex", "display:flex"}, {"inline-flex", "display:inline-flex"},
                {"grid", "display:grid"}, {"inline-grid", "display:inline-grid"},
                {"hidden", "display:none"}, {"contents", "display:contents"}, {"flow-root", "display:flow-root"}};
        for (String[] d : displays) {
            r.add(d);
        }
        // position
        r.add(new String[]{"static", "position:static"});
        r.add(new String[]{"fixed", "position:fixed"});
        r.add(new String[]{"absolute", "position:absolute"});
        r.add(new String[]{"relative", "position:relative"});
        r.add(new String[]{"sticky", "position:sticky"});
        r.add(new String[]{"top-0", "top:0"});
        r.add(new String[]{"bottom-0", "bottom:0"});
        r.add(new String[]{"left-0", "left:0"});
        r.add(new String[]{"right-0", "right:0"});
        r.add(new String[]{"inset-0", "top:0;right:0;bottom:0;left:0"});
        r.add(new String[]{"top-auto", "top:auto"});
        r.add(new String[]{"bottom-auto", "bottom:auto"});
        r.add(new String[]{"left-auto", "left:auto"});
        r.add(new String[]{"right-auto", "right:auto"});
        String[] zs = {"z-0", "z-10", "z-20", "z-30", "z-40", "z-50"};
        for (String z : zs) {
            r.add(new String[]{z, "z-index:" + z.substring(2)});
        }
        // flex
        String[][] flexes = {{"flex-row", "flex-direction:row"}, {"flex-row-reverse", "flex-direction:row-reverse"},
                {"flex-col", "flex-direction:column"}, {"flex-col-reverse", "flex-direction:column-reverse"},
                {"flex-wrap", "flex-wrap:wrap"}, {"flex-wrap-reverse", "flex-wrap:wrap-reverse"},
                {"flex-nowrap", "flex-wrap:nowrap"}, {"flex-1", "flex:1 1 0%"}, {"flex-auto", "flex:1 1 auto"},
                {"flex-none", "flex:none"}, {"flex-initial", "flex:0 1 auto"}, {"grow", "flex-grow:1"},
                {"grow-0", "flex-grow:0"}, {"shrink", "flex-shrink:1"}, {"shrink-0", "flex-shrink:0"}};
        for (String[] f : flexes) {
            r.add(f);
        }
        appendStartsEnds(r, "justify-", "justify-content");
        appendStartsEnds(r, "items-", "align-items");
        appendStartsEnds(r, "content-", "align-content");
        appendStartsEnds(r, "self-", "align-self");
        // grid
        for (int i = 1; i <= 12; i++) {
            r.add(new String[]{"grid-cols-" + i, "grid-template-columns:repeat(" + i + ",minmax(0,1fr))"});
            r.add(new String[]{"col-span-" + i, "grid-column:span " + i + " / span " + i});
            r.add(new String[]{"order-" + i, "order:" + i});
        }
        for (int i = 1; i <= 6; i++) {
            r.add(new String[]{"row-span-" + i, "grid-row:span " + i + " / span " + i});
            r.add(new String[]{"grid-rows-" + i, "grid-template-rows:repeat(" + i + ",minmax(0,1fr))"});
        }
        r.add(new String[]{"order-first", "order:-9999"});
        r.add(new String[]{"order-last", "order:9999"});
        r.add(new String[]{"grid-flow-row", "grid-auto-flow:row"});
        r.add(new String[]{"grid-flow-col", "grid-auto-flow:column"});
        r.add(new String[]{"grid-flow-dense", "grid-auto-flow:dense"});
        // 尺寸
        String[] wh = {"0", "0.5", "1", "1.5", "2", "2.5", "3", "4", "5", "6", "8", "10", "12", "16",
                "20", "24", "28", "32", "40", "48", "56", "64"};
        for (String w : wh) {
            String rem = (Double.parseDouble(w) * 0.25) + "rem";
            r.add(new String[]{"w-" + w, "width:" + rem});
            r.add(new String[]{"h-" + w, "height:" + rem});
        }
        String[][] sizes = {{"w-full", "width:100%"}, {"w-screen", "width:100vw"}, {"w-auto", "width:auto"},
                {"w-min", "width:min-content"}, {"w-max", "width:max-content"}, {"w-fit", "width:fit-content"},
                {"h-full", "height:100%"}, {"h-screen", "height:100vh"}, {"h-auto", "height:auto"},
                {"h-min", "height:min-content"}, {"h-max", "height:max-content"}, {"h-fit", "height:fit-content"},
                {"min-w-0", "min-width:0"}, {"min-w-full", "min-width:100%"},
                {"min-h-0", "min-height:0"}, {"min-h-full", "min-height:100%"}, {"min-h-screen", "min-height:100vh"},
                {"max-w-none", "max-width:none"}, {"max-w-full", "max-width:100%"}, {"max-w-prose", "max-width:65ch"},
                {"max-w-xs", "max-width:20rem"}, {"max-w-sm", "max-width:24rem"}, {"max-w-md", "max-width:28rem"},
                {"max-w-lg", "max-width:32rem"}, {"max-w-xl", "max-width:36rem"}, {"max-w-2xl", "max-width:42rem"},
                {"max-w-3xl", "max-width:48rem"}, {"max-w-4xl", "max-width:56rem"}, {"max-w-5xl", "max-width:64rem"},
                {"max-w-6xl", "max-width:72rem"}, {"max-w-7xl", "max-width:80rem"}};
        for (String[] s : sizes) {
            r.add(s);
        }
        // object-fit
        String[][] objects = {{"object-contain", "object-fit:contain"}, {"object-cover", "object-fit:cover"},
                {"object-fill", "object-fit:fill"}, {"object-none", "object-fit:none"},
                {"object-scale-down", "object-fit:scale-down"}, {"object-center", "object-position:center"},
                {"object-top", "object-position:top"}, {"object-bottom", "object-position:bottom"}};
        for (String[] o : objects) {
            r.add(o);
        }
        r.add(new String[]{"aspect-square", "aspect-ratio:1/1"});
        r.add(new String[]{"aspect-video", "aspect-ratio:16/9"});
        r.add(new String[]{"box-border", "box-sizing:border-box"});
        r.add(new String[]{"box-content", "box-sizing:content-box"});
        // overflow
        String[][] overflows = {{"overflow-auto", "overflow:auto"}, {"overflow-hidden", "overflow:hidden"},
                {"overflow-visible", "overflow:visible"}, {"overflow-scroll", "overflow:scroll"},
                {"overflow-x-auto", "overflow-x:auto"}, {"overflow-x-hidden", "overflow-x:hidden"},
                {"overflow-y-auto", "overflow-y:auto"}, {"overflow-y-hidden", "overflow-y:hidden"},
                {"overflow-clip", "overflow:clip"}};
        for (String[] o : overflows) {
            r.add(o);
        }
        // cursor / visibility / pointer
        r.add(new String[]{"cursor-pointer", "cursor:pointer"});
        r.add(new String[]{"cursor-default", "cursor:default"});
        r.add(new String[]{"cursor-not-allowed", "cursor:not-allowed"});
        r.add(new String[]{"cursor-text", "cursor:text"});
        r.add(new String[]{"visible", "visibility:visible"});
        r.add(new String[]{"invisible", "visibility:hidden"});
        r.add(new String[]{"pointer-events-none", "pointer-events:none"});
        r.add(new String[]{"pointer-events-auto", "pointer-events:auto"});
        r.add(new String[]{"select-none", "user-select:none"});
        r.add(new String[]{"select-text", "user-select:text"});
        r.add(new String[]{"sr-only", "position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border-width:0"});
        r.add(new String[]{"list-none", "list-style-type:none"});
        r.add(new String[]{"list-disc", "list-style-type:disc"});
        r.add(new String[]{"list-decimal", "list-style-type:decimal"});
    }

    private void appendStartsEnds(List<String[]> r, String prefix, String prop) {
        String[][] vals = {{"start", "flex-start"}, {"end", "flex-end"}, {"center", "center"},
                {"between", "space-between"}, {"around", "space-around"}, {"evenly", "space-evenly"},
                {"baseline", "baseline"}, {"stretch", "stretch"}};
        for (String[] v : vals) {
            if (prop.equals("align-items") || prop.equals("align-self")) {
                if (!v[0].equals("between") && !v[0].equals("around") && !v[0].equals("evenly")) {
                    r.add(new String[]{prefix + v[0], prop + ":" + v[1]});
                }
            } else if (prop.equals("justify-content")) {
                if (!v[0].equals("baseline") && !v[0].equals("stretch")) {
                    r.add(new String[]{prefix + v[0], prop + ":" + v[1]});
                }
            } else {
                r.add(new String[]{prefix + v[0], prop + ":" + v[1]});
            }
        }
    }

    private void appendColorUtilities(List<String[]> r) {
        // 文字/背景/边框 × slate/gray 色板
        appendPaletteUtilities(r, "slate", SLATE);
        appendPaletteUtilities(r, "gray", GRAY);
        // primary 系走 tokens 变量（换主色全局联动）
        for (String[] c : SLATE) {
            // 借用刻度编号 50~900
            r.add(new String[]{"text-primary-" + c[0], "color:var(--color-primary-" + c[0] + ")"});
            r.add(new String[]{"bg-primary-" + c[0], "background-color:var(--color-primary-" + c[0] + ")"});
            r.add(new String[]{"border-primary-" + c[0], "border-color:var(--color-primary-" + c[0] + ")"});
        }
        r.add(new String[]{"text-primary", "color:var(--color-primary)"});
        r.add(new String[]{"bg-primary", "background-color:var(--color-primary)"});
        r.add(new String[]{"border-primary", "border-color:var(--color-primary)"});
        r.add(new String[]{"text-white", "color:#fff"});
        r.add(new String[]{"text-black", "color:#000"});
        r.add(new String[]{"bg-white", "background-color:#fff"});
        r.add(new String[]{"bg-black", "background-color:#000"});
        r.add(new String[]{"bg-transparent", "background-color:transparent"});
        r.add(new String[]{"border-white", "border-color:#fff"});
        r.add(new String[]{"border-black", "border-color:#000"});
        r.add(new String[]{"border-transparent", "border-color:transparent"});
        r.add(new String[]{"text-danger", "color:var(--color-danger)"});
        r.add(new String[]{"bg-danger", "background-color:var(--color-danger)"});
        r.add(new String[]{"text-success", "color:var(--color-success)"});
        // 渐变（AI 常用于 hero 区）
        String[][] grads = {{"bg-gradient-to-t", "to top"}, {"bg-gradient-to-tr", "to top right"},
                {"bg-gradient-to-r", "to right"}, {"bg-gradient-to-br", "to bottom right"},
                {"bg-gradient-to-b", "to bottom"}, {"bg-gradient-to-bl", "to bottom left"},
                {"bg-gradient-to-l", "to left"}, {"bg-gradient-to-tl", "to top left"}};
        for (String[] g : grads) {
            r.add(new String[]{g[0], "background-image:linear-gradient(" + g[1] + ",var(--tw-gradient-stops))"});
        }
        for (String[] c : SLATE) {
            addGradientStop(r, "from", "slate-" + c[0], c[1]);
            addGradientStop(r, "to", "slate-" + c[0], c[1]);
            addGradientStop(r, "via", "slate-" + c[0], c[1]);
        }
        for (String[] c : GRAY) {
            addGradientStop(r, "from", "gray-" + c[0], c[1]);
            addGradientStop(r, "to", "gray-" + c[0], c[1]);
            addGradientStop(r, "via", "gray-" + c[0], c[1]);
        }
        addGradientStop(r, "from", "white", "#fff");
        addGradientStop(r, "to", "white", "#fff");
        addGradientStop(r, "via", "white", "#fff");
        for (String[] c : SLATE) {
            addGradientStop(r, "from", "primary-" + c[0], "var(--color-primary-" + c[0] + ")");
            addGradientStop(r, "to", "primary-" + c[0], "var(--color-primary-" + c[0] + ")");
            addGradientStop(r, "via", "primary-" + c[0], "var(--color-primary-" + c[0] + ")");
        }
    }

    private void appendPaletteUtilities(List<String[]> r, String name, String[][] palette) {
        for (String[] c : palette) {
            r.add(new String[]{"text-" + name + "-" + c[0], "color:" + c[1]});
            r.add(new String[]{"bg-" + name + "-" + c[0], "background-color:" + c[1]});
            r.add(new String[]{"border-" + name + "-" + c[0], "border-color:" + c[1]});
        }
    }

    private void addGradientStop(List<String[]> r, String pos, String color, String value) {
        String var = pos.equals("from") ? "--tw-gradient-from" : pos.equals("to") ? "--tw-gradient-to" : "--tw-gradient-via";
        String stops = pos.equals("from")
                ? "--tw-gradient-stops:var(--tw-gradient-from),var(--tw-gradient-to)"
                : pos.equals("to")
                ? "--tw-gradient-to:" + value
                : "--tw-gradient-stops:var(--tw-gradient-from),var(--tw-gradient-via),var(--tw-gradient-to)";
        r.add(new String[]{pos + "-" + color, var + ":" + value + ";" + stops});
    }

    private void appendTypographyUtilities(List<String[]> r) {
        String[][] sizes = {{"xs", "0.75rem", "1rem"}, {"sm", "0.875rem", "1.25rem"}, {"base", "1rem", "1.5rem"},
                {"lg", "1.125rem", "1.75rem"}, {"xl", "1.25rem", "1.75rem"}, {"2xl", "1.5rem", "2rem"},
                {"3xl", "1.875rem", "2.25rem"}, {"4xl", "2.25rem", "2.5rem"}, {"5xl", "3rem", "1.1"},
                {"6xl", "3.75rem", "1.05"}, {"7xl", "4.5rem", "1"}, {"8xl", "6rem", "1"}};
        for (String[] s : sizes) {
            String lh = s[0].equals("5xl") || s[0].equals("6xl") || s[0].equals("7xl") || s[0].equals("8xl")
                    ? "line-height:" + s[2] : "line-height:" + s[2];
            r.add(new String[]{"text-" + s[0], "font-size:" + s[1] + ";" + lh});
        }
        String[][] weights = {{"thin", "100"}, {"extralight", "200"}, {"light", "300"}, {"normal", "400"},
                {"medium", "500"}, {"semibold", "600"}, {"bold", "700"}, {"extrabold", "800"}, {"black", "900"}};
        for (String[] w : weights) {
            r.add(new String[]{"font-" + w[0], "font-weight:" + w[1]});
        }
        r.add(new String[]{"font-sans", "font-family:var(--font-sans)"});
        r.add(new String[]{"font-mono", "font-family:var(--font-mono)"});
        r.add(new String[]{"text-left", "text-align:left"});
        r.add(new String[]{"text-center", "text-align:center"});
        r.add(new String[]{"text-right", "text-align:right"});
        r.add(new String[]{"text-justify", "text-align:justify"});
        r.add(new String[]{"italic", "font-style:italic"});
        r.add(new String[]{"not-italic", "font-style:normal"});
        r.add(new String[]{"uppercase", "text-transform:uppercase"});
        r.add(new String[]{"lowercase", "text-transform:lowercase"});
        r.add(new String[]{"capitalize", "text-transform:capitalize"});
        r.add(new String[]{"normal-case", "text-transform:none"});
        r.add(new String[]{"underline", "text-decoration-line:underline"});
        r.add(new String[]{"line-through", "text-decoration-line:line-through"});
        r.add(new String[]{"no-underline", "text-decoration-line:none"});
        r.add(new String[]{"truncate", "overflow:hidden;text-overflow:ellipsis;white-space:nowrap"});
        r.add(new String[]{"text-ellipsis", "text-overflow:ellipsis"});
        r.add(new String[]{"whitespace-nowrap", "white-space:nowrap"});
        r.add(new String[]{"whitespace-normal", "white-space:normal"});
        r.add(new String[]{"whitespace-pre-line", "white-space:pre-line"});
        r.add(new String[]{"break-words", "overflow-wrap:break-word"});
        r.add(new String[]{"antialiased", "-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale"});
        String[][] leadings = {{"none", "1"}, {"tight", "1.25"}, {"snug", "1.375"}, {"normal", "1.5"},
                {"relaxed", "1.625"}, {"loose", "2"}};
        for (String[] l : leadings) {
            r.add(new String[]{"leading-" + l[0], "line-height:" + l[1]});
        }
        String[][] trackings = {{"tighter", "-0.05em"}, {"tight", "-0.025em"}, {"normal", "0"},
                {"wide", "0.025em"}, {"wider", "0.05em"}, {"widest", "0.1em"}};
        for (String[] t : trackings) {
            r.add(new String[]{"tracking-" + t[0], "letter-spacing:" + t[1]});
        }
        for (int i = 1; i <= 6; i++) {
            r.add(new String[]{"line-clamp-" + i,
                    "overflow:hidden;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:" + i});
        }
    }

    private void appendBorderShadowUtilities(List<String[]> r) {
        r.add(new String[]{"border", "border-width:1px"});
        r.add(new String[]{"border-0", "border-width:0"});
        r.add(new String[]{"border-2", "border-width:2px"});
        r.add(new String[]{"border-4", "border-width:4px"});
        r.add(new String[]{"border-8", "border-width:8px"});
        String[] dirs = {"t", "r", "b", "l"};
        String[] props = {"top", "right", "bottom", "left"};
        for (int i = 0; i < dirs.length; i++) {
            r.add(new String[]{"border-" + dirs[i], "border-" + props[i] + "-width:1px"});
            r.add(new String[]{"border-" + dirs[i] + "-0", "border-" + props[i] + "-width:0"});
            r.add(new String[]{"border-" + dirs[i] + "-2", "border-" + props[i] + "-width:2px"});
        }
        r.add(new String[]{"border-solid", "border-style:solid"});
        r.add(new String[]{"border-dashed", "border-style:dashed"});
        r.add(new String[]{"border-dotted", "border-style:dotted"});
        r.add(new String[]{"border-none", "border-style:none"});
        String[][] rounds = {{"rounded-none", "0"}, {"rounded-sm", "var(--radius-sm)"},
                {"rounded", "var(--radius-md)"}, {"rounded-md", "var(--radius-md)"},
                {"rounded-lg", "var(--radius-lg)"}, {"rounded-xl", "var(--radius-xl)"},
                {"rounded-2xl", "var(--radius-2xl)"}, {"rounded-3xl", "var(--radius-3xl)"},
                {"rounded-full", "9999px"}};
        for (String[] rd : rounds) {
            r.add(new String[]{rd[0], "border-radius:" + rd[1]});
        }
        r.add(new String[]{"rounded-t-lg", "border-top-left-radius:var(--radius-lg);border-top-right-radius:var(--radius-lg)"});
        r.add(new String[]{"rounded-b-lg", "border-bottom-left-radius:var(--radius-lg);border-bottom-right-radius:var(--radius-lg)"});
        r.add(new String[]{"rounded-t-xl", "border-top-left-radius:var(--radius-xl);border-top-right-radius:var(--radius-xl)"});
        r.add(new String[]{"rounded-b-xl", "border-bottom-left-radius:var(--radius-xl);border-bottom-right-radius:var(--radius-xl)"});
        String[][] shadows = {{"shadow-sm", "0 1px 2px 0 rgb(0 0 0 / 0.05)"},
                {"shadow", "0 1px 3px 0 rgb(0 0 0 / 0.1),0 1px 2px -1px rgb(0 0 0 / 0.1)"},
                {"shadow-md", "0 4px 6px -1px rgb(0 0 0 / 0.1),0 2px 4px -2px rgb(0 0 0 / 0.1)"},
                {"shadow-lg", "0 10px 15px -3px rgb(0 0 0 / 0.1),0 4px 6px -4px rgb(0 0 0 / 0.1)"},
                {"shadow-xl", "0 20px 25px -5px rgb(0 0 0 / 0.1),0 8px 10px -6px rgb(0 0 0 / 0.1)"},
                {"shadow-2xl", "0 25px 50px -12px rgb(0 0 0 / 0.25)"},
                {"shadow-inner", "inset 0 2px 4px 0 rgb(0 0 0 / 0.05)"},
                {"shadow-none", "box-shadow:0 0 #0000"}};
        for (String[] s : shadows) {
            r.add(new String[]{s[0], s[0].equals("shadow-none") ? s[1] : "box-shadow:" + s[1]});
        }
    }

    private void appendEffectUtilities(List<String[]> r) {
        r.add(new String[]{"transition", "transition-property:color,background-color,border-color,text-decoration-color,fill,stroke,opacity,box-shadow,transform,filter,backdrop-filter;transition-timing-function:cubic-bezier(0.4,0,0.2,1);transition-duration:150ms"});
        r.add(new String[]{"transition-colors", "transition-property:color,background-color,border-color,text-decoration-color,fill,stroke;transition-timing-function:cubic-bezier(0.4,0,0.2,1);transition-duration:150ms"});
        r.add(new String[]{"transition-opacity", "transition-property:opacity;transition-timing-function:cubic-bezier(0.4,0,0.2,1);transition-duration:150ms"});
        r.add(new String[]{"transition-shadow", "transition-property:box-shadow;transition-timing-function:cubic-bezier(0.4,0,0.2,1);transition-duration:150ms"});
        r.add(new String[]{"transition-transform", "transition-property:transform;transition-timing-function:cubic-bezier(0.4,0,0.2,1);transition-duration:150ms"});
        r.add(new String[]{"transition-all", "transition-property:all;transition-timing-function:cubic-bezier(0.4,0,0.2,1);transition-duration:150ms"});
        r.add(new String[]{"transition-none", "transition-property:none"});
        String[] durations = {"75", "100", "150", "200", "300", "500", "700", "1000"};
        for (String d : durations) {
            r.add(new String[]{"duration-" + d, "transition-duration:" + d + "ms"});
        }
        r.add(new String[]{"ease-linear", "transition-timing-function:linear"});
        r.add(new String[]{"ease-in", "transition-timing-function:cubic-bezier(0.4,0,1,1)"});
        r.add(new String[]{"ease-out", "transition-timing-function:cubic-bezier(0,0,0.2,1)"});
        r.add(new String[]{"ease-in-out", "transition-timing-function:cubic-bezier(0.4,0,0.2,1)"});
        r.add(new String[]{"transform", "transform:translate(var(--tw-translate-x,0),var(--tw-translate-y,0)) rotate(var(--tw-rotate,0)) scale(var(--tw-scale-x,1),var(--tw-scale-y,1))"});
        String[] opacities = {"0", "10", "20", "25", "30", "40", "50", "60", "70", "75", "80", "90", "95", "100"};
        for (String o : opacities) {
            r.add(new String[]{"opacity-" + o, "opacity:" + (Integer.parseInt(o) / 100.0)});
        }
        r.add(new String[]{"backdrop-blur", "backdrop-filter:blur(8px)"});
        r.add(new String[]{"backdrop-blur-sm", "backdrop-filter:blur(4px)"});
        r.add(new String[]{"backdrop-blur-md", "backdrop-filter:blur(12px)"});
    }

    /**
     * 响应式断点包装：布局关键类生成 {prefix}:xxx 前缀版本（sm:640 / md:768 / lg:1024）
     */
    private void appendResponsiveBlock(StringBuilder css, String prefix, String media, List<String[]> core) {
        css.append("\n@media ").append(media).append(" {\n");
        Pattern candidate = Pattern.compile(
                "(-?m[trblxy]?-.+|p[trblxy]?-.+|gap(-x|-y)?-.+|hidden|block|inline-block|inline$|flex(-row|-col|-wrap|-nowrap|-1|-none|-auto)?|inline-flex|grid$|inline-grid|grid-cols-.+|col-span-.+|row-span-.+|order(-first|-last|-\\d+)?|w-(full|auto|screen)|max-w-.+|min-w-.+|h-(full|auto|screen)|text-(xs|sm|base|lg|xl|\\dxl)|mx-auto|items-.+|justify-.+|self-.+|leading-.+|tracking-.+)");
        for (String[] u : core) {
            if (candidate.matcher(u[0]).matches()) {
                css.append("  .").append(prefix).append("\\:").append(u[0])
                        .append(" { ").append(u[1]).append(" }\n");
            }
        }
        css.append("}\n");
    }

    /**
     * preflight（精简 Tailwind reset）：默认样式归零，utility 完全接管视觉。
     * 注意保留表单元素可用性与图片块级化（img 默认 inline 会产生基线空隙）。
     */
    private static final String PREFLIGHT_CSS = """
            *,::before,::after { box-sizing:border-box; border-width:0; border-style:solid; border-color:var(--color-border,#e2e8f0); }
            html { line-height:1.5; -webkit-text-size-adjust:100%; }
            body { margin:0; font-family:var(--font-sans); color:var(--color-text-primary,#0f172a); background:#fff; }
            hr { height:0; color:inherit; border-top-width:1px; }
            h1,h2,h3,h4,h5,h6 { font-size:inherit; font-weight:inherit; margin:0; }
            p,blockquote,figure,pre { margin:0; }
            ul,ol { list-style:none; margin:0; padding:0; }
            a { color:inherit; text-decoration:inherit; }
            b,strong { font-weight:bolder; }
            img,svg,video,canvas { display:block; max-width:100%; }
            img,video { height:auto; }
            button,input,optgroup,select,textarea { font-family:inherit; font-size:100%; font-weight:inherit; line-height:inherit; color:inherit; margin:0; padding:0; }
            button,[type='button'] { -webkit-appearance:button; background-color:transparent; background-image:none; }
            table { border-collapse:collapse; border-color:inherit; text-indent:0; }
            input::placeholder,textarea::placeholder { color:var(--color-text-muted,#94a3b8); }""";

    /**
     * hover 常用子集（交互态；带 :hover 伪类无法内联，必须在此提供）
     */
    private static final String HOVER_CSS = """
            .hover\\:bg-white:hover { background-color:#fff }
            .hover\\:bg-slate-50:hover { background-color:#f8fafc }
            .hover\\:bg-slate-100:hover { background-color:#f1f5f9 }
            .hover\\:bg-slate-800:hover { background-color:#1e293b }
            .hover\\:bg-gray-50:hover { background-color:#f9fafb }
            .hover\\:bg-gray-100:hover { background-color:#f3f4f6 }
            .hover\\:bg-primary-50:hover { background-color:var(--color-primary-50) }
            .hover\\:bg-primary-600:hover { background-color:var(--color-primary-600) }
            .hover\\:bg-primary-700:hover { background-color:var(--color-primary-700) }
            .hover\\:text-white:hover { color:#fff }
            .hover\\:text-primary-600:hover { color:var(--color-primary-600) }
            .hover\\:text-primary-700:hover { color:var(--color-primary-700) }
            .hover\\:text-slate-900:hover { color:#0f172a }
            .hover\\:text-slate-700:hover { color:#334155 }
            .hover\\:shadow-sm:hover { box-shadow:0 1px 2px 0 rgb(0 0 0 / 0.05) }
            .hover\\:shadow-md:hover { box-shadow:0 4px 6px -1px rgb(0 0 0 / 0.1),0 2px 4px -2px rgb(0 0 0 / 0.1) }
            .hover\\:shadow-lg:hover { box-shadow:0 10px 15px -3px rgb(0 0 0 / 0.1),0 4px 6px -4px rgb(0 0 0 / 0.1) }
            .hover\\:opacity-60:hover { opacity:.6 }
            .hover\\:opacity-70:hover { opacity:.7 }
            .hover\\:opacity-75:hover { opacity:.75 }
            .hover\\:opacity-80:hover { opacity:.8 }
            .hover\\:opacity-90:hover { opacity:.9 }
            .hover\\:scale-95:hover { transform:scale(.95) }
            .hover\\:scale-105:hover { transform:scale(1.05) }
            .hover\\:scale-110:hover { transform:scale(1.1) }
            .hover\\:border-primary-300:hover { border-color:var(--color-primary-300) }
            .hover\\:border-slate-300:hover { border-color:#cbd5e1 }
            .hover\\:underline:hover { text-decoration-line:underline }
            .hover\\:no-underline:hover { text-decoration-line:none }""";

    /**
     * .prose 正文排版（AI 改造文章详情页时高频使用；配合 p-0 的 ul 恢复列表符号）
     */
    private static final String PROSE_CSS = """
            .prose { max-width:65ch; line-height:1.75; color:var(--color-text-secondary,#475569); }
            .prose h1,.prose h2,.prose h3,.prose h4 { color:var(--color-text-primary,#0f172a); font-weight:600; line-height:1.3; margin:1.25em 0 .6em; }
            .prose h1 { font-size:1.5em } .prose h2 { font-size:1.3em } .prose h3 { font-size:1.15em } .prose h4 { font-size:1em }
            .prose p { margin:.75em 0 }
            .prose a { color:var(--color-primary); text-decoration:underline; text-underline-offset:2px }
            .prose ul { list-style-type:disc; padding-left:1.4em; margin:.75em 0 }
            .prose ol { list-style-type:decimal; padding-left:1.4em; margin:.75em 0 }
            .prose li { margin:.3em 0 }
            .prose img { border-radius:var(--radius-lg,.5rem); margin:1em 0 }
            .prose blockquote { border-left:3px solid var(--color-primary); padding-left:1em; color:var(--color-text-muted,#94a3b8); margin:1em 0 }
            .prose table { width:100%; border:1px solid var(--color-border,#e2e8f0); margin:1em 0 }
            .prose th,.prose td { border:1px solid var(--color-border,#e2e8f0); padding:.4em .7em }
            .prose th { background:var(--color-bg-secondary,#f8fafc); font-weight:600 }
            .prose code { font-family:var(--font-mono); background:var(--color-bg-secondary,#f8fafc); padding:.15em .4em; border-radius:.25rem; font-size:.9em }
            .prose pre { background:var(--color-bg-secondary,#f8fafc); padding:1em; border-radius:var(--radius-lg,.5rem); overflow-x:auto; margin:1em 0 }
            .prose pre code { background:transparent; padding:0 }
            .prose hr { margin:1.5em 0 }""";

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
        writePlanRaw(workDir, anchors, pending, done,
                backupDir == null ? null : backupDir.toString(), 0, 0, List.of(), null);
    }

    /**
     * 全量重写计划文件。<strong>透传铁律</strong>：本方法每次从头构建 JSON，
     * 任何新增字段（adjustCount / rejectedDirections / lastRound）必须在
     * 全部调用点（writePlan / restartPlan / markDone / updatePlanLastRound /
     * incrAdjustCount）显式传值或在调用方从旧计划搬运，任一遗漏即静默丢字段。
     *
     * @param adjustCount        上次升级/焕新后的对话修改轮数（问题2b）
     * @param rejectedDirections 已被用户否决的方向资产键集合（问题4）
     */
    private void writePlanRaw(Path workDir, List<String> anchors, List<String> pending,
                              List<String> done, String backupDir, int refreshCount,
                              int adjustCount, List<String> rejectedDirections,
                              JsonNode lastRound) throws IOException {
        var root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.set("anchors", MAPPER.valueToTree(anchors));
        root.set("pending", MAPPER.valueToTree(pending));
        root.set("done", MAPPER.valueToTree(done));
        if (backupDir != null) {
            root.put("backupDir", backupDir);
        }
        root.put("refreshCount", refreshCount);
        root.put("adjustCount", adjustCount);
        root.set("rejectedDirections", MAPPER.valueToTree(
                rejectedDirections == null ? List.of() : rejectedDirections));
        // 上一轮焕新上下文（向后兼容：旧计划无此字段时 null 即不写，焕新走现状逻辑）
        if (lastRound != null && lastRound.isObject()) {
            root.set("lastRound", lastRound);
        }
        Files.writeString(workDir.resolve(UPGRADE_PLAN_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    /**
     * 读取「上次升级/焕新后的对话修改轮数」（问题2b；无计划/旧计划无字段返回 0）。
     * 供 status 接口透出给前端焕新确认弹窗做知情提示。
     */
    public int readAdjustCount(Path workDir) {
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return 0;
        }
        return plan.path("adjustCount").isInt() ? plan.path("adjustCount").asInt() : 0;
    }

    /**
     * 读取已被否决的方向资产键集合（问题4；旧计划无字段返回空集，可变集合供调用方追加）
     */
    public Set<String> readRejectedDirections(Path workDir) {
        JsonNode plan = readPlan(workDir);
        Set<String> rejected = new java.util.LinkedHashSet<>();
        if (plan != null) {
            rejected.addAll(readStringList(plan.path("rejectedDirections")));
        }
        return rejected;
    }

    /**
     * 对话修改轮数 +1（问题2b）：调整型会话每轮成功写盘后由服务层调用。
     * 计划不存在（未升级过的模板）时静默忽略——没有升级计划就没有焕新覆盖风险。
     */
    public void incrAdjustCount(Path workDir) throws IOException {
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return;
        }
        writePlanRaw(workDir, readStringList(plan.path("anchors")),
                readStringList(plan.path("pending")), readStringList(plan.path("done")),
                plan.path("backupDir").isTextual() ? plan.path("backupDir").asString() : null,
                plan.path("refreshCount").isInt() ? plan.path("refreshCount").asInt() : 0,
                readAdjustCount(workDir) + 1,
                readStringList(plan.path("rejectedDirections")),
                plan.path("lastRound").isObject() ? plan.path("lastRound") : null);
    }

    /**
     * 读取上一轮焕新上下文（计划文件 lastRound 字段；旧计划/无计划返回 null，调用方走现状逻辑）
     */
    public LastRoundInfo readLastRound(Path workDir) {
        JsonNode plan = readPlan(workDir);
        if (plan == null) {
            return null;
        }
        JsonNode lr = plan.path("lastRound");
        if (!lr.isObject()) {
            return null;
        }
        Map<String, String> digest = new LinkedHashMap<>();
        JsonNode d = lr.path("structureDigest");
        if (d.isObject()) {
            d.properties().forEach(e -> digest.put(e.getKey(), e.getValue().asString()));
        }
        return new LastRoundInfo(
                lr.path("direction").isTextual() ? lr.path("direction").asString() : null,
                digest,
                lr.path("userFeedback").isTextual() ? lr.path("userFeedback").asString() : null,
                readStringList(lr.path("auditIssues")),
                readFixPatches(lr.path("fixPatches")));
    }

    /**
     * 解析计划文件 lastRound.fixPatches（旧计划无该字段返回空表）
     */
    private Map<String, FileFixPatches> readFixPatches(JsonNode node) {
        Map<String, FileFixPatches> patches = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return patches;
        }
        node.properties().forEach(e -> {
            JsonNode f = e.getValue();
            if (f.isObject()) {
                patches.put(e.getKey(), new FileFixPatches(
                        readStringList(f.path("macroDefaults")),
                        readStringList(f.path("addedAnchors")),
                        readStringList(f.path("scriptDiffs"))));
            }
        });
        return patches;
    }

    /**
     * 收尾时更新计划的 lastRound 为本轮信息（供下一轮焕新回喂）。
     * 本方法在升级管线全部完成、审计结束后调用；pending/done/refreshCount 保持计划现状。
     */
    public void updatePlanLastRound(Path workDir, LastRoundInfo lastRound) throws IOException {
        JsonNode plan = readPlan(workDir);
        if (plan == null || lastRound == null) {
            return;
        }
        var node = MAPPER.createObjectNode();
        if (lastRound.direction() != null) {
            node.put("direction", lastRound.direction());
        }
        node.set("structureDigest", MAPPER.valueToTree(lastRound.structureDigest()));
        node.put("userFeedback", lastRound.userFeedback() == null ? "" : lastRound.userFeedback());
        node.set("auditIssues", MAPPER.valueToTree(lastRound.auditIssues()));
        if (lastRound.fixPatches() != null && !lastRound.fixPatches().isEmpty()) {
            node.set("fixPatches", MAPPER.valueToTree(lastRound.fixPatches()));
        }
        writePlanRaw(workDir, readStringList(plan.path("anchors")),
                readStringList(plan.path("pending")), readStringList(plan.path("done")),
                plan.path("backupDir").isTextual() ? plan.path("backupDir").asString() : null,
                plan.path("refreshCount").isInt() ? plan.path("refreshCount").asInt() : 0,
                plan.path("adjustCount").isInt() ? plan.path("adjustCount").asInt() : 0,
                readStringList(plan.path("rejectedDirections")),
                node);
    }

    // ==================== 上一轮结构指纹摘要 ====================

    /**
     * 提取文件当前（上一轮）版本的结构指纹摘要，用于焕新时回喂给 AI。
     *
     * <p>纯确定性正则/计数（零 token 成本，与 evaluateRefreshScope 的指纹统计同构），
     * 输出每文件 4 行：hero 形态（深色/渐变/超大标题/主色按钮计数）、栅格列数分布、
     * 卡片密度（圆角卡/阴影/分隔线）、分区节奏。摘要必须在 restartPlan 恢复备份底稿
     * <strong>之前</strong>对磁盘当前版本构建（恢复后即为旧稿，摘要失真）。</p>
     *
     * @param relPaths 相对路径清单（不存在的文件跳过）
     */
    public Map<String, String> buildStructureDigest(Path workDir, List<String> relPaths)
            throws IOException {
        Map<String, String> digest = new LinkedHashMap<>();
        if (relPaths == null) {
            return digest;
        }
        for (String rel : relPaths) {
            String n = normalizeRelPath(rel);
            if (n.isEmpty()) {
                continue;
            }
            Path file = workDir.resolve(n).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            digest.put(n, describeStructure(Files.readString(file, StandardCharsets.UTF_8)));
        }
        return digest;
    }

    /**
     * 单文件结构描述（确定性统计，输出 4 行紧凑文本）
     */
    private String describeStructure(String content) {
        int dark = countOccurrences(content, "bg-slate-800", "bg-slate-900",
                "bg-gray-800", "bg-gray-900", "bg-primary-700", "bg-primary-800",
                "bg-primary-900");
        int gradient = countOccurrences(content, "gradient");
        int largeTitle = countOccurrences(content, "text-4xl", "text-5xl",
                "text-6xl", "text-7xl", "text-8xl");
        int primaryBtn = countOccurrences(content, "bg-primary-500", "bg-primary-600");
        StringBuilder grid = new StringBuilder();
        for (int cols : new int[]{2, 3, 4}) {
            int c = countOccurrences(content, "grid-cols-" + cols);
            if (c > 0) {
                grid.append(cols).append("列×").append(c).append(' ');
            }
        }
        int cards = countOccurrences(content, "rounded-xl", "rounded-2xl", "rounded-lg");
        int shadows = countOccurrences(content, "shadow-sm", "shadow-md", "shadow-lg", "shadow-xl");
        int dividers = countOccurrences(content, "border-t");
        int sections = countOccurrences(content, "<section");
        return "- hero: 深色块×" + dark + ", 渐变×" + gradient
                + ", 超大标题×" + largeTitle + ", 主色按钮×" + primaryBtn + "\n"
                + "- 栅格: " + (grid.isEmpty() ? "无网格" : grid.toString().trim()) + "\n"
                + "- 卡片: 圆角卡×" + cards + ", 阴影×" + shadows + ", 分隔线×" + dividers + "\n"
                + "- 节奏: section 区块×" + sections;
    }

    /**
     * 多模式子串计数（结构指纹统计用）
     */
    private static int countOccurrences(String content, String... tokens) {
        int total = 0;
        for (String t : tokens) {
            int idx = 0;
            while ((idx = content.indexOf(t, idx)) >= 0) {
                total++;
                idx += t.length();
            }
        }
        return total;
    }

    // ==================== 功能修复补丁（P1 治 R4） ====================

    /**
     * 宏签名提取（{@code <#macro name param=...>} → 归一化签名字符串集合）
     */
    private static final Pattern MACRO_PATTERN =
            Pattern.compile("<#macro\\s+([^>]+)>");

    /**
     * 补丁提取的单文件清单上限（防补丁体积失控：宏/锚点/脚本各维度截断）
     */
    private static final int PATCH_LIST_LIMIT = 20;

    /**
     * 功能修复补丁提取（确定性，非 AI）：对每个计划文件，对「备份原始稿 vs 当前产物」
     * 做功能维度 diff——宏参数默认值 / JS 依赖锚点补全 / 脚本块差异。
     *
     * <p>提取时机：升级收尾（全部完成 + 审计结束后）。产物补丁落盘到计划文件
     * {@code lastRound.fixPatches}，下一轮焕新恢复备份底稿后回喂提示词并做写盘校验，
     * 修复债务不再随恢复丢失。</p>
     *
     * @param backupDir 原始备份目录（null 返回空表）
     * @param relPaths  计划文件清单（备份/当前任一缺失的文件跳过）
     * @param anchors   站点 JS 依赖锚点清单（"id:xxx"/"class:xxx"，锚点 class 比对范围）
     */
    public Map<String, FileFixPatches> buildFixPatches(Path workDir, Path backupDir,
                                                       List<String> relPaths, List<String> anchors) {
        Map<String, FileFixPatches> patches = new LinkedHashMap<>();
        if (backupDir == null || relPaths == null) {
            return patches;
        }
        Set<String> anchorClasses = new LinkedHashSet<>();
        if (anchors != null) {
            for (String a : anchors) {
                if (a != null && a.startsWith("class:")) {
                    anchorClasses.add(a.substring(6));
                }
            }
        }
        for (String rel : relPaths) {
            String n = normalizeRelPath(rel);
            if (n.isEmpty()) {
                continue;
            }
            Path backupFile = backupDir.resolve(n).normalize();
            Path currentFile = workDir.resolve(n).normalize();
            if (!Files.isRegularFile(backupFile) || !Files.isRegularFile(currentFile)) {
                continue;
            }
            try {
                FileFixPatches fp = diffFunctional(
                        Files.readString(backupFile, StandardCharsets.UTF_8),
                        Files.readString(currentFile, StandardCharsets.UTF_8),
                        anchorClasses);
                if (!fp.macroDefaults().isEmpty() || !fp.addedAnchors().isEmpty()
                        || !fp.scriptDiffs().isEmpty()) {
                    patches.put(n, fp);
                }
            } catch (IOException e) {
                log.warn("功能补丁提取读取失败: {}", n, e);
            }
        }
        return patches;
    }

    /**
     * 补丁存活校验（写盘前）：fixPatches 声明的宏默认值/新增锚点在新内容中必须存活。
     *
     * @return 缺失项清单（与锚点校验同格式："id:xxx"/"class:xxx"，宏默认值额外用
     * "macro:签名" 格式），供既有锚点修复循环回喂，不新增循环
     */
    public List<String> verifyFixPatches(String newContent, FileFixPatches patches) {
        List<String> missing = new ArrayList<>();
        if (newContent == null || patches == null) {
            return missing;
        }
        for (String anchor : patches.addedAnchors()) {
            if (anchor.startsWith("id:")) {
                if (!newContent.contains("id=\"" + anchor.substring(3) + "\"")) {
                    missing.add(anchor);
                }
            } else if (anchor.startsWith("class:")) {
                if (!hasClassToken(newContent, anchor.substring(6))) {
                    missing.add(anchor);
                }
            }
        }
        for (String sig : patches.macroDefaults()) {
            if (!macroDefaultsPresent(newContent, sig)) {
                missing.add("macro:" + sig);
            }
        }
        return missing;
    }

    /**
     * 单文件功能维度 diff（备份稿 vs 产物）：只提取「产物有、备份无」的功能性差异
     */
    private FileFixPatches diffFunctional(String backup, String current, Set<String> anchorClasses) {
        // 宏签名默认值：产物独有的带默认值签名（默认值兜底是渲染修复的高频产物）
        Set<String> backupMacros = extractMacroSignatures(backup);
        List<String> macroDefaults = new ArrayList<>();
        for (String sig : extractMacroSignatures(current)) {
            if (!backupMacros.contains(sig) && sig.contains("=")
                    && macroDefaults.size() < PATCH_LIST_LIMIT) {
                macroDefaults.add(sig);
            }
        }
        // 新增 JS 依赖锚点：产物新增的 id + 锚点 class（备份已有的由既有锚点校验保护）
        Set<String> backupIds = extractIds(backup);
        List<String> addedAnchors = new ArrayList<>();
        for (String id : extractIds(current)) {
            if (!backupIds.contains(id) && addedAnchors.size() < PATCH_LIST_LIMIT) {
                addedAnchors.add("id:" + id);
            }
        }
        for (String cls : anchorClasses) {
            if (hasClassToken(current, cls) && !hasClassToken(backup, cls)
                    && !addedAnchors.contains("class:" + cls)
                    && addedAnchors.size() < PATCH_LIST_LIMIT) {
                addedAnchors.add("class:" + cls);
            }
        }
        // 脚本块差异：产物新增/修改的脚本块摘要（仅提示词回喂，不做写盘校验）
        Set<String> backupScripts = extractScriptBodies(backup);
        List<String> scriptDiffs = new ArrayList<>();
        for (String body : extractScriptBodies(current)) {
            if (!backupScripts.contains(body) && scriptDiffs.size() < PATCH_LIST_LIMIT) {
                scriptDiffs.add("脚本块新增/修改: " + truncatePatchText(body));
            }
        }
        return new FileFixPatches(macroDefaults, addedAnchors, scriptDiffs);
    }

    /**
     * 宏默认值存活判定：同名 {@code <#macro>} 存在，且补丁签名中所有带默认值的参数
     * （含 {@code =} 的 token）在新签名中存在——容忍参数顺序调整，不容忍默认值丢失
     */
    private boolean macroDefaultsPresent(String content, String signature) {
        String norm = signature.replaceAll("\\s+", " ").trim();
        String[] tokens = norm.split(" ");
        Matcher m = Pattern.compile("<#macro\\s+" + Pattern.quote(tokens[0]) + "\\b[^>]*>",
                Pattern.DOTALL).matcher(content);
        while (m.find()) {
            String tag = m.group().replaceAll("\\s+", " ");
            boolean all = true;
            for (String token : tokens) {
                if (token.contains("=") && !tag.contains(token)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractMacroSignatures(String content) {
        Set<String> signatures = new LinkedHashSet<>();
        Matcher m = MACRO_PATTERN.matcher(content);
        while (m.find()) {
            String sig = m.group(1).replaceAll("\\s+", " ").trim();
            if (!sig.isEmpty()) {
                signatures.add(sig);
            }
        }
        return signatures;
    }

    private Set<String> extractIds(String content) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\bid\\s*=\\s*\"([^\"]+)\"").matcher(content);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /**
     * 脚本块正文提取（归一化空白后比对，格式差异不算修改）
     */
    private Set<String> extractScriptBodies(String content) {
        Set<String> bodies = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<script\\b[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE)
                .matcher(content);
        while (m.find()) {
            String body = m.group(1).replaceAll("\\s+", " ").trim();
            if (!body.isEmpty()) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /**
     * 补丁文本摘要（脚本差异等场景，取归一化正文前 80 字符）
     */
    private static String truncatePatchText(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    /**
     * 从备份目录恢复指定页面到工作目录（深度焕新用：焕新必须以原始旧版为底稿重新设计，
     * 否则是在已升级版本上二次改造，无视觉提升）
     *
     * @return 成功恢复的文件数
     */
    private int restorePagesFromBackup(Path workDir, String backupDir, List<String> relPaths) {
        if (backupDir == null || backupDir.isBlank() || relPaths == null || relPaths.isEmpty()) {
            return 0;
        }
        Path backupPath = Path.of(backupDir);
        if (!Files.isDirectory(backupPath)) {
            return 0;
        }
        int restored = 0;
        for (String rel : relPaths) {
            String n = normalizeRelPath(rel);
            if (n.isEmpty()) {
                continue;
            }
            Path src = backupPath.resolve(n).normalize();
            Path dst = workDir.resolve(n).normalize();
            if (Files.isRegularFile(src)) {
                try {
                    if (dst.getParent() != null) {
                        Files.createDirectories(dst.getParent());
                    }
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    restored++;
                } catch (IOException e) {
                    log.warn("深度焕新恢复备份文件失败: {}", rel, e);
                }
            }
        }
        return restored;
    }

    /**
     * 顶层 section 起止标签识别（{@link #locateTopLevelSections} 用；组匹配后按
     * {@code <s} 前缀区分开/闭标签）
     */
    private static final Pattern SECTION_TOKEN_PATTERN =
            Pattern.compile("<section\\b|</section\\s*>", Pattern.CASE_INSENSITIVE);

    /**
     * 顶层 {@code <section>} 区块定位（P2-2 区块级焕新/审计共用）：返回每个顶层
     * section 的 {@code [start, end)} 偏移（end 含闭合标签）。嵌套 section 通过
     * 深度计数归入外层；未闭合的 section 不产出（容错——宁可少切，不切错）
     */
    public static List<int[]> locateTopLevelSections(String content) {
        List<int[]> spans = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return spans;
        }
        Matcher m = SECTION_TOKEN_PATTERN.matcher(content);
        int depth = 0;
        int start = -1;
        while (m.find()) {
            if (m.group().toLowerCase().startsWith("<s")) {
                depth++;
                if (depth == 1) {
                    start = m.start();
                }
            } else if (depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    spans.add(new int[]{start, m.end()});
                    start = -1;
                }
            }
        }
        return spans;
    }

    /**
     * 区块级恢复（P2-2 智能焕新）：把工作目录当前版本中指定序号（1-based 顶层 section）
     * 的区块替换为备份旧稿的对应区块，其余区块（含 section 之间的非区块内容）保留当前版本。
     *
     * <p>降级硬保证：当前/备份区块数不匹配、序号越界或读写异常时返回 false，
     * 由调用方降级为整文件恢复（{@link #restorePagesFromBackup}），管线不中断。</p>
     *
     * @return true = 区块级拼接成功写盘
     */
    private boolean restoreSectionsFromBackup(Path workDir, String backupDir,
                                              String rel, List<Integer> sections) {
        if (backupDir == null || backupDir.isBlank()
                || sections == null || sections.isEmpty() || rel == null) {
            return false;
        }
        Path backupFile = Path.of(backupDir).resolve(rel).normalize();
        Path currentFile = workDir.resolve(rel).normalize();
        if (!Files.isRegularFile(backupFile) || !Files.isRegularFile(currentFile)) {
            return false;
        }
        try {
            String backup = Files.readString(backupFile, StandardCharsets.UTF_8);
            String current = Files.readString(currentFile, StandardCharsets.UTF_8);
            List<int[]> curSpans = locateTopLevelSections(current);
            List<int[]> bakSpans = locateTopLevelSections(backup);
            if (curSpans.isEmpty() || curSpans.size() != bakSpans.size()) {
                return false;
            }
            for (int idx : sections) {
                if (idx < 1 || idx > curSpans.size()) {
                    return false;
                }
            }
            Set<Integer> targets = new LinkedHashSet<>(sections);
            StringBuilder out = new StringBuilder(current.length() + 256);
            int cursor = 0;
            for (int i = 0; i < curSpans.size(); i++) {
                int[] cs = curSpans.get(i);
                out.append(current, cursor, cs[0]);
                if (targets.contains(i + 1)) {
                    out.append(backup, bakSpans.get(i)[0], bakSpans.get(i)[1]);
                } else {
                    out.append(current, cs[0], cs[1]);
                }
                cursor = cs[1];
            }
            out.append(current, cursor, current.length());
            Files.writeString(currentFile, out.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            log.warn("区块级恢复失败（将降级整文件恢复）: {}", rel, e);
            return false;
        }
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
     * 待 AI 改造页面：<strong>_layout.html 置于首位</strong>（站点门面：header/body/script 宏的
     * 视觉焕新对全站观感贡献最大，且先行改造后页面批次可对齐新布局语言）+ 顶层 html。
     *
     * <p>其余 _ 前缀文件仍排除：_articlePage 等宏文件改动风险高、分页逻辑必须原样保留。
     * _layout.html 文件最大最关键，服务层将其单独成批（不与页面拼批，防输出截断）。</p>
     */
    private List<String> listPageFiles(Path workDir) throws IOException {
        List<String> files = new ArrayList<>();
        if (Files.isRegularFile(workDir.resolve("_layout.html"))) {
            files.add("_layout.html");
        }
        List<String> pages = new ArrayList<>();
        try (Stream<Path> stream = Files.list(workDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".html"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .forEach(p -> pages.add(p.getFileName().toString()));
        }
        pages.sort(String::compareTo);
        files.addAll(pages);
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
