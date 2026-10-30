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

import com.fastcms.entity.AiModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 焕新轮输出上限解析测试（2026-10-29）
 *
 * <p><b>背景</b>：深度焕新轮对「整页重做」文件必须输出改造后的全文，
 * 而全文输出量 ≈ 输入量 × 2（实测 22KB 页面 → 44KB 输出 / 19212 token，系数 2.08）。
 * 固定 8192 上限对整页重做<b>必然截断</b>——JSON 在字符串中途断裂，
 * 该轮产出全部丢失，只能提额重跑（实测白等 191 秒 + 466 秒）。</p>
 *
 * @author wjun_java@163.com
 */
class UpgradeRoundTokensTest {

    private static AiModelConfig config(Integer maxTokens) {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setMaxTokens(maxTokens);
        return cfg;
    }

    @Test
    @DisplayName("用户未配上限时按批次估算值给足")
    void usesNeededWhenNoConfiguredLimit() {
        Integer tokens = AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(config(null), 24400);
        assertThat(tokens).isEqualTo(24400);
    }

    @Test
    @DisplayName("用户配的上限更大时以用户配置为准")
    void respectsLargerConfiguredLimit() {
        Integer tokens = AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(config(32768), 24400);
        assertThat(tokens).isEqualTo(32768);
    }

    @Test
    @DisplayName("用户配的上限更小时取较大值——避免整页重做必然截断")
    void takesMaxWhenConfiguredLimitIsTooSmall() {
        // 8192 是调整轮的收紧值，对整页重做不够用 → 必须抬高
        Integer tokens = AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(config(8192), 24400);
        assertThat(tokens).isEqualTo(24400);
    }

    @Test
    @DisplayName("模型配置为 null 时也能给出上限")
    void handlesNullConfig() {
        Integer tokens = AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(null, 20000);
        assertThat(tokens).isEqualTo(20000);
    }

    @Test
    @DisplayName("估算值为 0 且无配置时返回 null（走模型默认）")
    void returnsNullWhenNothingToApply() {
        assertThat(AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(config(null), 0)).isNull();
        assertThat(AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(null, 0)).isNull();
    }

    @Test
    @DisplayName("非法的零/负配置视为未配置")
    void treatsNonPositiveConfiguredLimitAsUnset() {
        assertThat(AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(config(0), 24400)).isEqualTo(24400);
        assertThat(AiTemplateGenServiceImpl.resolveUpgradeRoundTokens(config(-1), 24400)).isEqualTo(24400);
    }
}
