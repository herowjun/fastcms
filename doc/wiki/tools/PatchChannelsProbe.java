package probe;

import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.ai.support.AiReasoningSupport;
import com.fastcms.ai.support.AiThinkingBudget;
import com.fastcms.ai.support.FilePatchApplier;
import com.fastcms.ai.template.AiTemplatePatchDto;
import com.fastcms.ai.template.AiTemplateResponseParser;
import com.fastcms.ai.template.TemplateGenPromptBuilder;
import com.fastcms.entity.AiModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补丁协议推广到 4 条修改型链路的真实端点活体验证（2026-10-29）
 *
 * <p>刻意复用生产同款生产类：真实 {@link TemplateGenPromptBuilder}、真实
 * {@link AiTemplateResponseParser}、真实 {@link FilePatchApplier}。</p>
 */
public final class PatchChannelsProbe {

    private static final String BASE_URL = "http://203.176.93.143:30120/v1";
    private static final String MODEL = "Qwen3.6-27B";
    private static final String SAMPLE = "F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-site/index.html";

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "all";
        // 可选：第二个参数覆盖 maxTokens，第三个参数覆盖思考档位（off/low/medium/xhigh）
        int maxTokens = args.length > 1 ? Integer.parseInt(args[1]) : 8192;
        String effort = args.length > 2 ? args[2] : AiReasoningSupport.SCENE_REASONING;
        if ("refine".equals(scenario) || "all".equals(scenario)) {
            probeRefine(maxTokens, effort);
        }
        if ("anchor".equals(scenario) || "all".equals(scenario)) {
            probeAnchor(maxTokens, effort);
        }
        if ("audit".equals(scenario) || "all".equals(scenario)) {
            probeAudit(maxTokens, effort);
        }
        if ("upgrade".equals(scenario) || "all".equals(scenario)) {
            probeUpgrade(maxTokens, effort);
        }
        if ("trunc".equals(scenario)) {
            probeTruncatedBatch(maxTokens, effort);
        }
        if ("upgradeStream".equals(scenario)) {
            probeUpgradeStream(maxTokens, effort);
        }
    }

    // ============ 6. 深度焕新轮（流式，与生产一致）============

    /**
     * 深度焕新轮的<b>流式</b>验证（生产走流式，非流式在长输出时会长时间无反馈）。
     *
     * <p>观测：思考量 / 正文量 / finish_reason / 耗时 / 是否思考吃满。</p>
     */
    private static void probeUpgradeStream(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 深度焕新轮（流式，buildStyleUpgradePrompt） ================");
        // 小页面（便于在 10 分钟 HTTP 超时内跑完，验证链路本身可用）
        String html = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head><meta charset=\"UTF-8\">"
                + "<title>关于</title></head>\n<body>\n"
                + "<header id=\"hero\"><nav><a href=\"index.html\">首页</a></nav></header>\n"
                + "<main><section id=\"about\"><h1>关于我们</h1><p>我们是谁。</p></section>"
                + "<section id=\"features\"><div class=\"card\"><h2>质量</h2></div>"
                + "<div class=\"card\"><h2>效率</h2></div></section></main>\n"
                + "<footer><p>页脚</p></footer>\n</body>\n</html>\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildStyleUpgradePrompt(
                "### index.html\n```html\n" + html + "\n```\n", List.of(), 0, 1, false, 0, null,
                "把按钮改成圆角", null, null);
        System.out.println("提示词长度 = " + prompt.length() + " 字符 | 预算 = " + maxTokens);

        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl(BASE_URL);
        config.setModel(MODEL);
        config.setApiKey("probe-not-used");
        config.setTemperature(0.3);
        config.setMaxTokens(maxTokens);
        OpenAiChatOptions.Builder ob = AiModelConfigServiceImpl.baseOptionsBuilder(config);
        AiReasoningSupport.apply(ob, config, effort);
        ob.maxTokens(maxTokens);
        OpenAiChatOptions options = ob.build();

        ChatClient client = ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();

        final String[] finishReason = new String[1];
        final int[] reasoningChars = {0};
        final int[] contentChars = {0};
        final com.fastcms.ai.support.ReasoningStreamAccumulator acc =
                new com.fastcms.ai.support.ReasoningStreamAccumulator();
        long t0 = System.currentTimeMillis();
        client.prompt()
                .system("你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。")
                .user(prompt)
                .stream()
                .chatResponse()
                .doOnNext(resp -> {
                    if (resp.getResult() == null) {
                        return;
                    }
                    if (resp.getResult().getMetadata() != null) {
                        String fr = resp.getResult().getMetadata().getFinishReason();
                        if (fr != null && !fr.isBlank()) {
                            finishReason[0] = fr;
                        }
                    }
                    var out = resp.getResult().getOutput();
                    if (out == null) {
                        return;
                    }
                    Object rc = out.getMetadata() == null ? null : out.getMetadata().get("reasoningContent");
                    if (rc != null) {
                        String d = acc.feed(String.valueOf(rc));
                        if (d != null) {
                            reasoningChars[0] += d.length();
                        }
                    }
                    if (out.getText() != null) {
                        contentChars[0] += out.getText().length();
                    }
                })
                .blockLast();
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("finish_reason = " + finishReason[0]);
        System.out.println("思考长度     = " + reasoningChars[0] + " 字符");
        System.out.println("正文长度     = " + contentChars[0] + " 字符");
        System.out.println("耗时         = " + elapsed + " ms");
        boolean flooded = AiThinkingBudget.isThinkingFlooded(finishReason[0], contentChars[0],
                reasoningChars[0], null);
        System.out.println(">>> isThinkingFlooded = " + flooded
                + (flooded ? "（生产会走关思考重试）" : ""));
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

    private static String call(ChatClient client, String userPrompt) {
        long t0 = System.currentTimeMillis();
        ChatResponse response = client.prompt()
                .system("你是一个只输出 JSON 的助手，禁止输出 markdown 围栏与解释文字。")
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
        System.out.printf("耗时 = %d ms | usage: in=%s out=%s total=%s | 输出 %d 字符%n",
                elapsed,
                usage == null ? "-" : usage.getPromptTokens(),
                usage == null ? "-" : usage.getCompletionTokens(),
                usage == null ? "-" : usage.getTotalTokens(),
                content.length());
        return content;
    }

    // ============ 1. 微调轮（生成型会话改已生成文件）============

    private static void probeRefine(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 微调轮（buildRefinePrompt） ================");
        String html = Files.readString(Paths.get(SAMPLE), StandardCharsets.UTF_8);

        Path workDir = Files.createTempDirectory("probe-refine-");
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);
        Files.createDirectories(workDir.resolve("static/css"));
        Files.writeString(workDir.resolve("static/css/base.css"),
                ".btn {\n  color: #333;\n  padding: 8px 16px;\n}\n", StandardCharsets.UTF_8);

        String filesWithContent = "### index.html\n```html\n" + html + "\n```\n\n"
                + "### static/css/base.css\n```css\n"
                + Files.readString(workDir.resolve("static/css/base.css"), StandardCharsets.UTF_8)
                + "\n```\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildRefinePrompt(
                "把导航里的「插件市场」改成「精选插件市场」，同时把按钮色 #333 改成 #185FA5",
                filesWithContent);
        System.out.println("提示词长度 = " + prompt.length() + " 字符");

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client, prompt);
        System.out.println("---- 原始响应 ----");
        System.out.println(raw.length() > 2000 ? raw.substring(0, 2000) : raw);

        report(raw, workDir, "index.html");
        reportCss(raw, workDir);
    }

    // ============ 2. 锚点修复轮 ============

    private static void probeAnchor(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 锚点修复轮（buildAnchorFixPrompt） ================");
        String html = Files.readString(Paths.get(SAMPLE), StandardCharsets.UTF_8);

        // 模拟「改造丢了锚点」：把 id="hero" 去掉
        String broken = html.replace("id=\"hero\"", "class=\"hero-box\"");
        Path workDir = Files.createTempDirectory("probe-anchor-");
        Files.writeString(workDir.resolve("index.html"), broken, StandardCharsets.UTF_8);

        String filesWithContent = "### index.html\n```html\n" + broken + "\n```\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildAnchorFixPrompt(
                List.of("id:hero"), filesWithContent);
        System.out.println("提示词长度 = " + prompt.length() + " 字符");

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client, prompt);
        System.out.println("---- 原始响应 ----");
        System.out.println(raw.length() > 2000 ? raw.substring(0, 2000) : raw);

        report(raw, workDir, "index.html");
    }

    // ============ 3. 审计修复轮 ============

    private static void probeAudit(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 审计修复轮（buildAuditFixPrompt） ================");
        String html = Files.readString(Paths.get(SAMPLE), StandardCharsets.UTF_8);

        Path workDir = Files.createTempDirectory("probe-audit-");
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);

        String filesWithContent = "### index.html\n```html\n" + html + "\n```\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildAuditFixPrompt(
                List.of(new com.fastcms.ai.component.LegacyStyleUpgrader.AuditIssue(
                        "index.html", "missing_class", "该 class 在所有 CSS 中都不存在：btn-ghost")),
                filesWithContent, "现代商务风");
        System.out.println("提示词长度 = " + prompt.length() + " 字符");

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client, prompt);
        System.out.println("---- 原始响应 ----");
        System.out.println(raw.length() > 2000 ? raw.substring(0, 2000) : raw);

        report(raw, workDir, "index.html");
    }

    // ============ 4. 深度焕新轮（含 unchanged 协议）============

    private static void probeUpgrade(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 深度焕新轮（buildStyleUpgradePrompt） ================");
        String html = Files.readString(Paths.get(SAMPLE), StandardCharsets.UTF_8);

        Path workDir = Files.createTempDirectory("probe-upgrade-");
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);

        String filesWithContent = "### index.html\n```html\n" + html + "\n```\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildStyleUpgradePrompt(
                filesWithContent, List.of(), 0, 1, false, 0, null,
                "把按钮改成圆角", null, null);
        System.out.println("提示词长度 = " + prompt.length() + " 字符");

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client, prompt);
        System.out.println("---- 原始响应（前 2500 字符）----");
        System.out.println(raw.length() > 2500 ? raw.substring(0, 2500) : raw);

        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- 解析 ----");
        System.out.println("patches  = " + parsed.getPatches().size());
        System.out.println("files    = " + parsed.getFiles().size());
        System.out.println("unchanged= " + parsed.getUnchanged());

        report(raw, workDir, "index.html");
    }

    // ============ 5. 多文件批次截断抢救（模拟 maxTokens 撞顶）============

    /**
     * 用一个「必然截断」的输出上限跑多文件批次：模型会写第 1 个文件（完整）、
     * 第 2 个文件写到一半被切断 → 验证解析器能救回第 1 个文件。
     */
    private static void probeTruncatedBatch(int maxTokens, String effort) throws Exception {
        System.out.println("\n================ 多文件批次截断抢救（maxTokens=" + maxTokens + "） ================");
        String html = Files.readString(Paths.get(SAMPLE), StandardCharsets.UTF_8);

        Path workDir = Files.createTempDirectory("probe-trunc-");
        // 用小页面做第 1 个文件：保证它能在低上限下完整写出，
        // 第 2 个文件较大 → 写到一半被切断 → 验证「救回第 1 个」。
        String small = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head><meta charset=\"UTF-8\">"
                + "<title>关于</title></head>\n<body class=\"bg-white\">\n"
                + "<header class=\"sticky top-0 bg-white border-b\"><nav><a href=\"index.html\">首页</a></nav></header>\n"
                + "<main class=\"max-w-3xl mx-auto py-12\">\n"
                + "<section id=\"hero\" class=\"text-center py-16 bg-primary-50 rounded-xl\">"
                + "<h1 class=\"text-4xl font-bold\">关于我们</h1>"
                + "<p class=\"mt-4 text-slate-600\">我们是谁。</p></section>\n"
                + "<section id=\"features\" class=\"grid grid-cols-3 gap-6 mt-12\">"
                + "<div class=\"card p-6 rounded-lg border\"><h2>质量</h2></div>"
                + "<div class=\"card p-6 rounded-lg border\"><h2>效率</h2></div>"
                + "<div class=\"card p-6 rounded-lg border\"><h2>服务</h2></div></section>\n"
                + "</main>\n<footer class=\"bg-slate-900 text-white py-12\"><p>页脚</p></footer>\n"
                + "</body>\n</html>\n";
        Files.writeString(workDir.resolve("about.html"), small, StandardCharsets.UTF_8);
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);

        String filesWithContent = "### about.html\n```html\n" + small + "\n```\n\n"
                + "### index.html\n```html\n" + html + "\n```\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String prompt = builder.buildStyleUpgradePrompt(
                filesWithContent, List.of(), 0, 2, false, 0, null,
                "把两个页面都视觉重做", null, null);

        ChatClient client = buildClient(effort, maxTokens);
        String raw = call(client, prompt);
        System.out.println("---- 原始响应长度 = " + raw.length() + " 字符 ----");
        System.out.println("尾部 200 字符: " + raw.substring(Math.max(0, raw.length() - 200)));

        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- 解析 ----");
        System.out.println("truncated  = " + parsed.isTruncated());
        System.out.println("files      = " + parsed.getFiles().size());
        parsed.getFiles().forEach(f -> System.out.println("   - " + f.getPath()
                + " (" + len(f.getContent()) + " 字符)"));
        System.out.println("patches    = " + parsed.getPatches().size());
        System.out.println("unchanged  = " + parsed.getUnchanged());
        System.out.println("抢救结果   = " + (parsed.hasSalvagedContent() ? "有产出（未白跑）" : "无产出（白跑）"));
    }

    // ============ 报告 ============

    private static void report(String raw, Path workDir, String expectFile) {
        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- 解析结果 ----");
        System.out.println("files    = " + parsed.getFiles().size());
        parsed.getFiles().forEach(f -> System.out.println("   - " + f.getPath() + " (" + len(f.getContent()) + " 字符)"));
        System.out.println("patches  = " + parsed.getPatches().size());
        parsed.getPatches().forEach(p -> System.out.println("   - path=" + p.getPath()
                + " search=" + len(p.getSearch()) + " replace=" + len(p.getReplace())));
        System.out.println("unchanged= " + parsed.getUnchanged());

        if (!parsed.getPatches().isEmpty()) {
            FilePatchApplier applier = new FilePatchApplier();
            FilePatchApplier.ApplyResult result = applier.apply(workDir, parsed.getPatches());
            System.out.println("---- 补丁应用 ----");
            System.out.println("产物文件数 = " + result.fileContents().size());
            result.fileContents().forEach((k, v) -> System.out.println("   - " + k + " (" + v.length() + " 字符)"));
            System.out.println("失败数 = " + result.failures().size());
            result.failures().forEach(f -> System.out.println("   ! " + f));
            if (!result.fileContents().containsKey(expectFile)) {
                System.out.println("!! 未产出 " + expectFile);
            } else {
                System.out.println("产物长度 = " + result.fileContents().get(expectFile).length()
                        + "（原 " + len(readQuietly(workDir.resolve(expectFile))) + "）");
            }
        }
    }

    private static void reportCss(String raw, Path workDir) {
        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        for (AiTemplatePatchDto p : parsed.getPatches()) {
            if (p.getPath() != null && p.getPath().endsWith(".css")) {
                System.out.println("---- CSS 补丁验证 ----");
                System.out.println("  path = " + p.getPath());
                System.out.println("  search = " + p.getSearch().replace("\n", "\\n"));
                System.out.println("  replace = " + p.getReplace().replace("\n", "\\n"));
                Map<String, String> base = new LinkedHashMap<>();
                base.put(p.getPath(), readQuietly(workDir.resolve(p.getPath())));
                FilePatchApplier.ApplyResult r = new FilePatchApplier().applyToContents(base, List.of(p));
                System.out.println("  应用失败 = " + r.failures());
                r.fileContents().forEach((k, v) -> System.out.println("  产物:\n" + v));
            }
        }
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static int len(String s) {
        return s == null ? -1 : s.length();
    }
}
