import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Simulates the pf4j plugin classloader and resolves the SKILL.md resources
 * exactly the way ClasspathSkillProvider does, to prove which copy the runtime loads.
 */
public class SpecRuntimeProbe {

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String cp = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
        URLClassLoader parent = new URLClassLoader(
                java.util.Arrays.stream(cp.split(";"))
                        .filter(s -> !s.isBlank())
                        .map(s -> {
                            try {
                                return Paths.get(s).toUri().toURL();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }).toArray(URL[]::new),
                ClassLoader.getSystemClassLoader());
        URLClassLoader pluginLoader = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, parent);

        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(pluginLoader);
        Resource[] resources = resolver.getResources("classpath*:skills/*/SKILL.md");
        System.out.println("resolved resources = " + resources.length);
        for (Resource r : resources) {
            String path = r.getURL().getPath();
            String slug = path.substring(path.lastIndexOf("skills/") + 7);
            slug = slug.substring(0, slug.lastIndexOf('/'));
            String body = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("---- " + r.getURL());
            System.out.println("     slug=" + slug + " chars=" + body.length());
            System.out.println("     has Sec1.0 CSSJS  = " + body.contains("1.0 CSS/JS "));
            System.out.println("     has column page   = " + body.contains("栏目专属"));
            System.out.println("     has _components   = " + body.contains("_components"));
        }
    }
}
