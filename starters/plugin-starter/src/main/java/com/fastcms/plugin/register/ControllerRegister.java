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
package com.fastcms.plugin.register;

import com.fastcms.plugin.FastcmsPluginManager;
import com.fastcms.plugin.PassFastcms;
import jakarta.servlet.Filter;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * wjun_java@163.com
 * 注册插件controller
 */
public class ControllerRegister extends AbstractPluginRegister {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerRegister.class);

    RequestMappingHandlerMapping requestMappingHandlerMapping;
    Method getMappingForMethod;

    public ControllerRegister(FastcmsPluginManager pluginManger) {
        super(pluginManger);
        requestMappingHandlerMapping = pluginManger.getApplicationContext().getBean(RequestMappingHandlerMapping.class);
        getMappingForMethod = ReflectionUtils.findMethod(RequestMappingHandlerMapping.class, "getMappingForMethod", Method.class, Class.class);
        getMappingForMethod.setAccessible(true);
    }

    @Override
    public void registry(String pluginId) throws Exception {

        for (Class aClass : getControllerClass(pluginId)) {
            Object bean = pluginManger.getExtensionFactory().create(aClass);
            Method[] methods = aClass.getMethods();
            for (Method method : methods) {
                if (method.getAnnotation(RequestMapping.class) != null
                        || method.getAnnotation(GetMapping.class) != null
                        || method.getAnnotation(PostMapping.class) != null) {

                    RequestMappingInfo requestMappingInfo = (RequestMappingInfo) getMappingForMethod.invoke(requestMappingHandlerMapping, method, aClass);
                    requestMappingHandlerMapping.registerMapping(requestMappingInfo, bean, method);

                    /**
                     * 对一些特殊controller方法进行放行
                     */
                    if (method.getAnnotation(PassFastcms.class) != null) {
                        Set<PathPattern> patterns = requestMappingInfo.getPathPatternsCondition().getPatterns();
                        if (CollectionUtils.isNotEmpty(patterns)) {
                            String url = patterns.toArray()[0].toString();
                            addPermitAllSecurityFilterChain(url);
                        }
                    }

                }
            }
        }

    }

    protected List<Class> getControllerClass(String pluginId) throws Exception {
        return getPluginClasses(pluginId).stream().filter(aClass -> aClass.getAnnotation(Controller.class) != null || aClass.getAnnotation(RestController.class) != null).collect(Collectors.toList());
    }

    /**
     * 添加放行的SecurityFilterChain到FilterChainProxy的最前面
     * spring security 拦截在最前面生效
     */
    private void addPermitAllSecurityFilterChain(String url) {
        try {
            FilterChainProxy filterChainProxy = (FilterChainProxy) beanFactory.getBean("springSecurityFilterChain");
            filterChainProxy = unwrapCompositeFilterChainProxy(filterChainProxy);
            Field field = findField(filterChainProxy.getClass(), "filterChains");
            if (field == null) {
                throw new IllegalStateException("Can not find filterChains field on " + filterChainProxy.getClass());
            }
            field.setAccessible(true);
            List<SecurityFilterChain> filterChains = (List<SecurityFilterChain>) field.get(filterChainProxy);
            LOGGER.info("Add permit-all security filter chain for url: {}, filterChainProxy class: {}, filterChains class: {}, chains: {}",
                    url, filterChainProxy.getClass().getName(), filterChains == null ? null : filterChains.getClass().getName(),
                    filterChains == null ? 0 : filterChains.size());
            List<SecurityFilterChain> newFilterChains = new ArrayList<>();
            newFilterChains.add(new DefaultSecurityFilterChain(PathPatternRequestMatcher.pathPattern(url), new Filter[0]));
            if (filterChains != null) {
                newFilterChains.addAll(filterChains);
            }
            field.set(filterChainProxy, newFilterChains);
        } catch (Exception e) {
            LOGGER.error("Add permit-all security filter chain failed for url: " + url, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Spring Security 7 中 springSecurityFilterChain bean 是 WebSecurityConfiguration.CompositeFilterChainProxy，
     * 真正的过滤器链存放在其内部字段 springSecurityFilterChain 指向的子 FilterChainProxy 中，
     * 这里解包获取真正持有 filterChains 的 FilterChainProxy
     */
    private FilterChainProxy unwrapCompositeFilterChainProxy(FilterChainProxy proxy) {
        try {
            Field field = findField(proxy.getClass(), "springSecurityFilterChain");
            if (field != null) {
                field.setAccessible(true);
                Object child = field.get(proxy);
                if (child instanceof FilterChainProxy) {
                    return (FilterChainProxy) child;
                }
            }
        } catch (IllegalAccessException ignored) {
        }
        return proxy;
    }

    private Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    @Override
    public void unRegistry(String pluginId) throws Exception {

        for (RequestMappingInfo requestMappingInfo : getRequestMappingInfo(pluginId)) {
            requestMappingHandlerMapping.unregisterMapping(requestMappingInfo);
        }

    }

    List<RequestMappingInfo> getRequestMappingInfo(String pluginId) throws Exception {
        List<RequestMappingInfo> requestMappingInfoList = new ArrayList<>();
        for (Class<?> aClass : getPluginClasses(pluginId)) {
            Controller annotation = aClass.getAnnotation(Controller.class);
            RestController restAnnotation = aClass.getAnnotation(RestController.class);
            if (annotation != null || restAnnotation != null) {
                Method[] methods = aClass.getMethods();
                for (Method method : methods) {
                    RequestMappingInfo requestMappingInfo = (RequestMappingInfo) getMappingForMethod.invoke(requestMappingHandlerMapping, method, aClass);
                    requestMappingInfoList.add(requestMappingInfo);
                }
                destroyBean(aClass);
            }
        }
        return requestMappingInfoList;
    }

}
