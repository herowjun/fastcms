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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设计方向资产库：焕新方向的单一资产来源（P0-3 方向资产化）
 *
 * <p>每个方向从「一句话形容」升级为三件套资产，存于
 * {@code resources/ai/design-directions/{key}.json}：</p>
 * <ul>
 *     <li>{@code tokensOverride}：主色 + 风格预设——{@link TokenEngine} 按此生成 tokens.css，
 *     确定性换肤（方向落地不靠 AI 发挥色值，减少方差）</li>
 *     <li>{@code referenceBlocks}：参考区块 HTML（few-shot，质量远高于一句形容）</li>
 *     <li>{@code do / dont}：正反清单（AI 自查依据）</li>
 * </ul>
 *
 * <p>轮换顺序与 {@code TemplateGenPromptBuilder} 的方向轮换共用本库
 * （轮换位次 → key → 资产），提示词文案与 tokens 换肤由同一资产驱动，不再漂移。</p>
 *
 * <p>插件化预留：classpath 扫描用 {@code classpath*:} 前缀，后续「行业设计方向包」
 * 插件只需在 jar 内携带 {@code ai/design-directions/*.json} 即可挂载新方向，
 * 无需改引擎代码。</p>
 *
 * <p>资产为只读类路径数据，采用懒加载静态单例；加载失败降级为空库
 * （调用方回退内置文案，不阻断升级流程）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class DesignDirectionLibrary {

    private static final Logger log = LoggerFactory.getLogger(DesignDirectionLibrary.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 焕新轮换方向池（问题②扩池：3 内置 + 4 反馈修正方向，共 7 个）。
     *
     * <p>轮换经 {@link #firstUnrejected} 顺序取池中第一个未被否决的方向——
     * 反馈方向（提亮/疏朗/强对比/统一）拥有与内置方向同规格的完整资产
     * （tokensOverride（按 D3 决策不含主色，仅 stylePreset）+ 参考块 + do/dont），
     * 内置 3 方向用尽后自动落入反馈方向而非踩回被否决方向；7 个全部被否决
     * 才判定池耗尽（调用方提示转向对话微调，兜底文案轮换此时用户已知情）。</p>
     */
    private static final List<String> ROTATION_ORDER = List.of(
            "modern-business", "light-elegant", "editorial-magazine",
            "feedback-brighten", "feedback-spacious", "feedback-contrast", "feedback-unify");

    /**
     * 设计方向资产（JSON 文件结构的强类型投影）
     *
     * @param key             资产键（文件名，如 "modern-business"）
     * @param name            方向短名（如「现代商务风」，收尾摘要与提示词标题用）
     * @param summary         方向一句话形容（提示词方向段用）
     * @param primaryColor    tokensOverride 主色（可空 = 不覆写，用升级默认色）
     * @param stylePreset     tokensOverride 风格预设（可空 = 不覆写）
     * @param referenceBlocks 参考区块 HTML 清单（few-shot，可空）
     * @param doRules         正向清单（可空）
     * @param dontRules       反向清单（可空）
     */
    public record DesignDirectionAsset(
            String key, String name, String summary,
            String primaryColor, String stylePreset,
            List<String> referenceBlocks, List<String> doRules, List<String> dontRules) {
    }

    /**
     * 懒加载资产表（key → 资产）；加载失败为空表（调用方回退内置文案）
     */
    private static volatile Map<String, DesignDirectionAsset> assets;

    private DesignDirectionLibrary() {
    }

    /**
     * 按资产键取方向（未知 key / 资产库加载失败返回 null）
     */
    public static DesignDirectionAsset get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return assets().get(key);
    }

    /**
     * 全部方向资产（设计稿先行模式前端下拉数据源，key/name/summary 三字段即够展示）。
     *
     * <p>轮换池内方向按 {@link #ROTATION_ORDER} 顺序优先返回（用户可见顺序与焕新轮换顺序
     * 一致），池外资产（插件挂载）按加载顺序追加；资产库加载失败返回空表（前端下拉退化为
     * 仅「AI 自选」）。</p>
     */
    public static List<DesignDirectionAsset> listAssets() {
        Map<String, DesignDirectionAsset> all = assets();
        List<DesignDirectionAsset> ordered = new java.util.ArrayList<>(all.size());
        for (String key : ROTATION_ORDER) {
            DesignDirectionAsset asset = all.get(key);
            if (asset != null) {
                ordered.add(asset);
            }
        }
        for (DesignDirectionAsset asset : all.values()) {
            if (!ROTATION_ORDER.contains(asset.key())) {
                ordered.add(asset);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 方向短名反查资产键（问题4）：lastRound 只存方向短名（如「现代商务风」），
     * 否决方向持久化时反查 key。名未命中或资产库加载失败返回 null（跳过否决记录）。
     */
    public static String nameToKey(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (DesignDirectionAsset asset : assets().values()) {
            if (name.equals(asset.name())) {
                return asset.key();
            }
        }
        return null;
    }

    /**
     * 轮换方向池中第一个未被否决的方向资产（问题4）：焕新触发 = 否决上一轮方向，
     * 被否决的方向不再进入轮换——顺序取第一个未拒绝的，符合「挨个试方向」的直觉；
     * 全部被拒或资产库加载失败返回 null（调用方退兜底文案轮换并提示池耗尽）。
     */
    public static DesignDirectionAsset firstUnrejected(java.util.Set<String> rejectedKeys) {
        String key = firstUnrejectedKey(rejectedKeys);
        return key == null ? null : get(key);
    }

    /**
     * 轮换方向池中第一个未被否决的方向键（{@link #firstUnrejected} 的 key 版）
     */
    public static String firstUnrejectedKey(java.util.Set<String> rejectedKeys) {
        for (String key : ROTATION_ORDER) {
            if (rejectedKeys == null || !rejectedKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    /**
     * 轮换方向池（7 个，问题②扩池后）是否已全部被否决（问题4池耗尽判定）。
     * rejected 为空（含资产库加载失败导致否决记录无法反查 key 的情况）不视为耗尽
     * ——那是环境问题而非用户全盘否决。
     */
    public static boolean allDirectionsRejected(java.util.Set<String> rejectedKeys) {
        if (ROTATION_ORDER.isEmpty() || rejectedKeys == null || rejectedKeys.isEmpty()) {
            return false;
        }
        return firstUnrejectedKey(rejectedKeys) == null;
    }

    private static Map<String, DesignDirectionAsset> assets() {
        Map<String, DesignDirectionAsset> local = assets;
        if (local == null) {
            synchronized (DesignDirectionLibrary.class) {
                if (assets == null) {
                    assets = loadAssets();
                }
                local = assets;
            }
        }
        return local;
    }

    /**
     * 扫描 classpath 全部方向资产（classpath* 前缀：主 jar 与插件 jar 同路径均可挂载）；
     * 单个文件格式非法只跳过该文件并告警，不拖垮整库
     */
    private static Map<String, DesignDirectionAsset> loadAssets() {
        Map<String, DesignDirectionAsset> map = new LinkedHashMap<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:ai/design-directions/*.json");
            for (Resource res : resources) {
                try (InputStream in = res.getInputStream()) {
                    DesignDirectionAsset asset = parse(MAPPER.readTree(in));
                    if (asset != null) {
                        map.put(asset.key(), asset);
                    }
                } catch (Exception e) {
                    log.warn("设计方向资产解析失败，跳过: {}", res.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.warn("设计方向资产目录扫描失败，焕新方向回退内置文案", e);
        }
        log.info("设计方向资产加载完成: {} 个 {}", map.size(), map.keySet());
        return Collections.unmodifiableMap(map);
    }

    private static DesignDirectionAsset parse(JsonNode node) {
        String key = node.path("key").asString(null);
        String name = node.path("name").asString(null);
        if (key == null || key.isBlank() || name == null || name.isBlank()) {
            log.warn("设计方向资产缺少 key/name，跳过");
            return null;
        }
        JsonNode tokens = node.path("tokensOverride");
        return new DesignDirectionAsset(
                key,
                name,
                node.path("summary").asString(null),
                tokens.path("primaryColor").asString(null),
                tokens.path("stylePreset").asString(null),
                readStringList(node.path("referenceBlocks")),
                readStringList(node.path("do")),
                readStringList(node.path("dont")));
    }

    private static List<String> readStringList(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return List.of();
        }
        List<String> list = new java.util.ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (item.isTextual()) {
                String s = item.asString();
                if (s != null && !s.isBlank()) {
                    list.add(s);
                }
            }
        }
        return List.copyOf(list);
    }
}
