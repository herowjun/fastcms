package probe;

import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.ai.support.AiReasoningSupport;
import com.fastcms.ai.template.AiTemplateResponseParser;
import com.fastcms.ai.template.TemplateGenPromptBuilder;
import com.fastcms.ai.template.compliance.InlineAssetExtractor;
import com.fastcms.ai.template.compliance.TemplateComplianceChecker;
import com.fastcms.entity.AiModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CSS/JS 分离改造的活体验证（2026-10-29）
 *
 * <p>验证两件事：</p>
 * <ol>
 *   <li><b>提示词层</b>：真实提示词（{@link TemplateGenPromptBuilder}）发给真实端点，
 *       看 AI 是否<b>主动</b>把 CSS/JS 写成独立文件（而不是内联进 HTML）；</li>
 *   <li><b>兜底层</b>：即便 AI 不听话，合规校验器
 *       （{@link TemplateComplianceChecker} + {@link InlineAssetExtractor}）
 *       能否<b>确定性</b>地把内联 CSS/JS 抽成独立文件。</li>
 * </ol>
 *
 * <p>刻意复用生产同款类，不另写模拟实现。</p>
 */
public final class AssetSeparationProbe {

    private static final String BASE_URL = "http://203.176.93.143:30120/v1";
    private static final String MODEL = "Qwen3.6-27B";

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "all";
        int maxTokens = args.length > 1 ? Integer.parseInt(args[1]) : 16384;
        String effort = args.length > 2 ? args[2] : AiReasoningSupport.SCENE_REASONING;

