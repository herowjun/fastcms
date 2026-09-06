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
package com.fastcms.cms.directive;

import com.fastcms.cms.template.TemplateBaseController;
import com.fastcms.core.directive.BaseFunction;
import com.fastcms.core.template.Template;
import com.fastcms.core.template.TemplateService;
import freemarker.template.TemplateModelException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 获取模板上下文路径
 * ${ctx()}
 *
 * @author： wjun_java@163.com
 * @date： 2022/10/7
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
@Component("ctx")
public class TemplateCtxDirective extends BaseFunction {

	@Resource
	private TemplateService templateService;

	@Override
	public Object exec(List arguments) throws TemplateModelException {
		// 模板浏览态（/{模板pathName}/，见 TemplateBrowseController）返回被浏览模板的路径，
		// 使页面静态资源（css/js）加载该模板自己的资源映射（/{模板path}/**）
		Template template = getBrowseTemplate();
		if (template == null) {
			template = templateService.getCurrTemplate();
		}
		String path = template.getPath();
		return path.substring(0, path.length() - 1);
	}

	private Template getBrowseTemplate() {
		// 非请求线程（如静态化后台渲染）无浏览态，直接返回 null 走当前启用模板
		ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		if (attrs == null) {
			return null;
		}
		HttpServletRequest request = attrs.getRequest();
		Object browseTemplateId = request.getAttribute(TemplateBaseController.BROWSE_TEMPLATE_ID_ATTR);
		if (browseTemplateId == null) {
			return null;
		}
		return templateService.getTemplate(browseTemplateId.toString());
	}

}
