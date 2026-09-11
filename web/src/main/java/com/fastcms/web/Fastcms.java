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

package com.fastcms.web;

import com.fastcms.common.utils.FastcmsInstallState;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author： wjun_java@163.com
 * @date： 2021/10/24
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
@SpringBootApplication(exclude = {
        // Spring AI 2.x 的 OpenAI 自动配置默认 matchIfMissing=true 且强制要求 apiKey，
        // fastcms 的模型全部由数据库配置动态构建（AiModelConfigServiceImpl.buildChatModel），
        // 不使用自动装配的 ChatModel/EmbeddingModel 等 bean，故整体排除。
        ChatClientAutoConfiguration.class,
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
})
@ComponentScan("com.fastcms")
@EnableScheduling
public class Fastcms {

    /** 信号状态：NONE=无请求；RESTART=安装完成后同 JVM 重启；SHUTDOWN=进程退出 */
    private enum Signal { NONE, RESTART, SHUTDOWN }

    /**
     * 信号锁：信号的发布、等待、消费复位全部在同一把锁内原子完成。
     * 信号必须在消费时复位为 NONE——否则重启信号残留（CountDownLatch 又是一次性敞开的），
     * 会导致第二轮启动后等待立即返回 RESTART，应用陷入无限重启
     */
    private static final Object SIGNAL_LOCK = new Object();
    private static Signal signal = Signal.NONE;

    /** 当前上下文持有器：供 shutdown hook 关闭；重启路径由 main 自行关闭 */
    private static final AtomicReference<ConfigurableApplicationContext> CURRENT_CONTEXT = new AtomicReference<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (SIGNAL_LOCK) {
                // 进程退出优先级最高，直接覆盖可能尚未消费的重启信号
                signal = Signal.SHUTDOWN;
                SIGNAL_LOCK.notifyAll();
            }
            ConfigurableApplicationContext context = CURRENT_CONTEXT.getAndSet(null);
            if (context != null) {
                try {
                    context.close();
                } catch (Throwable ignored) {
                }
            }
        }, "fastcms-shutdown"));
    }

    public static void main(String[] args) {
        // main 循环化以支持"安装完成后同 JVM 自动重启"：
        // 第一轮 SpringApplication.run 通常为未安装模式（哑数据源）；安装向导成功后 InstallController
        // 调用 requestRestart() 触发旧上下文关闭（Tomcat 随 SmartLifecycle 先停，再销毁单例），
        // 随后重跑 SpringApplication.run，此时安装器写入的 ~/fastcms/config/application.yml 已存在，
        // 第二轮将以真实数据源构建上下文。fat jar 的 JarLauncher 只创建一个类加载器，两轮上下文共享
        // 同一份类；且安装只能发生一次（已安装后向导接口被封死），静态残留风险有上界，兜底为手动重启。
        while (true) {
            // 重启切换窗口内收到进程退出信号：直接退出，不再启动新一轮
            synchronized (SIGNAL_LOCK) {
                if (signal == Signal.SHUTDOWN) {
                    return;
                }
            }
            // 外部安装配置目录（~/fastcms/config/）：安装向导生成的数据源与密钥配置，优先级高于 jar 内配置。
            // 必须在每轮 run 之前以系统属性注入（Spring Boot 在 Environment 准备期即读取该属性），
            // 且不覆盖用户已有的设置；第一轮安装前该文件不存在，安装完成后的下一轮才会读到
            if (FastcmsInstallState.isExternalConfigPresent()
                    && System.getProperty("spring.config.additional-location") == null) {
                System.setProperty("spring.config.additional-location",
                        FastcmsInstallState.getExternalConfigDir().toUri().toString());
            }
            SpringApplication app = new SpringApplication(Fastcms.class);
            // 关闭 Boot 默认 hook（每次 run 都会注册，循环下会累积），由上方自建 hook 统一管理
            app.setRegisterShutdownHook(false);
            ConfigurableApplicationContext context = app.run(args);
            CURRENT_CONTEXT.set(context);
            // 阻塞等待信号；取出即消费并复位，一次重启请求只触发一轮重启
            if (awaitAndConsumeSignal() == Signal.SHUTDOWN) {
                return; // 进程关闭：上下文已由 shutdown hook 关闭
            }
            System.out.println("检测到重启请求（安装完成），正在关闭当前上下文并重建...");
            CURRENT_CONTEXT.set(null);
            try {
                context.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 安装（或配置修复）成功后触发自动重启。幂等：信号只允许 NONE → RESTART 一次。
     * 由 InstallController 在响应提交后延时调用
     */
    public static void requestRestart() {
        synchronized (SIGNAL_LOCK) {
            if (signal == Signal.NONE) {
                signal = Signal.RESTART;
                SIGNAL_LOCK.notifyAll();
            }
        }
    }

    /**
     * 阻塞等待信号；取出即消费并复位为 NONE（发布/等待/复位在同一把锁内原子完成，
     * 信号既不会丢失，也不会残留到下一轮导致无限重启）。返回消费到的信号
     */
    private static Signal awaitAndConsumeSignal() {
        synchronized (SIGNAL_LOCK) {
            while (signal == Signal.NONE) {
                try {
                    SIGNAL_LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Signal.SHUTDOWN;
                }
            }
            Signal consumed = signal;
            signal = Signal.NONE;
            return consumed;
        }
    }

}
