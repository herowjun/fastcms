package probe;

import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.ai.support.AiReasoningSupport;
import com.fastcms.ai.support.FilePatchApplier;
import com.fastcms.ai.template.AiTemplateResponseParser;
import com.fastcms.ai.template.AdjustIntent;
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
import java.util.List;
import java.util.Map;

/**
 * B2/B3/B4 真实端点活体验证探针
 *
 * <p><b>刻意复用生产同款路径</b>：OpenAiChatModel.builder().options(...) 与
 * {@link AiModelConfigServiceImpl#baseOptionsBuilder} + {@link AiReasoningSupport#apply}，
 * 与 {@code AiModelConfigServiceImpl.doBuildChatModel} 完全一致；
 * 提示词用真实 {@link TemplateGenPromptBuilder}，解析用真实 {@link AiTemplateResponseParser}，
 * 落盘用真实 {@link FilePatchApplier}。</p>
 */
public final class LiveProbe {

    private static final String BASE_URL = "http://203.176.93.143:30120/v1";
    private static final String MODEL = "Qwen3.6-27B";

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "all";

        if ("b2".equals(scenario) || "all".equals(scenario)) {
            probeB2();
        }
        if ("b4".equals(scenario) || "all".equals(scenario)) {
            probeB4();
        }
        if ("b3".equals(scenario) || "all".equals(scenario)) {
            probeB3();
        }
    }

    /** 构造与生产完全一致的 ChatClient（options 基底 + 场景档位） */
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
        System.out.println("档位装配: reasoningEffort=" + options.getReasoningEffort()
                + " extraBody=" + options.getExtraBody());

        OpenAiChatModel model = OpenAiChatModel.builder().options(options).build();
        return ChatClient.builder(model).build();
    }

    /** 调用并返回纯文本（走真实 Spring AI 请求组装） */
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
        System.out.printf("耗时 = %d ms | usage: in=%s out=%s total=%s%n",
                elapsed,
                usage == null ? "-" : usage.getPromptTokens(),
                usage == null ? "-" : usage.getCompletionTokens(),
                usage == null ? "-" : usage.getTotalTokens());
        return content;
    }

    // ==================== B2：补丁协议 ====================

    private static void probeB2() throws Exception {
        System.out.println("\n================ B2 补丁协议活体验证 ================");

        Path sample = Paths.get("F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-site/index.html");
        String html = Files.readString(sample, StandardCharsets.UTF_8);
        System.out.println("样本: " + sample + " (" + html.length() + " 字符)");

        Path workDir = Files.createTempDirectory("probe-b2-");
        Files.writeString(workDir.resolve("index.html"), html, StandardCharsets.UTF_8);
        String previewJson = """
                {
                  "menus": [
                    {"menuName": "首页", "url": "/index.html"},
                    {"menuName": "插件市场", "url": "/plugins.html"}
                  ],
                  "articles": []
                }
                """;
        Files.writeString(workDir.resolve("_preview_data.json"), previewJson, StandardCharsets.UTF_8);

        String filesWithContent = "### index.html\n```html\n" + html + "\n```\n\n"
                + "### _preview_data.json\n```json\n" + previewJson + "\n```\n";

        TemplateGenPromptBuilder builder = new TemplateGenPromptBuilder(new SkillRegistry(List.of()));
        String adjustPrompt = builder.buildAdjustPrompt(
                "把首页导航里的「插件市场」改成「精选插件市场」，其他内容保持不变",
                filesWithContent, "index.html", null, null, null, null);

        System.out.println("提示词长度: " + adjustPrompt.length() + " 字符");

        ChatClient client = buildClient(AiReasoningSupport.SCENE_REASONING, 8192);
        String raw = call(client, adjustPrompt);
        System.out.println("---- 原始响应（前 2500 字符）----");
        System.out.println(raw.length() > 2500 ? raw.substring(0, 2500) : raw);
        System.out.println("---- 原始响应总长度: " + raw.length() + " 字符 ----");

        AiTemplateResponseParser parser = new AiTemplateResponseParser();
        AiTemplateResponseParser.ParseResult parsed = parser.parseResponse(raw);
        System.out.println("---- 解析结果 ----");
        System.out.println("reply    = " + trim(parsed.getReply()));
        System.out.println("switchTo = " + parsed.getSwitchTo());
        System.out.println("files    = " + parsed.getFiles().size() + " 个");
        parsed.getFiles().forEach(f -> System.out.println("   - " + f.getPath() + " (" + len(f.getContent()) + " 字符)"));
        System.out.println("patches  = " + parsed.getPatches().size() + " 个");
        parsed.getPatches().forEach(p -> System.out.println("   - path=" + p.getPath()
                + " search=" + len(p.getSearch()) + " 字符 replace=" + len(p.getReplace()) + " 字符"));

        if (!parsed.getPatches().isEmpty()) {
            FilePatchApplier applier = new FilePatchApplier();
            FilePatchApplier.ApplyResult result = applier.apply(workDir, parsed.getPatches());
            System.out.println("---- 补丁应用 ----");
            System.out.println("产物文件数 = " + result.fileContents().size());
            result.fileContents().forEach((k, v) -> System.out.println("   - " + k + " (" + v.length() + " 字符)"));
            System.out.println("失败数 = " + result.failures().size());
            result.failures().forEach(f -> System.out.println("   ! " + f));
            if (!result.fileContents().containsKey("index.html")) {
                System.out.println("!! 补丁未产出 index.html（走了 files 兜底或失败）");
            } else {
                String patched = result.fileContents().get("index.html");
                System.out.println("产物含新文案「精选插件市场」= " + patched.contains("精选插件市场"));
                System.out.println("产物长度 = " + patched.length() + "（原 " + html.length() + "）");
            }
        }

        System.out.println("意图分类: 改动类 → " + AdjustIntent.classify("把首页导航里的「插件市场」改成「精选插件市场」"));
        System.out.println("意图分类: 诊断类 → " + AdjustIntent.classify("这个页面为什么渲染不出来？"));
        System.out.println("意图分类: 否定歧义 → " + AdjustIntent.classify("这个按钮不显示"));
    }

    // ==================== B4：骨架先行 ====================

    private static void probeB4() throws Exception {
        System.out.println("\n================ B4 骨架先行活体验证 ================");

        String skeletonPrompt = """
                【站点需求】做一个插件市场站点
                【设计方向：现代商务风】深色渐变 hero、大标题、粗留白
                【全站页面清单】首页 index.html、插件列表 plugins.html、插件详情 plugin-detail.html
                【本次任务】为页面 index.html（首页）规划页面骨架。
                需响应式：至少 2 个断点 + 移动端汉堡菜单
                【输出契约】只输出一个 JSON 对象，不要 markdown 围栏、不要解释文字：
                {
                  "layout": "一句话描述整页版式",
                  "sections": [
                    {"id": "hero", "title": "首屏主视觉", "purpose": "一句话说明这一段承载什么", "content": "这一段要放的具体内容要点"}
                  ]
                }
                sections 按页面从上到下排列；通常 3~6 段。id 用小写英文短横线。只规划结构与内容，不要写任何 HTML/CSS 代码。""";

        ChatClient client = buildClient(AiReasoningSupport.SCENE_DETERMINISTIC, 4096);
        String raw = call(client, skeletonPrompt);
        System.out.println("---- 骨架响应 ----");
        System.out.println(raw);
        String json = extractJson(raw);
        System.out.println("---- 校验 ----");
        System.out.println("JSON 可解析 = " + (json != null));
        if (json != null) {
            System.out.println("含 layout 字段 = " + json.contains("\"layout\""));
            System.out.println("含 sections 字段 = " + json.contains("\"sections\""));
            System.out.println("section 数量 = " + (json.split("\"id\"", -1).length - 1));
        }
    }

    // ==================== B3：上下文裁剪 ====================

    private static void probeB3() throws Exception {
        System.out.println("\n================ B3 上下文裁剪活体验证 ================");

        Path sample = Paths.get("F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-site/index.html");
        String html = Files.readString(sample, StandardCharsets.UTF_8);

        String summary = com.fastcms.ai.template.design.SiteStructureExtractor.extract(html);
        System.out.println("原文长度 = " + html.length());
        System.out.println("摘要长度 = " + summary.length());
        System.out.println("压缩比 = " + String.format("%.1f%%",
                100.0 * summary.length() / Math.max(1, html.length())));
        System.out.println("---- 摘要内容（前 1500 字符）----");
        System.out.println(summary.length() > 1500 ? summary.substring(0, 1500) : summary);

        String iaPrompt = """
                【上传来源】fastcms-site/index.html
                【首页结构摘要】（已抽取站点结构，未包含样式、脚本与图片）
                %s

                请按分析步骤产出 JSON。导航菜单项是栏目划分的首要依据；若导航缺失，请依据标题层级与链接清单归纳 3~5 个合理栏目。""".formatted(summary);

        ChatClient client = buildClient(AiReasoningSupport.SCENE_DETERMINISTIC, 4096);
        String raw = call(client, iaPrompt);
        System.out.println("---- 基于摘要的 IA 输出 ----");
        System.out.println(raw.length() > 2000 ? raw.substring(0, 2000) : raw);
    }

    // ==================== 工具 ====================

    private static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static int len(String s) {
        return s == null ? -1 : s.length();
    }
}
