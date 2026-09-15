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

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * Skill 供给方扩展点：所有技能来源实现此接口
 *
 * <p>skill 即一份 SKILL.md 文件（Agent Skills 开放标准）：</p>
 * <ul>
 *     <li>L1 摘要（frontmatter 的 name + description）常驻智能体 system prompt，
 *         让模型知道"有哪些能力"</li>
 *     <li>L2 正文（frontmatter 之后的 markdown）是完整指令，由模型按需拉取（load_skill 工具，
 *         随 AgentCore 落地），不占常驻上下文</li>
 * </ul>
 *
 * <p><b>供给方实现</b>（与 CapabilityProvider / SectionComponentProvider 同构）：</p>
 * <ul>
 *     <li>主工程磁盘文件源：{@link FileSkillRepository}（扫描 ~/fastcms/skills，
 *         用户直接放目录即安装，记事本可改）</li>
 *     <li>插件源：继承 {@link ClasspathSkillProvider} + {@code @Extension} 注册，
 *         插件 jar 内 {@code skills/<slug>/SKILL.md} 随插件分发、随插件装卸</li>
 * </ul>
 *
 * <p><b>skillId 命名空间</b>：插件贡献的技能 id 为 {@code {pluginId}/{slug}}（如
 * {@code article-skills-plugin/article-topic}），防多插件冲突；磁盘文件源为裸 slug。
 * 供给方由此天然可区分——磁盘技能可本地覆盖插件同名技能的语义不适用于本接口，
 * 冲突处理统一在 {@link SkillRegistry}。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public interface SkillProvider extends ExtensionPoint {

    /**
     * 供给方标识
     *
     * <p>插件实现返回自己的 pluginId；磁盘文件源返回 {@link #FILE_SOURCE}。</p>
     */
    String getProviderId();

    /**
     * 本供给方的全部 skill L1 元数据（不含正文）
     */
    List<SkillDescriptor> listSkills();

    /**
     * 读取 skill 的 L2 正文（SKILL.md frontmatter 之后的 markdown 指令）
     *
     * @param skillId 完整技能 id（含本供给方命名空间前缀）
     * @return 正文内容；技能不存在或解析失败时返回 null
     */
    String loadContent(String skillId);

    /** 磁盘文件源标识 */
    String FILE_SOURCE = "file";

}
