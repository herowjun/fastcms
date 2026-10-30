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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码侧硬依赖技能清单：声明「主程序代码直接读取正文并注入提示词」的技能。
 *
 * <p><b>与 skill manifest（模型侧 L1 清单）的区别</b>——这是两套完全不同的东西，不要混淆：</p>
 * <table border="1">
 *     <tr><th></th><th>skill manifest（模型侧 L1 清单）</th><th>本类（代码侧硬依赖）</th></tr>
 *     <tr><td>谁决定加载</td><td>模型按需自主决定（load_skill 两段式）</td>
 *         <td>代码直接读正文注入系统提示词，无裁量空间</td></tr>
 *     <tr><td>缺失后果</td><td>模型少知道一点背景，仍能完成任务</td>
 *         <td>提示词少一段硬约束，产出静默降级且无人察觉</td></tr>
 *     <tr><td>缺失处理</td><td>不阻断（这正是"按需 load"的语义）</td>
 *         <td><b>必须明确失败</b>，不允许静默降级</td></tr>
 * </table>
 *
 * <p><b>为什么不允许静默降级</b>：曾出现过真实事故——设计方法论技能
 * {@code design-brief} 只存在于某台开发机的 {@code ~/fastcms/skills/} 目录（由外部工具
 * 手工写入、从未进版本控制），换机器后凭空消失，而代码侧调用点照常返回 null，
 * 模板照常生成，只是少了一段设计方法论约束。这类问题从日志里完全看不出来，
 * 只能靠"代码声明依赖 + 缺失即失败"把它挡在启动期。</p>
 *
 * <p><b>约束（防复发四条）</b>：</p>
 * <ol>
 *     <li><b>必须来自插件</b>——技能 id 必须带 {@code {pluginId}/{slug}} 前缀。
 *         插件 jar 随版本分发、可被 {@code deploy-plugins.bat} 统一部署；
 *         而裸 slug 的 file 源只存在于某台机器的磁盘目录，无法随代码分发。</li>
 *     <li><b>缺失即失败</b>——{@link #requireContent} 在缺失时抛异常，
 *         宁可让功能不可用，也不产出"少了硬约束但看起来正常"的结果。</li>
 *     <li><b>漂移即失败</b>——插件 jar 里的正文可能比主程序期望的旧（插件停留在几个月前、
 *         源码已新增规范章节）。这类漂移不会导致加载失败，只会让模型拿到残缺规范照常出图，
 *         从日志里完全看不出来。{@link SkillRequirement#requiredParts()} 声明主程序依赖的正文锚点，
 *         {@link #requireContent} 与 {@link #checkAll} 都会校验——这是「缺失」之外的第二道闸门。</li>
 *     <li><b>构建期校验</b>——{@code RequiredSkillSourceTest} 断言清单里每个技能
 *         都能在插件源码树中找到对应 SKILL.md，防止"加了依赖但忘了把技能放进插件"。</li>
 * </ol>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public final class RequiredSkills {

    private static final Logger log = LoggerFactory.getLogger(RequiredSkills.class);

    /**
     * 代码侧硬依赖技能 → 依赖声明
     *
     * <p>key 必须是插件源 id（形如 {@code {pluginId}/{slug}}，含斜杠）。
     * 若某个技能确实只存在于本机磁盘目录（如用户自写的实验性技能），
     * <b>不要</b>登记到这里——代码侧硬依赖必须可随代码分发。</p>
     */
    private static final Map<String, SkillRequirement> DEPENDENCIES = new LinkedHashMap<>();

    static {
        DEPENDENCIES.put("template-skills-plugin/template-spec", new SkillRequirement(
                "fastcms 模板制作规范（模板生成与设计稿转化的硬约束）",
                // 这些章节对应代码侧的硬依赖能力，缺失即意味着主程序与插件正文漂移：
                //  §1.0 CSS/JS 分离 —— 对应 InlineAssetExtractor 的抽取对象与合规校验第 10 项
                //  §1.1 栏目专属页 —— 对应 PageSpec.suffixedPageKey 的专属页命名约定
                //  §1.2 _components —— 对应 PageSpecRenderer.COMPONENTS_DIR 的组件物化目录
                List.of(
                        "### 1.0 CSS/JS 分离",
                        "### 1.1 栏目专属页",
                        "### 1.2 _components 组件目录")));
        DEPENDENCIES.put("design-skills-plugin/design-brief", new SkillRequirement(
                "设计方法论（设计稿质量约束）",
                // 设计契约的正文侧依赖：方法论缺失这四节，设计稿的层次/留白/色彩/CTA 约束即失效
                List.of(
                        "## 视觉层次",
                        "## 留白与节奏",
                        "## 色彩语义",
                        "## CTA 设计")));
    }

    /**
     * 代码侧硬依赖技能的依赖声明
     *
     * @param purpose       用途说明（异常与启动自检日志里展示，便于定位）
     * @param requiredParts 正文必须包含的锚点片段（章节标题）；任一缺失即视为正文漂移
     */
    public record SkillRequirement(String purpose, List<String> requiredParts) {
    }

    private RequiredSkills() {
    }

    /**
     * 代码侧硬依赖技能清单（skillId → 依赖声明）
     */
    public static Map<String, SkillRequirement> dependencies() {
        return Map.copyOf(DEPENDENCIES);
    }

    /**
     * 读取代码侧硬依赖技能的正文。
     *
     * <p><b>缺失时抛异常而不是返回 null</b>——见类注释「为什么不允许静默降级」。
     * 调用方都是"直接注入系统提示词"的硬依赖点，缺失意味着提示词缺了一段
     * 不可省略的约束，继续跑只会产出看似正常实则降级的结果。</p>
     *
     * <p><b>正文漂移时同样抛异常</b>——插件 jar 里的正文可能比主程序期望的旧
     * （例如插件停留在几个月前、源码已新增规范章节）。这类漂移<b>不会导致加载失败</b>，
     * 只会让模型拿到残缺规范照常出图，从日志里完全看不出来。契约锚点校验把它挡在运行期。</p>
     *
     * @param skillId    技能 id，必须在 {@link #dependencies()} 中登记
     * @param registry   技能注册中心
     * @param scene      场景名（异常信息里展示，便于定位是哪个链路缺技能）
     * @return 技能正文（非空白，且满足正文契约）
     * @throws IllegalStateException 技能未登记、正文缺失/为空、或正文漂移（缺必需锚点）
     */
    public static String requireContent(SkillRegistry registry, String skillId, String scene) {
        SkillRequirement requirement = DEPENDENCIES.get(skillId);
        if (requirement == null) {
            throw new IllegalStateException("技能 [" + skillId + "] 未登记到 RequiredSkills——"
                    + "代码侧硬依赖的技能必须先登记，否则缺失时无法被自检发现（场景：" + scene + "）");
        }
        String content = registry == null ? null : registry.loadContent(skillId);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("必需技能 [" + skillId + "] 加载失败（用途：" + requirement.purpose()
                    + "，场景：" + scene + "）——该技能必须由插件提供，"
                    + "请确认对应插件已安装并与当前 fastcms 版本匹配（缺失时提示词会缺少硬约束，"
                    + "不能静默降级）");
        }
        List<String> missing = findMissingParts(content, requirement.requiredParts());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("必需技能 [" + skillId + "] 正文漂移（用途：" + requirement.purpose()
                    + "，场景：" + scene + "）——插件 jar 内的技能正文缺少以下必需章节：" + String.join("、", missing)
                    + "。当前加载到的技能版本低于主程序期望，模型会拿到残缺规范却毫无察觉。"
                    + "请重新构建并部署对应插件（deploy-plugins.bat），使插件正文与主程序版本匹配");
        }
        return content;
    }

    /**
     * 启动期自检：检查全部硬依赖技能是否可用、正文是否满足契约，缺失时输出醒目告警。
     *
     * <p>不抛异常中断启动——插件可由用户在后台装卸，硬依赖缺失时应让站点可启动、
     * 但在使用该技能的链路上明确失败（由 {@link #requireContent} 负责）。
     * 这里只负责把"缺失"与"漂移"这两件事在启动日志里显式说出来。</p>
     *
     * @param registry 技能注册中心
     */
    public static void checkAll(SkillRegistry registry) {
        int missing = 0;
        int drifted = 0;
        for (Map.Entry<String, SkillRequirement> entry : DEPENDENCIES.entrySet()) {
            String skillId = entry.getKey();
            SkillRequirement requirement = entry.getValue();
            String content = registry == null ? null : registry.loadContent(skillId);
            if (content == null || content.isBlank()) {
                missing++;
                log.error("【必需技能缺失】技能 [{}]（用途：{}）未加载。"
                                + "该技能由插件提供，请确认对应插件已部署到 fastcms 插件目录；"
                                + "缺失时使用该技能的链路会直接失败，不会静默降级",
                        skillId, requirement.purpose());
                continue;
            }
            List<String> missingParts = findMissingParts(content, requirement.requiredParts());
            if (!missingParts.isEmpty()) {
                drifted++;
                log.error("【必需技能正文漂移】技能 [{}]（用途：{}）的正文缺少必需章节：{}。"
                                + "插件 jar 版本低于主程序期望，模型会拿到残缺规范却毫无察觉；"
                                + "请重新构建并部署对应插件",
                        skillId, requirement.purpose(), String.join("、", missingParts));
            }
        }
        if (missing == 0 && drifted == 0) {
            log.info("必需技能自检通过：{} 个硬依赖技能全部就绪且正文满足契约", DEPENDENCIES.size());
        } else {
            log.error("【必需技能自检未通过】共 {} 个：缺失 {} 个、正文漂移 {} 个，"
                            + "相关链路的产出质量不可信",
                    DEPENDENCIES.size(), missing, drifted);
        }
    }

    /**
     * 找出正文中缺失的必需锚点
     *
     * @param content       技能正文
     * @param requiredParts 必需锚点
     * @return 缺失的锚点列表（空列表表示全部满足）
     */
    private static List<String> findMissingParts(String content, List<String> requiredParts) {
        if (requiredParts == null || requiredParts.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String part : requiredParts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!content.contains(part)) {
                missing.add(part);
            }
        }
        return missing;
    }
}
