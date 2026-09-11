/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.ai.capability;

import java.util.Map;

/**
 * 插件能力中的单个 HTTP 端点契约
 *
 * <p>path 必须写<strong>前端可见的真实路径</strong>（含 context-path，如
 * {@code /fastcms/api/client/order/save}），禁止从 Java {@code @RequestMapping} 推导——
 * 两者经常不一致（如插件 Controller 的类级映射叠加主应用 context-path）。
 * 路径中的动态段用 {@code {name}} 表示（如 {@code /order/status/check/{orderId}}），
 * 渠道型参数用 {@code {platform}} 占位（由具体支付渠道能力替换为实际值，如 wxPay）。</p>
 *
 * @param id          端点标识（能力内唯一，如 createOrder / queryStatus）
 * @param method      HTTP 方法（GET/POST）
 * @param path        前端可见真实路径（含 context-path）
 * @param contentType 请求体类型（POST 时给 AI 的编码提示）
 * @param params      参数说明（key=参数名，value=中文说明；含取值来源提示）
 * @param response    响应结构说明（key=字段路径，value=中文说明；禁止模糊描述）
 * @param auth        认证要求：required=需登录 / optional / none
 * @param usageHint   前端调用提示（如"文件流用 <a href> 触发，不要 fetch"）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record CapabilityEndpoint(
        String id,
        String method,
        String path,
        String contentType,
        Map<String, String> params,
        Map<String, String> response,
        String auth,
        String usageHint) {

    public boolean requiresAuth() {
        return !"none".equalsIgnoreCase(auth);
    }

    /**
     * 该端点的路径模板是否包含动态段（{xxx}），校验白名单时按前缀匹配
     */
    public boolean hasPathVariables() {
        return path != null && path.contains("{");
    }

    /**
     * 路径前缀（第一个动态段之前的部分），用于渲染期路径校验白名单匹配
     */
    public String pathPrefix() {
        if (path == null) {
            return "";
        }
        int idx = path.indexOf('{');
        return idx > 0 ? path.substring(0, idx) : path;
    }
}
