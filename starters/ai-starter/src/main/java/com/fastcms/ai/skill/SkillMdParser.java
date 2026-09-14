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
package com.fastcms.ai.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SKILL.md 解析器（Agent Skills 开放标准）
 *
 * <p>文件结构：</p>
 * <pre>
 * ---
 * name: 选题策划
 * description: 围绕站点定位规划文章选题…
 * version: 1.0.0        ← 可选
 * ---
 * （L2 正文：完整指令 markdown）
 * </pre>
 *
 * <p>frontmatter 用 SnakeYAML {@link SafeConstructor} 解析（禁用任意对象反序列化，
 * 防恶意 SKILL.md 攻击）。name/description 必填，缺失时返回带 error 的结果，
 * 由调用方隔离展示，不阻断启动。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public final class SkillMdParser {

    private static final Logger log = LoggerFactory.getLogger(SkillMdParser.class);

    private static final Pattern FRONTMATTER_DELIMITER = Pattern.compile("^---\\s*$");

    private static final int MAX_FILE_SIZE = 100 * 1024;

    private SkillMdParser() {
    }

    /**
     * 解析结果；解析失败时 error 非空，其余字段尽力填充
     */
    public record ParsedSkill(String name, String description, String version, String content, String error) {
        public boolean valid() {
            return error == null;
        }
    }

    public static ParsedSkill parse(String skillMd) {
        if (skillMd == null || skillMd.isBlank()) {
            return new ParsedSkill(null, null, null, null, "SKILL.md 内容为空");
        }
        if (skillMd.length() > MAX_FILE_SIZE) {
            return new ParsedSkill(null, null, null, null, "SKILL.md 超过 " + MAX_FILE_SIZE / 1024 + "KB 上限");
        }

        String[] lines = skillMd.split("\n", -1);
        int lineCount = lines.length;

        // 定位 frontmatter 边界：首行必须是 ---
        if (lineCount < 3 || !FRONTMATTER_DELIMITER.matcher(lines[0].trim()).matches()) {
            return new ParsedSkill(null, null, null, null, "缺少 frontmatter（首行必须是 ---）");
        }
        Integer end = null;
        for (int i = 1; i < lineCount; i++) {
            if (FRONTMATTER_DELIMITER.matcher(lines[i].trim()).matches()) {
                end = i;
                break;
            }
        }
        if (end == null) {
            return new ParsedSkill(null, null, null, null, "frontmatter 未闭合（缺少结束 ---）");
        }

        String yamlBlock = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, end));
        String content = String.join("\n", java.util.Arrays.copyOfRange(lines, end + 1, lineCount)).strip();

        Map<String, String> front;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(yamlBlock);
            if (loaded == null) {
                return new ParsedSkill(null, null, null, content, "frontmatter 为空（缺少 name/description）");
            }
            if (!(loaded instanceof Map)) {
                return new ParsedSkill(null, null, null, content, "frontmatter 必须是 key: value 结构");
            }
            front = asStringMap(loaded);
        } catch (Exception e) {
            log.warn("SKILL.md frontmatter YAML 解析失败: {}", e.getMessage());
            return new ParsedSkill(null, null, null, null, "frontmatter YAML 解析失败: " + e.getMessage());
        }

        String name = front.get("name");
        String description = front.get("description");
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            return new ParsedSkill(name, description, front.get("version"), content,
                    "frontmatter 缺少必填字段 name / description");
        }
        if (description.length() > 500) {
            return new ParsedSkill(name, description, front.get("version"), content,
                    "description 超过 500 字符（当前 " + description.length() + "），请精简 L1 摘要");
        }
        return new ParsedSkill(name.strip(), description.strip(), front.get("version"), content, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object loaded) {
        // SafeConstructor 只产出基本类型；值统一转字符串供读取
        Map<Object, Object> raw = (Map<Object, Object>) loaded;
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null) {
                result.put(String.valueOf(k), v == null ? null : String.valueOf(v));
            }
        });
        return result;
    }

}
