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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 必需技能启动自检：在应用就绪后检查代码侧硬依赖技能是否可用。
 *
 * <p><b>为什么要在启动期就检查</b>：代码侧硬依赖技能若缺失，最典型的表现是
 * "功能照常跑、产出少了硬约束"——运行期从日志里看不出来，往往是用户投诉模板质量
 * 才发现。放到启动日志里显式说出来，问题就在第一次部署时暴露，而不是几周后。</p>
 *
 * <p><b>为什么用 ApplicationReadyEvent 而不是 ApplicationStartedEvent</b>：
 * 插件加载发生在 {@code FastcmsPluginManager} 的 {@code ApplicationStartedEvent}
 * 处理器里（{@code initPlugins()}），必须等插件的 SkillProvider 注册成 Spring Bean
 * 之后，本自检才能看到完整的技能集合。ApplicationReadyEvent 在其后。</p>
 *
 * <p>自检只告警不中断启动——插件支持后台装卸，硬依赖缺失时应当让站点可启动、
 * 由使用该技能的链路明确失败（见 {@link RequiredSkills#requireContent}）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequiredSkillChecker {

    private static final Logger log = LoggerFactory.getLogger(RequiredSkillChecker.class);

    private final SkillRegistry skillRegistry;

    public RequiredSkillChecker(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("开始检查代码侧硬依赖技能（共 {} 个）", RequiredSkills.dependencies().size());
        RequiredSkills.checkAll(skillRegistry);
    }

}
