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
 * 设计稿先行模式的取消信号（客户端断开/用户停止）
 *
 * <p>设计模块不能引用 AiTemplateGenServiceImpl 的私有 ChatCancelledException，
 * 自持同语义异常；宿主在 doChatStream 分流处（§6.1 允许的修改点内）捕获本异常
 * 并转为既有的取消处理路径（落"已中断"消息、保留已落盘设计稿——断点续传语义）。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public class DesignCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DesignCancelledException() {
        super("设计任务已取消（连接断开或已停止），已生成的设计稿已保存");
    }
}
