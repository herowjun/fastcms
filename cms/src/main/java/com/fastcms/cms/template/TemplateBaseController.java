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
import com.fastcms.core.template.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @author： wjun_java@163.com
 * @date： 2021/5/27
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
public abstract class TemplateBaseController {

	/**
	 * 模板浏览态的 request attribute key：模板管理页「浏览」未启用模板时
	 * （/{模板pathName}/ 路由，见 {@link TemplateBrowseController}）写入被浏览模板 ID，
	 * 渲染链路（getTemplate、ctx 指令）据此优先使用被浏览模板而非当前启用模板，
	 * 实现"未启用模板 + 真实数据"渲染
	 */
	public static final String BROWSE_TEMPLATE_ID_ATTR = "FASTCMS_BROWSE_TEMPLATE_ID";

	@Autowired
	protected TemplateService templateService;

	protected Template getTemplate() {
		Template browseTemplate = getBrowseTemplate();
		return browseTemplate != null ? browseTemplate : templateService.getCurrTemplate();
	}

	/**
	 * 当前是否处于模板浏览态（/{模板pathName}/ 路由 forward 进来）
	 */
	protected boolean isBrowseMode() {
		return getBrowseTemplate() != null;
	}

	/**
	 * 取被浏览的模板（浏览态），非浏览态或非请求线程（如静态化后台渲染）返回 null
	 */
	private Template getBrowseTemplate() {
		ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		if (attrs == null) {
			return null;
		}
		HttpServletRequest request = attrs.getRequest();
		Object browseTemplateId = request.getAttribute(BROWSE_TEMPLATE_ID_ATTR);
		if (browseTemplateId == null) {
			return null;
		}
		return templateService.getTemplate(browseTemplateId.toString());
	}

	protected String getTemplatePath() {
		return getTemplatePath(null);
	}

	protected String getTemplatePath(String id) {
		Template template = templateService.getTemplate(id);
		if (template == null) {
			template = getTemplate();
		}
		return template == null ? "/cms/" : template.getPath();
	}

}
