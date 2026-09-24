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
package com.fastcms.ai.template.compliance;

import com.fastcms.ai.component.PageSpecRenderer;
import com.fastcms.ai.template.AiTemplateConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 模板规范合规校验/兜底（doc/wiki/ai-template-spec-compliance-remediation-design.md §6-R4）
 *
 * <p>规范合规从"提示词自觉"升级为"代码层确定性保证"的统一收口：所有生成链路
 * （A-pipeline / A-design-import / B 直写 HTML / C 单轮调整）在产物落盘后调用本组件，
 * 做 fastcms 模板规范的结构/内容存在性校验，并对可修复项做确定性兜底补齐。</p>
 *
 * <p><b>铁律：合规检查是兜底增强，绝不能把原本成功的生成变成失败</b>——修复失败
 * 逐项捕获降级为"待处理"播报，不抛出、不阻断生成流程。</p>
 *
 * <p>不做的事（职责边界）：</p>
 * <ul>
 *     <li>不回改已应用正式模板目录的<b>内容</b>（调整型会话 readOnly=true，只校验播报）</li>
 *     <li>不校验视觉/样式质量（MockupAuditor 审计器职责）</li>
 *     <li>不校验 static/js、static/images 的存在性（决议 D2：有才建，无不建）</li>
 *     <li>不重复渲染校验（checkRenderedFiles 已各自持有，本组件不引入 freemarker 引擎）</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
