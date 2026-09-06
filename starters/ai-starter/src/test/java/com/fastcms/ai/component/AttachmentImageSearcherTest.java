package com.fastcms.ai.component;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fastcms.entity.Attachment;
import com.fastcms.service.IAttachmentService;
import com.fastcms.ai.template.AiTemplatePreviewRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AttachmentImageSearcher：media 槽位 search: 引用解析闭环测试
 *
 * <p>覆盖：附件库命中（含 imageAssets 记录）/ 演示图兜底（含落盘与主题匹配）/
 * 解析后 spec 的渲染链路（ctx() 表达式 + 预览渲染校验通过）。</p>
 */
class AttachmentImageSearcherTest {

    private final ComponentRegistry registry =
            new ComponentRegistry(List.of(new BuiltinTailwindPackProvider()));
    private final PageSpecRenderer renderer = new PageSpecRenderer(registry, new TokenEngine());
    private final AiTemplatePreviewRenderer previewRenderer = new AiTemplatePreviewRenderer();

    @TempDir
    Path tempDir;

    private PageSpec specWithHeroImage(String imageValue) {
        Map<String, Object> heroData = new LinkedHashMap<>();
        heroData.put("title", "创新土鸡");
        heroData.put("image", imageValue);
        return new PageSpec(
                PageSpec.SPEC_VERSION, BuiltinTailwindPackProvider.FOUNDATION,
                "image-demo", "演示", "farm-site", "warm", "#ea580c", null,
                Map.of(PageSpec.PAGE_INDEX, new PageSpecPage(List.of(
                        new SectionSpec("hero", "tw:hero", "split", heroData),
                        new SectionSpec("footer", "tw:footer", "simple", Map.of("brand", "演示"))))),
                null);
    }

    private Attachment imageAttachment(Long id, String fileName, String filePath) {
        Attachment att = new Attachment();
        att.setId(id);
        att.setFileName(fileName);
        att.setFilePath(filePath);
        att.setFileType(Attachment.TYPE_IMAGE);
        return att;
    }

    @Test
    void shouldFallbackToDemoWhenAttachmentServiceUnavailable() {
        // 附件服务不可用（如无应用上下文）：加载图片池防御性降级为空，全部走演示图兜底
        AttachmentImageSearcher searcher = new AttachmentImageSearcher(registry, null);
        PageSpec spec = specWithHeroImage("search:产品主图 科技感");

        AttachmentImageSearcher.Result result = searcher.resolve(spec, tempDir);

        // 槽位值替换为模板内演示图引用
        Object image = result.spec().pages().get("index").safeSections().get(0).safeData().get("image");
        assertEquals("static/images/demo-product.svg", image);
        // 演示图落盘（模板自包含）
        assertTrue(Files.isRegularFile(tempDir.resolve("static/images/demo-product.svg")),
                "演示图应复制到模板 static/images/");
        assertTrue(result.writtenFiles().contains("static/images/demo-product.svg"));
        // imageAssets 记录（来源追溯）
        assertEquals(1, result.spec().safeImageAssets().size());
        ImageAssetSpec asset = result.spec().safeImageAssets().get(0);
        assertEquals("产品主图 科技感", asset.search());
        assertEquals("static/images/demo-product.svg", asset.resolved());
        assertEquals(ImageAssetSpec.SOURCE_DEMO, asset.source());
        assertEquals(0, result.attachmentHits());
        assertEquals(1, result.demoFallbacks());
    }

    @Test
    void shouldPreferAttachmentLibraryMatch() {
        Attachment att = imageAttachment(9L, "产品主图-科技感.png", "2026/09/product.png");
        IAttachmentService service = Mockito.mock(IAttachmentService.class);
        Mockito.when(service.list(ArgumentMatchers.<Wrapper<Attachment>>any())).thenReturn(List.of(att));
        AttachmentImageSearcher searcher = new AttachmentImageSearcher(registry, service);

        AttachmentImageSearcher.Result result = searcher.resolve(
                specWithHeroImage("search:产品主图"), tempDir);

        // 命中附件：站内绝对路径（fileDomain 未配置场景），无需演示图落盘
        Object image = result.spec().pages().get("index").safeSections().get(0).safeData().get("image");
        assertEquals("/2026/09/product.png", image);
        assertTrue(result.writtenFiles().isEmpty(), "附件命中不应写演示图");

        ImageAssetSpec asset = result.spec().safeImageAssets().get(0);
        assertEquals(ImageAssetSpec.SOURCE_ATTACHMENT, asset.source());
        assertEquals(Long.valueOf(9L), asset.attachmentId());
        assertEquals(1, result.attachmentHits());
        assertEquals(0, result.demoFallbacks());
    }

