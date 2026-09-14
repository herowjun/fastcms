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
package com.fastcms.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.ai.agent.AgentProfile;
import com.fastcms.ai.agent.AiAgentSaveRequest;
import com.fastcms.ai.skill.SkillDescriptor;
import com.fastcms.entity.AiAgent;

import java.util.List;
import java.util.Map;

/**
 * AI 智能体 Service（内置 + 自定义合并的注册中心）
 *
 * <p>职责：AgentProfile 的合并查询（内置代码注册 ∪ ai_agent 表）、
 * 自定义智能体的增删改（含 skill/tool 白名单校验）、复制派生。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
public interface IAiAgentService extends IService<AiAgent> {

    /**
     * 智能体全量列表（内置在前、自定义按 sortNum/created 排序在后）
     */
    List<AgentProfile> listAgents();

    /**
     * 按 agentId 查询智能体（内置或自定义）
     *
     * @return 不存在时返回 null
     */
    AgentProfile getAgent(String agentId);

    /**
     * 保存自定义智能体（新建或更新；执行模式固定为 chat）
     *
     * @throws IllegalArgumentException 校验失败（名称/提示词缺失、skill 或 tool 不存在、模型配置不存在等）
     */
    AgentProfile saveAgent(AiAgentSaveRequest request);

    /**
     * 删除自定义智能体（按数据库ID；内置智能体不落库，天然不可删）
     */
    void deleteAgent(Long id);

    /**
     * 复制智能体为自定义副本（内置或自定义皆可复制；副本为 chat 型，
     * 复制来源记入 baseAgentId，skill/tool 取与当前可用注册表的交集）
     *
     * @throws IllegalArgumentException 源智能体不存在
     */
    AgentProfile copyAgent(String agentId);

    /**
     * 可绑定的 skill 清单（SkillRegistry 全量）
     */
    List<SkillDescriptor> listSkills();

    /**
     * 可绑定的工具清单（AiToolRegistry 全量：name + description）
     */
    List<Map<String, String>> listTools();

}
