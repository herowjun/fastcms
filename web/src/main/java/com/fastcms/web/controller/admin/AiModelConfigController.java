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

import com.fastcms.common.auth.ActionTypes;
import com.fastcms.common.auth.Secured;
import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import com.fastcms.entity.AiModelConfig;
import com.fastcms.ai.service.IAiModelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.fastcms.service.IResourceService.ResourceI18n.*;

/**
 * AI 模型配置管理
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@RestController
@RequestMapping(FastcmsConstants.ADMIN_MAPPING + "/ai/model")
public class AiModelConfigController {

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /**
     * apiKey 脱敏：非空时替换为占位符，前端回传该占位符表示未修改
     */
    private static void maskApiKey(AiModelConfig config) {
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            config.setApiKey("********");
        }
    }

    /**
     * 模型配置列表
     */
    @GetMapping("list")
    @Secured(name = RESOURCE_NAME_AI_MODEL_LIST, resource = "ai:model:list", action = ActionTypes.READ)
    public RestResult<List<AiModelConfig>> list() {
        List<AiModelConfig> configs = aiModelConfigService.listAll();
        // apiKey 脱敏，避免列表接口明文暴露密钥
        if (configs != null) {
            configs.forEach(AiModelConfigController::maskApiKey);
        }
        return RestResultUtils.success(configs);
    }

    /**
     * 获取当前激活的模型配置
     */
    @GetMapping("active")
    @Secured(name = RESOURCE_NAME_AI_MODEL_LIST, resource = "ai:model:active", action = ActionTypes.READ)
    public RestResult<AiModelConfig> active() {
        AiModelConfig config = aiModelConfigService.getActiveConfig();
        maskApiKey(config);
        return RestResultUtils.success(config);
    }

    /**
     * 获取单个配置详情
     */
    @GetMapping("get/{id}")
    @Secured(name = RESOURCE_NAME_AI_MODEL_GET, resource = "ai:model:get", action = ActionTypes.READ)
    public RestResult<AiModelConfig> get(@PathVariable("id") Long id) {
        AiModelConfig config = aiModelConfigService.getById(id);
        maskApiKey(config);
        return RestResultUtils.success(config);
    }

    /**
     * 新增或更新配置
     */
    @PostMapping("save")
    @Secured(name = RESOURCE_NAME_AI_MODEL_SAVE, resource = "ai:model:save", action = ActionTypes.WRITE)
    public RestResult<AiModelConfig> save(@RequestBody AiModelConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return RestResultUtils.failed("配置名称不能为空");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            return RestResultUtils.failed("API 端点不能为空");
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            return RestResultUtils.failed("模型名称不能为空");
        }
        return RestResultUtils.success(aiModelConfigService.saveConfig(config));
    }

    /**
     * 删除配置
     */
    @PostMapping("delete/{id}")
    @Secured(name = RESOURCE_NAME_AI_MODEL_DELETE, resource = "ai:model:delete", action = ActionTypes.WRITE)
    public RestResult<Boolean> delete(@PathVariable("id") Long id) {
        return RestResultUtils.success(aiModelConfigService.removeById(id));
    }

    /**
     * 激活某个配置
     */
    @PostMapping("activate/{id}")
    @Secured(name = RESOURCE_NAME_AI_MODEL_ACTIVATE, resource = "ai:model:activate", action = ActionTypes.WRITE)
    public RestResult<Boolean> activate(@PathVariable("id") Long id) {
        aiModelConfigService.activate(id);
        return RestResultUtils.success(true);
    }

    /**
     * 测试某个配置是否可用
     */
    @PostMapping("test/{id}")
    @Secured(name = RESOURCE_NAME_AI_MODEL_TEST, resource = "ai:model:test", action = ActionTypes.WRITE)
    public RestResult<String> test(@PathVariable("id") Long id) {
        String result = aiModelConfigService.testConnection(id);
        if ("ok".equals(result)) {
            return RestResultUtils.success("ok");
        }
        return RestResultUtils.failed(result);
    }

}
