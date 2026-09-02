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
        String home = System.getenv(FASTCMS_HOME_ENV);
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home") + File.separator + "fastcms";
        }
        String pluginDir = home + File.separator + "plugins" + File.separator;
        File dir = new File(pluginDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return pluginDir;
    }

    String getTemplateDir() {
        if (isDev) {
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
            // templates 已从 Maven 模块移除，dev 模式直接使用其源码资源目录
            // （唯一一份模板文件，AI 应用/调整直写于此，mvn clean 不再影响模板）
            String templatePath = substring + sep + "templates" + sep + "src" + sep + "main" + sep + "resources" + sep;
            if (new File(templatePath).exists()) {
                return templatePath;
            }

            String userDir = System.getProperty("user.dir");
            File parent = new File(userDir).getParentFile();
            if (parent != null) {
                String altPath = parent.getAbsolutePath() + sep + "templates" + sep + "src" + sep + "main" + sep + "resources" + sep;
                if (new File(altPath).exists()) {
                    return altPath;
                }
            }
        }
        return workDir.getAbsolutePath() + File.separator + dirNames[0] + File.separator;
    }

    String getLuceneDir() {
        return workDir.getAbsolutePath() + File.separator + dirNames[1] + File.separator;
    }

}
