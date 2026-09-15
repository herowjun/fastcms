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

import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.core.auth.FastcmsUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 强制改密防线：must_change_pwd=1 的账号（如手工导入SQL部署的默认账号）首次登录后，
 * 除改密接口外的所有管理接口一律拒绝，防止绕过前端直接调用 API
 */
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

    /**
     * 改密期间放行的接口：修改密码、退出登录
     */
    private static final String PASSWORD_UPDATE_URI = FastcmsConstants.ADMIN_MAPPING + "/user/password/update";

    private static final String LOGOUT_URI = "/logout";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String uri = request.getRequestURI();
        if (uri.startsWith(FastcmsConstants.API_PREFIX_MAPPING) && needChangePassword()) {
            boolean isPasswordUpdate = PASSWORD_UPDATE_URI.equals(uri) && HttpMethod.POST.matches(request.getMethod());
            boolean isLogout = LOGOUT_URI.equals(uri);
            if (!isPasswordUpdate && !isLogout) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"首次登录请先修改密码\",\"data\":{\"mustChangePwd\":true}}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean needChangePassword() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof FastcmsUserDetails userDetails) {
            return userDetails.getUser() != null
                    && userDetails.getUser().getMustChangePwd() != null
                    && userDetails.getUser().getMustChangePwd() == 1;
        }
        return false;
    }

}
