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
package com.fastcms.ai.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调整轮意图分类测试（B2 思考档位分流）
 *
 * @author wjun_java@163.com
 */
class AdjustIntentTest {

    @Test
    @DisplayName("问原因 → 诊断类")
    void detectsDiagnostic() {
        assertThat(AdjustIntent.classify("导航为什么不居中")).isEqualTo(AdjustIntent.DIAGNOSTIC);
        assertThat(AdjustIntent.classify("这个按钮为什么不生效")).isEqualTo(AdjustIntent.DIAGNOSTIC);
        assertThat(AdjustIntent.classify("为什么页脚是空的")).isEqualTo(AdjustIntent.DIAGNOSTIC);
        assertThat(AdjustIntent.classify("能解释一下这个布局吗")).isEqualTo(AdjustIntent.DIAGNOSTIC);
        assertThat(AdjustIntent.classify("有什么建议")).isEqualTo(AdjustIntent.DIAGNOSTIC);
    }

    @Test
    @DisplayName("明确改动 → 改动类")
    void detectsChange() {
        assertThat(AdjustIntent.classify("把主色改成蓝色")).isEqualTo(AdjustIntent.CHANGE);
        assertThat(AdjustIntent.classify("页脚增加一个备案号")).isEqualTo(AdjustIntent.CHANGE);
        assertThat(AdjustIntent.classify("删掉轮播图")).isEqualTo(AdjustIntent.CHANGE);
        assertThat(AdjustIntent.classify("把标题换成公司名")).isEqualTo(AdjustIntent.CHANGE);
    }

    @Test
    @DisplayName("混合语句：含强改动词时按改动处理（即使含诊断词）")
    void changeWinsOverDiagnostic() {
        assertThat(AdjustIntent.classify("把不居中的改成居中")).isEqualTo(AdjustIntent.CHANGE);
    }

    @Test
    @DisplayName("否定感知：「不显示」是诊断，「帮我显示出来」是改动")
    void negationAwareAmbiguousKeywords() {
        assertThat(AdjustIntent.classify("这个按钮不显示")).isEqualTo(AdjustIntent.DIAGNOSTIC);
        assertThat(AdjustIntent.classify("图片没显示出来")).isEqualTo(AdjustIntent.DIAGNOSTIC);
        assertThat(AdjustIntent.classify("这个不显示的地方帮我显示出来")).isEqualTo(AdjustIntent.CHANGE);
    }

    @Test
    @DisplayName("诊断词优先于弱改动词（为什么 + 配色 → 诊断）")
    void diagnosticBeatsWeakChangeKeyword() {
        assertThat(AdjustIntent.classify("为什么不优化配色")).isEqualTo(AdjustIntent.DIAGNOSTIC);
    }

    @Test
    @DisplayName("弱改动词单独出现 → 改动类")
    void weakChangeKeyword() {
        assertThat(AdjustIntent.classify("把它居中")).isEqualTo(AdjustIntent.CHANGE);
        assertThat(AdjustIntent.classify("配色再优化一下")).isEqualTo(AdjustIntent.CHANGE);
    }

    @Test
    @DisplayName("无法判断 / 空输入 → 默认改动类（关思考的答错风险高于多花几秒）")
    void defaultsToChange() {
        assertThat(AdjustIntent.classify("")).isEqualTo(AdjustIntent.CHANGE);
        assertThat(AdjustIntent.classify(null)).isEqualTo(AdjustIntent.CHANGE);
        assertThat(AdjustIntent.classify("帮我看看")).isEqualTo(AdjustIntent.CHANGE);
    }
}
