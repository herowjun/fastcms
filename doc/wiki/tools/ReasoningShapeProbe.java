package probe;

import com.fastcms.ai.support.ReasoningStreamAccumulator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断探针：流式下 Spring AI 透传的 reasoningContent 到底是什么形态，
 * 以及 {@link com.fastcms.ai.support.ReasoningStreamAccumulator} 的差分是否正确
 * （直接累加原始快照会平方级膨胀，可能误触生产的 256KB 思考保险丝）。
 *
 * <p>用法：{@code probe.ReasoningShapeProbe [maxTokens]}，默认 512。</p>
 *
 * <p>2026-10-29 实测：513 个 chunk，原始累计 529250 字符（平方级假象），
 * 差分后真实思考量 2126 字符 → <b>保险丝不会误杀</b>（距 256KB 阈值差 123 倍）。</p>
 */
public final class ReasoningShapeProbe {
    static final String HARD_TASK = """
            请分析下面这个 Java 工具类中的所有并发缺陷，逐个说明根因、给出修复方案，
            并评估修复后对吞吐量与延迟的影响，最后按 JSON 输出（格式：
            {"defects":[{"location":"...","rootCause":"...","fix":"...","impact":"..."}],"summary":"..."}）。
            要求至少找出 4 个缺陷。

            ```java
            public class Pool {
                private final List<Worker> workers = new ArrayList<>();
                private boolean running = true;
                public void add(Worker w) { workers.add(w); }
                public void tick() {
                    for (int i = 0; i < workers.size(); i++) {
                        Worker w = workers.get(i);
                        if (w.isBusy()) continue;
                        w.run();
                        if (!running) break;
                    }
                }
                public void shutdown() {
                    running = false;
                    for (Worker w : workers) { w.interrupt(); }
                    workers.clear();
                }
                public int size() { return workers.size(); }
            }
            ```
            """;

    public static void main(String[] args) {
        int maxTokens = args.length > 0 ? Integer.parseInt(args[0]) : 512;
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl("http://203.176.93.143:30120/v1")
                .model("Qwen3.6-27B")
                .apiKey("probe-not-used")
                .temperature(0.3)
                .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofMinutes(10))
                .reasoningEffort("low")
                .build();
        ChatClient client = ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();

        ReasoningStreamAccumulator acc = new ReasoningStreamAccumulator();
        List<String> rawLens = new ArrayList<>();
        List<String> deltaLens = new ArrayList<>();
        long[] total = {0L};
        long[] rawTotal = {0L};
        int[] chunks = {0};
        List<Integer> seq = new ArrayList<>();
        int[] rollbacks = {0};
        int[] prevLen = {-1};

        client.prompt()
                .user(HARD_TASK)
                .stream()
                .chatResponse()
                .doOnNext(resp -> {
                    if (resp.getResult() == null || resp.getResult().getOutput() == null) return;
                    var out = resp.getResult().getOutput();
                    Object rc = out.getMetadata() == null ? null : out.getMetadata().get("reasoningContent");
                    if (rc == null) return;
                    chunks[0]++;
                    String raw = String.valueOf(rc);
                    rawTotal[0] += raw.length();
                    String delta = acc.feed(raw);
                    int dl = delta == null ? 0 : delta.length();
                    total[0] += dl;
                    if (rawLens.size() < 12) {
                        rawLens.add(raw.length() + "→" + dl);
                    if (prevLen[0] >= 0 && raw.length() < prevLen[0]) rollbacks[0]++;
                    prevLen[0] = raw.length();
                    seq.add(raw.length());
                    }
                })
                .blockLast();

        System.out.println("chunk 数（带 reasoning 的） = " + chunks[0]);
        System.out.println("原始 reasoningContent 累计长度 = " + rawTotal[0]);
        System.out.println("累加器 delta 累计长度        = " + total[0]);
        System.out.println("保险丝阈值 256KB = 262144 → 累加器是否越线 = " + (total[0] > 262144));
        System.out.println("前 12 帧（原始长度→增量长度）:");
        rawLens.forEach(s -> System.out.println("   " + s));
        System.out.println("长度序列（原始）:");
        for (int i = 0; i < seq.size(); i++) {
            System.out.print(seq.get(i) + " ");
            if ((i + 1) % 40 == 0) System.out.println();
        }
        System.out.println();
        System.out.println("回退次数（原始长度比上一帧小）= " + rollbacks);
    }
}
