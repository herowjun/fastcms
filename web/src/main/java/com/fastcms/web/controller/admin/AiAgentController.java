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
package com.fastcms.web.controller.admin;

import com.fastcms.ai.agent.AgentProfile;
import com.fastcms.ai.agent.AiAgentSaveRequest;
import com.fastcms.ai.service.IAiAgentService;
import com.fastcms.ai.skill.SkillDescriptor;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.common.auth.ActionTypes;
import com.fastcms.common.auth.Secured;
import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.fastcms.service.IResourceService.ResourceI18n.*;

/**
 * AI 智能体管理（多智能体架构的管理入口）
 *
 * <p>内置智能体只读（可复制为自定义副本）；自定义智能体仅 chat 型，
 * 保存时后端校验 skill/tool/模型配置白名单。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@RestController
@RequestMapping(FastcmsConstants.ADMIN_MAPPING + "/ai/agent")
public class AiAgentController {

    @Autowired
    private IAiAgentService aiAgentService;

    @Autowired
    private SkillRegistry skillRegistry;

    /**
     * 智能体列表（内置 + 自定义合并）
     */
    @GetMapping("list")
    @Secured(name = RESOURCE_NAME_AI_AGENT_LIST, resource = "ai:agent:list", action = ActionTypes.READ)
    public RestResult<List<AgentProfile>> list() {
        return RestResultUtils.success(aiAgentService.listAgents());
    }

    /**
     * 智能体详情（按 agentId，内置或自定义）
     */
    @GetMapping("get/{agentId}")
    @Secured(name = RESOURCE_NAME_AI_AGENT_LIST, resource = "ai:agent:list", action = ActionTypes.READ)
    public RestResult<AgentProfile> get(@PathVariable("agentId") String agentId) {
        AgentProfile profile = aiAgentService.getAgent(agentId);
        if (profile == null) {
            return RestResultUtils.failed("智能体不存在");
        }
        return RestResultUtils.success(profile);
    }

    /**
     * 可绑定的 skill 清单（能力绑定用）
     */
    @GetMapping("skills")
    @Secured(name = RESOURCE_NAME_AI_AGENT_LIST, resource = "ai:agent:list", action = ActionTypes.READ)
    public RestResult<List<SkillDescriptor>> skills() {
        return RestResultUtils.success(aiAgentService.listSkills());
    }

    /**
     * skill 详情规则（SKILL.md 正文，只读查看）
     *
     * @param skillId 完整技能 id（插件源为 {pluginId}/{slug}，文件源为裸 slug；含斜杠故走 query 参数）
     */
    @GetMapping("skill-content")
    @Secured(name = RESOURCE_NAME_AI_AGENT_LIST, resource = "ai:agent:list", action = ActionTypes.READ)
    public RestResult<Map<String, String>> skillContent(@RequestParam("skillId") String skillId) {
        String content = skillRegistry.loadContent(skillId);
        if (content == null) {
            return RestResultUtils.failed("skill 不存在或无有效内容: " + skillId);
        }
        return RestResultUtils.success(Map.of("skillId", skillId, "content", content));
    }

    /**
     * 可绑定的工具清单（能力绑定用）
     */
    @GetMapping("tools")
    @Secured(name = RESOURCE_NAME_AI_AGENT_LIST, resource = "ai:agent:list", action = ActionTypes.READ)
    public RestResult<List<Map<String, String>>> tools() {
        return RestResultUtils.success(aiAgentService.listTools());
    }

    /**
     * 保存自定义智能体（新建或更新）
     */
    @PostMapping("save")
    @Secured(name = RESOURCE_NAME_AI_AGENT_SAVE, resource = "ai:agent:save", action = ActionTypes.WRITE)
    public RestResult<AgentProfile> save(@RequestBody AiAgentSaveRequest request) {
        try {
            return RestResultUtils.success(aiAgentService.saveAgent(request));
        } catch (IllegalArgumentException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 复制智能体为自定义副本（内置/自定义皆可复制）
     */
    @PostMapping("copy/{agentId}")
    @Secured(name = RESOURCE_NAME_AI_AGENT_SAVE, resource = "ai:agent:save", action = ActionTypes.WRITE)
    public RestResult<AgentProfile> copy(@PathVariable("agentId") String agentId) {
        try {
            return RestResultUtils.success(aiAgentService.copyAgent(agentId));
        } catch (IllegalArgumentException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 删除自定义智能体（内置智能体不落库，无删除入口）
     */
    @PostMapping("delete/{id}")
    @Secured(name = RESOURCE_NAME_AI_AGENT_DELETE, resource = "ai:agent:delete", action = ActionTypes.WRITE)
    public RestResult<Boolean> delete(@PathVariable("id") Long id) {
        aiAgentService.deleteAgent(id);
        return RestResultUtils.success(true);
    }

}
