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
import com.fastcms.common.exception.I18nFastcmsException;
import com.fastcms.common.model.TreeNode;
import com.fastcms.common.model.TreeNodeConvert;
import com.fastcms.common.utils.DirUtils;
import com.fastcms.common.utils.FastcmsInstallState;
import com.fastcms.common.utils.FileUtils;
import com.fastcms.common.utils.StrUtils;
import com.fastcms.entity.Config;
import com.fastcms.service.IConfigService;
import com.fastcms.utils.ApplicationUtils;
import com.fastcms.utils.CollectionUtils;
import com.fastcms.utils.I18nUtils;
import com.fastcms.utils.ReflectUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static com.fastcms.core.template.TemplateService.TemplateI18n.*;

/**
 * @author： wjun_java@163.com
 * @date： 2021/2/18
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
@Service
public class DefaultTemplateService<T extends TreeNode> implements TemplateService, TreeNodeConvert<T>, InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTemplateService.class);

//    private String templateDir = "./htmls";

    private String i18nDir = "i18n";

    private Map<String, Template> templateMap = Collections.synchronizedMap(new HashMap<>());

    /**
     * 上一轮 refreshStaticMapping 登记过的模板 handler path（形如 {@code /test001/**}）。
     * 卸载后模板已不在 templateMap 里，只按当前列表剪枝会漏掉它的旧条目，
     * 所以在内存里记一份，下一轮先按这份记录剪干净。
     */
    private final Set<String> registeredHandlerPaths = Collections.synchronizedSet(new HashSet<>());

