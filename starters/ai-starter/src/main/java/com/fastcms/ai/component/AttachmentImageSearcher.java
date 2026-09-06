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
package com.fastcms.ai.component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fastcms.entity.Attachment;
import com.fastcms.service.IAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 附件库图片搜索器：media 槽位 {@code search:} 引用 → 附件库匹配 / 演示图兜底
 *
 * <p>生成/微调渲染前的确定性预处理（组件化生成服务在 render 之前调用）：</p>
 * <ol>
 *     <li>扫描 spec 全部 section 的 media 类型槽位，定位 {@code search:关键词} 引用</li>
 *     <li>按关键词匹配附件库图片（文件名权重 &gt; 描述权重，优先未复用过的附件），
 *         命中则槽位值替换为附件访问路径（站内绝对路径 / 或完整域名 URL，预览/生产通用）</li>
 *     <li>未命中（或关闭复用）时回退演示图：内置主题 SVG 复制到 {@code static/images/}，
 *         槽位值替换为模板内相对引用（{@code static/images/demo-*.svg}，
 *         由 {@link PageSpecRenderer} 转换为 {@code ctx()} 表达式适配预览/生产环境）</li>
 *     <li>解析结果回写 {@code PageSpec.imageAssets}（来源追溯 + 微调沿用旧图时保留附件关联）</li>
 * </ol>
 *
 * <p>解析在渲染前完成并随 _pagespec.json 落盘：微调往返中已解析槽位为普通 URL（稳定不动），
 * 用户主动换图时 AI 输出新的 {@code search:} 引用触发重新解析。演示图复制进模板目录，
 * 模板自包含（应用后不依赖 ai-starter 运行时）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class AttachmentImageSearcher {

    private static final Logger log = LoggerFactory.getLogger(AttachmentImageSearcher.class);

    /**
     * 演示图在模板内的落盘目录（相对模板根，渲染器将 static/ 前缀值转为 ctx() 表达式）
     */
    static final String DEMO_DIR = "static/images";

    /**
     * 附件库图片候选池上限（评分在内存完成，控制查询量）
     */
    private static final int IMAGE_POOL_LIMIT = 500;

    /**
     * 演示图主题（关键词标签匹配 + 使用轮转保证同页多样性）
     */
    private record DemoTheme(String file, List<String> tags) {
    }

    private static final List<DemoTheme> DEMO_THEMES = List.of(
            new DemoTheme("demo-product", List.of("产品", "商品", "主图", "物品", "设备", "案例", "作品", "展示", "门店")),
            new DemoTheme("demo-tech", List.of("科技", "技术", "数字", "智能", "软件", "互联网", "数据", "云", "创新")),
            new DemoTheme("demo-nature", List.of("自然", "生态", "农场", "山林", "养殖", "环境", "绿色", "农业", "有机", "田园", "健康")),
            new DemoTheme("demo-team", List.of("团队", "关于", "公司", "文化", "员工", "办公", "服务", "专业", "联系", "历程")),
            new DemoTheme("demo-food", List.of("餐饮", "美食", "菜品", "餐厅", "咖啡", "食材", "烘培", "味道", "菜单")),
            new DemoTheme("demo-abstract", List.of("创意", "艺术", "设计", "抽象", "背景", "概念", "品牌")));

    private final ComponentRegistry registry;
    private final IAttachmentService attachmentService;

    public AttachmentImageSearcher(ComponentRegistry registry, IAttachmentService attachmentService) {
        this.registry = registry;
        this.attachmentService = attachmentService;
    }

    /**
     * 解析结果：替换后的 spec + 演示图落盘清单（供文件持久化/事件合并）+ 统计
     */
    public record Result(PageSpec spec, List<String> writtenFiles, int attachmentHits, int demoFallbacks) {

        public static Result untouched(PageSpec spec) {
            return new Result(spec, List.of(), 0, 0);
        }
    }

    /**
     * 解析 spec 的 media 槽位引用（附件库优先匹配，未命中走演示图兜底）
     *
     * @param workDir 模板工作目录（演示图落盘到 static/images/）
     */
    public Result resolve(PageSpec spec, Path workDir) {
        if (spec == null || spec.pages() == null || spec.pages().isEmpty()) {
            return Result.untouched(spec);
        }

        List<Attachment> imagePool = loadImagePool();
        // 同关键词去重（多个槽位同一 search: 引用只解析一次，结果共享）
        Map<String, ImageAssetSpec> resolvedByKeyword = new HashMap<>();
        Set<String> writtenDemoFiles = new HashSet<>();

        List<ImageAssetSpec> newAssets = new ArrayList<>();
        List<String> writtenFiles = new ArrayList<>();
        Set<Long> usedAttachmentIds = new HashSet<>();
        Map<String, Integer> themeUsage = new HashMap<>();

        Map<String, PageSpecPage> newPages = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, PageSpecPage> pageEntry : spec.pages().entrySet()) {
            List<SectionSpec> newSections = new ArrayList<>();
            for (SectionSpec section : pageEntry.getValue().safeSections()) {
                SectionSpec resolved = resolveSection(section, workDir, imagePool, resolvedByKeyword,
                        newAssets, writtenFiles, writtenDemoFiles, usedAttachmentIds, themeUsage);
                if (resolved != null) {
                    newSections.add(resolved);
                    changed = true;
                } else {
                    newSections.add(section);
                }
            }
            newPages.put(pageEntry.getKey(),
                    new PageSpecPage(newSections, pageEntry.getValue().standalone()));
        }

        // 沿用旧图：resolved 仍被当前 spec 引用的旧记录保留（微调未动这些槽位）
        for (ImageAssetSpec old : spec.safeImageAssets()) {
            if (old != null && old.resolved() != null && !old.resolved().isBlank()
                    && isStillReferenced(old.resolved(), newPages)
                    && newAssets.stream().noneMatch(a -> a.resolved().equals(old.resolved()))) {
                newAssets.add(old);
            }
        }

        int attachmentHits = 0;
        int demoFallbacks = 0;
        for (ImageAssetSpec asset : newAssets) {
            if (ImageAssetSpec.SOURCE_ATTACHMENT.equals(asset.source())) {
                attachmentHits++;
            } else if (ImageAssetSpec.SOURCE_DEMO.equals(asset.source())) {
                demoFallbacks++;
            }
        }

        if (!changed && newAssets.equals(spec.safeImageAssets())) {
            // 无 search: 引用且旧记录全部沿用：返回原 spec
            return Result.untouched(spec);
        }
        if (attachmentHits > 0 || demoFallbacks > 0) {
            log.info("图片槽位解析完成: 附件库命中 {}，演示图兜底 {}，演示图落盘 {} 个",
                    attachmentHits, demoFallbacks, writtenFiles.size());
        }
        PageSpec newSpec = new PageSpec(spec.specVersion(), spec.foundation(), spec.templateName(),
                spec.siteName(), spec.siteType(), spec.stylePreset(), spec.primaryColor(),
                spec.safeSite(), newPages, newAssets);
        return new Result(newSpec, writtenFiles, attachmentHits, demoFallbacks);
    }

    /**
     * 解析单个 section：返回替换了 media 槽位值的新 section；无 media 槽位或无 search: 引用时返回 null
     */
    private SectionSpec resolveSection(SectionSpec section, Path workDir, List<Attachment> imagePool,
                                       Map<String, ImageAssetSpec> resolvedByKeyword,
                                       List<ImageAssetSpec> newAssets, List<String> writtenFiles,
                                       Set<String> writtenDemoFiles, Set<Long> usedAttachmentIds,
                                       Map<String, Integer> themeUsage) {
        if (PageSpec.CONTENT_BODY_SECTION.equals(section.component())) {
            return null;
        }
        Set<String> mediaSlots = mediaSlotNames(section.component());
        if (mediaSlots.isEmpty()) {
            return null;
        }
        boolean touched = false;
        Map<String, Object> newData = new LinkedHashMap<>(section.safeData());
        for (String slot : mediaSlots) {
            Object value = newData.get(slot);
            if (!(value instanceof String s) || s.isBlank() || !ImageAssetSpec.isSearchRef(s)) {
                continue;
            }
            String keyword = ImageAssetSpec.searchKeyword(s);
            ImageAssetSpec asset = resolvedByKeyword.computeIfAbsent(keyword,
                    k -> resolveKeyword(k, workDir, imagePool, newAssets, writtenFiles,
                            writtenDemoFiles, usedAttachmentIds, themeUsage));
            if (asset != null && asset.resolved() != null) {
                newData.put(slot, asset.resolved());
            } else {
                // 解析彻底失败（演示图也写不进）：清空槽位走组件占位兜底
                newData.put(slot, "");
            }
            touched = true;
        }
        return touched ? new SectionSpec(section.id(), section.component(), section.variant(), newData) : null;
    }

    /**
     * 关键词 → 图片资产（附件库优先，演示图兜底）。返回 null 表示彻底失败
     */
    private ImageAssetSpec resolveKeyword(String keyword, Path workDir, List<Attachment> imagePool,
                                          List<ImageAssetSpec> newAssets, List<String> writtenFiles,
                                          Set<String> writtenDemoFiles, Set<Long> usedAttachmentIds,
                                          Map<String, Integer> themeUsage) {
        Attachment hit = !imagePool.isEmpty() ? bestMatch(keyword, imagePool, usedAttachmentIds) : null;
        if (hit != null) {
            usedAttachmentIds.add(hit.getId());
            ImageAssetSpec asset = new ImageAssetSpec(keyword, attachmentUrl(hit),
                    ImageAssetSpec.SOURCE_ATTACHMENT, hit.getId());
            newAssets.add(asset);
            return asset;
        }
        return demoFallback(keyword, workDir, newAssets, writtenFiles, writtenDemoFiles, themeUsage);
    }

    /**
     * 关键词评分匹配（文件名命中权重 3 / 描述命中权重 1，已用附件乘 0.5 惩罚）
     */
    private Attachment bestMatch(String keyword, List<Attachment> imagePool, Set<Long> usedAttachmentIds) {
        List<String> tokens = tokens(keyword);
        if (tokens.isEmpty()) {
            return null;
        }
        Attachment best = null;
        double bestScore = 0;
        for (Attachment att : imagePool) {
            double score = 0;
            String fileName = att.getFileName() == null ? "" : att.getFileName();
            String fileDesc = att.getFileDesc() == null ? "" : att.getFileDesc();
            for (String token : tokens) {
                if (fileName.contains(token)) {
                    score += 3;
                }
                if (fileDesc.contains(token)) {
                    score += 1;
                }
            }
            if (score > 0 && usedAttachmentIds.contains(att.getId())) {
                score *= 0.5;
            }
            if (score > bestScore) {
                bestScore = score;
                best = att;
            }
        }
        return best;
    }

    /**
     * 演示图兜底：主题标签匹配 + 使用轮转，选中 SVG 复制到模板 static/images/
     */
    private ImageAssetSpec demoFallback(String keyword, Path workDir, List<ImageAssetSpec> newAssets,
                                        List<String> writtenFiles, Set<String> writtenDemoFiles,
                                        Map<String, Integer> themeUsage) {
        try {
            DemoTheme theme = pickTheme(keyword, themeUsage);
            String relPath = DEMO_DIR + "/" + theme.file() + ".svg";
            if (writtenDemoFiles.add(relPath)) {
                writeDemoImage(theme.file(), workDir.resolve(relPath));
                writtenFiles.add(relPath);
            }
            ImageAssetSpec asset = new ImageAssetSpec(keyword, relPath,
                    ImageAssetSpec.SOURCE_DEMO, null);
            newAssets.add(asset);
            return asset;
        } catch (Exception e) {
            log.warn("演示图兜底失败: keyword={}", keyword, e);
            return null;
        }
    }

    private DemoTheme pickTheme(String keyword, Map<String, Integer> themeUsage) {
        String k = keyword == null ? "" : keyword;
        DemoTheme best = null;
        int bestScore = 0;
        for (DemoTheme theme : DEMO_THEMES) {
            int score = 0;
            for (String tag : theme.tags()) {
                if (k.contains(tag)) {
                    score++;
                }
            }
            // 使用轮转惩罚：已被使用的主题降权，让多图页面呈现不同主题
            int effective = score - themeUsage.getOrDefault(theme.file(), 0);
            if (effective > bestScore) {
                bestScore = effective;
                best = theme;
            }
        }
        if (best == null) {
            best = DEMO_THEMES.get(DEMO_THEMES.size() - 1);
        }
        themeUsage.merge(best.file(), 1, Integer::sum);
        return best;
    }

    /**
     * 演示图落盘（classpath:demo-images/{file}.svg → 模板 static/images/）
     */
    private void writeDemoImage(String file, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource("demo-images/" + file + ".svg").getInputStream()) {
            Files.copy(in, target);
        }
    }

    // ==================== 辅助 ====================

    /**
     * media 类型槽位名集合（组件不在注册表时返回空集，渲染校验已拦截不存在组件）
     */
    private Set<String> mediaSlotNames(String componentId) {
        return registry.find(componentId)
                .map(rc -> {
                    Set<String> names = new HashSet<>();
                    for (ComponentSlot slot : rc.descriptor().safeSlots()) {
                        if ("media".equals(slot.type())) {
                            names.add(slot.name());
                        }
                    }
                    return names;
                })
                .orElse(Set.of());
    }

    private List<Attachment> loadImagePool() {
        try {
            List<Attachment> pool = attachmentService.list(Wrappers.<Attachment>lambdaQuery()
                    .eq(Attachment::getFileType, Attachment.TYPE_IMAGE)
                    .orderByDesc(Attachment::getId)
                    .last("limit " + IMAGE_POOL_LIMIT));
            return pool == null ? List.of() : pool;
        } catch (Exception e) {
            log.warn("附件库图片加载失败（演示图兜底）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 附件访问路径：fileDomain 配置时为完整 URL；否则为站内绝对路径（预览/生产通用）。
     * fileDomain 经 ConfigUtils 读取（需应用上下文），防御式降级到相对路径
     */
    private String attachmentUrl(Attachment att) {
        String url = null;
        try {
            url = att.getPath();
        } catch (Exception e) {
            // 无应用上下文（单测等场景）：fileDomain 视为未配置
            url = null;
        }
        if (url == null || url.isBlank()) {
            url = att.getFilePath();
        }
        if (url != null && !url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("/")) {
            url = "/" + url;
        }
        return url;
    }

    private boolean isStillReferenced(String resolved, Map<String, PageSpecPage> pages) {
        for (PageSpecPage page : pages.values()) {
            for (SectionSpec section : page.safeSections()) {
                for (Object value : section.safeData().values()) {
                    if (resolved.equals(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<String> tokens(String keyword) {
        List<String> tokens = new ArrayList<>();
        if (keyword == null) {
            return tokens;
        }
        for (String token : keyword.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

}
