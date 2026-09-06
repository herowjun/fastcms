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
import com.fastcms.entity.AiModelConfig;

import java.util.List;

/**
 * AI 模型配置服务
 *
 * <p>接口放在 ai-starter 模块，因为它会通过 Spring AI 的 ChatModel 进行测试连接；
 * 实现类 {@code AiModelConfigServiceImpl} 也在此模块中。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface IAiModelConfigService extends IService<AiModelConfig> {

    /**
     * 配置场景：对话（默认）
     */
    String SCENE_CHAT = "chat";

    /**
     * 配置场景：生图（DashScope qwen-image 系列）
     */
    String SCENE_IMAGE = "image";

    /**
     * 获取当前激活的模型配置（chat 场景）
     * <p>同一时刻仅一条记录为激活状态；如果没有激活的，返回最新的那一条</p>
     *
     * @return 激活的配置，没有则返回 null
     */
    AiModelConfig getActiveConfig();

    /**
     * 获取指定场景当前激活的模型配置
     *
     * @param scene 场景（chat/image），见 {@link #SCENE_CHAT} {@link #SCENE_IMAGE}
     * @return 激活的配置，没有则返回 null
     */
    AiModelConfig getActiveConfig(String scene);

    /**
     * 设置某个配置为激活状态，同场景的其他配置自动设为未激活
     *
     * @param id 要激活的配置 id
     */
    void activate(Long id);

    /**
     * 测试某个配置是否可用
     *
     * @param id 配置 id
     * @return 测试结果，成功返回 ok，失败返回错误信息
     */
    String testConnection(Long id);

    /**
     * 保存或更新配置
     * <p>如果是新增且没有指定 active，默认设为 false；
     * 如果 active=true，会自动取消其他配置的激活状态</p>
     *
     * @param config 配置
     * @return 保存后的实体
     */
    AiModelConfig saveConfig(AiModelConfig config);

    /**
     * 列出所有配置，按 sort_num、id 排序
     */
    List<AiModelConfig> listAll();

    /**
     * 删除配置（同时失效对应 ChatModel 缓存，避免已删配置的模型实例残留）
     *
     * @param id 配置 id
     */
    void deleteConfig(Long id);

}
