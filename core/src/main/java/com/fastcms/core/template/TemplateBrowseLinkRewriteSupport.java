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
package com.fastcms.core.template;

import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.utils.ConfigUtils;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateModel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板浏览态链接重写支持：浏览未启用模板（/{模板pathName}/ 路由，由 cms 模块
 * TemplateBrowseController forward 进正式路由）时，把渲染出的 HTML 中站内链接
 * 统一加上 /{模板pathName} 前缀，使站内跳转保持在浏览态，不"跳回"当前启用模板。
 *
 * <p>在视图渲染层（{@link FastcmsTemplateView} / FastcmsFreeMarkerView 的 doRender）实施，
 * 而不是包装 response 的 Filter：浏览路由经 Tomcat forward 派发，容器会对自定义
 * response 包装类做写关闭等清理，包装缓存与 forward 派发交互易导致响应体丢失
 * （表现为主页 200 但 Content-Length: 0 空白页）。渲染层方案不改 response 对象本身，
 * 与正常渲染路径完全一致，无此风险。</p>
 *
 * <p>覆盖两类站内链接（均只匹配浏览路由已映射的路径，见 TemplateBrowseController）：
 * <ul>
 *     <li>相对路径：href="/"、href="/index"、href="/article..."、href="/page..."</li>
 *     <li>带域名的绝对 URL（数据库菜单等）：href="http://域名/article..." 等，
 *     域名须为站点配置域名或当前请求域名，避免误伤外链（如备案链接）</li>
 * </ul></p>
 *
 * @author wjun_java@163.com
 * @since 2026
 */
public final class TemplateBrowseLinkRewriteSupport {

	/**
	 * 浏览态 request attribute：值为被浏览模板的路径前缀（如 /test001），
	 * 由 TemplateBrowseController 在 forward 前写入
	 */
	public static final String BROWSE_PREFIX_ATTR = "FASTCMS_BROWSE_TEMPLATE_PREFIX";

	/**
	 * 相对路径 href="/xxx"：捕获属性名、引号、路径（支持单双引号、属性值两侧空白）
	 */
	private static final Pattern HREF_PATTERN = Pattern.compile("(?i)(href\\s*=\\s*)([\"'])(/[^\"'#?]*)([?\"'#])");

	/**
	 * 绝对 URL href="http://域名/xxx"：捕获属性名、引号、origin（协议+域名+端口）、路径
	 */
	private static final Pattern ABS_HREF_PATTERN =
			Pattern.compile("(?i)(href\\s*=\\s*)([\"'])(https?://[^/\"']+)(/[^\"'#?]*)([?\"'#])");

	private TemplateBrowseLinkRewriteSupport() {
	}

	/**
	 * 当前请求是否处于模板浏览态
	 *
	 * @return 浏览前缀（如 /test001），非浏览态返回 null
	 */
	public static String resolveBrowsePrefix(HttpServletRequest request) {
		Object prefix = request == null ? null : request.getAttribute(BROWSE_PREFIX_ATTR);
		return (prefix instanceof String value && !value.isBlank()) ? value : null;
	}

	/**
	 * 浏览态渲染：先渲染到内存缓冲，重写站内链接后一次性写回响应
	 *
	 * @param template     FreeMarker 模板
	 * @param model        模板数据模型
	 * @param request      当前请求（用于解析站点域名）
	 * @param response     响应（写回时才触碰，渲染阶段不改 response 状态）
	 * @param browsePrefix 浏览前缀（如 /test001）
	 */
	public static void processWithRewrite(Template template, TemplateModel model,
										  HttpServletRequest request, HttpServletResponse response,
										  String browsePrefix)
			throws IOException, TemplateException {

		Set<String> siteOrigins = resolveSiteOrigins(request);
		StringWriter buffer = new StringWriter(8 * 1024);
		template.process(model, buffer);
		response.getWriter().write(rewriteBrowseLinks(buffer.toString(), browsePrefix, siteOrigins));
	}

	/**
	 * 解析需要重写的站点域名（origin，不含尾斜杠）：
	 * 当前请求自身的域名 + 系统配置的站点域名（菜单等绝对 URL 的生成来源）
	 */
	private static Set<String> resolveSiteOrigins(HttpServletRequest request) {
		Set<String> origins = new HashSet<>(4);
		StringBuilder self = new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
		int port = request.getServerPort();
		if (port != 80 && port != 443) {
			self.append(":").append(port);
		}
		origins.add(self.toString().toLowerCase(Locale.ROOT));

		try {
			String domain = ConfigUtils.getConfig(FastcmsConstants.WEBSITE_DOMAIN);
			if (StringUtils.isNotBlank(domain)) {
				origins.add(StringUtils.removeEnd(domain.trim(), "/").toLowerCase(Locale.ROOT));
			}
		} catch (Exception ignore) {
			// 非应用上下文等异常场景降级为仅匹配请求自身域名
		}
		return origins;
	}

	/**
	 * 重写 HTML 中的站内链接，加上浏览前缀（如 /test001）：
	 * 相对路径 href="/"、href="/index"、href="/article..."、href="/page..."，
	 * 以及站点域名下的绝对 URL；与 TemplateBrowseController 的浏览路由一一对应。
	 * 其它路径（静态资源 /test001/...、管理后台 /fastcms/...、外部链接）不重写
	 */
	static String rewriteBrowseLinks(String html, String browsePrefix, Set<String> siteOrigins) {
		String rewritten = rewriteAbsolute(html, browsePrefix, siteOrigins);
		return rewriteRelative(rewritten, browsePrefix);
	}

	/**
	 * 绝对 URL：href="http://域名/article/category/1.html" → href="http://域名/test001/article/category/1.html"。
	 * 域名不在 siteOrigins 内（外链）或路径非站内路由时保持原样
	 */
	private static String rewriteAbsolute(String html, String browsePrefix, Set<String> siteOrigins) {
		Matcher matcher = ABS_HREF_PATTERN.matcher(html);
		StringBuilder result = new StringBuilder(html.length() + 256);
		while (matcher.find()) {
			String origin = matcher.group(3);
			String path = matcher.group(4);
			String replacement = matcher.group();
			if (siteOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
				if (isSiteRoute(path)) {
					replacement = matcher.group(1) + matcher.group(2) + origin
							+ browsePrefix + path + matcher.group(5);
				} else if (path.equals("/")) {
					replacement = matcher.group(1) + matcher.group(2) + origin
							+ browsePrefix + "/" + matcher.group(5);
				}
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * 相对路径：href="/" → href="/test001/"，href="/article..." / href="/page..." → 加前缀
	 */
	private static String rewriteRelative(String html, String browsePrefix) {
		Matcher matcher = HREF_PATTERN.matcher(html);
		StringBuilder result = new StringBuilder(html.length() + 256);
		while (matcher.find()) {
			String path = matcher.group(3);
			String replacement;
			if (path.equals("/")) {
				// href="/" → href="/test001/"
				replacement = matcher.group(1) + matcher.group(2) + browsePrefix + "/" + matcher.group(2);
			} else if (isSiteRoute(path)) {
				// href="/article..."、href="/page..."、href="/index" → href="/test001/article..."
				replacement = matcher.group(1) + matcher.group(2) + browsePrefix + path + matcher.group(4);
			} else {
				// 其它路径不重写
				replacement = matcher.group();
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * 路径是否为浏览路由已映射的站内路由（/index、/article、/page 开头，含伪静态后缀）
	 */
	private static boolean isSiteRoute(String path) {
		return path.equals("/index") || path.equals("/article") || path.equals("/page")
				|| path.startsWith("/article/") || path.startsWith("/page/");
	}

}
