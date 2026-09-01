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
 * Unless required by applicable law or in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.template;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 模板生成系统提示词构建器
 *
 * <p>封装 fastcms 模板的完整规范：
 * <ol>
 *     <li>目录结构与 _template.properties 配置</li>
 *     <li>_layout.html 宏定义（header / body / script）</li>
 *     <li>FreeMarker 指令清单（articleListTag、menuTag、articlePageTag 等）</li>
 *     <li>上下文变量（article、category、articleVoPage 等）</li>
 *     <li>必备页面（index.html / article.html / article_list.html / page.html）</li>
 *     <li>响应格式（JSON 数组：[{path, content, action}]）</li>
 * </ol>
 *
 * <p>该提示词注入到 ChatClient 系统消息中，确保 AI 生成的文件可直接被 fastcms 识别。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class TemplateGenPromptBuilder {

    /**
     * 构建系统提示词
     *
     * <p>系统提示词是固定的，不随用户需求变化。
     * 用户需求通过 user 消息注入，由 {@link #buildUserPrompt(String, String)} 生成。</p>
     *
     * @param templateName 模板目录名（英文，作为 pathName）
     */
    public String buildSystemPrompt(String templateName) {
        return BASE_SYSTEM_PROMPT
                .replace("${templateName}", templateName);
    }

    /**
     * 构建用户首条消息（初始需求描述）
     *
     * @param templateName 模板目录名
     * @param requirement  用户对模板的需求描述（风格、配色、布局、栏目等）
     */
    public String buildUserPrompt(String templateName, String requirement) {
        return "请为模板目录「" + templateName + "」生成一套完整的网站模板。\n\n"
                + "## 用户需求\n\n" + requirement + "\n\n"
                + "## 输出要求\n\n"
                + "1. 必须包含必备文件：_template.properties、_layout.html、index.html、article.html、article_list.html、page.html\n"
                + "2. 必须生成 _preview_data.json 预览演示数据：菜单/分类/单页/文章标题贴合用户需求主题（如餐饮模板用\"菜品展示/门店故事\"）\n"
                + "3. 至少包含一个基础样式文件 static/css/base.css\n"
                + "4. 静态资源路径使用 ${ctx()} 前缀，例如 <link href=\"${ctx()}/css/base.css\">\n"
                + "5. 页面通过 <#import \"_layout.html\" as layout> 引入布局宏\n"
                + "6. 使用 fastcms 指令渲染动态内容，不要硬编码文章列表\n"
                + "7. 严格按照约定的 JSON 对象格式输出（reply 字段总结生成结果，files 字段为文件数组），不要输出额外解释\n"
                + "8. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建规划轮提示词（分批流水线第一轮：只输出文件清单，不生成内容）
     *
     * <p>生成型会话首次对话不再一次性输出整套模板（单轮输出易超 max_tokens 上限被截断），
     * 而是先让模型规划文件清单（输出量极小、结构上不可能截断），再逐文件生成。</p>
     *
     * @param templateName 模板目录名
     * @param requirement  用户需求描述
     */
    public String buildPlanPrompt(String templateName, String requirement) {
        return "请为模板目录「" + templateName + "」规划一套完整的网站模板。\n\n"
                + "## 用户需求\n\n" + requirement + "\n\n"
                + "## 输出要求\n\n"
                + "1. 本轮只做规划，不生成任何文件内容：files 数组中每一项只包含 path 和 action 两个字段，禁止输出 content 字段\n"
                + "2. 必须涵盖必备文件：_template.properties、_layout.html、index.html、article.html、article_list.html、page.html，"
                + "以及基础样式 static/css/base.css 与预览演示数据 _preview_data.json（菜单/文章标题贴合需求主题）\n"
                + "3. 可根据需求补充其他文件（如 static/js/main.js、_articlePage.html），但文件总数控制在 10 个以内\n"
                + "4. reply 字段简要说明整体设计思路（配色、布局、栏目结构，100 字以内）\n"
                + "5. 严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n"
                + "6. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建单文件生成轮提示词（分批流水线：一次只生成一个文件的完整内容）
     *
     * <p>单文件输出量级天然在几 K token 以内，远低于 max_tokens 上限，
     * 从结构上避免整套模板一次性输出导致的截断问题。</p>
     *
     * @param requirement      用户需求描述
     * @param targetPath       本次要生成的文件相对路径
     * @param existingContext  已生成文件的上下文（文件清单 + _layout.html 完整内容），可为空
     * @param retryHint        重试提示（上次输出截断/格式非法时非空，附加压缩篇幅要求）
     */
    public String buildSingleFilePrompt(String requirement, String targetPath,
                                        String existingContext, String retryHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("请生成模板中的一个文件。\n\n## 用户需求\n\n").append(requirement).append("\n\n");
        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("## 已生成的文件（保持风格一致，复用其中的路径与宏）\n\n")
                    .append(existingContext).append("\n\n");
        }
        sb.append("## 本次任务\n\n")
                .append("只生成文件 `").append(targetPath).append("` 的完整内容。\n\n")
                .append("## 输出要求\n\n")
                .append("1. files 数组只包含一个元素：path 为 `").append(targetPath)
                .append("`，content 为完整文件内容，action 为 create\n")
                .append("2. content 必须是可直接使用的完整内容，禁止省略或输出占位符（如 ... 省略 ...）\n")
                .append("3. 控制篇幅：").append(buildSizeHint(targetPath)).append('\n')
                .append("4. 静态资源路径使用 ${ctx()} 前缀\n")
                .append("5. 严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n")
                .append("6. 请全程使用中文思考和回复\n");
        if (retryHint != null && !retryHint.isBlank()) {
            sb.append("\n## 注意\n\n").append(retryHint).append("\n");
        }
        return sb.toString();
    }

    /**
     * 按文件类型给出差异化的篇幅约束（端到端测试中 CSS 最易超限截断，要求最严格）
     */
    private String buildSizeHint(String targetPath) {
        String ext = targetPath.contains(".")
                ? targetPath.substring(targetPath.lastIndexOf('.') + 1).toLowerCase()
                : "";
        return switch (ext) {
            // CSS 在 JSON 中转义开销最大，最易被 max_tokens 截断：紧凑写法 + 变量复用 + 硬性行数上限
            case "css" -> "采用紧凑写法（每条规则一行），总行数不超过 200 行；"
                    + "主题色/字体/间距用 CSS 变量（:root）统一定义后复用；删除全部注释；"
                    + "响应式只需桌面 + 移动两档断点";
            case "js" -> "总行数不超过 150 行，只实现必要交互（导航切换、回到顶部等），删除全部注释";
            case "properties" -> "只输出配置键值对，不超过 10 行";
            case "json" -> "只输出预览演示数据 JSON，总行数不超过 60 行，字段名与系统提示中的 schema 一致";
            default -> "HTML 文件不超过 250 行，注释精简";
        };
    }

    /**
     * 构建分块规划轮提示词（单文件直出失败后的分块生成路径第一步）
     *
     * <p>针对超出 max_tokens 上限的大文件：先让模型按功能划分块（输出量极小，
     * 结构上不可能截断），再逐块生成（见 {@link #buildChunkPartPrompt}），
     * 从结构上保证任意大小的文件都能生成。</p>
     *
     * @param requirement     用户需求描述
     * @param targetPath      目标文件相对路径
     * @param existingContext 已生成文件上下文（保持风格一致），可为空
     */
    public String buildChunkPlanPrompt(String requirement, String targetPath, String existingContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件 `").append(targetPath).append("` 内容较大，需要分块生成，本轮先做分块规划。\n\n")
                .append("## 用户需求\n\n").append(requirement).append("\n\n");
        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("## 已生成的文件（保持风格一致）\n\n").append(existingContext).append("\n\n");
        }
        sb.append("## 输出要求\n\n")
                .append("只输出一个 JSON 对象（不要包裹 markdown 代码块，不要输出其他字段）：\n")
                .append("{\"total\": 块数, \"outline\": [\"第1块摘要\", \"第2块摘要\", ...]}\n\n")
                .append("1. 块数尽可能少：每块尽量写满（接近块行数上限），通常 2~5 块，绝对不超过 6 块；")
                .append("禁止按单个功能/组件切小块（不要出现\"头部一块、页脚一块\"这种碎片划分）\n")
                .append("2. 按文件结构顺序大块划分，块间内容不重叠、合起来是完整文件\n")
                .append("3. 每块摘要不超过 12 个字（如：变量与基础重置、布局与组件、响应式适配）\n")
                .append("4. 本轮禁止输出任何文件内容\n")
                .append("5. 请全程使用中文思考和回复\n");
        return sb.toString();
    }

    /**
     * 构建单块生成轮提示词（分块生成路径：一次只生成目标文件的一块）
     *
     * <p>每块输出量级远低于 max_tokens 上限，从结构上保证不截断；
     * 单块解析失败时通过 retryHint 压缩篇幅重试该块，错误不传播到其他块。</p>
     *
     * @param requirement     用户需求描述
     * @param targetPath      目标文件相对路径
     * @param partIndex      当前块序号（从 1 开始）
     * @param totalParts     总块数
     * @param partOutline    分块规划轮得到的全部块摘要（让模型明确自己负责哪块）
     * @param existingContext 已生成文件上下文，可为空
     * @param maxLines       本块行数上限（按 max_tokens 动态计算）
     * @param retryHint      重试提示（单块输出截断/格式非法重试时非空）
     */
    public String buildChunkPartPrompt(String requirement, String targetPath, int partIndex, int totalParts,
                                       List<String> partOutline, String existingContext,
                                       int maxLines, String retryHint) {
        StringBuilder outline = new StringBuilder();
        for (int i = 0; i < partOutline.size(); i++) {
            outline.append("第 ").append(i + 1).append(" 块：").append(partOutline.get(i)).append('\n');
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本轮是分块生成模式，只生成文件 `").append(targetPath).append("` 的其中一块。\n\n")
                .append("## 用户需求\n\n").append(requirement).append("\n\n")
                .append("## 分块方案（共 ").append(totalParts).append(" 块）\n\n")
                .append(outline).append('\n');
        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("## 已生成的文件（保持风格一致，复用其中的路径与宏）\n\n")
                    .append(existingContext).append("\n\n");
        }
        sb.append("## 本次任务\n\n")
                .append("只生成第 ").append(partIndex).append('/').append(totalParts)
                .append(" 块（").append(partOutline.get(partIndex - 1)).append("），忽略其他块。\n\n")
                .append("## 输出要求\n\n")
                .append("1. 本轮为分块生成：content 只包含该块的内容（不是完整文件），忽略系统提示中\"content 必须是完整文件\"的要求\n")
                .append("2. files 数组只包含一个元素：path 为 `").append(targetPath)
                .append("`，content 为本块完整内容，action 为 create\n")
                .append("3. 本块不超过 ").append(maxLines).append(" 行，采用紧凑写法，禁止省略或输出占位符\n")
                .append("4. 块首尾保持语法完整（CSS 到完整规则、HTML/JS 到完整标签/语句），不要重复其他块的内容\n")
                .append("5. 静态资源路径使用 ${ctx()} 前缀；严格按照约定的 JSON 对象格式输出，不要包裹 markdown 代码块\n")
                .append("6. 请全程使用中文思考和回复\n");
        if (retryHint != null && !retryHint.isBlank()) {
            sb.append("\n## 注意\n\n").append(retryHint).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建微调提示词（用户在已有会话中提出修改要求）
     *
     * @param requirement 用户的微调需求
     * @param currentFiles 当前会话已生成的文件清单（供 AI 参考上下文）
     */
    public String buildRefinePrompt(String requirement, String currentFiles) {
        return "请基于当前已有的模板文件进行微调。\n\n"
                + "## 微调需求\n\n" + requirement + "\n\n"
                + "## 当前已有文件（相对路径）\n\n" + currentFiles + "\n\n"
                + "## 数据与展示的边界（重要）\n\n"
                + "菜单、分类、标签、单页、文章标题等演示内容由 `_preview_data.json` 驱动。"
                + "凡属于内容增删改的需求（如\"去掉XX菜单\"\"增加一个栏目\"\"更换文章标题\"），"
                + "必须通过修改 _preview_data.json 实现（没有则新建，action=create，menus 等字段填入调整后的完整内容）；"
                + "严禁在模板 HTML 中加入菜单名过滤、内容判断等写死逻辑，模板必须保持数据驱动。\n\n"
                + "## 输出要求\n\n"
                + "1. files 数组中仅输出需要修改或新增的文件，未提及的文件保持不变\n"
                + "2. action 字段：新增文件用 create，修改文件用 modify\n"
                + "3. 严格按照约定的 JSON 对象格式输出（reply 字段说明本次微调内容，files 字段为变更文件数组）\n"
                + "4. 请全程使用中文思考和回复\n";
    }

    /**
     * 构建调整型会话提示词（绑定正式模板的会话，每一轮都携带当前模板文件内容）
     *
     * <p>调整型会话的文件源是正式模板目录，用户可能在两轮对话之间通过编辑器手工修改过文件，
     * 因此每轮都从磁盘读取最新内容注入，保证 AI 始终基于最新状态调整。</p>
     *
     * @param requirement 用户的调整需求
     * @param currentFilesWithContent 当前模板文件及完整内容（从磁盘实时读取）
     */
    public String buildAdjustPrompt(String requirement, String currentFilesWithContent, String currentFile) {
        String currentFileSection = (currentFile == null || currentFile.isBlank()) ? ""
                : "## 用户当前正在查看的页面\n\n"
                + "用户当前正在编辑/预览 `" + currentFile + "`，未明确指定其他页面时请优先调整该页面。\n\n"
                + "注意：页面渲染依赖公共布局文件（如 _layout.html），若调整需求涉及公共部分（导航、页脚等），应修改布局文件而非每个页面。\n\n";
        return "请基于当前正式模板的文件内容进行调整，调整结果将直接写入正式模板。\n\n"
                + "## 调整需求\n\n" + requirement + "\n\n"
                + currentFileSection
                + "## 数据与展示的边界（重要）\n\n"
                + "模板中菜单、分类、标签、单页、文章标题等演示内容由 `_preview_data.json` 驱动（预览数据源）。"
                + "凡属于内容增删改的需求（如\"去掉XX菜单\"\"把某菜单改名为XX\"\"增加一个栏目\"\"更换文章标题\"），"
                + "必须通过修改 _preview_data.json 实现：\n"
                + "- 目录中已有该文件：按其现有结构输出修改后的完整 JSON（action=modify）\n"
                + "- 目录中没有该文件：按系统规范新建，menus 等字段填入调整后的完整内容（action=create）\n"
                + "- 严禁在模板 HTML 中加入菜单名过滤、内容判断等写死逻辑"
                + "（如 <#if item.menuName?contains('XX')>），模板必须保持数据驱动\n\n"
                + "## 当前模板文件（相对路径 + 完整内容）\n\n" + currentFilesWithContent + "\n\n"
                + "## 输出要求\n\n"
                + "1. files 数组中仅输出需要修改或新增的文件，未提及的文件保持不变\n"
                + "2. action 字段：新增文件用 create，修改文件用 modify，删除文件用 delete\n"
                + "3. 修改文件时必须基于上述文件内容输出修改后的完整内容，不要凭空臆造原有内容\n"
                + "4. 严格按照约定的 JSON 对象格式输出（reply 字段说明本次调整内容，files 字段为变更文件数组）\n"
                + "5. 请全程使用中文思考和回复\n"
                + "6. 控制思考时间在最短必要范围：调整方案明确后直接输出，不要反复推演\n";
    }

    /**
     * 构建渲染校验失败后的自动修复提示（调整型会话专用）
     *
     * @param renderErrors           渲染失败文件及错误摘要（非空）
     * @param currentFilesWithContent 当前模板文件内容（已写盘的最新版本）
     * @param currentFile            用户当前聚焦的页面（可空）
     */
    public String buildRenderFixPrompt(List<String> renderErrors, String currentFilesWithContent, String currentFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统对刚写入的模板文件做了渲染校验，以下文件渲染失败（FreeMarker 错误，含文件名与行号）：\n\n");
        for (int i = 0; i < renderErrors.size(); i++) {
            sb.append(i + 1).append(". ").append(renderErrors.get(i)).append('\n');
        }
        sb.append("\n请立即修复以上错误。常见修复方式：\n")
                .append("- 变量空引用（InvalidReferenceException）：改为 `${item.url!''}` 或用 `<#if item?? && item.url??>` 包裹\n")
                .append("- 宏参数不匹配：核对宏定义（如 _layout.html 中 <#macro> 的参数名）与调用处\n")
                .append("- 指令未闭合/语法错误（ParseException）：按行号定位修复\n\n")
                .append((currentFile == null || currentFile.isBlank()) ? ""
                        : "用户当前聚焦页面：" + currentFile + "，优先修复与该页面相关的错误。\n\n")
                .append("## 当前模板文件（渲染失败的最新版本，相对路径 + 完整内容）\n\n")
                .append(currentFilesWithContent).append("\n\n")
                .append("## 输出要求\n\n")
                .append("1. 只输出需要修改的文件（action=modify），基于上述内容给出修复后的完整文件\n")
                .append("2. 修复必须消除报错本身，不要用 try/catch 或判断语句绕过/吞掉错误\n")
                .append("3. 严格按照约定的 JSON 对象格式输出（reply 简述修复了什么，files 为修复后的文件）\n");
        return sb.toString();
    }

    // ==================== 系统提示词常量 ====================

    private static final String BASE_SYSTEM_PROMPT = """
            你是一名资深的前端工程师和 fastcms 模板开发专家，精通 FreeMarker 模板引擎与响应式网页设计。
            你的任务是：根据用户需求，生成符合 fastcms 规范的完整网站模板文件。

            # fastcms 模板规范

            ## 1. 目录结构

            模板目录名为 `${templateName}`，整体结构如下：
            ```
            ${templateName}/
            ├── _template.properties      # 模板元信息（必备）
            ├── _layout.html              # 公共布局宏（必备）
            ├── _articlePage.html         # 文章分页宏（可选，被 _layout.html include）
            ├── index.html                # 首页（必备）
            ├── article.html              # 文章详情页（必备）
            ├── article_list.html         # 文章列表页（必备）
            ├── page.html                 # 单页面（必备）
            ├── _preview_data.json        # 预览演示数据（建议生成，内容贴合需求主题）
            └── static/                   # 静态资源目录
                ├── css/
                │   └── base.css          # 基础样式
                ├── js/
                │   └── main.js           # 基础脚本（可选）
                └── images/               # 图片资源
            ```

            ## 2. _template.properties 模板元信息

            必须包含以下字段，格式为 key=value：
            ```properties
            template.id=www.${templateName}.com
            template.name=${templateName}
            template.path=/${templateName}/
            template.version=0.0.1
            template.i18n=${templateName}
            template.provider=ai-generated
            template.description=AI generated template
            ```
            注意：template.path 必须以 `/` 开头和结尾，pathName 会自动去除前后斜杠得到 `${templateName}`。

            ## 3. _layout.html 公共布局宏

            使用 FreeMarker macro 定义三个核心宏，所有页面通过 `<#import "_layout.html" as layout>` 引入：

            ```html
            <#macro header title>
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>${'$'}{title!""}</title>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="keywords" content="${'$'}{seoTag("website_title")!""}">
              <meta name="description" content="${'$'}{seoTag("website_sub_title")!""}">
              <link href="${'$'}{ctx()}/css/base.css" rel="stylesheet">
              <#nested/>
            </head>
            </#macro>

            <#macro body>
            <body>
              <header>
                <div class="logo"><a href="/"><img src="${'$'}{ctx()}/images/logo.png" alt="Logo"></a></div>
                <nav>
                  <ul>
                    <li><a href="/">首页</a></li>
                    <@menuTag>
                      <#if data?? && (data?size > 0)>
                        <#list data as item>
                          <li><a href="${'$'}{item.url!}" target="${'$'}{item.target!"_self"}">${'$'}{item.menuName!}</a></li>
                        </#list>
                      </#if>
                    </@menuTag>
                  </ul>
                </nav>
              </header>
              <#nested/>
              <footer>
                <p>Copyright © ${'$'}{.now?string("yyyy")} - Powered by Fastcms</p>
              </footer>
            </body>
            </#macro>

            <#macro script>
              <script src="${'$'}{ctx()}/js/main.js"></script>
              <#nested/>
            </body>
            </html>
            </#macro>
            ```
            注意：`<#nested/>` 是宏的占位符，调用方传入的内容会渲染到这里。

            ## 4. FreeMarker 指令清单（fastcms 自定义指令）

            指令使用 `<@指令名 参数=值></@指令名>` 调用，数据通过 `${'$'}{data}` 访问：

            | 指令名 | 作用 | 常用参数 | 返回数据 |
            |---|---|---|---|
            | articleListTag | 文章列表 | categoryId、tagId、includeTagIds、excludeTagIds、orderBy、count | data（List<Article>），每项含 id、title、summary、thumbnail、url、created、viewCount |
            | article | 单篇文章详情 | （由 URL 路由注入） | article 对象，含 id、title、contentHtml、created、viewCount |
            | articlePageTag | 文章分页 | （由 URL 路由注入） | data 对象：total、current、list（页码项）、prev、next、last |
            | menuTag | 站点菜单 | （无） | data（List<Menu>），每项含 menuName、url、target、children |
            | categoryList | 分类列表 | （无） | data（List<Category>），每项含 id、title、url |
            | tagList | 标签列表 | （无） | data（List<Tag>），每项含 id、name、url |
            | singlePageList | 单页列表 | （无） | data（List<SinglePage>），每项含 id、title、url |
            | prevArticleTag | 上一篇 | articleId | data：Article |
            | nextArticleTag | 下一篇 | articleId | data：Article |
            | relatedArticleList | 相关文章 | articleId、count | data：List<Article> |
            | seoTag | SEO 配置项 | key（如 "website_title"） | 直接返回字符串 |
            | ctx | 模板路径前缀 | （无） | 返回当前模板的静态资源根路径，如 /xjd2022/ |
            | i18n | 国际化 | key | 返回对应语言的字符串 |
            | formatTime | 时间格式化 | value、format（如 "yyyy-MM-dd"） | <@formatTime value=(item.created)! format="yyyy-MM-dd"/> |
            | fieldValue | 扩展字段 | （插件扩展） | 扩展字段值 |

            指令使用示例：
            ```html
            <@articleListTag categoryId=3 orderBy="created" count=10>
              <#if data??>
                <#list data as item>
                  <article>
                    <h2><a href="${'$'}{(item.url)!}">${'$'}{(item.title)!}</a></h2>
                    <p>${'$'}{(item.summary)!}</p>
                    <span><@formatTime value=(item.created)! format="yyyy-MM-dd"/></span>
                  </article>
                </#list>
              </#if>
            </@articleListTag>
            ```

            ## 5. 上下文变量（页面级变量，由路由自动注入）

            不同页面会自动注入不同的上下文变量：

            - **index.html**: 无特殊上下文变量（用 articleListTag 主动拉取）
            - **article.html**: 注入 `article` 对象（含 title、contentHtml、created、viewCount、thumbnail、summary）
            - **article_list.html**: 注入 `category` 对象（含 id、title、url）和 `articleVoPage`（分页对象）
              - articleVoPage.records: 当前页文章列表
              - articleVoPage.size、current、total、pages
              - 分页渲染用 `<@articlePageTag>` 指令
            - **page.html**: 注入 `singlePage` 对象（含 id、title、contentHtml）

            ## 6. 文章列表分页示例（article_list.html 关键片段）

            ```html
            <#import "_layout.html" as layout>
            <@layout.header "${'$'}{(category.title)!}列表"></@layout.header>
            <@layout.body>
              <div class="page-title">${'$'}{(category.title)!}列表</div>
              <#if articleVoPage??>
                <#list articleVoPage.records as item>
                  <article>
                    <h2><a href="${'$'}{item.url!}">${'$'}{item.title!}</a></h2>
                    <p>${'$'}{item.summary!}</p>
                  </article>
                </#list>
              </#if>
              <@layout._articlePage/>
            </@layout.body>
            ```
            其中 `<@layout._articlePage/>` 会渲染分页条，分页宏定义在 `_articlePage.html` 中：
            ```html
            <#macro _articlePage>
              <@articlePageTag>
                <div class="pagelist">
                  <a href="${'$'}{data.prev.url!}">${'$'}{data.prev.text!}</a>
                  <#list data.list as item>
                    <a href="${'$'}{item.url!}">${'$'}{item.text!}</a>
                  </#list>
                  <a href="${'$'}{data.next.url!}">${'$'}{data.next.text!}</a>
                </div>
              </@articlePageTag>
            </#macro>
            ```

            ## 7. URL 路由约定（语义化长路径）

            - 首页: /
            - 文章详情: /article/{articleId}
            - 文章分类列表: /article/category/{categoryId}
            - 文章标签列表: /article/tag/{tagId}
            - 单页面: /page/{pageName}

            模板文件名与路由的映射关系（由 fastcms 自动处理，无需在模板中配置）：
            - index.html → /
            - article.html → /article/{id}
            - article_list.html → /article/category/{id}、/article/tag/{id}
            - page.html → /page/{name}

            ## 8. 静态资源引用约定

            所有静态资源（CSS、JS、图片）必须通过 `${'$'}{ctx()}` 前缀引用，它会自动解析为模板根路径：
            ```html
            <link href="${'$'}{ctx()}/css/base.css" rel="stylesheet">
            <script src="${'$'}{ctx()}/js/main.js"></script>
            <img src="${'$'}{ctx()}/images/logo.png" alt="Logo">
            ```
            不要硬编码路径如 `/static/css/...` 或 `/xjd2022/css/...`。

            ## 9. 预览演示数据 _preview_data.json

            模板目录下应包含 `_preview_data.json`，定义模板预览时使用的演示数据（菜单、分类、标签、单页、文章标题、SEO）。
            内容必须贴合用户需求主题：如餐饮模板用"菜品展示/门店故事/在线订座"，科技模板用"新闻动态/产品中心"。
            格式（所有字段可选，未配置的字段使用系统默认演示数据）：
            ```json
            {
              "menus": [
                { "name": "新闻动态", "type": "article_list", "children": [
                  { "name": "公司新闻", "type": "article_list" }
                ]},
                { "name": "关于我们", "type": "page", "suffix": "about" }
              ],
              "categories": ["科技前沿", "产品动态"],
              "tags": ["Java", "Spring Boot"],
              "singlePages": [{ "title": "关于我们", "suffix": "about" }, "服务条款"],
              "articles": {
                "titles": ["文章标题1", "文章标题2"],
                "summaries": ["摘要1", "摘要2"],
                "suffixes": ["news", ""]
              },
              "seo": { "website_title": "站点标题" }
            }
            ```

            字段规则：
            - menus：最多 8 项，最多两级（children 每层最多 6 项），每项含 name、type、可选 suffix、可选 children
            - type 只能取：index、article_list、article、page；省略时默认 article_list
            - suffix 对应模板文件 {type}_{suffix}.html（如 "about" 对应 page_about.html，"about_h5" 对应 page_about_h5.html）；
              配置了 suffix 时必须同时生成对应的模板文件
            - 禁止在 JSON 中写任何 url 字段，预览链接由系统按 type + suffix 自动解析
            - categories/tags/singlePages 数组元素可以是字符串（无 suffix）或 { "title": ..., "suffix": ... } 对象
            - articles 的 titles/summaries/suffixes 是平行数组，最多 12 项；summaries/suffixes 可省略
            - seo 的 key 与 seoTag 指令一致（website_title、website_sub_title、website_seo、public_website_domain）

            ## 10. 响应格式（严格 JSON）

            你的每次回复必须是一个 JSON 对象（不能是数组），包含两个字段：
            ```json
            {
              "reply": "这里是给用户看的自然语言回复：总结本次生成了什么/修改了什么，或直接回答用户的问题",
              "files": [
                {
                  "path": "_template.properties",
                  "content": "template.id=www.${templateName}.com\\ntemplate.name=${templateName}\\n...",
                  "action": "create"
                },
                {
                  "path": "_layout.html",
                  "content": "<#macro header title>\\n...",
                  "action": "create"
                },
                {
                  "path": "index.html",
                  "content": "<#import \\"_layout.html\\" as layout>\\n...",
                  "action": "create"
                },
                {
                  "path": "static/css/base.css",
                  "content": "body { margin: 0; }\\n...",
                  "action": "create"
                }
              ]
            }
            ```

            ### 字段说明
            - **reply**: 给用户的自然语言回复（必填）
              - 生成模板时：简要说明本次生成的模板风格、包含的文件和设计要点
              - 微调时：说明本次修改了哪些文件、做了什么调整
              - 用户提出与模板无关的问题（如咨询、闲聊、询问你的身份）时：直接在 reply 中回答，此时 files 为空数组
            - **files**: 文件数组（必填，可为空数组）
              - **path**: 文件相对路径，相对于模板目录根（如 `index.html`、`static/css/base.css`）
              - **content**: 文件完整内容（字符串，JSON 字符串中的换行用 `\\n`，引号用 `\\"`）
              - **action**: 操作类型
                - `create`: 新建文件
                - `modify`: 修改已有文件
                - `delete`: 删除文件（content 可为空）

            ### 重要约束
            1. **只输出 JSON 对象本身**，不要包裹在 markdown 代码块中，不要添加任何前后文字解释
            2. JSON 必须严格合法，可被 `JSON.parse` 直接解析
            3. content 中的特殊字符必须正确转义：换行符用 `\\n`、双引号用 `\\"`、反斜杠用 `\\\\`
            4. 微调场景下，files 中只输出需要变动的文件，未提及的文件不要重复输出
            5. 不要输出占位符内容（如 `... 省略 ...`），每个文件的 content 必须是可直接使用的完整内容
            6. reply 保持简洁（一般不超过 200 字），详细内容放在文件里

            # 你的行为准则

            1. **遵循规范**：严格遵循上述 fastcms 模板规范，使用正确的指令和宏
            2. **完整可用**：生成的模板必须能被 fastcms 直接识别和应用，不缺文件
            3. **响应式设计**：CSS 应支持桌面和移动端自适应
            4. **可访问性**：HTML 语义化，alt 属性完整，aria 属性适当使用
            5. **性能优先**：CSS 放头部、JS 放尾部，避免内联样式
            6. **不硬编码内容**：动态内容（菜单、文章列表、文章详情）必须用指令渲染，不要写死文章标题
            7. **风格统一**：配色、字体、间距遵循视觉一致性，参考现代化网站设计
            8. **语言要求**：全程使用中文。思考推理过程（reasoning）必须使用中文，reply 回复也必须是中文
            """;
}
