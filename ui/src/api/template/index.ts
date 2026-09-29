import request from '/@/utils/request';

export function TemplateApi() {
	return {
		/**
		 * 获取系统已安装模板列表
		 * @returns 
		 */
		getTemplateList() {
			return request({
				url: '/admin/template/list',
				method: 'get'
			});
		},

		/**
		 * 安装模板
		 */
		installTemplate() {
			return request({
				url: '/admin/template/install',
				method: 'post'
			});
		},

		/**
		 * 卸载模板
		 * @param templateId      模板id
		 * @param permanent       true=彻底删除（不生成备份 + 清理数据库残余记录）；false=移动到备份目录
		 * @param overwriteBackup 备份目录已存在同名备份时是否覆盖（仅 permanent=false 时有意义）
		 * @returns 若已存在同名备份且未允许覆盖，返回 code=409，前端据此二次确认
		 */
		unInstallTemplate(templateId: string, permanent = false, overwriteBackup = false) {
			return request({
				url: '/admin/template/unInstall/' + templateId,
				method: 'post',
				params: { permanent, overwriteBackup }
			});
		},

		/**
		 * 启用模板
		 */
		enableTemplate(templateId: string) {
			return request({
				url: '/admin/template/enable/' + templateId,
				method: 'post'
			});
		},

		/**
		 * 文件树形结构
		 * @param templateId  模板id（可选，缺省为当前激活模板）
		 * @returns
		 */
		getTemplateFileTree(templateId?: string) {
			return request({
				url: '/admin/template/files/tree/list' + (templateId ? '?templateId=' + templateId : ''),
				method: 'get'
			});
		},

		/**
		 * 获取文件内容
		 * @param filePath
		 * @param templateId  模板id（可选，缺省为当前激活模板）
		 * @returns
		 */
		getTemplateFile(filePath: string, templateId?: string) {
			return request({
				url: '/admin/template/files/get?filePath=' + filePath + (templateId ? '&templateId=' + templateId : ''),
				method: 'get'
			});
		},

		/**
		 * 保存模板文件
		 * @param params  filePath/fileContent/templateId
		 * @returns
		 */
		saveTemplateFile(params: object) {
			return request({
				url: "/admin/template/file/save",
				method: 'post',
				headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
				data: params
			});
		},

		/**
		 * 删除模板文件
		 * @param filePath
		 * @param templateId  模板id（可选，缺省为当前激活模板）
		 * @returns
		 */
		delTemplateFile(filePath: string, templateId?: string) {
			return request({
				url: "/admin/template/file/delete?filePath=" + filePath + (templateId ? '&templateId=' + templateId : ''),
				method: 'post'
			});
		},

		/**
		 * 恢复 AI 修图前的原图（原图备份 .bak 覆盖回原路径）
		 * @param filePath
		 * @param templateId  模板id（可选，缺省为当前激活模板）
		 * @returns
		 */
		restoreImage(filePath: string, templateId?: string) {
			return request({
				url: '/admin/template/file/restore-image',
				method: 'post',
				params: { filePath, templateId }
			});
		},

		/**
		 * 获取网站模板菜单
		 * @returns 
		 */
		getTemplateMenuList() {
			return request({
				url: "/admin/template/menu/list",
				method: 'get'
			});
		},

		/**
		 * 获取网站模板菜单
		 * @param menuId 
		 * @returns 
		 */
		getTemplateMenu(menuId: number) {
			return request({
				url: "/admin/template/menu/get/" + menuId,
				method: "get"
			})
		},

		/**
		 * 删除网站模板菜单
		 */
		delTemplateMenu(menuId: string) {
			return request({
				url: "/admin/template/menu/delete/" + menuId,
				method: 'post'
			});
		},

		/**
		 * 保存菜单数据
		 */
		saveTemplateMenu(params: object) {
			return request({
				url: "/admin/template/menu/save",
				method: 'post',
				params: params
			});
		},

	};
}