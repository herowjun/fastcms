import com.fastcms.plugin.compat.FastcmsPluginDescriptor;
import com.fastcms.plugin.compat.FastcmsPluginDescriptorFinder;
import com.fastcms.plugin.compat.PluginCompatibility;
import org.pf4j.PluginDescriptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * 端到端验证「插件版本不兼容 → 加载阶段被拦截」。
 *
 * <p>模拟 pf4j 的加载路径：manifest → FastcmsPluginDescriptorFinder → PluginCompatibility.check。
 * 分别验证：兼容插件放行、不兼容插件被拒、未声明的第三方插件放行。</p>
 */
public class PluginCompatVerify {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 当前 fastcms 主程序版本（来源 fastcms-version.txt）===");
        String current = PluginCompatibility.currentFastcmsVersion();
        System.out.println("  currentFastcmsVersion() = " + current);
        if (current == null) {
            System.out.println("  !! 版本读不到——兼容校验将退化为「无法判定」，需检查 fastcms-version.txt 是否被资源过滤");
        }

        System.out.println();
        System.out.println("=== 2. 真实插件 jar 的 manifest 解析（走 FastcmsPluginDescriptorFinder.find）===");
        String pluginDir = args[0];
        FastcmsPluginDescriptorFinder finder = new FastcmsPluginDescriptorFinder();
        for (String jarName : new String[]{
                "template-skills-plugin-1.0.0-SNAPSHOT.jar",
                "design-skills-plugin-1.0.0-SNAPSHOT.jar"}) {
            Path jar = Paths.get(pluginDir, jarName);
            // find(Path) 是 pf4j 的公开入口，走完整读取路径（jar/zip/目录三态）
            PluginDescriptor descriptor = finder.find(jar);
            PluginCompatibility.Result result = PluginCompatibility.check(descriptor, current);
            System.out.println("  " + descriptor.getPluginId()
                    + " : 声明=" + PluginCompatibility.requiredVersion(descriptor)
                    + " -> " + (result.compatible() ? "兼容（放行）" : "不兼容（拦截）"));
            if (!result.compatible()) {
                System.out.println("    原因: " + result.reason());
            }
        }

        System.out.println();
        System.out.println("=== 3. 反向验证：插件声明了更高的主程序版本 → 应被拦截 ===");
        PluginDescriptor tooNew = descriptorWith(finder, "9.9.9");
        PluginCompatibility.Result rejected = PluginCompatibility.check(tooNew, current);
        System.out.println("  声明 9.9.9 / 主程序 " + current
                + " -> " + (rejected.compatible() ? "兼容（错误！）" : "不兼容（正确拦截）"));
        System.out.println("  原因: " + rejected.reason());
        if (rejected.compatible()) {
            throw new IllegalStateException("不该走到这里：版本更高的插件竟然被放行");
        }

        System.out.println();
        System.out.println("=== 4. 反向验证：第三方插件未声明版本 → 放行（不做拦截）===");
        PluginDescriptor thirdParty = descriptorWith(finder, null);
        PluginCompatibility.Result ok = PluginCompatibility.check(thirdParty, current);
        System.out.println("  未声明 -> " + (ok.compatible() ? "兼容（放行，正确）" : "被拦截（错误！）"));
        if (!ok.compatible()) {
            throw new IllegalStateException("不该走到这里：未声明版本的第三方插件被拦截");
        }
    }

    /**
     * 构造一个临时插件 jar（带指定兼容声明），走 finder.find() 完整解析
     */
    private static PluginDescriptor descriptorWith(FastcmsPluginDescriptorFinder finder, String fastcmsVersion)
            throws Exception {
        Path jar = Files.createTempFile("probe-plugin-", ".jar");
        jar.toFile().deleteOnExit();
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Plugin-Id", "probe-plugin");
        attributes.putValue("Plugin-Class", "org.pf4j.Plugin");
        attributes.putValue("Plugin-Version", "1.0.0-SNAPSHOT");
        if (fastcmsVersion != null) {
            attributes.putValue(FastcmsPluginDescriptor.FASTCMS_VERSION, fastcmsVersion);
        }
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jar), manifest)) {
            jos.putNextEntry(new java.util.zip.ZipEntry("dummy.txt"));
            jos.write("probe".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return finder.find(jar);
    }
}
