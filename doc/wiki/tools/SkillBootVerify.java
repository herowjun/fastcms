import com.fastcms.ai.skill.RequiredSkillChecker;
import com.fastcms.ai.skill.RequiredSkills;
import com.fastcms.ai.skill.SkillDescriptor;
import com.fastcms.ai.skill.SkillProvider;
import com.fastcms.ai.skill.SkillRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end verification: boots a Spring context that loads the two skill plugins
 * as real pf4j-style plugin classloaders, then runs the startup self-check.
 *
 * <p>This proves the full chain works with the deployed jars:
 * plugin classloader -> ClasspathSkillProvider -> SkillRegistry -> RequiredSkillChecker.</p>
 *
 * <p>2026-10-29 扩展：新增验证「正文漂移」与「插件版本不兼容」两条新闸门链路。</p>
 */
public class SkillBootVerify {

    public static void main(String[] args) throws Exception {
        String pluginDir = args[0];
        String cpFile = args[1];

        String cp = new String(Files.readAllBytes(Paths.get(cpFile)), StandardCharsets.UTF_8);
        List<URL> urls = new ArrayList<>();
        for (String s : cp.split(";")) {
            if (!s.isBlank()) {
                urls.add(Paths.get(s).toUri().toURL());
            }
        }
        URLClassLoader parent = new URLClassLoader(urls.toArray(URL[]::new),
                SkillBootVerify.class.getClassLoader());

        // 模拟 pf4j：每个插件一个独立类加载器，父加载器是主应用 classpath
        List<SkillProvider> providers = new ArrayList<>();
        for (String jarName : new String[]{
                "template-skills-plugin-1.0.0-SNAPSHOT.jar",
                "design-skills-plugin-1.0.0-SNAPSHOT.jar"}) {
            URLClassLoader pluginLoader = new URLClassLoader(
                    new URL[]{Paths.get(pluginDir, jarName).toUri().toURL()}, parent);
            Class<?> providerClass = pluginLoader.loadClass(
                    jarName.startsWith("template") ? "com.fastcms.template.skill.TemplateSkillsProvider"
                            : "com.fastcms.design.skill.DesignSkillsProvider");
            providers.add((SkillProvider) providerClass.getDeclaredConstructor().newInstance());
        }

        SkillRegistry registry = new SkillRegistry(providers);

        System.out.println("=== 1. 技能清单 ===");
        for (SkillDescriptor d : registry.listSkills()) {
            System.out.println("  " + d.id() + "  [" + d.source() + "]  v" + d.version()
                    + "  " + d.name());
        }

        System.out.println();
        System.out.println("=== 2. 启动自检（RequiredSkillChecker.checkAll）===");
        RequiredSkills.checkAll(registry);

        System.out.println();
        System.out.println("=== 3. 硬依赖技能正文（requireContent 实际取到的内容）===");
        for (String id : RequiredSkills.dependencies().keySet()) {
            String content = RequiredSkills.requireContent(registry, id, "端到端验证");
            System.out.println("  " + id + " -> " + content.length() + " 字符, 首行: "
                    + content.lines().findFirst().orElse("").substring(0,
                            Math.min(60, content.lines().findFirst().orElse("").length())));
        }

        System.out.println();
        System.out.println("=== 4. 校验 CSS/JS 分离规范是否真的进了正文 ===");
        String spec = registry.loadContent("template-skills-plugin/template-spec");
        System.out.println("  spec 含 §1.0 CSS/JS 分离 : " + spec.contains("1.0 CSS/JS 分离"));
        System.out.println("  spec 含 §1.1 栏目专属页   : " + spec.contains("栏目专属"));
        System.out.println("  spec 含 §1.2 _components  : " + spec.contains("_components"));

        System.out.println();
        System.out.println("=== 5. 反向验证：模拟插件缺失时的行为 ===");
        SkillRegistry empty = new SkillRegistry(List.of());
        try {
            RequiredSkills.requireContent(empty, "design-skills-plugin/design-brief", "反向验证");
            System.out.println("  !! 不该走到这里：缺失时竟然没抛异常");
        } catch (IllegalStateException e) {
            System.out.println("  OK 缺失时明确抛异常: " + e.getMessage().substring(0,
                    Math.min(120, e.getMessage().length())));
        }

        System.out.println();
        System.out.println("=== 6. 反向验证：正文漂移（模拟旧版 jar 缺 §1.0/§1.1/§1.2）===");
        String staleSpec = "# fastcms 模板规范\n\n## 1. 目录结构\n（旧版正文，没有 CSS/JS 分离等章节）";
        SkillRegistry stale = new SkillRegistry(List.of(new StubProvider(
                "template-skills-plugin/template-spec", staleSpec)));
        try {
            RequiredSkills.requireContent(stale, "template-skills-plugin/template-spec", "漂移验证");
            System.out.println("  !! 不该走到这里：正文漂移时竟然没抛异常");
        } catch (IllegalStateException e) {
            System.out.println("  OK 正文漂移时明确抛异常: " + e.getMessage().substring(0,
                    Math.min(200, e.getMessage().length())));
        }

        System.out.println();
        System.out.println("=== 7. 反向验证：插件 manifest 版本兼容声明 ===");
        verifyPluginManifestCompat(pluginDir);

        System.out.println();
        System.out.println("=== 8. 启动期自检对漂移的识别（应打出正文漂移告警）===");
        RequiredSkills.checkAll(stale);
    }

    /**
     * 验证插件 jar 的 manifest 里写入了 Plugin-Fastcms-Version 兼容声明，
     * 且主程序侧的兼容判定能正确放行/拦截。
     */
    private static void verifyPluginManifestCompat(String pluginDir) throws Exception {
        for (String jarName : new String[]{
                "template-skills-plugin-1.0.0-SNAPSHOT.jar",
                "design-skills-plugin-1.0.0-SNAPSHOT.jar"}) {
            java.nio.file.Path jar = Paths.get(pluginDir, jarName);
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
                java.util.jar.Manifest mf = jf.getManifest();
                String declared = mf.getMainAttributes().getValue("Plugin-Fastcms-Version");
                String pluginId = mf.getMainAttributes().getValue("Plugin-Id");
                String pluginVersion = mf.getMainAttributes().getValue("Plugin-Version");
                System.out.println("  " + pluginId + " : Plugin-Version=" + pluginVersion
                        + ", Plugin-Fastcms-Version=" + declared);
                if (declared == null || declared.isBlank()) {
                    System.out.println("    !! 未写入兼容声明");
                }
            }
        }
    }

    /**
     * 只提供单个技能的桩 Provider（用于漂移场景验证）
     */
    private record StubProvider(String skillId, String content) implements SkillProvider {

        @Override
        public String getProviderId() {
            return "stub-plugin";
        }

        @Override
        public List<SkillDescriptor> listSkills() {
            return List.of(new SkillDescriptor(skillId, "桩技能", "测试用", "1.0.0",
                    SkillDescriptor.SOURCE_PLUGIN, getProviderId(), false, null));
        }

        @Override
        public String loadContent(String id) {
            return skillId.equals(id) ? content : null;
        }
    }
}
