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
package com.fastcms.plugin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件免认证URL注册器
 * <p>
 * 插件Controller方法标注 {@link PassFastcms} 后，由 ControllerRegister 在插件注册时
 * 调用 {@link #permit(String)} 动态加入放行列表；插件卸载时调用 {@link #revoke(String)} 撤销。
 * <p>
 * FastcmsAuthConfig 的 webSecurityCustomizer 通过 {@link #asRequestMatcher()} 把本注册器
 * 挂到 web.ignoring() 上（SecurityFilterChain 每次请求都会实时调用 matcher），
 * 实现免认证放行，不依赖反射修改 Spring Security 内部结构。
 * @author： wjun_java@163.com
 * @date： 2026/8/25
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
public class PluginPermitAllManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginPermitAllManager.class);

    private final Map<String, RequestMatcher> matchers = new ConcurrentHashMap<>();

    /**
     * 放行一个url，重复调用自动去重
     */
    public void permit(String url) {
        matchers.computeIfAbsent(url, u -> {
            LOGGER.info("Permit all plugin url: {}", u);
            return PathPatternRequestMatcher.pathPattern(u);
        });
    }

    /**
     * 撤销一个url的放行
     */
    public void revoke(String url) {
        if (matchers.remove(url) != null) {
            LOGGER.info("Revoke permit all plugin url: {}", url);
        }
    }

    /**
     * 判断请求是否命中放行列表
     */
    public boolean matches(HttpServletRequest request) {
        for (RequestMatcher matcher : matchers.values()) {
            if (matcher.matches(request)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 以 RequestMatcher 视图暴露给 Spring Security 配置使用，
     * matcher 每次请求实时求值，运行期动态生效
     */
    public RequestMatcher asRequestMatcher() {
        return this::matches;
    }

}
