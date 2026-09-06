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
package com.fastcms.twplus;

import com.fastcms.plugin.PluginBase;
import org.pf4j.PluginWrapper;

/**
 * twx 组件包扩充插件：以 PF4J 插件形式为 AI 模板生成供给内容区组件
 *
 * <p>插件本体只负责生命周期；组件供给由 {@link TwPlusComponentPackProvider}
 * （{@code @Extension}）完成——安装后自动注册为 Spring bean，
 * {@code ComponentRegistry} 惰性感知容器变化将其纳入组件菜单，AI 下一次规划即可选用。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class TwPlusPlugin extends PluginBase {

    public TwPlusPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * 本插件无管理界面配置页（组件清单由 AI 模板生成对话框自动呈现），返回空
     */
    @Override
    public String getConfigUrl() {
        return "";
    }

}
