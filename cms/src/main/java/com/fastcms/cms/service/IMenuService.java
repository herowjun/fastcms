package com.fastcms.cms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastcms.cms.entity.Menu;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.List;

/**
 * 网站菜单服务类
 * @author wjun_java@163.com
 * @since 2021-05-27
 */
public interface IMenuService extends IService<Menu> {

	/**
	 * 前台渲染入口：按当前模板/站点应用显示范围过滤（模板专属覆盖全局、全局排除规则）
	 */
	List<MenuNode> getMenus();

	/**
	 * 管理端菜单树：不做显示范围过滤，后台可见全部菜单（含被排除/其他模板专属的）
	 */
	List<MenuNode> getMenuNodeTree();

	class MenuNode extends Menu implements Serializable {
		@JsonIgnore
		boolean hasChildren = false;
		List<MenuNode> children;

		public boolean getHasChildren() {
			return hasChildren;
		}

		public boolean isHasChildren() {
			return hasChildren;
		}

		public void setHasChildren(boolean hasChildren) {
			this.hasChildren = hasChildren;
		}

		public List<MenuNode> getChildren() {
			return children;
		}

		public void setChildren(List<MenuNode> children) {
			this.children = children;
		}
	}

}
