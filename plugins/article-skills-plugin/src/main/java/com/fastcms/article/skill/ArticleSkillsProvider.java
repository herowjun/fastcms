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
package com.fastcms.article.skill;

import com.fastcms.ai.skill.ClasspathSkillProvider;
import org.pf4j.Extension;

/**
 * 文章技能供给方：扫描插件 jar 内 {@code skills/<slug>/SKILL.md} 注册 4 个写作技能
 *
 * <p>技能 id 为 {@code article-skills-plugin/{slug}}，内置"文章续写智能体"
 * （见 BuiltinAgents）按此 id 绑定。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Extension
public class ArticleSkillsProvider extends ClasspathSkillProvider {

    @Override
    public String getProviderId() {
        return "article-skills-plugin";
    }

}