@Component
public class TemplateComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(TemplateComplianceChecker.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 校验项总数（summary 播报"通过 N 项"的分母） */
    private static final int TOTAL_CHECKS = 9;

    /** 基础页清单（规范必备，目录根下） */
    private static final List<String> BASE_PAGES = List.of(
            AiTemplateConstants.FILE_INDEX,
            AiTemplateConstants.FILE_ARTICLE,
            AiTemplateConstants.FILE_ARTICLE_LIST,
            AiTemplateConstants.FILE_PAGE);

    /** _template.properties 必备字段（与 PageSpecRenderer.writeTemplateProperties 一致） */
    private static final List<String> PROPS_FIELDS = List.of(
            "template.id", "template.name", "template.path", "template.version",
            "template.i18n", "template.provider", "template.description");

    /** _layout.html 顶层 include 行（R1：规范分页宏） */
    private static final String ARTICLE_PAGE_INCLUDE = "<#include \"_articlePage.html\">";

    /** 链路标识（决定修复策略差异：missing-base-page 仅直写链路可代码补页） */
    public enum Link { PIPELINE, DESIGN_IMPORT, BATCH_HTML }

    /** 问题严重级：ERROR 进链路 B 修复轮；WARNING 仅播报 */
    public enum Severity { ERROR, WARNING }

    /** 合规播报回调（各链路用不同实现把结果送进自己的 SSE 通道；null 表示静默） */
    @FunctionalInterface
    public interface Sink {
        void send(String message);
    }

    /**
     * 合规问题（code 见检查项清单；fixable 表示本组件可确定性自动修复）
     */
    public record ComplianceIssue(String code, String file, Severity severity, boolean fixable, String detail) {
    }

    /**
     * 校验报告：剩余问题 + 已自动修复的文件（调用方据 hasErrors() 决定是否触发修复轮，
     * 据 fixed() 补文件注册/SSE file 事件）
     */
    public record Report(List<ComplianceIssue> issues, List<String> fixed) {

        public boolean hasErrors() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        }

        /**
         * 播报文案："模板合规检查：通过 N 项 / 已自动修复 M 项（…）/ 待处理 K 项（…）"；
         * 全通过时省略后两段
         */
        public String summary(boolean readOnly) {
            if (issues.isEmpty() && fixed.isEmpty()) {
                return "模板合规检查：" + TOTAL_CHECKS + " 项全部通过";
            }
            long distinctFailedChecks = issues.stream().map(ComplianceIssue::code).distinct().count();
            StringBuilder sb = new StringBuilder("模板合规检查：通过 ")
                    .append(TOTAL_CHECKS - distinctFailedChecks).append(" 项");
            if (!fixed.isEmpty()) {
                sb.append(" / 已自动修复 ").append(fixed.size()).append(" 项（")
                        .append(String.join("、", fixed)).append("）");
            }
            if (!issues.isEmpty()) {
                sb.append(readOnly ? " / 待人工处理 " : " / 待处理 ").append(issues.size()).append(" 项（");
                for (int i = 0; i < issues.size(); i++) {
                    ComplianceIssue issue = issues.get(i);
                    if (i > 0) {
                        sb.append("；");
                    }
                    sb.append(issue.code()).append(": ").append(issue.file());
                    if (issue.detail() != null && !issue.detail().isBlank()) {
                        sb.append("（").append(issue.detail()).append("）");
                    }
                }
                sb.append("）");
            }
            return sb.toString();
        }
    }

    private final PageSpecRenderer pageSpecRenderer;

    public TemplateComplianceChecker(PageSpecRenderer pageSpecRenderer) {
        this.pageSpecRenderer = pageSpecRenderer;
    }

    /**
     * 校验模板工作目录的规范符合度，可修复项做确定性兜底补齐
     *
     * @param workDir  模板工作目录（生成型=会话产物目录；调整型=正式模板目录）
     * @param link     链路标识（决定修复策略差异）
     * @param readOnly 调整型会话（workDir 是正式模板目录）传 true：只校验+播报，不写任何文件
     * @param sink     播报通道；null 表示静默（只返回 Report）
     * @return 校验报告（issues 为修复后仍剩余的问题；fixed 为本次自动修复/补写的文件相对路径）
     * @throws IOException 目录不可读等致命 IO 问题（调用方兜底捕获，不阻断生成）
     */
    public Report check(Path workDir, Link link, boolean readOnly, Sink sink) throws IOException {
        List<ComplianceIssue> issues = new ArrayList<>();
        List<String> fixed = new ArrayList<>();

        // ===== 校验阶段（短路规则：基础页/layout 缺失时 5/6/7 无对象，跳过；3/4/8/9 恒执行） =====
        boolean layoutMissing = checkLayout(workDir, issues);
        List<String> missingBasePages = checkBasePages(workDir, link, issues);
        checkTemplateProps(workDir, issues);
        checkPreviewData(workDir, issues);
        boolean structureBroken = !missingBasePages.isEmpty() || layoutMissing;
        if (!structureBroken) {
            checkPlainHtmlPages(workDir, issues);
            checkMenuDirective(workDir, issues);
            checkArticlePage(workDir, issues);
        }
        checkStaticStructure(workDir, issues);
        checkEmptyJsDir(workDir, issues);

        // ===== 修复阶段（readOnly 恒不修复；逐项捕获，失败降级为"待处理"播报，不抛出） =====
        if (!readOnly) {
            // 顺序敏感：先保证分页宏支撑（_articlePage.html + layout include），
            // 再补基础页（article_list 骨架引用 <@layout._articlePage/>）
            fixArticlePage(workDir, issues, fixed, layoutMissing);
            fixBasePages(workDir, link, issues, fixed, missingBasePages, layoutMissing);
            fixTemplateProps(workDir, issues, fixed);
            fixPreviewData(workDir, issues, fixed);
            fixEmptyJsDir(workDir, issues, fixed);
        }

        Report report = new Report(List.copyOf(issues), List.copyOf(fixed));
        if (sink != null) {
            sink.send(report.summary(readOnly));
        }
        log.info("模板合规检查完成: workDir={}, link={}, readOnly={}, issues={}, fixed={}",
                workDir, link, readOnly, issues.size(), fixed.size());
        return report;
    }

    // ==================== 校验项 ====================

    /** 检查 1：基础页缺失（ERROR；仅直写链路 B 生成/C 生成型可代码补页，其余链路播报人工处理） */
    private List<String> checkBasePages(Path workDir, Link link, List<ComplianceIssue> issues) {
        List<String> missing = new ArrayList<>();
        boolean fixable = link == Link.BATCH_HTML;
        for (String page : BASE_PAGES) {
            if (!Files.isRegularFile(workDir.resolve(page))) {
                missing.add(page);
                issues.add(new ComplianceIssue("missing-base-page", page, Severity.ERROR,
                        fixable, "必备页面文件缺失"));
            }
        }
        return missing;
    }

    /** _layout.html 是否含布局宏定义（单宏 page / 三宏 header 两种合法形态均通过） */
    private boolean hasLayoutMacro(String layoutContent) {
        return layoutContent.contains("<#macro page") || layoutContent.contains("<#macro header");
    }

    /** 三宏布局判定（header/body/script 规范；随包 cms/xjd2022 与 AI 规范段约定） */
    private boolean isTripleMacroLayout(String layoutContent) {
        return !layoutContent.contains("<#macro page") && layoutContent.contains("<#macro header");
    }

    /** _layout.html 顶层 include 插入锚点：单宏取 <#macro page 前；三宏取文件首个 <#macro 前（顶层） */
    private int layoutTopLevelIdx(String content) {
        int macroIdx = content.indexOf("<#macro page");
        if (macroIdx < 0) {
            macroIdx = content.indexOf("<#macro");
        }
        return macroIdx;
    }

    /** 检查 2：_layout.html 缺失或缺布局宏（ERROR；AI 修复轮对象，不自动修） */
    private boolean checkLayout(Path workDir, List<ComplianceIssue> issues) throws IOException {
        Path layout = workDir.resolve(AiTemplateConstants.FILE_LAYOUT);
        if (!Files.isRegularFile(layout)) {
            issues.add(new ComplianceIssue("missing-layout", AiTemplateConstants.FILE_LAYOUT,
                    Severity.ERROR, false, "公共布局文件缺失"));
            return true;
        }
        String content = Files.readString(layout, StandardCharsets.UTF_8);
        if (!hasLayoutMacro(content)) {
            issues.add(new ComplianceIssue("missing-layout", AiTemplateConstants.FILE_LAYOUT,
                    Severity.ERROR, false, "布局文件缺少布局宏定义（<#macro page 或三宏 <#macro header）"));
            return true;
        }
        return false;
    }

    /** 检查 3：_template.properties 缺失或字段不全（ERROR；可自动补齐） */
    private void checkTemplateProps(Path workDir, List<ComplianceIssue> issues) throws IOException {
        Path file = workDir.resolve(AiTemplateConstants.FILE_TEMPLATE_PROPERTIES);
        if (!Files.isRegularFile(file)) {
            issues.add(new ComplianceIssue("missing-template-props",
                    AiTemplateConstants.FILE_TEMPLATE_PROPERTIES, Severity.ERROR, true, "模板元信息文件缺失"));
            return;
        }
        Map<String, String> props = readProps(file);
        for (String field : PROPS_FIELDS) {
            if (!props.containsKey(field) || props.get(field).isBlank()) {
                issues.add(new ComplianceIssue("missing-template-props",
                        AiTemplateConstants.FILE_TEMPLATE_PROPERTIES, Severity.ERROR, true,
                        "缺少必备字段: " + field));
                return;
            }
        }
    }

    /** 检查 4：_preview_data.json 缺失或 menus 为空（ERROR；可自动补齐） */
    private void checkPreviewData(Path workDir, List<ComplianceIssue> issues) {
        Path file = workDir.resolve(AiTemplateConstants.FILE_PREVIEW_DATA);
        if (!Files.isRegularFile(file)) {
            issues.add(new ComplianceIssue("missing-preview-data",
                    AiTemplateConstants.FILE_PREVIEW_DATA, Severity.ERROR, true, "预览演示数据文件缺失"));
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode menus = root.get("menus");
            if (menus == null || !menus.isArray() || menus.isEmpty()) {
                issues.add(new ComplianceIssue("missing-preview-data",
                        AiTemplateConstants.FILE_PREVIEW_DATA, Severity.ERROR, true, "menus 为空"));
            }
        } catch (Exception e) {
            issues.add(new ComplianceIssue("missing-preview-data",
                    AiTemplateConstants.FILE_PREVIEW_DATA, Severity.ERROR, true,
                    "文件解析失败: " + e.getMessage()));
        }
    }

    /** 检查 5：基础页为纯静态 HTML（无 FreeMarker 指令；ERROR；AI 修复轮对象） */
    private void checkPlainHtmlPages(Path workDir, List<ComplianceIssue> issues) throws IOException {
        for (String page : BASE_PAGES) {
            Path file = workDir.resolve(page);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!content.contains("<#")) {
                issues.add(new ComplianceIssue("plain-html-page", page, Severity.ERROR, false,
                        "该页无 FreeMarker 指令，疑似 AI 直写静态 HTML"));
            }
        }
    }

    /** 检查 6：全站无 menuTag（WARNING；菜单为静态链接，CMS 菜单配置不生效） */
    private void checkMenuDirective(Path workDir, List<ComplianceIssue> issues) throws IOException {
        if (scanAllProductFiles(workDir, "menuTag")) {
            return;
        }
        issues.add(new ComplianceIssue("no-menu-directive", "(全站)", Severity.WARNING, false,
                "未发现 <@menuTag>：菜单为静态链接，CMS 后台菜单配置不生效"));
    }

    /** 检查 7：_articlePage.html 缺失且 article_list.html 无内联分页（WARNING；可自动补齐） */
    private void checkArticlePage(Path workDir, List<ComplianceIssue> issues) throws IOException {
        boolean hasMacroFile = Files.isRegularFile(workDir.resolve(AiTemplateConstants.FILE_ARTICLE_PAGE));
        boolean hasInlinePage = false;
        Path articleList = workDir.resolve(AiTemplateConstants.FILE_ARTICLE_LIST);
        if (Files.isRegularFile(articleList)) {
            hasInlinePage = Files.readString(articleList, StandardCharsets.UTF_8).contains("articlePageTag");
        }
        // 内联版（存量模板）= 合规等价态，不判缺失
        if (!hasMacroFile && !hasInlinePage) {
            issues.add(new ComplianceIssue("missing-article-page",
                    AiTemplateConstants.FILE_ARTICLE_PAGE, Severity.WARNING, true,
                    "分页宏文件缺失（article_list.html 亦无内联分页）"));
        }
    }

    /** 检查 8：static/css 目录缺失（ERROR；渲染链路结构问题，不自动修） */
    private void checkStaticStructure(Path workDir, List<ComplianceIssue> issues) {
        if (!Files.isDirectory(workDir.resolve(AiTemplateConstants.DIR_STATIC_CSS))) {
            issues.add(new ComplianceIssue("static-structure", AiTemplateConstants.DIR_STATIC_CSS,
                    Severity.ERROR, false, "static/css 目录缺失"));
        }
    }

    /** 检查 9：static/js 空目录（WARNING；决议 D2——缺目录不是问题，空目录才是） */
    private void checkEmptyJsDir(Path workDir, List<ComplianceIssue> issues) throws IOException {
        Path jsDir = workDir.resolve(AiTemplateConstants.DIR_STATIC_JS);
        if (!Files.isDirectory(jsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(jsDir)) {
            if (stream.findAny().isEmpty()) {
                issues.add(new ComplianceIssue("empty-js-dir", AiTemplateConstants.DIR_STATIC_JS,
                        Severity.WARNING, true, "static/js 为空目录"));
            }
        }
    }

    // ==================== 修复项（逐项捕获，失败降级为待处理） ====================

    /**
     * 保证分页宏支撑就位：_articlePage.html 存在 + _layout.html 顶层 include 存在。
     * 任何落盘"引用 layout._articlePage 的骨架"（补基础页/规范宏文件）前必须调用
     */
    private void ensureArticlePageSupport(Path workDir, List<String> fixed, boolean layoutMissing)
            throws IOException {
        Path macroFile = workDir.resolve(AiTemplateConstants.FILE_ARTICLE_PAGE);
        if (!Files.isRegularFile(macroFile)) {
            Files.writeString(macroFile, AiTemplateConstants.ARTICLE_PAGE_HTML, StandardCharsets.UTF_8);
            if (!fixed.contains(AiTemplateConstants.FILE_ARTICLE_PAGE)) {
                fixed.add(AiTemplateConstants.FILE_ARTICLE_PAGE);
            }
        }
        if (!layoutMissing) {
            patchLayoutInclude(workDir, fixed);
        }
    }

    /** 修复 7：补写 _articlePage.html + _layout.html 顶层 include（保持与渲染器同源） */
    private void fixArticlePage(Path workDir, List<ComplianceIssue> issues,
                                List<String> fixed, boolean layoutMissing) {
        List<ComplianceIssue> targets = issues.stream()
                .filter(i -> "missing-article-page".equals(i.code())).toList();
        if (targets.isEmpty()) {
            return;
        }
        try {
            issues.removeAll(targets);
            ensureArticlePageSupport(workDir, fixed, layoutMissing);
        } catch (Exception e) {
            log.warn("分页宏文件补齐失败（降级为待处理）: {}", e.getMessage());
            issues.add(new ComplianceIssue("missing-article-page",
                    AiTemplateConstants.FILE_ARTICLE_PAGE, Severity.WARNING, false,
                    "自动修复失败: " + e.getMessage()));
        }
    }

    /** 修复 1：补写缺失基础页（组件包正文骨架 + 最小布局页包裹；仅直写链路） */
    private void fixBasePages(Path workDir, Link link, List<ComplianceIssue> issues, List<String> fixed,
                              List<String> missingPages, boolean layoutMissing) {
        if (missingPages.isEmpty() || layoutMissing || link != Link.BATCH_HTML) {
            return;
        }
        String layoutContent = readLayoutOrNull(workDir);
        boolean triple = layoutContent != null && isTripleMacroLayout(layoutContent);
        for (String page : missingPages) {
            try {
                issues.removeIf(i -> "missing-base-page".equals(i.code()) && page.equals(i.file()));
                String base = page.substring(0, page.length() - ".html".length());
                String skeleton = pageSpecRenderer.contentSkeletonOf(base);
                if (skeleton == null) {
                    issues.add(new ComplianceIssue("missing-base-page", page, Severity.ERROR, false,
                            "组件包缺少正文骨架，无法自动补页"));
                    continue;
                }
                // 骨架引用 layout._articlePage：先保证宏文件与 include 就位
                ensureArticlePageSupport(workDir, fixed, layoutMissing);
                StringBuilder html = new StringBuilder();
                html.append("<#import \"_layout.html\" as layout>\n");
                html.append("<#-- 合规兜底补页（AI 未生成该必备页） -->\n");
                html.append("<#assign pageTitle>").append(pageTitleExpr(base)).append("</#assign>\n");
                if (triple) {
                    // 三宏布局（header/body/script）：与随包 cms 页面同形
                    html.append("<@layout.header \">").append(pageTitleExpr(base)).append("\"></@layout.header>\n");
                    html.append("<@layout.body>\n");
                    html.append(skeleton);
                    html.append("</@layout.body>\n");
                    html.append("<@layout.script>\n</@layout.script>\n");
                } else {
                    html.append("<@layout.page>\n");
                    html.append(skeleton);
                    html.append("</@layout.page>\n");
                }
                Files.writeString(workDir.resolve(page), html.toString(), StandardCharsets.UTF_8);
                fixed.add(page);
            } catch (Exception e) {
                log.warn("基础页补齐失败（降级为待处理）: page={}, {}", page, e.getMessage());
                issues.add(new ComplianceIssue("missing-base-page", page, Severity.ERROR, false,
                        "自动修复失败: " + e.getMessage()));
            }
        }
    }

    /** 读 _layout.html 内容（缺失返回 null，不抛） */
    private String readLayoutOrNull(Path workDir) {
        try {
            Path layout = workDir.resolve(AiTemplateConstants.FILE_LAYOUT);
            return Files.isRegularFile(layout)
                    ? Files.readString(layout, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 修复 3：补齐 _template.properties（7 字段；id 沿用已注册值，与渲染器同款保护） */
    private void fixTemplateProps(Path workDir, List<ComplianceIssue> issues, List<String> fixed) {
        List<ComplianceIssue> targets = issues.stream()
                .filter(i -> "missing-template-props".equals(i.code())).toList();
        if (targets.isEmpty()) {
            return;
        }
        try {
            issues.removeAll(targets);
            String name = workDir.getFileName().toString();
            String id = PageSpecRenderer.readExistingTemplateId(workDir);
            if (id == null || id.isBlank()) {
                id = name;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("template.id=").append(id).append('\n');
            sb.append("template.name=").append(name).append('\n');
            sb.append("template.path=/").append(name).append("/\n");
            sb.append("template.version=0.0.1\n");
            sb.append("template.i18n=").append(name).append('\n');
            sb.append("template.provider=ai\n");
            sb.append("template.description=AI 生成模板（合规兜底补齐）\n");
            Files.writeString(workDir.resolve(AiTemplateConstants.FILE_TEMPLATE_PROPERTIES),
                    sb.toString(), StandardCharsets.UTF_8);
            fixed.add(AiTemplateConstants.FILE_TEMPLATE_PROPERTIES);
        } catch (Exception e) {
            log.warn("_template.properties 补齐失败（降级为待处理）: {}", e.getMessage());
            issues.add(new ComplianceIssue("missing-template-props",
                    AiTemplateConstants.FILE_TEMPLATE_PROPERTIES, Severity.ERROR, false,
                    "自动修复失败: " + e.getMessage()));
        }
    }

    /** 修复 4：补齐 _preview_data.json（字段集与 writePreviewData 一致：name/type/suffix/children） */
    private void fixPreviewData(Path workDir, List<ComplianceIssue> issues, List<String> fixed) {
        List<ComplianceIssue> targets = issues.stream()
                .filter(i -> "missing-preview-data".equals(i.code())).toList();
        if (targets.isEmpty()) {
            return;
        }
        try {
            issues.removeAll(targets);
            Path file = workDir.resolve(AiTemplateConstants.FILE_PREVIEW_DATA);
            ObjectNode root;
            if (Files.isRegularFile(file)) {
                // menus 为空但文件存在：只补"首页"项，保留其余内容（含用户换图映射）
                root = (ObjectNode) MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            } else {
                root = MAPPER.createObjectNode();
                ObjectNode seo = root.putObject("seo");
                seo.put("website_title", workDir.getFileName().toString());
                root.putArray("categories");
                root.putArray("singlePages");
            }
            JsonNode menus = root.get("menus");
            if (menus == null || !menus.isArray() || menus.isEmpty()) {
                if (menus == null) {
                    root.putArray("menus");
                }
                ObjectNode home = ((tools.jackson.databind.node.ArrayNode) root.get("menus")).addObject();
                home.put("name", "首页");
                home.put("type", "index");
                home.putArray("children");
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
            fixed.add(AiTemplateConstants.FILE_PREVIEW_DATA);
        } catch (Exception e) {
            log.warn("_preview_data.json 补齐失败（降级为待处理）: {}", e.getMessage());
            issues.add(new ComplianceIssue("missing-preview-data",
                    AiTemplateConstants.FILE_PREVIEW_DATA, Severity.ERROR, false,
                    "自动修复失败: " + e.getMessage()));
        }
    }

    /** 修复 9：删除空 static/js 目录（决议 D2：无 js 不建目录） */
    private void fixEmptyJsDir(Path workDir, List<ComplianceIssue> issues, List<String> fixed) {
        List<ComplianceIssue> targets = issues.stream()
                .filter(i -> "empty-js-dir".equals(i.code())).toList();
        if (targets.isEmpty()) {
            return;
        }
        try {
            issues.removeAll(targets);
            Files.deleteIfExists(workDir.resolve(AiTemplateConstants.DIR_STATIC_JS));
        } catch (Exception e) {
            log.warn("空 static/js 目录删除失败（降级为待处理）: {}", e.getMessage());
            issues.add(new ComplianceIssue("empty-js-dir", AiTemplateConstants.DIR_STATIC_JS,
                    Severity.WARNING, false, "自动修复失败: " + e.getMessage()));
        }
    }

    // ==================== 工具 ====================

    /** _layout.html 顶层补插分页宏 include（首个布局宏定义之前；三宏布局无 page 宏，同样插顶层；已存在则跳过） */
    private void patchLayoutInclude(Path workDir, List<String> fixed) throws IOException {
        Path layout = workDir.resolve(AiTemplateConstants.FILE_LAYOUT);
        String content = Files.readString(layout, StandardCharsets.UTF_8);
        if (content.contains(ARTICLE_PAGE_INCLUDE)) {
            return;
        }
        int macroIdx = layoutTopLevelIdx(content);
        String patched = macroIdx >= 0
                ? content.substring(0, macroIdx) + ARTICLE_PAGE_INCLUDE + "\n" + content.substring(macroIdx)
                : ARTICLE_PAGE_INCLUDE + "\n" + content;
        Files.writeString(layout, patched, StandardCharsets.UTF_8);
        if (!fixed.contains(AiTemplateConstants.FILE_LAYOUT)) {
            fixed.add(AiTemplateConstants.FILE_LAYOUT);
        }
    }

    /** 基础页 title 的 FTL 表达式（运行期取 CMS 数据，与渲染器同形） */
    private String pageTitleExpr(String base) {
        return switch (base) {
            case "index" -> "${seoTag(\"website_title\")!\"\"}";
            case "article_list" -> "${(category.title)!\"\"}";
            case "article" -> "${(article.title)!\"\"}";
            default -> "${(singlePage.title)!\"\"}";
        };
    }

    /** 产物文件中是否含指定标记（html/ftl；排除 design/ 中间态目录） */
    private boolean scanAllProductFiles(Path workDir, String marker) throws IOException {
        try (Stream<Path> stream = Files.walk(workDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String rel = workDir.relativize(f).normalize().toString().replace('\\', '/');
                        String lower = rel.toLowerCase(Locale.ROOT);
                        return !lower.startsWith("design/")
                                && (lower.endsWith(".html") || lower.endsWith(".ftl"));
                    })
                    .anyMatch(f -> {
                        try {
                            return Files.readString(f, StandardCharsets.UTF_8).contains(marker);
                        } catch (IOException e) {
                            return false;
                        }
                    });
        }
    }

    /** 读取 properties（UTF-8，容忍注释与空行；与渲染器手写口径一致） */
    private Map<String, String> readProps(Path file) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                props.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return props;
    }
}