    @Test
    void shouldFallbackToDemoWhenNoAttachmentMatches() {
        Attachment unrelated = imageAttachment(1L, " unrelated-doc.png", "2026/09/doc.png");
        IAttachmentService service = Mockito.mock(IAttachmentService.class);
        Mockito.when(service.list(ArgumentMatchers.<Wrapper<Attachment>>any()))
                .thenReturn(List.of(unrelated));
        AttachmentImageSearcher searcher = new AttachmentImageSearcher(registry, service);

        AttachmentImageSearcher.Result result = searcher.resolve(
                specWithHeroImage("search:山林散养 生态农场"), tempDir);

        // 关键词不匹配：演示图兜底（nature 主题）
        Object image = result.spec().pages().get("index").safeSections().get(0).safeData().get("image");
        assertEquals("static/images/demo-nature.svg", image);
        assertTrue(Files.isRegularFile(tempDir.resolve("static/images/demo-nature.svg")));
        assertEquals(0, result.attachmentHits());
        assertEquals(1, result.demoFallbacks());
    }

    @Test
    void shouldRenderResolvedDemoImageWithCtxExpression() throws Exception {
        AttachmentImageSearcher searcher = new AttachmentImageSearcher(registry, null);
        AttachmentImageSearcher.Result result = searcher.resolve(
                specWithHeroImage("search:团队合影"), tempDir);

        // 解析后的 spec 走渲染管线：页面 FTL 中 media 槽位为 ctx() 表达式（预览/生产通用）
        Path templateDir = tempDir.resolve("rendered");
        renderer.render(result.spec(), templateDir);
        String index = Files.readString(templateDir.resolve("index.html"));
        assertTrue(index.contains("ctx() + \"/images/demo-team.svg\""),
                "模板内图片引用应为 ctx() 表达式: " + index);
        // S4-1：图片槽位点选标记——include 处注入 _aiSection assign，组件源码 img 注入 data-ai-slot
        assertTrue(index.contains("<#assign _aiSection = \"hero\">"),
                "include 处应注入 _aiSection assign: " + index);
        String heroFtl = Files.readString(templateDir.resolve("_components/tw__hero__split.ftl"));
        assertTrue(heroFtl.contains("data-ai-slot=\"image\"")
                        && heroFtl.contains("data-ai-section=\"${(_aiSection)!''}\""),
                "组件源码 img 应注入 data-ai-slot 标记: " + heroFtl);
        // 演示图文件已由渲染管线前的 searcher 落盘（模板自包含），预览渲染校验通过
        Files.createDirectories(templateDir.resolve("static/images"));
        Files.copy(tempDir.resolve("static/images/demo-team.svg"),
                templateDir.resolve("static/images/demo-team.svg"));
        List<String> errors = previewRenderer.checkRenderedFiles(templateDir, List.of("index.html"));
        assertEquals(List.of(), errors, "渲染校验应通过: " + errors);
    }

    @Test
    void shouldPreserveOldAssetRecordForUnchangedUrlSlot() {
        // 微调沿用：槽位已是解析后的 URL，旧 imageAssets 记录保留
        PageSpec spec = specWithHeroImage("/2026/09/product.png");
        spec = new PageSpec(spec.specVersion(), spec.foundation(), spec.templateName(),
                spec.siteName(), spec.siteType(), spec.stylePreset(), spec.primaryColor(),
                spec.safeSite(), spec.pages(),
                List.of(new ImageAssetSpec("产品主图", "/2026/09/product.png",
                        ImageAssetSpec.SOURCE_ATTACHMENT, 9L)));

        AttachmentImageSearcher searcher = new AttachmentImageSearcher(registry, null);
        AttachmentImageSearcher.Result result = searcher.resolve(spec, tempDir);

        assertEquals(1, result.spec().safeImageAssets().size());
        ImageAssetSpec asset = result.spec().safeImageAssets().get(0);
        assertEquals("/2026/09/product.png", asset.resolved());
        assertEquals(ImageAssetSpec.SOURCE_ATTACHMENT, asset.source());
        assertEquals(Long.valueOf(9L), asset.attachmentId());
        // 槽位值不变
        Object image = result.spec().pages().get("index").safeSections().get(0).safeData().get("image");
        assertEquals("/2026/09/product.png", image);
        assertNotNull(result.spec().safeImageAssets());
    }

}
