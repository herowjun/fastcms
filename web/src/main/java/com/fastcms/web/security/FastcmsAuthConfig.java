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
package com.fastcms.web.security;

import com.fastcms.oauth2.authentication.FastcmsSavedRequestAwareAuthenticationSuccessHandler;
import com.fastcms.oauth2.endpoint.FastcmsAuthorizationCodeTokenResponseClient;
import com.fastcms.oauth2.endpoint.FastcmsOAuth2AuthorizationRequestResolver;
import com.fastcms.oauth2.userinfo.FastcmsOAuth2UserService;
import com.fastcms.plugin.PluginPermitAllManager;
import com.fastcms.utils.RequestUtils;
import com.fastcms.web.filter.JwtAuthTokenFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsUtils;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 *  @author： wjun_java@163.com
 *  * @date： 2021/10/24
 *  * @description：
 *  * @modifiedBy：
 *  * @version: 1.0
 */
@Configuration
@EnableWebSecurity
public class FastcmsAuthConfig {

    @Autowired
    private DelegatingTokenManager tokenManager;

    @Autowired
    private AuthConfigs authConfigs;

    @Autowired
    private PluginPermitAllManager pluginPermitAllManager;

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> {
            web.ignoring().requestMatchers(authConfigs.getIgnoreUrls().toArray(new String[] {}));
            web.ignoring().requestMatchers(RequestUtils.getIgnoreUrls().toArray(new String[] {}));
            // 插件@PassFastcms端点动态放行：matcher每次请求实时求值，插件注册/卸载后即时生效
            web.ignoring().requestMatchers(pluginPermitAllManager.asRequestMatcher());
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.formLogin(formLoginConfigurer -> formLoginConfigurer.loginPage("/fastcms.html").loginProcessingUrl("/login").successHandler(fastcmsAuthenticationSuccessHandler()));
        http.authorizeHttpRequests((authorizeRequests) -> authorizeRequests.requestMatchers("/fastcms/**").authenticated().requestMatchers(CorsUtils::isPreFlightRequest).permitAll().anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable).cors(withDefaults())
                .sessionManagement(sessionManagementConfigurer -> sessionManagementConfigurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.oauth2Login(oAuth2LoginConfigurer
                -> oAuth2LoginConfigurer.authorizationEndpoint(
                authorizationEndpointConfig -> authorizationEndpointConfig.authorizationRequestResolver(
                        new FastcmsOAuth2AuthorizationRequestResolver(http.getSharedObject(ApplicationContext.class).getBean(ClientRegistrationRepository.class),
                                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI)
                ))
                .tokenEndpoint(tokenEndpointConfig -> tokenEndpointConfig.accessTokenResponseClient(new FastcmsAuthorizationCodeTokenResponseClient()))
                .userInfoEndpoint(userInfoEndpointConfig -> userInfoEndpointConfig.userService(new FastcmsOAuth2UserService()))
                .successHandler(new FastcmsSavedRequestAwareAuthenticationSuccessHandler())
        );
        http.headers((headersConfigurer) -> headersConfigurer.cacheControl(withDefaults()));
        http.headers((headersConfigurer) -> headersConfigurer.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable));
        http.addFilterBefore(new JwtAuthTokenFilter(tokenManager), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        DelegatingPasswordEncoder delegatingPasswordEncoder = (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
        delegatingPasswordEncoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return delegatingPasswordEncoder;
    }

    @Bean
    public FastcmsAuthenticationSuccessHandler fastcmsAuthenticationSuccessHandler() {
        return new FastcmsAuthenticationSuccessHandler();
    }

}
