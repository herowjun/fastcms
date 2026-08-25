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

package com.fastcms.oauth2.endpoint;

import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.util.Assert;

/**
 * 重写spring security RestClientAuthorizationCodeTokenResponseClient
 * 支持插件动态添加AbstractOAuth2AuthorizationGrantRequestEntityConverter转换请求参数
 * @author： wjun_java@163.com
 * @date： 2022/01/29
 * @description：
 * @modifiedBy：
 * @version: 1.0
 * @see RestClientAuthorizationCodeTokenResponseClient
 * @see AbstractOAuth2AuthorizationGrantRequestEntityConverter
 */
public final class FastcmsAuthorizationCodeTokenResponseClient
        implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {

    private final RestClientAuthorizationCodeTokenResponseClient delegate = new RestClientAuthorizationCodeTokenResponseClient();

    @Override
    public OAuth2AccessTokenResponse getTokenResponse(
            OAuth2AuthorizationCodeGrantRequest authorizationCodeGrantRequest) {
        Assert.notNull(authorizationCodeGrantRequest, "authorizationCodeGrantRequest cannot be null");

        String registrationId = authorizationCodeGrantRequest.getClientRegistration().getRegistrationId();

        if (OAuth2AuthorizationGrantRequestEntityConverterManager.hasRequestEntityConverter(registrationId)) {
            AbstractOAuth2AuthorizationGrantRequestEntityConverter customConverter =
                    OAuth2AuthorizationGrantRequestEntityConverterManager.getRequestEntityConverter(registrationId);
            delegate.setHeadersConverter(customConverter.getHeadersConverter());
            delegate.setParametersConverter(customConverter.getParametersConverter());
        }

        return delegate.getTokenResponse(authorizationCodeGrantRequest);
    }

}