//    @Autowired
//    private Environment environment;

    @Autowired
    private TemplateFinder templateFinder;

    @Autowired
    private IConfigService configService;

    @Override
    public void initialize() throws IOException {
        templateMap.clear();

        List<Path> collect = Files.list(getTemplateRootPath()).collect(Collectors.toList());
        collect.forEach(item -> {
            if(Files.isDirectory(item)) {
                Template template = templateFinder.find(item);
                if(template != null) {
                    templateMap.putIfAbsent(template.getId(), template);
                }
            }
        });

        setDefaultTemplate();
    }

    /**
     * 运行时刷新静态资源目录
     *
     * <p>会把上一轮注册过的模板 path 映射先剪掉再重注册。剪枝靠 {@link #registeredHandlerPaths}
     * 记录（而不是当前模板列表）—— 卸载后的模板已经不在列表里，只按列表剪会永远漏掉它的旧条目，
     * 在 handlerMap 里留下悬空映射。</p>
     */
    @Override
    public void refreshStaticMapping() throws Exception {

        final HandlerMapping resourceHandlerMapping = ApplicationUtils.getBean("resourceHandlerMapping", HandlerMapping.class);
        final Map<String, Object> handlerMap = (Map<String, Object>) ReflectUtil.getFieldValue(resourceHandlerMapping, "handlerMap");
        handlerMap.remove("/**");

        // 先剪枝：移除上一轮登记过的全部模板 path 映射（含已卸载模板的陈旧条目）
        for (String registeredPath : registeredHandlerPaths) {
            handlerMap.remove(registeredPath);
        }
        registeredHandlerPaths.clear();

        final UrlPathHelper mvcUrlPathHelper = ApplicationUtils.getBean("mvcUrlPathHelper", UrlPathHelper.class);
        final ContentNegotiationManager mvcContentNegotiationManager = ApplicationUtils.getBean("mvcContentNegotiationManager", ContentNegotiationManager.class);
        final ServletContext servletContext = ApplicationUtils.getBean(ServletContext.class);

        final ResourceHandlerRegistry resourceHandlerRegistry = new ResourceHandlerRegistry(ApplicationUtils.getApplicationContext(), servletContext, mvcContentNegotiationManager, mvcUrlPathHelper);

        final String uploadDir = DirUtils.getUploadDir();
        final String templateDir = DirUtils.getTemplateDir();
        Set<String> locations = new HashSet<>();
        locations.add(ResourceUtils.CLASSPATH_URL_PREFIX + FastcmsConstants.TEMPLATE_STATIC);
        locations.add(ResourceUtils.FILE_URL_PREFIX + uploadDir);
        resourceHandlerRegistry.addResourceHandler("/**").addResourceLocations(locations.toArray(new String[]{}));
        for (Template template : getTemplateList()) {
            locations = new HashSet<>();
            locations.add(ResourceUtils.FILE_URL_PREFIX + templateDir + template.getPath() + FastcmsConstants.TEMPLATE_STATIC);
            String mappingPath = template.getPath().concat("**");
            resourceHandlerRegistry.addResourceHandler(mappingPath).addResourceLocations(locations.toArray(new String[]{}));
            registeredHandlerPaths.add(mappingPath);
        }

        SimpleUrlHandlerMapping simpleUrlHandlerMapping = (SimpleUrlHandlerMapping) ReflectUtil.invokeMethod(resourceHandlerRegistry, "getHandlerMapping");
        Method registerHandlers = ReflectionUtils.findMethod(SimpleUrlHandlerMapping.class, "registerHandlers", Map.class);
        ReflectUtil.invokeMethod(resourceHandlerMapping, registerHandlers, simpleUrlHandlerMapping.getUrlMap());

    }

    @Override
    public Template getTemplate(String id) {
        return templateMap.get(id);
    }

    @Override
    public Template getCurrTemplate() {
        // 未安装模式下配置缓存为空，往下走会触发 saveConfig 写库（哑数据源不可用），直接返回 null；
        // 调用方 getTemplateList 等已按可空处理，未安装期间也不会有模板渲染请求（InstallGuardFilter 已拦截）
        if (FastcmsInstallState.isInstallMode()) {
            return null;
        }
        Config config = configService.findByKey(FastcmsConstants.TEMPLATE_ENABLE_ID);
        if(config == null) {
            //#I4NI6J https://gitee.com/xjd2020/fastcms/issues/I4NI6J
            List<Template> templateList = new ArrayList<>(templateMap.values());
            Template template = !templateList.isEmpty() ? templateList.get(0) : null;
            if(template == null) return null;
            config = configService.saveConfig(FastcmsConstants.TEMPLATE_ENABLE_ID, template.getId());
        }
        return getTemplate(config.getValue());
    }

    @Override
    public void setDefaultTemplate() {
        // 未安装模式下哑数据源不可用，跳过默认模板写配置，安装完成重启后由初始化逻辑补齐
        if (FastcmsInstallState.isInstallMode()) {
            return;
        }
        List<Template> templateList = getTemplateList();
        if(CollectionUtils.isNotEmpty(templateList)) {
            String config = configService.getValue(FastcmsConstants.TEMPLATE_ENABLE_ID);
            if(StrUtils.isBlank(config) || !containsKey(config)) {
                configService.saveConfig(FastcmsConstants.TEMPLATE_ENABLE_ID, templateList.get(0).getId());
            }
        }
    }

    boolean containsKey(String templateId) {
        return templateMap.containsKey(templateId);
    }

    @Override
    public List<Template> getTemplateList() {
        ArrayList<Template> templates = new ArrayList<>(templateMap.values());
        templates.forEach(item -> item.setActive(getCurrTemplate() != null && item.getId().equals(getCurrTemplate().getId())));
        return templates;
    }

    @Override
    public void install(File file) throws Exception {
        String name = file.getName();
        name = name.substring(0, name.lastIndexOf("."));
        final String path = StrUtils.SLASH.concat(name).concat(StrUtils.SLASH);

        if(checkPath(path)) {
            throw new RuntimeException(String.format(I18nUtils.getMessage(CMS_TEMPLATE_PATH_IS_EXIST), path));
        }

        String templatePath = getTemplateRootPath().toString().concat(path);

        Path tempPath = Paths.get(templatePath);
        try {
            FileUtils.unzip(file.toPath(), DirUtils.getTemplateDir());
        } catch (IOException e) {
            org.apache.commons.io.FileUtils.deleteDirectory(tempPath.toFile());
            throw new RuntimeException(e.getMessage());
        }

        //check properties
        Template template = templateFinder.find(tempPath);
        if (template == null || StringUtils.isBlank(template.getId()) || StringUtils.isBlank(template.getPath())) {
            //上传的zip文件包不符合规范 删除
            org.apache.commons.io.FileUtils.deleteDirectory(tempPath.toFile());
            throw new RuntimeException(String.format(I18nUtils.getMessage(CMS_TEMPLATE_PATH_MISSING_REQUIRED_ATTR), path));
        }

        try {
            initialize();
            refreshStaticMapping();
            //设置i18n
            ApplicationUtils.getBean(ReloadableResourceBundleMessageSource.class).setBasenames(getI18nNames());
        } catch (Exception e) {
            org.apache.commons.io.FileUtils.deleteDirectory(tempPath.toFile());
            templateMap.remove(template.getId());
            throw new RuntimeException(e.getMessage());
        }

    }

    boolean checkPath(String uploadPath) {
        for (Template template : getTemplateList()) {
            if(template.getPath().equals(uploadPath)) {
                return true;
            }
        }
        return false;
    }


    @Override
    public void unInstall(String templateId, boolean permanent, boolean overwriteBackup) throws Exception {
        Template template = getTemplate(templateId);
        if(template == null) {
            throw new I18nFastcmsException(CMS_TEMPLATE_NOT_EXIST);
        }

        Template currTemplate = getCurrTemplate();
        if(currTemplate != null && templateId.equals(currTemplate.getId())) {
            throw new I18nFastcmsException(CMS_TEMPLATE_USING_IS_NOT_ALLOW_UNINSTALL);
        }

        Path templatePath = template.getTemplatePath();
        if (permanent) {
            // 彻底删除：目录直接删掉不备份，并清理该模板的数据库残余记录
            org.apache.commons.io.FileUtils.deleteDirectory(templatePath.toFile());
            cleanTemplateData(templateId);
        } else {
            moveToBackup(template, overwriteBackup);
        }

        // 顺序不能反：必须先 initialize() 把该模板从 templateMap 摘掉，再 refreshStaticMapping()。
        // refreshStaticMapping 是按 templateMap 的内容注册映射的，先刷新会把已卸载模板的映射又注册回去。
        // 收尾再刷一次模板 i18n basenames（卸载后该模板的 i18n 目录已不存在，不能继续挂在 basename 列表里）。
        initialize();
        refreshStaticMapping();
        ApplicationUtils.getBean(ReloadableResourceBundleMessageSource.class).setBasenames(getI18nNames());
    }

    /**
     * 默认卸载模式：把模板目录整体移动到备份目录（跨文件系统时退化为复制后删除）。
     *
     * @param overwriteBackup 备份目录已存在同名备份时是否覆盖；false 时抛
     *                        {@link TemplateBackupExistsException} 交给前端二次确认
     */
    private void moveToBackup(Template template, boolean overwriteBackup) throws Exception {
        Path source = template.getTemplatePath();
        Path dirName = source == null ? null : source.getFileName();
        if (dirName == null) {
            throw new I18nFastcmsException(CMS_TEMPLATE_NOT_EXIST);
        }
        // 备份名取磁盘上的真实目录名（而非 _template.properties 里的 template.path）：
        // 目录是扫描出来的实体，被人工改过 path 属性时目录名才是可定位的真相
        String backupName = dirName.toString();
        Path backup = getTemplateBackupRootPath().resolve(backupName);

        if (Files.exists(backup)) {
            if (!overwriteBackup) {
                throw new TemplateBackupExistsException(CMS_TEMPLATE_BACKUP_EXISTS, backupName);
            }
            org.apache.commons.io.FileUtils.deleteDirectory(backup.toFile());
        }

        Files.createDirectories(backup.getParent());
        try {
            org.apache.commons.io.FileUtils.moveDirectory(source.toFile(), backup.toFile());
        } catch (IOException e) {
            // renameTo 跨文件系统会失败（FASTCMS_HOME 指向别的挂载点/盘的场景），
            // 退化为复制；复制失败时源目录保持原样，不会出现"删了但没备份"的数据丢失
            org.apache.commons.io.FileUtils.copyDirectory(source.toFile(), backup.toFile());
            org.apache.commons.io.FileUtils.deleteDirectory(source.toFile());
        }
    }

    /**
     * 彻底删除时遍历容器里所有 {@link TemplateDataCleaner}，谁的数据谁清。
     * 清理失败不影响模板目录已被删除的事实，只记日志 —— 不能因为一张表的清理异常
     * 就让整个卸载失败（此时文件已经删了，抛异常只会让前端显示失败但实际已删）。
     */
    private void cleanTemplateData(String templateId) {
        Map<String, TemplateDataCleaner> cleaners = ApplicationUtils.getApplicationContext()
                .getBeansOfType(TemplateDataCleaner.class);
        for (TemplateDataCleaner cleaner : cleaners.values()) {
            try {
                int affected = cleaner.cleanTemplateData(templateId);
                LOGGER.info("彻底删除模板[{}]：{} 清理 {} 条数据库记录", templateId,
                        cleaner.getClass().getSimpleName(), affected);
            } catch (Exception e) {
                LOGGER.warn("彻底删除模板[{}]时 {} 清理失败（模板目录已删除，不影响卸载结果）",
                        templateId, cleaner.getClass().getSimpleName(), e);
            }
        }
    }

    Path getTemplateBackupRootPath() {
        String backupDir = DirUtils.getTemplateBackupDir();
        if (StrUtils.isBlank(backupDir)) {
            // 兜底：DirUtils 由 FastcmsApplicationRunListener 注入，未注入时（如脱离 Spring 直接 new）
            // 退化为模板目录的兄弟目录，语义与运行时一致
            Path templateRoot = Paths.get(DirUtils.getTemplateDir());
            Path parent = templateRoot.getParent();
            backupDir = (parent == null ? templateRoot : parent).resolve("template-backup").toString() + File.separator;
        }
        return Paths.get(backupDir);
    }

    @Override
    public List<FileTreeNode> getTemplateTreeFiles() throws IOException {
        return getTemplateTreeFiles(getCurrTemplate());
    }

    @Override
    public List<FileTreeNode> getTemplateTreeFiles(Template template) throws IOException {
        if(template == null) return null;

        // 仅过滤 i18n 目录下的 .properties（国际化文件，无在线编辑需求）；
        // 模板根下的 _template.properties（模板元信息）保持可见、可在线编辑。
        // .bak 为 AI 修图的原图备份（内部产物，通过「恢复原图」入口使用），不在文件树展示
        List<FileTreeNode> treeNodeList = Files.walk(template.getTemplatePath())
                .filter(item -> !(item.toString().endsWith(".properties")
                        && item.getParent() != null
                        && i18nDir.equals(item.getParent().getFileName().toString())))
                .filter(item -> !item.toString().endsWith(".bak"))
                .map(item -> new FileTreeNode(item, template.getTemplatePath()))
                .sorted(Comparator.comparing(FileTreeNode::getSortNum)).collect(Collectors.toList());
        return (List<FileTreeNode>) getTreeNodeList(treeNodeList);
    }

    @Override
    public String[] getI18nNames() {
        List<String> namesList = new ArrayList<>();
        List<Template> templateList = getTemplateList();
        for (Template template : templateList) {
            Path i18nPath = template.getTemplatePath().resolve(i18nDir);
            if (i18nPath.toFile().isDirectory() && i18nPath.toFile().exists()) {
                String i18n = ResourceUtils.FILE_URL_PREFIX.concat(i18nPath.toString().concat(StrUtils.SLASH).concat(template.getI18n()));
                namesList.add(i18n);
            }
        }
        namesList.add(ResourceUtils.CLASSPATH_URL_PREFIX.concat("i18n/message"));
        return namesList.toArray(new String[]{});
    }

    @Override
    public T convert2Node(Object object) {
        FileTreeNode fileTreeNode = (FileTreeNode) object;
        // 优先使用节点自带的模板根路径（支持构建任意模板的文件树），未携带时回退到当前激活模板（兼容旧用法）
        String rootPath = fileTreeNode.getRootPath() != null ? fileTreeNode.getRootPath()
                : getCurrTemplate().getTemplatePath().toString();
        // filePath 以模板目录名开头，与 TemplateController.getFilePath 的前缀截取规则保持一致
        fileTreeNode.setFilePath(getTemplateDirName(rootPath)
                .concat(fileTreeNode.getPath().substring(rootPath.length()).replaceAll("\\\\", "/")));
        return (T) fileTreeNode;
    }

    @Override
    public boolean isParent(T node) {
        FileTreeNode fileTreeNode = (FileTreeNode) node;
        String rootPath = fileTreeNode.getRootPath() != null ? fileTreeNode.getRootPath()
                : getCurrTemplate().getTemplatePath().toString();
        return fileTreeNode.getPath().equals(rootPath);
    }

    /**
     * 取模板根路径的最后一段目录名（与 Template.getPathName 的约定一致）
     */
    private String getTemplateDirName(String rootPath) {
        String normalized = rootPath.replaceAll("[\\\\/]+$", "");
        int idx = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    @Override
    public boolean accept(T node1, T node2) {
        return Objects.equals(((FileTreeNode)node1).getParent(), ((FileTreeNode)node2).getPath());
    }

    Path getTemplateRootPath() {
        return Paths.get(DirUtils.getTemplateDir());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        initialize();
    }

}
