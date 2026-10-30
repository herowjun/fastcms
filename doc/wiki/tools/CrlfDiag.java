package probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 诊断：模型输出的 search 片段为何在源文件中找不到 */
public final class CrlfDiag {
    public static void main(String[] args) throws Exception {
        Path sample = Paths.get("F:/wangjun/ideaProjects/fastcms/doc/wiki/fastcms-site/index.html");
        String content = Files.readString(sample, StandardCharsets.UTF_8);

        // 模型实际输出的 search（从上一次活体响应中复制，JSON 里 \n 是 LF）
        String search = "      <a href=\"plugins.html\">插件市场</a>\n"
                + "      <a href=\"index.html#pricing\">定价</a>";

        System.out.println("文件是否 CRLF = " + content.contains("\r\n"));
        System.out.println("search 是否含 CR = " + search.contains("\r"));
        System.out.println();
        System.out.println("原样匹配(CRLF 文件) = " + content.contains(search));
        System.out.println("归一化后匹配(LF 文件) = " + content.replace("\r\n", "\n").contains(search));

        // 反向验证：若把 search 也归一化到 CRLF，能否匹配
        System.out.println("search 转 CRLF 后匹配 = " + content.contains(search.replace("\n", "\r\n")));

        // 出现次数检查
        int count = 0;
        int idx = 0;
        String norm = content.replace("\r\n", "\n");
        while ((idx = norm.indexOf(search, idx)) >= 0) { count++; idx++; }
        System.out.println("归一化后出现次数 = " + count);
    }
}
