/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.fastcms.ai.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 预览导航条目编辑：预览缺页面引导页「删除该条目」的落地实现
 *
 * <p>背景：菜单/分类/标签/单页指向的 {@code {type}_{suffix}.html} 不存在时，预览把它导向
 * 引导页（不再静默回退到基础页）。用户可选择"删除该条目"——本类负责把该条目从预览数据中移除。</p>
 *
 * <p><b>真源分流（关键）</b>：{@code _preview_data.json} 对组件化模板是<b>派生文件</b>
 * （{@code PageSpecRenderer.writePreviewData} 每次 AI 调整都从 {@code _pagespec.json} 的
 * {@code site} 段全量重写）。因此：</p>
 * <ul>
 *     <li>目录存在 {@code _pagespec.json} → 同时改真源 {@code site.*}，否则用户删完
 *         下一次 AI 调整就"复活"</li>
 *     <li>无 {@code _pagespec.json}（存量/手工模板）→ {@code _preview_data.json} 本身就是真源</li>
 * </ul>
 *
 * <p><b>索引漂移防护</b>：两份文件按 ref（如 {@code m0-2}）指向的下标本应一致，
 * 但 {@code _preview_data.json} 可能被手工编辑过。改动真源前先比对两份文件在同一下标处的
 * 名称，一致才改——避免索引漂移时删错条目。</p>
 *
 * <p>不做的事：不删除对应页面文件（用户的模板资产不因预览操作被删）；不触发重渲染
 * （{@code _preview_data.json} 同步改掉即可让预览立即生效，下一轮 AI 渲染会从已修正的
 * 真源重新派生，两者收敛）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
@Component
public class PreviewMenuEditor {

    private static final Logger log = LoggerFactory.getLogger(PreviewMenuEditor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 条目种类前缀 → _preview_data.json 中的数组字段 / _pagespec.json site 段的数组字段 */
    private static final char KIND_MENU = 'm';
    private static final char KIND_CATEGORY = 'c';
    private static final char KIND_TAG = 't';
    private static final char KIND_SINGLE_PAGE = 's';

    /**
     * 删除结果
     *
     * @param changed 是否有文件被修改
     * @param files   被修改的文件（相对模板目录，如 _preview_data.json / _pagespec.json）
     */
    public record Result(boolean changed, List<String> files) {
    }

    /**
     * 从模板目录的预览数据中删除 ref 指向的条目
     *
     * @param workDir 模板根目录
     * @param ref     条目引用（渲染期生成，见 AiTemplatePreviewMockSupport 的 REF_* 常量）
     * @return 删除结果；ref 非法或定位不到时 changed=false（不抛异常，由调用方回执失败）
     * @throws IOException 文件读写失败
     */
    public Result removeItem(Path workDir, String ref) throws IOException {
        if (workDir == null || ref == null || ref.length() < 2) {
            return new Result(false, List.of());
        }
        Path previewFile = workDir.resolve(AiTemplateConstants.FILE_PREVIEW_DATA);
        Path specFile = workDir.resolve(AiTemplateConstants.FILE_PAGESPEC);
        ObjectNode preview = readObject(previewFile);
        ObjectNode spec = readObject(specFile);

        // 索引漂移防护：两份文件在 ref 处指向的条目名称是否一致。
        // 必须在 remove 之前比对——remove 是原地删除，删完同一下标已经是"顺延上来的下一条"
        // （删最后一条时直接越界），此时再比名称既比不到也已失去意义。
        boolean specMatches = spec != null && preview != null
                && sameItem(preview, ref, spec.path("site"), ref);

        List<String> files = new ArrayList<>();

        // 1) 预览数据：改完预览立即生效（用户点完按钮刷新即可看到结果）
        if (preview != null && remove(preview, ref)) {
            write(previewFile, preview);
            files.add(AiTemplateConstants.FILE_PREVIEW_DATA);

            // 2) 真源：仅当两份数据在同一条目上（名称一致）才改，防索引漂移删错
            if (specMatches && remove(spec.path("site"), ref)) {
                write(specFile, spec);
                files.add(AiTemplateConstants.FILE_PAGESPEC);
            }
        }

        if (files.isEmpty()) {
            log.warn("预览条目删除未生效（ref 定位不到或预览数据缺失）: workDir={}, ref={}", workDir, ref);
        } else {
            log.info("预览条目已删除: workDir={}, ref={}, files={}", workDir, ref, files);
        }
        return new Result(!files.isEmpty(), List.copyOf(files));
    }

    // ==================== JSON 定位与删除 ====================

    /** ref 指向的 (所属数组, 下标)；解析不到返回 null */
    private record Slot(ArrayNode array, int index) {
    }

    private static Slot resolve(JsonNode base, String ref) {
        if (base == null || base.isMissingNode() || !base.isObject() || ref == null || ref.length() < 2) {
            return null;
        }
        String field = switch (ref.charAt(0)) {
            case KIND_MENU -> "menus";
            case KIND_CATEGORY -> "categories";
            case KIND_TAG -> "tags";
            case KIND_SINGLE_PAGE -> "singlePages";
            default -> null;
        };
        if (field == null) {
            return null;
        }
        JsonNode node = base.path(field);
        if (!(node instanceof ArrayNode array)) {
            return null;
        }
        // 菜单是多级结构：ref m0-2 → menus[0].children[2]；下标取原始 JSON 位置
        String[] parts = ref.substring(1).split("-");
        ArrayNode parent = array;
        for (int i = 0; i < parts.length - 1; i++) {
            int idx = parseIndex(parts[i]);
            if (idx < 0 || idx >= parent.size()) {
                return null;
            }
            JsonNode children = parent.get(idx).path("children");
            if (!(children instanceof ArrayNode childArray)) {
                return null;
            }
            parent = childArray;
        }
        int last = parseIndex(parts[parts.length - 1]);
        return (last >= 0 && last < parent.size()) ? new Slot(parent, last) : null;
    }

    private static boolean remove(JsonNode base, String ref) {
        Slot slot = resolve(base, ref);
        if (slot == null) {
            return false;
        }
        slot.array().remove(slot.index());
        return true;
    }

    /** 两份数据在各自 ref 处指向的条目名称是否一致（name / title 任一） */
    private static boolean sameItem(JsonNode left, String leftRef, JsonNode right, String rightRef) {
        String a = nameAt(left, leftRef);
        String b = nameAt(right, rightRef);
        return a != null && a.equals(b);
    }

    private static String nameAt(JsonNode base, String ref) {
        Slot slot = resolve(base, ref);
        if (slot == null) {
            return null;
        }
        JsonNode item = slot.array().get(slot.index());
        for (String key : new String[]{"name", "title"}) {
            JsonNode value = item.get(key);
            if (value != null && value.isTextual() && !value.asString().isBlank()) {
                return value.asString().trim();
            }
        }
        return null;
    }

    private static int parseIndex(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==================== 文件读写 ====================

    private static ObjectNode readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return root instanceof ObjectNode object ? object : null;
        } catch (Exception e) {
            log.warn("预览条目删除：读取失败，跳过该文件: {}", file, e);
            return null;
        }
    }

    private static void write(Path file, ObjectNode root) throws IOException {
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }

}
