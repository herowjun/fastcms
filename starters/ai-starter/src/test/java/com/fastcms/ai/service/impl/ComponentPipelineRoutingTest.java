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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fastcms.ai.service.impl.AiTemplateGenServiceImpl.shouldUseComponentPipeline;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组件化管线路由判定测试（B5）
 *
 * @author wjun_java@163.com
 */
class ComponentPipelineRoutingTest {

    @Test
    @DisplayName("生成型首次对话（无 spec）→ 组件管线（首轮生成 spec）")
    void generationFirstChatUsesComponent() {
        assertThat(shouldUseComponentPipeline("component", true, false, false)).isTrue();
    }

    @Test
    @DisplayName("生成型微调（已有 spec）→ 组件管线")
    void generationRefineUsesComponent() {
        assertThat(shouldUseComponentPipeline("component", false, true, false)).isTrue();
    }

    @Test
    @DisplayName("调整型会话 + 正式模板已组件化 → 组件管线（B5 新增）")
    void adjustSessionOnComponentizedTemplateUsesComponent() {
        assertThat(shouldUseComponentPipeline("component", true, true, true)).isTrue();
    }

    @Test
    @DisplayName("调整型会话 + 正式模板未组件化 → 退回 HTML 调整链路（老路径不受影响）")
    void adjustSessionOnLegacyTemplateFallsBackToHtml() {
        assertThat(shouldUseComponentPipeline("component", true, false, true)).isFalse();
    }

    @Test
    @DisplayName("生成型非首次对话且无 spec → 不走组件管线（走分批 HTML 管线）")
    void generationNonFirstWithoutSpecSkipsComponent() {
        assertThat(shouldUseComponentPipeline("component", false, false, false)).isFalse();
    }

    @Test
    @DisplayName("gen-mode=html 时一律不走组件管线")
    void htmlModeNeverUsesComponent() {
        assertThat(shouldUseComponentPipeline("html", true, false, false)).isFalse();
        assertThat(shouldUseComponentPipeline("html", true, true, true)).isFalse();
    }

    @Test
    @DisplayName("gen-mode 大小写不敏感")
    void genModeCaseInsensitive() {
        assertThat(shouldUseComponentPipeline("COMPONENT", true, false, false)).isTrue();
    }
}