        if ("generate".equals(scenario) || "all".equals(scenario)) {
            probeGenerate(maxTokens, effort);
        }
        if ("induce".equals(scenario) || "all".equals(scenario)) {
            probeInduced(maxTokens, effort);
        }
        if ("manual".equals(scenario) || "all".equals(scenario)) {
            probeManualInline(maxTokens, effort);
        }
        if ("extract".equals(scenario) || "all".equals(scenario)) {
            probeExtract();
        }
    }

    private static ChatClient buildClient(String sceneEffort, int maxTokens) {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl(BASE_URL);
        config.setModel(MODEL);
        config.setApiKey("probe-not-used");
        config.setTemperature(0.3);
        config.setMaxTokens(maxTokens);
        OpenAiChatOptions.Builder builder = AiModelConfigServiceImpl.baseOptionsBuilder(config);
        AiReasoningSupport.apply(builder, config, sceneEffort);
        OpenAiChatOptions options = builder.build();
        System.out.println("档位: reasoningEffort=" + options.getReasoningEffort()
                + " extraBody=" + options.getExtraBody());
        return ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();
    }

    private static String call(ChatClient client, String systemPrompt, String userPrompt) {
        long t0 = System.currentTimeMillis();
        ChatResponse response = client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();
        long elapsed = System.currentTimeMillis() - t0;
        String content = "";
        if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null) {
            content = response.getResult().getOutput().getText();
        }
        var usage = response == null ? null : response.getMetadata().getUsage();
        System.out.printf("耗时 = %d ms | usage: in=%s out=%s | 输出 %d 字符%n",
                elapsed,
                usage == null ? "-" : usage.getPromptTokens(),
                usage == null ? "-" : usage.getCompletionTokens(),
                content.length());
        return content;
    }

    // ============ 1. 生成型：AI 是否主动把 CSS/JS 分开 ============

    /**
     * 走真实的「单文件生成轮」提示词，让端点生成 index.html，
     * 检查 AI 是否把 CSS/JS 写进独立文件（files 里出现 static/css/*.css），
     * 而不是全部内联进 HTML。
     */
    private static void probeGenerate(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 生成型：AI 是否主动分离 CSS/JS ================");

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String requirement = "做一个宠物咖啡馆的单页首页：顶部导航（含汉堡菜单）、主视觉大图区、"
                + "「今日特调」三列卡片区、页脚。需要滚动到顶部的回到顶部按钮与卡片 hover 动效。";
        // 走真实的单文件生成轮提示词（HTML 目标文件）
        String prompt = builder.buildSingleFilePrompt(requirement, "index.html",
                "", "", true);
        System.out.println("提示词长度 = " + prompt.length() + " 字符");
        System.out.println("---- 提示词中的 CSS/JS 分离约束片段 ----");
        int idx = prompt.indexOf("CSS/JS 分离");
        if (idx >= 0) {
            System.out.println(prompt.substring(Math.max(0, idx - 20),
                    Math.min(prompt.length(), idx + 420)));
        }

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client,
                "你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。", prompt);

        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- 解析 ----");
        System.out.println("files = " + parsed.getFiles().size());
        parsed.getFiles().forEach(f -> System.out.println("   - " + f.getPath()
                + " (" + f.getContent().length() + " 字符)"));

        // 判定：AI 产出的 HTML 是否内联了大量 CSS/JS
        int inlineViolations = 0;
        int separatedFiles = 0;
        for (var f : parsed.getFiles()) {
            String path = f.getPath() == null ? "" : f.getPath();
            String content = f.getContent() == null ? "" : f.getContent();
            if (path.endsWith(".css") || path.endsWith(".js")) {
                separatedFiles++;
            }
            if (path.endsWith(".html")) {
                List<InlineAssetExtractor.Violation> vs =
                        InlineAssetExtractor.detect(content, path);
                System.out.println("   [内联检测] " + path + " → 违规 " + vs.size() + " 项"
                        + (vs.isEmpty() ? "" : " " + vs.stream()
                        .map(v -> v.type() + "(" + v.chars() + "字符/" + v.lines() + "行)")
                        .toList()));
                inlineViolations += vs.size();
                // 看 HTML 是否用了引用引入
                boolean hasLink = content.contains("<link rel=\"stylesheet\"");
                boolean hasScriptSrc = content.contains("<script src=");
                System.out.println("   [引用检查] 含 <link rel=\"stylesheet\"> = " + hasLink
                        + " / 含 <script src= = " + hasScriptSrc);
            }
        }
        System.out.println("---- 结论 ----");
        System.out.println("本轮产出中的独立 CSS/JS 文件数 = " + separatedFiles
                + "（单文件轮只产出目标文件，CSS/JS 在后续轮次生成）");
        System.out.println("HTML 内联违规数 = " + inlineViolations);
        if (inlineViolations == 0) {
            System.out.println("✅ AI 主动分离：HTML 只放结构，样式/脚本走引用引入"
                    + "（是否引用了尚不存在的 CSS/JS 文件，由后续文件轮补齐）");
        } else {
            System.out.println("⚠ AI 未完全遵守 → 由合规校验器确定性抽取兜底");
        }
    }

    // ============ 2. 诱导场景：AI 被诱导内联时，兜底层能否救回来 ============

    /**
     * 用「方便预览」的诱导性需求（历史上 AI 确实会这么干）逼 AI 把 CSS 写进 HTML，
     * 然后把 AI 的<b>真实产物</b>落盘，交给真实合规校验器 → 验证兜底层在真实产物上生效。
     */
    private static void probeInduced(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 诱导场景：AI 内联 → 合规校验器兜底 ================");

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        // 诱导性需求：明确要求「样式都写在这个页面里，方便单文件预览」
        String requirement = "做一个活动落地页，所有 CSS 样式都写在这个 HTML 页面里（方便单文件预览），"
                + "要有大标题、倒计时区块、报名表单和页脚，配色用紫蓝渐变，样式写详细一点。";
        String prompt = builder.buildSingleFilePrompt(requirement, "index.html", "", "", true);

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client,
                "你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。", prompt);

        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- AI 原始产物 ----");
        System.out.println("files = " + parsed.getFiles().size());
        parsed.getFiles().forEach(f -> System.out.println("   - " + f.getPath()
                + " (" + f.getContent().length() + " 字符)"));

        // 落盘 AI 的真实产物
        Path workDir = Files.createTempDirectory("probe-induced-");
        Files.createDirectories(workDir.resolve("static/css"));
        for (var f : parsed.getFiles()) {
            Path target = workDir.resolve(f.getPath()).normalize();
            if (!target.startsWith(workDir)) continue;
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, f.getContent(), StandardCharsets.UTF_8);
        }
        Path index = workDir.resolve("index.html");
        if (!Files.exists(index)) {
            System.out.println("!! AI 未产出 index.html，跳过");
            return;
        }
        String before = Files.readString(index, StandardCharsets.UTF_8);
        List<InlineAssetExtractor.Violation> vs =
                InlineAssetExtractor.detect(before, "index.html");
        System.out.println("落盘后内联检测 = " + vs.size() + " 项 "
                + vs.stream().map(v -> v.type() + "(" + v.chars() + "字符/" + v.lines() + "行)")
                .toList());

        // 交给真实合规校验器
        TemplateComplianceChecker checker =
                new TemplateComplianceChecker(new com.fastcms.ai.component.PageSpecRenderer(null, null));
        TemplateComplianceChecker.Report report = checker.check(workDir,
                TemplateComplianceChecker.Link.BATCH_HTML, false,
                msg -> System.out.println("[播报] " + msg));

        String after = Files.readString(index, StandardCharsets.UTF_8);
        System.out.println("---- 兜底结果 ----");
        System.out.println("已自动修复 = " + report.fixed());
        System.out.println("HTML 体积 " + before.length() + " → " + after.length() + " 字符");
        System.out.println("仍含 <style> = " + after.contains("<style>"));
        System.out.println("含 link 引用 = " + after.contains("<link rel=\"stylesheet\""));
        System.out.println("含 script src = " + after.contains("<script src="));
        System.out.println("静态资源文件 = "
                + (Files.exists(workDir.resolve("static/css/inline-index.css"))
                ? "static/css/inline-index.css 已生成" : "未生成"));
        System.out.println("兜底后残余 inline-assets 问题 = "
                + report.issues().stream().filter(i -> "inline-assets".equals(i.code())).count());
    }

    // ============ 3. 人工内联保护：人工写的 CSS/JS 不该被 AI 顺手拆掉 ============

    /**
     * 场景：人工在正式模板里手写了一段内联 CSS（合法写法）→ 让 AI 改样式。
     * 验证两件事：
     * <ol>
     *   <li>AI <b>不会</b>顺手把人工的内联 CSS 拆到独立文件（修改型提示词已声明「保持原样」）；</li>
     *   <li>合规校验器在 readOnly 模式下只提示（WARNING），不改人工代码、不触发 AI 重写。</li>
     * </ol>
     */
    private static void probeManualInline(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 人工内联保护 ================");

        // 人工手写的内联 CSS（模板里已存在）
        String manualCss = "  .hero { background: linear-gradient(135deg, #185FA5, #0d3b66); padding: 64px 0; }\n"
                + "  .hero-title { color: #fff; font-size: 42px; font-weight: 700; }\n"
                + "  .hero-sub { color: rgba(255,255,255,.85); margin-top: 16px; }\n";
        String html = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"UTF-8\">\n<title>首页</title>\n"
                + "<style>\n" + manualCss + "</style>\n"
                + "</head>\n<body>\n"
                + "<section id=\"hero\" class=\"hero\">\n"
                + "  <h1 class=\"hero-title\">宠物咖啡馆</h1>\n"
                + "  <p class=\"hero-sub\">今天想喝点什么？</p>\n"
                + "</section>\n"
                + "</body>\n</html>\n";

        Path workDir = Files.createTempDirectory("probe-manual-");
        Files.createDirectories(workDir.resolve("static/css"));
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);
        // 配套的独立样式文件（人工也写了 CSS 文件）
        Files.writeString(workDir.resolve("static/css/base.css"),
                ":root { --c-primary: #185FA5; }\n", StandardCharsets.UTF_8);

        System.out.println("人工内联 CSS = " + manualCss.length() + " 字符 / "
                + manualCss.split("\n").length + " 行（阈值内 → 合规放行）");

        // 走修改型提示词（微调轮），让 AI 改 hero 标题颜色
        String filesWithContent = "### index.html\n```html\n" + html + "\n```\n\n"
                + "### static/css/base.css\n```css\n"
                + Files.readString(workDir.resolve("static/css/base.css"), StandardCharsets.UTF_8)
                + "\n```\n";
        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildRefinePrompt("把 hero 标题颜色改成金色 #F59E0B", filesWithContent);

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client,
                "你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。", prompt);

        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- AI 产出 ----");
        System.out.println("files = " + parsed.getFiles().size());
        parsed.getFiles().forEach(f -> System.out.println("   - " + f.getPath()
                + " (" + f.getContent().length() + " 字符)"));
        System.out.println("patches = " + parsed.getPatches().size());
        parsed.getPatches().forEach(p -> System.out.println("   - path=" + p.getPath()
                + " search=" + p.getSearch().replace("\n", "\\n")
                + " replace=" + p.getReplace().replace("\n", "\\n")));

        // 判定：AI 是否动了内联 CSS（不应该把它拆走，也不应该无谓重写整页）
        boolean touchedInline = false;
        for (var p : parsed.getPatches()) {
            if (p.getSearch() != null && p.getSearch().contains(".hero-title")) {
                touchedInline = true;
            }
        }
        for (var f : parsed.getFiles()) {
            if (f.getPath() != null && f.getPath().endsWith(".html")
                    && f.getContent() != null && !f.getContent().contains("<style>")) {
                touchedInline = true;
            }
        }
        System.out.println("AI 是否动了内联 CSS 块 = " + touchedInline
                + "（改颜色本身可以，但不该把整块拆走/丢掉）");

        // 合规校验器：readOnly=true（正式模板目录）→ 只提示不改
        TemplateComplianceChecker checker =
                new TemplateComplianceChecker(new com.fastcms.ai.component.PageSpecRenderer(null, null));
        TemplateComplianceChecker.Report report = checker.check(workDir,
                TemplateComplianceChecker.Link.BATCH_HTML, true,
                msg -> System.out.println("[播报] " + msg));
        System.out.println("---- 校验器行为（readOnly=true）----");
        System.out.println("已自动修复 = " + report.fixed() + "（应为空：不能改人工代码）");
        System.out.println("inline-assets 问题 = "
                + report.issues().stream().filter(i -> "inline-assets".equals(i.code()))
                .map(i -> i.severity() + " / " + i.detail()).toList());
        String after = Files.readString(workDir.resolve("index.html"), StandardCharsets.UTF_8);
        System.out.println("人工内联 CSS 是否原样保留 = " + after.contains(".hero-title"));
        System.out.println("是否被抽到独立文件 = "
                + Files.exists(workDir.resolve("static/css/inline-index.css")));
    }

    // ============ 4. 兜底层：合规校验器的确定性抽取 ============

    /**
     * 不依赖端点，直接验证「AI 不听话时」的第二层兜底：
     * 造一个内联大段 CSS/JS 的 HTML → 跑真实合规校验器 → 看是否自动抽出独立文件。
     */
    private static void probeExtract() throws Exception {
        System.out.println("\n================ 兜底层：合规校验器确定性抽取 ================");

        Path workDir = Files.createTempDirectory("probe-extract-");
        Files.createDirectories(workDir.resolve("static/css"));
        // 用「AI 真实会写出的大段内联」规模（超过 2000 字符阈值），否则会落在阈值内被放行
        StringBuilder cssSb = new StringBuilder("/* 内联样式 */\n");
        for (int i = 0; i < 60; i++) {
            cssSb.append(".cls-").append(i)
                    .append(" { display: flex; align-items: center; gap: ").append(i * 2)
                    .append("px; color: #185FA5; padding: ").append(i).append("px 16px; }\n");
        }
        String bigCss = cssSb.toString();
        StringBuilder jsSb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            jsSb.append("function fn").append(i)
                    .append("() { var el = document.querySelector('.cls-").append(i)
                    .append("'); return el ? el.dataset.id : null; }\n");
        }
        String bigJs = jsSb.toString();
        String html = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"UTF-8\">\n<title>宠物咖啡馆</title>\n"
                + "<style>\n" + bigCss + "</style>\n"
                + "</head>\n<body>\n"
                + "<section class=\"hero\"><h1>欢迎</h1></section>\n"
                + "<script>\n" + bigJs + "</script>\n"
                + "</body>\n</html>\n";
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);
        System.out.println("输入: index.html " + html.length() + " 字符，内联 CSS "
                + bigCss.length() + " 字符 / 内联 JS " + bigJs.length() + " 字符");

        TemplateComplianceChecker checker =
                new TemplateComplianceChecker(new com.fastcms.ai.component.PageSpecRenderer(null, null));
        TemplateComplianceChecker.Report report =
                checker.check(workDir, TemplateComplianceChecker.Link.BATCH_HTML, false,
                        msg -> System.out.println("[播报] " + msg));

        System.out.println("---- 结果 ----");
        System.out.println("已自动修复 = " + report.fixed());
        System.out.println("剩余问题   = " + report.issues().size());
        report.issues().forEach(i -> System.out.println("   ! " + i.code() + " " + i.file()
                + " " + i.severity() + " " + i.detail()));

        Path cssFile = workDir.resolve("static/css/inline-index.css");
        Path jsFile = workDir.resolve("static/js/inline-index.js");
        System.out.println("抽出 CSS 文件存在 = " + Files.exists(cssFile));
        System.out.println("抽出 JS  文件存在 = " + Files.exists(jsFile));
        String rewritten = Files.readString(workDir.resolve("index.html"), StandardCharsets.UTF_8);
        System.out.println("改写后 HTML 含 link  = "
                + rewritten.contains("<link rel=\"stylesheet\" href=\"${ctx()}/css/inline-index.css\">"));
        System.out.println("改写后 HTML 含 script= "
                + rewritten.contains("<script src=\"${ctx()}/js/inline-index.js\"></script>"));
        System.out.println("改写后 HTML 仍含 <style> = " + rewritten.contains("<style>"));
        System.out.println("HTML 体积 " + html.length() + " → " + rewritten.length() + " 字符");
        if (Files.exists(cssFile)) {
            System.out.println("---- 抽出的 CSS ----");
            System.out.println(Files.readString(cssFile, StandardCharsets.UTF_8));
        }
    }
}
