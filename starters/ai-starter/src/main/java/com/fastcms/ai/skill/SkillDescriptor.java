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

/**
 * Skill L1 元数据（不含正文）
 *
 * <p>注意：本类型刻意没有 prompt/content 字段——技能详情规则只存在于 SKILL.md 文件中，
 * 通过 {@link SkillProvider#loadContent} 按需读取，杜绝代码硬编码技能内容。</p>
 *
 * @param id          完整技能 id：插件源为 {pluginId}/{slug}，磁盘文件源为裸 slug
 * @param name        展示名（frontmatter name，中文）
 * @param description 一句话描述（frontmatter description，L1 常驻注入，须简短）
 * @param version     版本（frontmatter version，可选）
 * @param source      来源："file" / "plugin"
 * @param pluginId    贡献该技能的插件 id；磁盘文件源为 null
 * @param editable    是否可编辑（磁盘文件源 true，插件源随插件版本只读）
 * @param error       解析失败原因；null 表示正常。带 error 的技能不进 L1 注入
 */
public record SkillDescriptor(String id, String name, String description, String version,
                              String source, String pluginId, boolean editable, String error) {

    public static final String SOURCE_FILE = "file";
    public static final String SOURCE_PLUGIN = "plugin";

}
