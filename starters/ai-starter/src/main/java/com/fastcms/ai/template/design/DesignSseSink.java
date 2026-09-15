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
package com.fastcms.ai.template.design;

/**
 * 设计稿先行模式的 SSE 通道抽象（设计模块与宿主 Service 的解耦边界）
 *
 * <p>设计模块（design 包）不引用 AiTemplateGenServiceImpl 的私有 SseChannel；
 * 宿主在 doChatStream 分流处用 lambda 适配（§6.1 允许的修改点内）：
 * {@code (event, data) -> sendEvent(channel, event, data)} 与 {@code channel::isCancelled}。
 * 事件名与数据格式与既有管线完全同构（AiTemplateConstants.SSE_EVENT_*）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public interface DesignSseSink {

    /**
     * 推送 SSE 事件（event/data 语义与管线一致；接收方断开时实现方自行静默）
     */
    void send(String eventName, String data);

    /**
     * 客户端是否已断开/停止（断开后设计 loop 不再发起下一轮模型调用）
     */
    boolean isCancelled();
}
