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
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.fastcms.web.install;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安装向导 Web 配置：守卫过滤器注册（最高优先级，先于 Spring Security）与向导页面路由
 */
@Configuration
public class InstallWebConfig implements WebMvcConfigurer {

    /**
     * 安装守卫过滤器：必须位于所有过滤器（含 springSecurityFilterChain）之前，
     * 未安装模式下 Spring Security 依赖的数据库信息尚不可用
     */
    @Bean
    public FilterRegistrationBean<InstallGuardFilter> installGuardFilterRegistration() {
        FilterRegistrationBean<InstallGuardFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InstallGuardFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("installGuardFilter");
        return registration;
    }

    /**
     * /install → 向导静态页 install.html
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/install").setViewName("forward:/install.html");
    }

}
