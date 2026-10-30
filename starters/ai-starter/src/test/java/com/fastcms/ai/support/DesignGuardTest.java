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
package com.fastcms.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 早停守卫测试（B4 早停三件套）
 *
 * @author wjun_java@163.com
 */
class DesignGuardTest {

    @Test
    @DisplayName("思考预算超限 → 抛异常中止")
    void throwsWhenReasoningBudgetExceeded() {
        DesignGuard guard = new DesignGuard(100, 0, 0, 0);

        assertThatThrownBy(() -> {
            for (int i = 0; i < 20; i++) {
                guard.onReasoning("0123456789");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超预算");
    }

    @Test
    @DisplayName("预算内不抛异常，计数正确")
    void countsWithinBudget() {
        DesignGuard guard = new DesignGuard(1000, 0, 0, 0);

        guard.onReasoning("12345");
        guard.onReasoning("67890");

        assertThat(guard.reasoningChars()).isEqualTo(10);
    }

    @Test
    @DisplayName("预算 ≤ 0 表示不限制")
    void unlimitedBudget() {
        DesignGuard guard = new DesignGuard(0, 0, 0, 0);

        guard.onReasoning("x".repeat(100000));

        assertThat(guard.reasoningChars()).isEqualTo(100000);
    }

    @Test
    @DisplayName("尾部片段反复出现 → 判循环并中止")
    void detectsRepeatedLoop() {
        DesignGuard guard = new DesignGuard(0, 0, 10, 3);
        String loop = "ABCDEFGHIJ";

        assertThatThrownBy(() -> {
            for (int i = 0; i < 10; i++) {
                guard.onReasoning(loop);
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("循环");
    }

    @Test
    @DisplayName("正常思考不误判为循环")
    void normalThinkingNotLooping() {
        DesignGuard guard = new DesignGuard(0, 0, 10, 3);

        for (int i = 0; i < 50; i++) {
            guard.onReasoning("思考内容片段" + i + "，");
        }

        assertThat(guard.isLooping()).isFalse();
    }

    @Test
    @DisplayName("循环检测关闭（窗口 ≤0 或阈值 ≤1）→ 不误报")
    void loopDetectionDisabled() {
        DesignGuard guard = new DesignGuard(0, 0, 0, 3);
        for (int i = 0; i < 20; i++) {
            guard.onReasoning("ABCDEFGHIJ");
        }
        assertThat(guard.isLooping()).isFalse();

        DesignGuard guard2 = new DesignGuard(0, 0, 10, 1);
        for (int i = 0; i < 20; i++) {
            guard2.onReasoning("ABCDEFGHIJ");
        }
        assertThat(guard2.isLooping()).isFalse();
    }

    @Test
    @DisplayName("静止超时 → 抛异常")
    void throwsWhenStalled() throws Exception {
        DesignGuard guard = new DesignGuard(0, 50, 0, 0);

        Thread.sleep(80);

        assertThatThrownBy(guard::checkStall)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无响应");
    }

    @Test
    @DisplayName("有活动则重置静止计时，不抛异常")
    void activityResetsStallTimer() throws Exception {
        DesignGuard guard = new DesignGuard(0, 200, 0, 0);

        Thread.sleep(120);
        guard.onActivity();
        Thread.sleep(120);

        // 活动后空闲 120ms < 阈值 200ms → 不该触发
        guard.checkStall();
    }

    @Test
    @DisplayName("静止阈值 ≤ 0 表示不限制")
    void stallDetectionDisabled() {
        DesignGuard guard = new DesignGuard(0, 0, 0, 0);

        guard.checkStall();
    }

    @Test
    @DisplayName("空增量不计数也不触发预算")
    void emptyDeltaIgnored() {
        DesignGuard guard = new DesignGuard(10, 0, 0, 0);

        guard.onReasoning(null);
        guard.onReasoning("");

        assertThat(guard.reasoningChars()).isZero();
    }
}
