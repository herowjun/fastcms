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
import com.fastcms.common.utils.FastcmsInstallState;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 安装守卫过滤器：注册于所有过滤器之前（含 Spring Security）
 * <ul>
 *     <li>未安装模式：全站拦截，仅放行安装向导相关路径，其余一律跳转 /install</li>
 *     <li>已安装模式：拒绝再次访问向导（302 跳转后台首页 / API 返回 403），杜绝已安装系统被重放安装</li>
 * </ul>
 */
public class InstallGuardFilter implements Filter {

    static final String INSTALL_PAGE_PATH = "/install";

    static final String INSTALL_PAGE_FILE = "/install.html";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String uri = request.getRequestURI();

        if (FastcmsInstallState.isInstallMode()) {
            if (isWizardPath(uri)) {
                chain.doFilter(request, response);
            } else if (uri.startsWith(FastcmsConstants.API_PREFIX_MAPPING) || uri.startsWith(FastcmsConstants.PLUGIN_MAPPING)) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"系统尚未安装，请先完成安装向导\"}");
            } else {
                response.sendRedirect(INSTALL_PAGE_PATH);
            }
            return;
        }

        // 已安装：向导入口全部封死
        if (uri.equals(INSTALL_PAGE_PATH) || uri.equals(INSTALL_PAGE_FILE)) {
            response.sendRedirect("/fastcms.html");
            return;
        }
        if (uri.startsWith(FastcmsConstants.INSTALL_MAPPING)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"系统已安装\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isWizardPath(String uri) {
        return uri.equals(INSTALL_PAGE_PATH)
                || uri.equals(INSTALL_PAGE_FILE)
                || uri.startsWith(FastcmsConstants.INSTALL_MAPPING)
                || uri.startsWith(INSTALL_PAGE_PATH + "/")
                || uri.equals("/favicon.ico");
    }

}
