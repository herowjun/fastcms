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

package com.fastcms.template.skill;

import com.fastcms.ai.skill.ClasspathSkillProvider;
import org.pf4j.Extension;

/**
 * 模板技能供给方：扫描插件 jar 内 {@code skills/<slug>/SKILL.md} 注册模板制作规范技能
 *
 * <p>技能 id 为 {@code template-skills-plugin/template-spec}，由
 * {@code TemplateGenPromptBuilder#buildSystemPrompt} 全文直注系统提示词的规范段
 * （消费方式与 design-brief 技能一致：loadContent 直注，不走 load_skill 两段式）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Extension
public class TemplateSkillsProvider extends ClasspathSkillProvider {

    @Override
    public String getProviderId() {
        return "template-skills-plugin";
    }

}
