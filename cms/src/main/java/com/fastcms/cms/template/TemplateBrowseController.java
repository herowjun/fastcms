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
package com.fastcms.cms.template;

import com.fastcms.core.template.Template;
import com.fastcms.core.template.TemplateBrowseLinkRewriteSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.view.UrlBasedViewResolver;

import java.io.IOException;

/**
 * 模板浏览控制器：/{模板pathName}/（如 /test001/）及其站内子路由
 *
 * <p>模板管理页「浏览」未启用模板时打开本路由：用该模板 + 真实数据库数据渲染页面，
 * 方便启用前评估真实效果。实现方式是把被浏览模板 ID 写入 request attribute
 * （{@link TemplateBaseController#BROWSE_TEMPLATE_ID_ATTR}）后 forward 到对应的正式路由，
 * 复用正式站点的渲染链路（getTemplate、ctx 指令据此优先使用被浏览模板）。</p>
 *
 * <p>覆盖正式站点的全部站内路由（forward 后 query string 自动保留，分页参数可用）：
 * <ul>
 *     <li>/test001/ → / （首页）</li>
 *     <li>/test001/article/{id} → /article/{id} （文章详情，兼容伪静态后缀如 2.html）</li>
 *     <li>/test001/article/category/{id} → /article/category/{id} （分类列表）</li>
 *     <li>/test001/article/tag/{id} → /article/tag/{id} （标签列表）</li>
 *     <li>/test001/page/{path} → /page/{path} （单页，兼容伪静态后缀）</li>
 * </ul>
 * <p>页面 HTML 中的站内链接（菜单、分页、模板硬编码）在视图渲染层统一加上
 * /{模板pathName} 前缀（见 core 模块 TemplateBrowseLinkRewriteSupport），
 * 使点击跳转保持在浏览态，不会"跳回"当前启用模板。</p>
 *
 * <p>只匹配带尾斜杠的首页路径与既有站内路由前缀，不会误伤根级静态文件
 * （favicon.ico、bg-login.png 等无尾斜杠路径）和管理/API 路由（均有各自前缀）。</p>
 *
 * @author wjun_java@163.com
 * @since 2026
 */
@Controller
public class TemplateBrowseController extends TemplateBaseController {

    @GetMapping("/{templatePathName}/")
    public String browse(@PathVariable("templatePathName") String templatePathName,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        return forwardToBrowse(templatePathName, "/", request, response);
    }

    @GetMapping("/{templatePathName}/index")
    public String browseIndex(@PathVariable("templatePathName") String templatePathName,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        return forwardToBrowse(templatePathName, "/index", request, response);
    }

    @GetMapping("/{templatePathName}/article/category/{id}")
    public String browseCategory(@PathVariable("templatePathName") String templatePathName,
                                 @PathVariable("id") String id,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        return forwardToBrowse(templatePathName, "/article/category/" + id, request, response);
    }

    @GetMapping("/{templatePathName}/article/tag/{id}")
    public String browseTag(@PathVariable("templatePathName") String templatePathName,
                            @PathVariable("id") String id,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        return forwardToBrowse(templatePathName, "/article/tag/" + id, request, response);
    }

    @GetMapping("/{templatePathName}/article/{id}")
    public String browseArticle(@PathVariable("templatePathName") String templatePathName,
                                @PathVariable("id") String id,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        return forwardToBrowse(templatePathName, "/article/" + id, request, response);
    }

    @GetMapping("/{templatePathName}/page/{path}")
    public String browsePage(@PathVariable("templatePathName") String templatePathName,
                             @PathVariable("path") String path,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        return forwardToBrowse(templatePathName, "/page/" + path, request, response);
    }

    /**
     * 校验被浏览模板存在后写入浏览态标记，forward 到正式路由复用真实数据渲染链路
     *
     * @param templatePathName 模板路径名（URL 第一段，如 test001）
     * @param target           forward 目标正式路由（如 /article/category/2）
     */
    private String forwardToBrowse(String templatePathName, String target,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {

        String templatePath = "/".concat(templatePathName).concat("/");
        Template template = templateService.getTemplateList().stream()
                .filter(item -> templatePath.equals(item.getPath()))
                .findFirst()
                .orElse(null);

        if (template == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "模板不存在: " + templatePathName);
            return null;
        }

        request.setAttribute(BROWSE_TEMPLATE_ID_ATTR, template.getId());
        // 浏览前缀（如 /test001）供视图渲染层重写站内链接（见 TemplateBrowseLinkRewriteSupport）
        request.setAttribute(TemplateBrowseLinkRewriteSupport.BROWSE_PREFIX_ATTR,
                templatePath.endsWith("/") ? templatePath.substring(0, templatePath.length() - 1) : templatePath);
        return UrlBasedViewResolver.FORWARD_URL_PREFIX.concat(target);
    }

}
