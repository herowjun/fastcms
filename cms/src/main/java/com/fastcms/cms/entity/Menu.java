package com.fastcms.cms.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fastcms.common.utils.StrUtils;
import com.fastcms.core.template.StaticPathHelper;
import com.fastcms.language.Language;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 网站菜单
 *
 * @author wjun_java@163.com
 * @since 2021-05-27
 */
public class Menu implements Serializable, StaticPathHelper, Language {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_SHOW = "show";
    public static final String STATUS_HIDDEN = "hidden";

    public static final Integer ARTICLE_URL_TYPE = 1;
    public static final Integer PAGE_URL_TYPE = 2;
    public static final Integer CATEGORY_URL_TYPE = 3;
    public static final Integer TAG_URL_TYPE = 4;

    /**
     * id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 上级id
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long parentId;

    /**
     * 创建者
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createUserId;

    /**
     * 菜单名称
     */
    @NotBlank(message = "{fastcms.cms.template.menu.title.is.not.allow.empty}")
    private String menuName;

    /**
     * 菜单地址
     */
    @NotBlank(message = "{fastcms.cms.template.menu.url.is.not.allow.empty}")
    private String menuUrl;

    /**
     * 菜单图标
     */
    private String menuIcon;

    /**
     * 排序
     */
    private Integer sortNum;

    /**
     * 打开方式
     */
    private String target;

    /**
     * 菜单跳转url类型 1，文章，2，页面，3，分类， 4，标签
     */
    private Integer urlType;

    /**
     * 状态
     */
    private String status;

    /**
     * 专属模板ID：NULL=全局菜单（所有模板共用），非空=仅该模板显示
     * （当前模板存在专属菜单时，全局菜单整体隐藏，专属菜单完全替代）
     */
    private String templateId;

    /**
     * 排除显示的模板ID列表，逗号分隔（仅全局菜单生效）
     */
    private String excludeTemplateIds;

    /**
     * 排除显示的站点key列表（域名或路径），逗号分隔（仅全局菜单生效）
     */
    private String excludeSiteKeys;

    /**
     * 语言
     */
    private String language;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime created;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updated;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Long getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(Long createUserId) {
        this.createUserId = createUserId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getMenuUrl() {
        return menuUrl;
    }

    public void setMenuUrl(String menuUrl) {
        this.menuUrl = menuUrl;
    }

    public String getMenuIcon() {
        return menuIcon;
    }

    public void setMenuIcon(String menuIcon) {
        this.menuIcon = menuIcon;
    }

    public Integer getSortNum() {
        return sortNum;
    }

    public void setSortNum(Integer sortNum) {
        this.sortNum = sortNum;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public Integer getUrlType() {
        return urlType;
    }

    public void setUrlType(Integer urlType) {
        this.urlType = urlType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getExcludeTemplateIds() {
        return excludeTemplateIds;
    }

    public void setExcludeTemplateIds(String excludeTemplateIds) {
        this.excludeTemplateIds = excludeTemplateIds;
    }

    public String getExcludeSiteKeys() {
        return excludeSiteKeys;
    }

    public void setExcludeSiteKeys(String excludeSiteKeys) {
        this.excludeSiteKeys = excludeSiteKeys;
    }

    public String getLanguage() {
        return language == null ? getLang() : language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public LocalDateTime getCreated() {
        return created;
    }

    public void setCreated(LocalDateTime created) {
        this.created = created;
    }

    public LocalDateTime getUpdated() {
        return updated;
    }

    public void setUpdated(LocalDateTime updated) {
        this.updated = updated;
    }

    @Override
    public String getUrl() {

        if (StrUtils.isBlank(menuUrl)) {
            return menuUrl;
        }

        if(menuUrl.startsWith("http")) {
            return menuUrl;
        }

        String typePath = "";
        if (getUrlType() == ARTICLE_URL_TYPE) {
            typePath = getArticleStaticPath();
        } else if (getUrlType() == PAGE_URL_TYPE) {
            typePath = getPageStaticPath();
        } else if (getUrlType() == CATEGORY_URL_TYPE) {
            typePath = getCategoryStaticPath();
        } else if (getUrlType() == TAG_URL_TYPE) {
            typePath = getTagStaticPath();
        }

        if (isEnable() && !menuUrl.endsWith(getStaticSuffix())) {
            return getWebSiteDomain().concat(typePath).concat(menuUrl).concat(getStaticSuffix());
        }

        return getWebSiteDomain().concat(typePath).concat(menuUrl);
    }

}
