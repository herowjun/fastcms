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
package com.fastcms.design.skill;

import com.fastcms.plugin.PluginBase;
import org.pf4j.PluginWrapper;

/**
 * 设计技能插件主类
 *
 * <p>纯技能供给型插件：无 Controller / 无数据表 / 无配置界面
 * （getConfigUrl 返回 null，前端插件管理不展示"配置"入口）。
 * 技能内容见 jar 内 {@code skills/<slug>/SKILL.md}，由
 * {@link DesignSkillsProvider} 注册进主应用。</p>
 *
 * <p><b>为什么 design-brief 必须是插件而不是本地磁盘文件</b>：设计段把它全文直注系统提示词，
 * 是模板生成链路的<b>硬依赖</b>。若内容只存在于某台机器的 {@code ~/fastcms/skills/} 目录里，
 * 换机器 / 重新部署 / 新同事拉代码后该技能凭空消失，而代码侧的调用点仍会静默降级
 * （少一段方法论却照常生成模板），问题被掩盖到无法追溯。放进插件后：
 * 内容随 jar 一起版本化、由 {@code deploy-plugins.bat} 统一分发、缺失可被启动自检发现。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public class DesignSkillsPlugin extends PluginBase {

    public DesignSkillsPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getConfigUrl() {
        return null;
    }

}
