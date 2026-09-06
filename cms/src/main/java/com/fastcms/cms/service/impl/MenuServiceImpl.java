package com.fastcms.cms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.cms.entity.Menu;
import com.fastcms.cms.mapper.MenuMapper;
import com.fastcms.cms.service.IMenuService;
import com.fastcms.core.site.Site;
import com.fastcms.core.site.SiteContextHolder;
import com.fastcms.core.template.Template;
import com.fastcms.core.template.TemplateService;
import com.fastcms.utils.ApplicationUtils;
import com.fastcms.common.utils.StrUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 网站菜单表 服务实现类
 *
 * <p>显示范围模型（menu 表 0.2.0 新增三列）：</p>
 * <ul>
 *     <li>template_id 非空：模板专属菜单，仅所属模板显示；当前模板存在专属菜单时，
 *         全局菜单整体隐藏（专属完全替代，避免出现用户未预期的混合导航）</li>
 *     <li>template_id 为空：全局菜单，所有模板共用，可被 exclude_template_ids /
 *         exclude_site_keys 排除在特定模板/站点之外</li>
 * </ul>
 *
 * @author wjun_java@163.com
 * @since 2021-05-27
 */
@Service
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements IMenuService {

    @Autowired
    private TemplateService templateService;

    @Override
    public List<MenuNode> getMenus() {
        List<Menu> menus = ApplicationUtils.getBean(IMenuService.class).list(Wrappers.<Menu>lambdaQuery().eq(Menu::getStatus, Menu.STATUS_SHOW));
        return buildTree(applyDisplayScope(menus));
    }

    @Override
    public List<MenuNode> getMenuNodeTree() {
        List<Menu> menus = ApplicationUtils.getBean(IMenuService.class).list(Wrappers.<Menu>lambdaQuery().eq(Menu::getStatus, Menu.STATUS_SHOW));
        return buildTree(menus);
    }

    /**
     * 显示范围过滤：内存过滤（菜单数据量小，无需 SQL 级条件）
     *
     * <p>插件站点（SiteContextHolder 非空）的数据经 IMenuServiceAspect 接管后同样过此处，
     * 插件未设置作用域字段的菜单（全 NULL）行为不变。</p>
     */
    List<Menu> applyDisplayScope(List<Menu> menus) {
        if (menus == null || menus.isEmpty()) {
            return menus;
        }
        String currTemplateId = currentTemplateId();
        String siteKey = currentSiteKey();
        // 当前模板存在专属菜单 → 全局菜单整体隐藏（覆盖语义）
        boolean hasExclusive = menus.stream()
                .anyMatch(item -> currTemplateId != null && currTemplateId.equals(item.getTemplateId()));
        return menus.stream().filter(item -> {
            // 专属菜单：仅所属模板显示
            if (StrUtils.isNotBlank(item.getTemplateId())) {
                return item.getTemplateId().equals(currTemplateId);
            }
            if (hasExclusive) {
                return false;
            }
            // 全局菜单：模板排除 + 站点排除
            if (currTemplateId != null && csvContains(item.getExcludeTemplateIds(), currTemplateId)) {
                return false;
            }
            return siteKey == null || !csvContains(item.getExcludeSiteKeys(), siteKey);
        }).collect(Collectors.toList());
    }

    private String currentTemplateId() {
        try {
            Template template = templateService.getCurrTemplate();
            return template == null ? null : template.getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前站点 key：多站点插件生效时取域名（无域名回退路径）；默认单站点无站点上下文，返回 null（站点排除不生效）
     */
    private String currentSiteKey() {
        Site site = SiteContextHolder.getSite();
        if (site == null) {
            return null;
        }
        return StrUtils.isNotBlank(site.getDomain()) ? site.getDomain() : site.getPath();
    }

    /**
     * 逗号分隔列表是否包含 key（忽略空白）
     */
    private boolean csvContains(String csv, String key) {
        if (StrUtils.isBlank(csv) || key == null) {
            return false;
        }
        for (String item : csv.split(",")) {
            if (key.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<MenuNode> buildTree(List<Menu> menus) {
        List<MenuNode> menuNodeList = menus.stream().map(this::getMenuNode).collect(Collectors.toList());
        List<MenuNode> parentMenuList = menuNodeList.stream().filter(item -> item.getParentId() == 0).collect(Collectors.toList());
        parentMenuList.forEach(item -> getChildren(item, menuNodeList));
        return parentMenuList.stream().sorted(Comparator.comparing(MenuNode::getSortNum)).collect(Collectors.toList());
    }

    void getChildren(MenuNode menuNode, List<MenuNode> menuNodeList) {
        List<MenuNode> childrenNodeList = menuNodeList.stream().filter(item -> Objects.equals(item.getParentId(), menuNode.getId())).collect(Collectors.toList());
        if(!childrenNodeList.isEmpty()) {
            menuNode.setChildren(childrenNodeList);
            menuNode.setHasChildren(true);
            childrenNodeList.forEach(item -> getChildren(item, menuNodeList));
        }
    }

    MenuNode getMenuNode(Menu menu) {
        MenuNode menuNode = new MenuNode();
        BeanUtils.copyProperties(menu, menuNode);
        return menuNode;
    }

}
