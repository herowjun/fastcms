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
package com.fastcms.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.ai.agent.AgentProfile;
import com.fastcms.ai.agent.AiAgentSaveRequest;
import com.fastcms.ai.agent.BuiltinAgents;
import com.fastcms.ai.skill.SkillDescriptor;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.ai.service.IAiAgentService;
import com.fastcms.ai.service.IAiModelConfigService;
import com.fastcms.ai.tool.AiToolRegistry;
import com.fastcms.entity.AiAgent;
import com.fastcms.entity.AiModelConfig;
import com.fastcms.mapper.AiAgentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 智能体 Service 实现
 *
 * <p>合并视图：内置智能体（BuiltinAgents 代码注册）∪ 自定义智能体（ai_agent 表）。
 * 校验策略：保存时 skill/tool/modelConfigId 硬校验（前端勾选列表即全量来源），
 * 读取时不二次过滤——插件卸载导致的失效引用在下次保存时暴露。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Service
public class AiAgentServiceImpl extends ServiceImpl<AiAgentMapper, AiAgent> implements IAiAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_SCENE_CHAT = "chat";

    @Autowired
    private BuiltinAgents builtinAgents;

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private AiToolRegistry aiToolRegistry;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private com.fastcms.service.IAiUsageLogService aiUsageLogService;

    @Override
    public List<AgentProfile> listAgents() {
        List<AgentProfile> result = new ArrayList<>(builtinAgents.list());
        list(Wrappers.<AiAgent>lambdaQuery()
                .orderByAsc(AiAgent::getSortNum)
                .orderByDesc(AiAgent::getId))
                .forEach(entity -> result.add(toProfile(entity)));
        // 内置在前（注册顺序），自定义按 sortNum/创建时间
        result.sort(Comparator.comparing((AgentProfile p) -> AgentProfile.SOURCE_BUILTIN.equals(p.getSource()) ? 0 : 1)
                .thenComparing(p -> p.getSortNum() == null ? 0 : p.getSortNum()));
        // 当日用量填充（一次 GROUP BY，展示字段；配额判断不依赖它，见 AgentProfile.todayTokens 注释）
        Map<String, Long> todayUsage = aiUsageLogService.getTodayAgentUsage();
        result.forEach(p -> p.setTodayTokens(todayUsage.getOrDefault(p.getAgentId(), 0L)));
        return result;
    }

    @Override
    public AgentProfile getAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        AgentProfile builtin = builtinAgents.get(agentId);
        if (builtin != null) {
            return builtin;
        }
        AiAgent entity = getByAgentId(agentId);
        return entity == null ? null : toProfile(entity);
    }

    @Override
    public AgentProfile saveAgent(AiAgentSaveRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("智能体名称不能为空");
        }
        // chat 型必须有系统提示词（pipeline 型由系统注册，不走本入口）
        if (request.getSystemPrompt() == null || request.getSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        validateSkills(request.getSkills());
        validateTools(request.getTools());
        validateModelConfig(request.getModelConfigId());

        AiAgent entity;
        if (request.getId() != null) {
            entity = getById(request.getId());
            if (entity == null) {
                throw new IllegalArgumentException("智能体不存在（id=" + request.getId() + "）");
            }
        } else {
            entity = new AiAgent();
            entity.setAgentId(newCustomAgentId());
            entity.setExecutionMode(AgentProfile.MODE_CHAT);
        }

        entity.setName(request.getName().trim());
        entity.setDescription(request.getDescription());
        entity.setSystemPrompt(request.getSystemPrompt());
        entity.setModelConfigId(request.getModelConfigId());
        entity.setTemperature(request.getTemperature());
        entity.setMaxTokens(request.getMaxTokens());
        entity.setSkills(toJson(request.getSkills()));
        entity.setTools(toJson(request.getTools()));
        entity.setDailyTokenQuota(request.getDailyTokenQuota() == null ? 0L : request.getDailyTokenQuota());
        entity.setSortNum(request.getSortNum() == null ? 0 : request.getSortNum());
        entity.setStatus(request.getStatus() == null ? 1 : request.getStatus());

        saveOrUpdate(entity);
        return toProfile(entity);
    }

    @Override
    public void deleteAgent(Long id) {
        AiAgent entity = getById(id);
        // 内置智能体不落库，getById 只可能命中自定义智能体；防御性兜底
        if (entity == null || BuiltinAgents.ARTICLE_WRITER_ID.equals(entity.getAgentId())) {
            return;
        }
        removeById(id);
    }

    @Override
    public AgentProfile copyAgent(String agentId) {
        AgentProfile source = getAgent(agentId);
        if (source == null) {
            throw new IllegalArgumentException("源智能体不存在：" + agentId);
        }

        AiAgent entity = new AiAgent();
        entity.setAgentId(newCustomAgentId());
        // 复制产物固定为 chat 型（pipeline 型无法由界面编辑）
        entity.setExecutionMode(AgentProfile.MODE_CHAT);
        entity.setName(source.getName() + " 副本");
        entity.setDescription(source.getDescription());
        entity.setSystemPrompt(source.getSystemPrompt());
        entity.setModelConfigId(source.getModelConfigId());
        entity.setTemperature(source.getTemperature());
        entity.setMaxTokens(source.getMaxTokens());
        // skill/tool 取与当前可用注册表的交集（内置引用的会话级闭包工具不在全局池，自然被滤除）
        entity.setSkills(toJson(intersect(source.getSkills(), skillIds())));
        entity.setTools(toJson(intersect(source.getTools(), toolNames())));
        entity.setDailyTokenQuota(source.getDailyTokenQuota() == null ? 0L : source.getDailyTokenQuota());
        entity.setSortNum(source.getSortNum() == null ? 0 : source.getSortNum());
        entity.setStatus(1);
        entity.setBaseAgentId(source.getAgentId());

        save(entity);
        return toProfile(entity);
    }

    @Override
    public List<SkillDescriptor> listSkills() {
        return skillRegistry.listSkills();
    }

    @Override
    public List<Map<String, String>> listTools() {
        List<Map<String, String>> result = new ArrayList<>();
        aiToolRegistry.getToolDescriptors().forEach((name, descriptor) -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("description", descriptor.getDescription());
            result.add(item);
        });
        return result;
    }

    private AiAgent getByAgentId(String agentId) {
        return getOne(Wrappers.<AiAgent>lambdaQuery()
                .eq(AiAgent::getAgentId, agentId)
                .last("limit 1"));
    }

    private String newCustomAgentId() {
        return "custom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void validateSkills(List<String> skills) {
        if (CollectionUtils.isEmpty(skills)) {
            return;
        }
        List<String> unknown = skills.stream().filter(id -> !skillRegistry.exists(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("skill 不存在：" + String.join(", ", unknown));
        }
    }

    private void validateTools(List<String> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            return;
        }
        List<String> known = new ArrayList<>(aiToolRegistry.getToolDescriptors().keySet());
        List<String> unknown = tools.stream().filter(name -> !known.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("工具不存在：" + String.join(", ", unknown));
        }
    }

    private void validateModelConfig(Long modelConfigId) {
        if (modelConfigId == null) {
            return;
        }
        AiModelConfig config = aiModelConfigService.getById(modelConfigId);
        if (config == null) {
            throw new IllegalArgumentException("模型配置不存在（id=" + modelConfigId + "）");
        }
        if (!MODEL_SCENE_CHAT.equals(config.getScene() == null ? MODEL_SCENE_CHAT : config.getScene())) {
            throw new IllegalArgumentException("只能绑定对话场景的模型配置：" + config.getName());
        }
    }

    private List<String> skillIds() {
        return skillRegistry.listSkills().stream().map(SkillDescriptor::id).toList();
    }

    private List<String> toolNames() {
        return new ArrayList<>(aiToolRegistry.getToolDescriptors().keySet());
    }

    private List<String> intersect(List<String> candidates, List<String> universe) {
        if (CollectionUtils.isEmpty(candidates)) {
            return new ArrayList<>();
        }
        return candidates.stream().filter(universe::contains).toList();
    }

    private String toJson(List<String> list) {
        if (CollectionUtils.isEmpty(list)) {
            return "[]";
        }
        return MAPPER.writeValueAsString(list);
    }

    private List<String> fromJson(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode node = MAPPER.readTree(json);
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    result.add(value);
                }
            });
        }
        return result;
    }

    private AgentProfile toProfile(AiAgent entity) {
        return AgentProfile.builder()
                .agentId(entity.getAgentId())
                .dbId(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .executionMode(entity.getExecutionMode() == null ? AgentProfile.MODE_CHAT : entity.getExecutionMode())
                .source(AgentProfile.SOURCE_CUSTOM)
                .systemPrompt(entity.getSystemPrompt())
                .modelConfigId(entity.getModelConfigId())
                .temperature(entity.getTemperature())
                .maxTokens(entity.getMaxTokens())
                .skills(fromJson(entity.getSkills()))
                .tools(fromJson(entity.getTools()))
                .dailyTokenQuota(entity.getDailyTokenQuota() == null ? 0L : entity.getDailyTokenQuota())
                .sortNum(entity.getSortNum() == null ? 0 : entity.getSortNum())
                .status(entity.getStatus() == null ? 1 : entity.getStatus())
                .baseAgentId(entity.getBaseAgentId())
                .build();
    }

}
