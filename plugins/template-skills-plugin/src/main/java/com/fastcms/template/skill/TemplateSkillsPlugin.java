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

import com.fastcms.plugin.PluginBase;
import org.pf4j.PluginWrapper;

/**
 * 模板制作规范插件主类
 *
 * <p>纯技能供给型插件：无 Controller / 无数据表 / 无配置界面
 * （getConfigUrl 返回 null，前端插件管理不展示"配置"入口）。
 * 技能内容见 jar 内 {@code skills/<slug>/SKILL.md}，由
 * {@link TemplateSkillsProvider} 注册进主应用；消费方为
 * {@code TemplateGenPromptBuilder}（AI 生成 fastcms 模板文件的系统提示词规范段）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public class TemplateSkillsPlugin extends PluginBase {

    public TemplateSkillsPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getConfigUrl() {
        return null;
    }

}
