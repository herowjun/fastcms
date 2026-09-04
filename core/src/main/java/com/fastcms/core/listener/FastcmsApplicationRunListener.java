package com.fastcms.core.listener;

import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.common.utils.DirUtils;
import com.fastcms.common.utils.VersionUtils;
import com.fastcms.core.utils.AttachUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import static org.springframework.boot.context.logging.LoggingApplicationListener.CONFIG_PROPERTY;
import static org.springframework.core.io.ResourceLoader.CLASSPATH_URL_PREFIX;

/**
 * wjun_java@163.com
 */
public class FastcmsApplicationRunListener implements SpringApplicationRunListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(FastcmsApplicationRunListener.class);

    private static final String DEFAULT_FASTCMS_LOGBACK_LOCATION = CLASSPATH_URL_PREFIX + "META-INF/logback/fastcms.xml";

    private final SpringApplication application;

    private final String[] args;

    private static File workDir;

    /**
     * 运行时数据根目录（默认 ~/fastcms），Docker 等场景可通过 FASTCMS_HOME 环境变量覆盖
     */
    static final String FASTCMS_HOME_ENV = "FASTCMS_HOME";

    final static String [] dirNames = { "htmls", "lucene" };

    private Boolean isDev = false;

    static {

        try {
            workDir = new File(ResourceUtils.getURL(ResourceUtils.CLASSPATH_URL_PREFIX).getPath());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        if(!workDir.exists()) {
            workDir = new File(".");
        }

    }

    public FastcmsApplicationRunListener(SpringApplication application, String[] args) {
        this.application = application;
        this.args = args;
    }

    @Override
    public void starting(ConfigurableBootstrapContext bootstrapContext) {

    }

    @Override
    public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
        System.setProperty("application.version", VersionUtils.getFullClientVersion());
        System.setProperty("fastcms.local.ip", AttachUtils.getInternetIp());

        String[] activeProfiles = environment.getActiveProfiles();
        String profile = activeProfiles == null || activeProfiles.length <=0 ? FastcmsConstants.DEV_MODE : activeProfiles[0];

        if(FastcmsConstants.DEV_MODE.equals(profile)) {
            isDev = true;
        }

        if (!environment.containsProperty(CONFIG_PROPERTY)) {
            System.setProperty(CONFIG_PROPERTY, DEFAULT_FASTCMS_LOGBACK_LOCATION);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("There is no property named \"{}\" in Spring Boot Environment, "
                                + "and whose value is {} will be set into System's Properties", CONFIG_PROPERTY,
                        DEFAULT_FASTCMS_LOGBACK_LOCATION);
            }
        }

        for (String dirName : dirNames) {
            File dir = new File(workDir.getAbsolutePath(), dirName);
            if(!dir.exists()) dir.mkdirs();
        }

        DirUtils.injectUploadDir(getUploadDir());
        DirUtils.injectPluginDir(getPluginDir());
        DirUtils.injectTemplateDir(getTemplateDir());
        DirUtils.injectLuceneDir(getLuceneDir());
    }

    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {

    }

    @Override
    public void contextLoaded(ConfigurableApplicationContext context) {

    }

    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {

    }

    /**
     * 附件上传目录固定在用户主目录下的 fastcms 数据目录（~/fastcms/upload），
     * 与 classpath 解耦，mvn clean / IDEA rebuild 不会误删用户上传文件；
     * Docker 等工作目录与数据目录分离的场景可用 FASTCMS_HOME 指定数据根目录
     */
    String getUploadDir() {
        String home = System.getenv(FASTCMS_HOME_ENV);
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home") + File.separator + "fastcms";
        }
        String uploadDir = home + File.separator + "upload" + File.separator;
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return uploadDir;
    }

    /**
     * 插件目录固定在用户主目录下的 fastcms 数据目录（~/fastcms/plugins），
     * 与 classpath 解耦：mvn clean / IDEA rebuild 不会误删已安装插件，
     * dev 与 prod 行为一致（均以 jar 形式安装/加载插件）；
     * Docker 等场景可用 FASTCMS_HOME 覆盖数据根目录
     */
    String getPluginDir() {
        String pluginDir = fastcmsHome() + File.separator + "plugins" + File.separator;
        File dir = new File(pluginDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return pluginDir;
    }

    /**
     * 模板目录固定在用户主目录下的 fastcms 数据目录（~/fastcms/templates），
     * 与 upload/plugins 同惯例：dev 与 prod 行为一致，AI 生成/用户安装的模板
     * 与源码目录解耦，mvn clean / IDEA rebuild / 部署升级均不影响模板数据；
     * Docker 等场景可用 FASTCMS_HOME 覆盖数据根目录。
     *
     * <p>首次启动（目录为空）时从源模板目录 seed 官方模板：
     * dev = 项目源码 templates/src/main/resources（仅作官方示例分发源），
     * prod = 启动目录 ./htmls（build.bat 分发物）。已有模板数据时不做任何事，
     * 升级部署不会覆盖用户数据；需要重新分发官方模板时删除数据目录对应模板重启即可。</p>
     */
    String getTemplateDir() {
        String templateDir = fastcmsHome() + File.separator + "templates" + File.separator;
        File dir = new File(templateDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        seedTemplates(dir);
        return templateDir;
    }

    /**
     * 首次启动 seed 官方模板：目标目录为空时从源目录复制全部模板子目录
     */
    private void seedTemplates(File targetDir) {
        File[] existing = targetDir.listFiles(File::isDirectory);
        if (existing != null && existing.length > 0) {
            return;
        }
        File source = resolveTemplateSeedSource();
        if (source == null || !source.isDirectory()) {
            LOGGER.warn("模板数据目录为空且未找到官方模板源，站点将无可用模板: {}", targetDir.getAbsolutePath());
            return;
        }
        File[] templates = source.listFiles(File::isDirectory);
        if (templates == null || templates.length == 0) {
            return;
        }
        for (File template : templates) {
            try {
                File dest = new File(targetDir, template.getName());
                org.apache.commons.io.FileUtils.copyDirectory(template, dest);
                LOGGER.info("已初始化官方模板: {}", dest.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("官方模板初始化失败: {}", template.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 官方模板 seed 源：dev = 项目源码 templates/src/main/resources，prod = 启动目录 ./htmls
     */
    private File resolveTemplateSeedSource() {
        if (isDev) {
            return resolveSourceTemplateResources();
        }
        return new File(workDir.getAbsolutePath(), dirNames[0]);
    }

    /**
     * dev 模式定位项目源码的 templates/src/main/resources 目录（官方示例模板分发源）
     */
    private File resolveSourceTemplateResources() {
        String osName = System.getProperty("os.name");
        String sep = osName.contains("Windows") ? "\\" : "/";
        String targetClasses = sep + "target" + sep + "classes";
        String targetTestClasses = sep + "target" + sep + "test-classes";

        String substring = workDir.getAbsolutePath()
                .replace(targetClasses, "")
                .replace(targetTestClasses, "");
        int lastSep = substring.lastIndexOf(sep);
        if (lastSep > 0) {
            substring = substring.substring(0, lastSep);
        }
        File source = new File(substring, "templates" + sep + "src" + sep + "main" + sep + "resources");
        if (source.isDirectory()) {
            return source;
        }

        String userDir = System.getProperty("user.dir");
        File parent = new File(userDir).getParentFile();
        if (parent != null) {
            File alt = new File(parent, "templates" + sep + "src" + sep + "main" + sep + "resources");
            if (alt.isDirectory()) {
                return alt;
            }
        }
        return null;
    }

    String getLuceneDir() {
        return workDir.getAbsolutePath() + File.separator + dirNames[1] + File.separator;
    }

    private String fastcmsHome() {
        String home = System.getenv(FASTCMS_HOME_ENV);
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home") + File.separator + "fastcms";
        }
        return home;
    }

}
