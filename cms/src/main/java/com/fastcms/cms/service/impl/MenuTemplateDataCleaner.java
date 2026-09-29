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
package com.fastcms.cms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fastcms.cms.entity.Menu;
import com.fastcms.cms.mapper.MenuMapper;
import com.fastcms.common.utils.StrUtils;
import com.fastcms.core.template.TemplateDataCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模板彻底删除时清理网站菜单上的模板绑定。
 *
 * <p>菜单表有两处引用模板id，处理口径不同：</p>
 * <ol>
 *   <li>{@code menu.template_id} —— 该模板的<b>专属菜单</b>（非空表示仅该模板显示，替代全局菜单）。
 *       模板没了这些菜单无人可见，直接删行。</li>
 *   <li>{@code menu.exclude_template_ids} —— 全局菜单的<b>排除列表</b>（逗号分隔的模板id）。
 *       只从串里摘掉该 id，菜单本身保留；摘空则置 NULL（恢复"对所有模板显示"）。</li>
 * </ol>
 *
 * <p>这是目前 {@link TemplateDataCleaner} 唯一的实现，也是"彻底删除"在数据库侧的全部动作 ——
 * AI 侧的会话、对话、文件备份、生图任务与 token 用量记录<b>一律不动</b>（计费审计数据，
 * 理由见 {@link TemplateDataCleaner} 的清理口径说明）。不要在这里顺手加 AI 表的清理。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
@Component
public class MenuTemplateDataCleaner implements TemplateDataCleaner {

    @Autowired
    private MenuMapper menuMapper;

    @Override
    public int cleanTemplateData(String templateId) {
        int affected = 0;

        // 1) 专属菜单随模板一起删除
        affected += menuMapper.delete(Wrappers.<Menu>lambdaQuery().eq(Menu::getTemplateId, templateId));

        // 2) 全局菜单的排除列表里摘掉该模板 id。
        //    用 like 粗筛（可能命中子串）后再逐行精确比对，避免把排除列表写坏。
        List<Menu> menus = menuMapper.selectList(Wrappers.<Menu>lambdaQuery()
                .isNotNull(Menu::getExcludeTemplateIds)
                .like(Menu::getExcludeTemplateIds, templateId));

        for (Menu menu : menus) {
            String origin = menu.getExcludeTemplateIds();
            String remaining = removeCsvToken(origin, templateId);
            if (Objects.equals(origin, remaining)) {
                continue;
            }
            // 显式 set() 而不是 updateById(实体)：MyBatis-Plus 默认忽略实体里的 null 字段，
            // 用实体无法把列清成 NULL
            affected += menuMapper.update(null, Wrappers.<Menu>lambdaUpdate()
                    .eq(Menu::getId, menu.getId())
                    .set(Menu::getExcludeTemplateIds, StrUtils.isBlank(remaining) ? null : remaining));
        }

        return affected;
    }

    /**
     * 从逗号分隔串里摘掉一个 token，顺带规整空白与空段
     */
    private String removeCsvToken(String csv, String token) {
        if (StrUtils.isBlank(csv)) {
            return csv;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty() && !item.equals(token))
                .collect(Collectors.joining(","));
    }

}
