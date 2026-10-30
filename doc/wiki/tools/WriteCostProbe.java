package probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 「改一行 → 回写整个文件」各层开销实测
 *
 * <p>量化 B2 补丁协议落地后，从「模型只回一行」到「正式模板落盘」这条链路上，
 * 到底哪几层在处理整个文件、各自耗时多少。</p>
 */
public final class WriteCostProbe {

    public static void main(String[] args) throws Exception {
        Path sample = Paths.get("F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-site/index.html");
        String original = Files.readString(sample, StandardCharsets.UTF_8);
        System.out.println("样本文件 = " + sample);
        System.out.println("文件大小 = " + original.length() + " 字符（" + original.getBytes(StandardCharsets.UTF_8).length + " 字节）");
        System.out.println();

        // 模拟模型输出：一行补丁（与真实端点返回的结构一致）
        String search = "      <a href=\"plugins.html\">插件市场</a>";
        String replace = "      <a href=\"plugins.html\">精选插件市场</a>";
        int patchChars = search.length() + replace.length();
        System.out.println("① 模型输出（补丁片段）= " + patchChars + " 字符");
        System.out.println("   对比「整文件回写」= " + original.length() + " 字符 → 输出量降 "
                + String.format("%.1f", original.length() / (double) patchChars) + " 倍");
        System.out.println();

        // ② 补丁应用：读整文件 + 内存替换 + 产出整文件
        long t0 = System.nanoTime();
        String patched = original.replace(search, replace);
        long applyNs = System.nanoTime() - t0;
        System.out.printf("② 补丁应用（读全文+内存替换）= %.2f ms%n", applyNs / 1_000_000.0);
        System.out.println("   产出整文件 = " + patched.length() + " 字符（内存里必然是整文件）");
        System.out.println();

        // ③ 落盘：整文件写
        Path tmp = Files.createTempFile("probe-write-", ".html");
        long t1 = System.nanoTime();
        Files.write(tmp, patched.getBytes(StandardCharsets.UTF_8));
        long writeNs = System.nanoTime() - t1;
        System.out.printf("③ 落盘（整文件写盘）= %.2f ms%n", writeNs / 1_000_000.0);

        // ④ 备份：读旧全文（真实 backupBeforeWrite 的行为）
        long t2 = System.nanoTime();
        String oldContent = Files.readString(tmp, StandardCharsets.UTF_8);
        long backupNs = System.nanoTime() - t2;
        System.out.printf("④ 备份（读旧全文存 DB）= %.2f ms%n", backupNs / 1_000_000.0);
        System.out.println();

        double totalMs = (applyNs + writeNs + backupNs) / 1_000_000.0;
        System.out.printf("⑤ 补丁路线后端总开销 = %.2f ms%n", totalMs);

        // 对照：整文件回写路线（模型输出全文）
        System.out.println();
        System.out.println("=== 对照：整文件回写路线 ===");
        System.out.println("① 模型输出 = " + original.length() + " 字符（比补丁路线多 "
                + (original.length() - patchChars) + " 字符）");
        System.out.printf("   按端点 decode 48 tok/s 折算，输出耗时 ≈ %.1f 秒；补丁路线 ≈ %.1f 秒%n",
                original.length() / 4.0 / 48.0, patchChars / 4.0 / 48.0);
        System.out.printf("   → 模型侧节省 ≈ %.1f 秒，端侧开销差异 ≈ %.2f ms%n",
                (original.length() - patchChars) / 4.0 / 48.0,
                totalMs);

        System.out.println();
        System.out.println("=== 结论 ===");
        System.out.println("模型侧（秒级）已解决；后端「整文件」处理（毫秒级）不是瓶颈。");
        Files.deleteIfExists(tmp);
    }
}
