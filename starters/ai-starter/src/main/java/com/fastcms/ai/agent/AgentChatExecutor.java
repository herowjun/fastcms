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
package com.fastcms.ai.agent;

import com.fastcms.ai.audit.AiQuotaChecker;
import com.fastcms.ai.service.IAiAgentService;
import com.fastcms.ai.service.IAiModelConfigService;
import com.fastcms.ai.service.impl.AiModelConfigServiceImpl;
import com.fastcms.ai.skill.SkillDescriptor;
import com.fastcms.ai.skill.SkillRegistry;
import com.fastcms.ai.tool.AiToolCallbackProvider;
import com.fastcms.entity.AiModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能体执行要素装配器（多智能体架构的统一执行入口）
 *
 * <p>所有走智能体的 AI 调用（文章写作、模板生成管线等）在发起模型调用前
 * 先经 {@link #prepare(String, Long)} 装配执行要素，得到：</p>
 * <ul>
 *     <li><b>模型</b>：profile.modelConfigId 绑定的配置优先；未绑定继承当前激活配置</li>
 *     <li><b>runtime options</b>：从 {@link AiModelConfigServiceImpl#baseOptionsBuilder}
 *     基底出发叠加 profile 的 temperature/maxTokens 覆盖——保证 model/apiKey/baseUrl/timeout
 *     永远在场（runtime options 不会自动合并默认 options，漏设会 404/断流）</li>
 *     <li><b>工具白名单</b>：仅挂载 profile.tools 勾选的工具（空 = 不挂载任何工具）；
 *     绑定技能时自动附加 {@code load_skill} 工具供模型按需读取技能完整指令（L2）</li>
 *     <li><b>system prompt 基底</b>：profile.systemPrompt + 技能 L1 摘要清单（渐进式披露：
 *     L1 常驻，L2 由模型调用 load_skill 按需加载），场景契约（如输出 JSON 格式）由调用方追加</li>
 *     <li><b>配额</b>：全局用户级配额 + 智能体级日配额双重检查</li>
 * </ul>
 *
 * <p>装配失败（智能体不存在/已停用/模型配置缺失/配额超限）抛
 * {@link IllegalArgumentException} 或 {@code AiQuotaExceededException}，
 * 由调用方转为用户可见的错误提示。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.1
 */
@Component
public class AgentChatExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentChatExecutor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 技能加载工具名（仅绑定技能的智能体挂载，见 buildLoadSkillTool） */
    static final String TOOL_LOAD_SKILL = "load_skill";

    private final IAiAgentService agentService;
    private final IAiModelConfigService modelConfigService;
    private final AiToolCallbackProvider toolCallbackProvider;
    private final SkillRegistry skillRegistry;
    private final AiQuotaChecker quotaChecker;

    public AgentChatExecutor(IAiAgentService agentService, IAiModelConfigService modelConfigService,
                             AiToolCallbackProvider toolCallbackProvider, SkillRegistry skillRegistry,
                             AiQuotaChecker quotaChecker) {
        this.agentService = agentService;
        this.modelConfigService = modelConfigService;
        this.toolCallbackProvider = toolCallbackProvider;
        this.skillRegistry = skillRegistry;
        this.quotaChecker = quotaChecker;
    }

    /**
     * 装配智能体执行要素（每次模型调用前调用，默认注入技能清单）
     *
     * @param agentId 智能体业务ID（builtin.* 或 custom-*）
     * @param userId 触发用户（全局配额检查用，可为 null）
     * @throws IllegalArgumentException 智能体不存在/已停用/模型配置不可用
     * @throws com.fastcms.ai.audit.AiQuotaExceededException 配额超限
     */
    public Prepared prepare(String agentId, Long userId) {
        return prepare(agentId, userId, true);
    }

    /**
     * 装配智能体执行要素（场景化技能注入）
     *
     * <p>injectSkills=false 时跳过技能清单注入且不挂 load_skill 工具，用于<b>确定性文本变换
     * 场景</b>（划词改写/扩写/翻译、字段候选生成等）：此类任务有明确的场景契约
     * （直接输出结果、保留 HTML 结构），模型按契约执行即可；注入技能清单反而诱导模型
     * 中途调用 load_skill 读技能指令，多一轮工具往返且输出风格被技能带偏
     * （实例：扩写被"长文续写"技能干扰，流式循环未正常收尾）。
     * 开放式写作场景（全文生成、对话）应传 true，让技能体系发挥作用。</p>
     *
     * @param injectSkills 是否注入技能 L1 清单并挂载 load_skill 工具
     */
    public Prepared prepare(String agentId, Long userId, boolean injectSkills) {
        AgentProfile profile = agentService.getAgent(agentId);
        if (profile == null) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        if (profile.getStatus() == null || profile.getStatus() != 1) {
            throw new IllegalArgumentException("智能体已停用: " + profile.getName());
        }

        // 配额：全局用户级 + 智能体级（dailyTokenQuota=0/null 不限）
        quotaChecker.check(userId);
        quotaChecker.checkAgent(profile);

        // 模型：profile 绑定优先；未绑定继承激活配置
        AiModelConfig modelConfig;
        if (profile.getModelConfigId() != null) {
            modelConfig = modelConfigService.getById(profile.getModelConfigId());
            if (modelConfig == null) {
                throw new IllegalArgumentException(
                        "智能体绑定的模型配置已不存在（id=" + profile.getModelConfigId() + "），请重新编辑智能体");
            }
        } else {
            modelConfig = modelConfigService.getActiveConfig();
            if (modelConfig == null) {
                throw new IllegalArgumentException("未配置 AI 模型，请先在模型管理中添加并激活一个配置");
            }
        }

        // runtime options：从配置基底构建（请求级字段永远在场），叠加 profile 覆盖
        OpenAiChatOptions.Builder optionsBuilder = AiModelConfigServiceImpl.baseOptionsBuilder(modelConfig);
        if (profile.getTemperature() != null) {
            optionsBuilder.temperature(profile.getTemperature());
        }
        if (profile.getMaxTokens() != null) {
            optionsBuilder.maxTokens(profile.getMaxTokens());
        }
        OpenAiChatOptions runtimeOptions = optionsBuilder.build();

        ChatModel chatModel = AiModelConfigServiceImpl.buildChatModel(modelConfig);
        ToolCallback[] whitelistedTools = buildTools(profile, injectSkills);

        return new Prepared(profile, modelConfig, chatModel, whitelistedTools, runtimeOptions,
                injectSkills ? buildSkillManifest(profile) : "");
    }

    /**
     * 工具白名单过滤：仅挂载 profile.tools 勾选的工具；注入技能时附加 load_skill
     */
    private ToolCallback[] buildTools(AgentProfile profile, boolean injectSkills) {
        Set<String> whitelist = new HashSet<>(profile.getTools());
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : toolCallbackProvider.getToolCallbacks()) {
            String name = callback.getToolDefinition() == null ? null : callback.getToolDefinition().name();
            if (name != null && whitelist.contains(name)) {
                callbacks.add(callback);
            }
        }
        if (injectSkills && !profile.getSkills().isEmpty()) {
            callbacks.add(buildLoadSkillTool(profile));
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    /**
     * load_skill 工具：读取技能完整指令（SKILL.md L2 正文）
     *
     * <p>仅允许加载该智能体白名单内的技能（防止模型越权读取其他技能）。</p>
     */
    private ToolCallback buildLoadSkillTool(AgentProfile profile) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(TOOL_LOAD_SKILL)
                .description("读取某项技能的完整指令（SKILL.md 正文）。仅当系统提示的技能清单中有匹配当前任务的技能时调用，"
                        + "读取后严格按指令执行。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"skillId\":{\"type\":\"string\","
                        + "\"description\":\"技能ID（技能清单中圆括号内的 id）\"}},\"required\":[\"skillId\"]}")
                .build();
        Set<String> allowed = Set.copyOf(profile.getSkills());
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                String skillId = extractStringParam(toolInput, "skillId");
                if (skillId == null || skillId.isBlank()) {
                    return "参数错误：skillId 不能为空";
                }
                if (!allowed.contains(skillId)) {
                    return "该技能不在本智能体的技能白名单内: " + skillId;
                }
                String content = skillRegistry.loadContent(skillId);
                if (content == null || content.isBlank()) {
                    return "技能不存在或无有效内容: " + skillId;
                }
                return "===== " + skillId + " 完整指令 =====\n" + content;
            }
        };
    }

    /**
     * 技能 L1 摘要清单（注入 system prompt；缺失/无效的技能自动跳过并记日志）
     */
    private String buildSkillManifest(AgentProfile profile) {
        if (profile.getSkills().isEmpty()) {
            return "";
        }
        Set<String> whitelist = new HashSet<>(profile.getSkills());
        List<SkillDescriptor> available = skillRegistry.listSkills().stream()
                .filter(d -> whitelist.contains(d.id()))
                .filter(d -> d.error() == null || d.error().isBlank())
                .collect(Collectors.toList());
        if (available.isEmpty()) {
            log.warn("智能体 [{}] 绑定的技能全部不可用（插件未安装或 SKILL.md 无效）: {}",
                    profile.getAgentId(), profile.getSkills());
            return "";
        }
        String items = available.stream()
                .map(d -> "- " + d.name() + "（" + d.id() + "）：" + d.description())
                .collect(Collectors.joining("\n"));
        return "# 可用技能\n"
                + "当前任务匹配以下技能时，先调用 " + TOOL_LOAD_SKILL + " 工具读取该项技能的完整指令，然后严格按指令执行：\n"
                + items;
    }

    private String extractStringParam(String toolInput, String field) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode node = root.get(field);
            return node != null && node.isTextual() ? node.asString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 装配产物：一次智能体调用的全部执行要素
     */
    public static class Prepared {

        private final AgentProfile profile;
        private final AiModelConfig modelConfig;
        private final ChatModel chatModel;
        /** 白名单过滤后的全局工具（含绑定技能时的 load_skill） */
        private final ToolCallback[] whitelistedTools;
        private final OpenAiChatOptions runtimeOptions;
        private final String skillManifest;

        /** 懒构建的默认 ChatClient（无附加工具，chat 型场景直接使用） */
        private volatile ChatClient chatClient;

        private Prepared(AgentProfile profile, AiModelConfig modelConfig, ChatModel chatModel,
                         ToolCallback[] whitelistedTools, OpenAiChatOptions runtimeOptions, String skillManifest) {
            this.profile = profile;
            this.modelConfig = modelConfig;
            this.chatModel = chatModel;
            this.whitelistedTools = whitelistedTools;
            this.runtimeOptions = runtimeOptions;
            this.skillManifest = skillManifest;
        }

        public AgentProfile getProfile() { return profile; }

        public String getAgentId() { return profile.getAgentId(); }

        public AiModelConfig getModelConfig() { return modelConfig; }

        /** 审计用模型名 */
        public String getModelName() { return modelConfig.getModel(); }

        public ChatModel getChatModel() { return chatModel; }

        /** 白名单过滤后的全局工具（含 load_skill），供需要自组装 ChatClient 的管线场景合并使用 */
        public ToolCallback[] getWhitelistedTools() { return whitelistedTools; }

        /**
         * 默认 ChatClient（白名单工具挂 defaultTools）。
         * chat 型场景（无请求级工具）直接使用；懒构建，重复调用返回同一实例。
         */
        public ChatClient getChatClient() {
            ChatClient result = chatClient;
            if (result == null) {
                synchronized (this) {
                    if (chatClient == null) {
                        chatClient = ChatClient.builder(chatModel).defaultTools(whitelistedTools).build();
                    }
                    result = chatClient;
                }
            }
            return result;
        }

        /**
         * 构建附加了请求级工具的 ChatClient（白名单工具 + additionalTools 合并挂 defaultTools）。
         *
         * <p>pipeline 型场景（模板生成：会话级闭包工具 read_template_file /
         * search_template_files 按请求创建）用此方法组装；builder 是轻量包装，
         * 每请求构建无性能负担。</p>
         */
        public ChatClient createChatClient(ToolCallback... additionalTools) {
            ToolCallback[] merged = new ToolCallback[whitelistedTools.length + additionalTools.length];
            System.arraycopy(whitelistedTools, 0, merged, 0, whitelistedTools.length);
            System.arraycopy(additionalTools, 0, merged, whitelistedTools.length, additionalTools.length);
            return ChatClient.builder(chatModel).defaultTools(merged).build();
        }

        public OpenAiChatOptions getRuntimeOptions() { return runtimeOptions; }

        /**
         * 智能体级 system prompt 基底 = profile.systemPrompt + 技能 L1 清单。
         * 场景契约（输出 JSON 格式等）由调用方在此基础上追加。
         */
        public String getBaseSystemPrompt() {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(profile.getSystemPrompt())) {
                sb.append(profile.getSystemPrompt().trim());
            }
            if (StringUtils.hasText(skillManifest)) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(skillManifest);
            }
            return sb.toString();
        }

        /**
         * 构建 Prompt（显式携带 runtime options，防漏设 model/timeout）
         */
        public Prompt createPrompt(String systemPrompt, String userPrompt) {
            return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                    runtimeOptions);
        }
    }
}
