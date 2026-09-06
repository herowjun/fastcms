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
package com.fastcms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastcms.entity.AiModelConfig;

/**
 * AI 模型配置 Mapper
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public interface AiModelConfigMapper extends BaseMapper<AiModelConfig> {

    /**
     * 原子化激活：将同场景的其他配置置为未激活
     *
     * @param id 要激活的配置 id
     * @param scene 配置场景（chat/image）
     * @return 受影响行数
     */
    @org.apache.ibatis.annotations.Update("UPDATE ai_model_config SET is_active = 0 WHERE is_active = 1 AND scene = #{scene} AND id <> #{id}")
    int deactivateOthers(@org.apache.ibatis.annotations.Param("id") Long id,
                         @org.apache.ibatis.annotations.Param("scene") String scene);

    /**
     * 原子化激活目标配置
     *
     * @param id 要激活的配置 id
     * @return 受影响行数（0 表示配置不存在）
     */
    @org.apache.ibatis.annotations.Update("UPDATE ai_model_config SET is_active = 1 WHERE id = #{id}")
    int activateById(@org.apache.ibatis.annotations.Param("id") Long id);

    /**
     * 将指定场景的所有配置置为未激活（新增激活配置前调用，此时尚无新配置 id）
     *
     * @param scene 配置场景（chat/image）
     * @return 受影响行数
     */
    @org.apache.ibatis.annotations.Update("UPDATE ai_model_config SET is_active = 0 WHERE is_active = 1 AND scene = #{scene}")
    int deactivateAllByScene(@org.apache.ibatis.annotations.Param("scene") String scene);
}
