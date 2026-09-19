package perf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简全链路压测工具（纯 JDK，无第三方依赖）。
 *
 * 用法：java -cp target/classes;target/test-classes perf.LoadRunner <scenario> <concurrency> <total> [baseUrl]
 * 场景：chat | rag | stream(首token) | agent | rate-limit(限流触发验证)
 *
 * 输出指标：请求数 / 错误率 / QPS / TP50 / TP90 / TP95 / TP99 / 平均 / 最小 / 最大（毫秒）
 */
public class LoadRunner {

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "chat";
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int total = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        String baseUrl = args.length > 3 ? args[3] : "http://localhost:8080";

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        AtomicLong rateLimited = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(total);
        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < total; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    long t0 = System.currentTimeMillis();
                    Result result = execute(client, scenario, baseUrl, seq);
                    long cost = System.currentTimeMillis() - t0;
                    latencies.add(result.latencyMs());
                    if (result.rateLimited()) {
                        rateLimited.incrementAndGet();
                    } else if (result.httpStatus() < 200 || result.httpStatus() >= 300) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        pool.shutdown();

        report(scenario, concurrency, total, latencies, errors.get(), rateLimited.get(), elapsed);
    }

    /** 各场景请求构造与执行；返回 HTTP 状态码 */
    /** 请求结果：HTTP 状态 + 是否被限流（业务码 D0001） */
    record Result(int httpStatus, long latencyMs, boolean rateLimited) {
    }

    private static Result execute(HttpClient client, String scenario, String baseUrl, int seq) throws Exception {
        long t0 = System.currentTimeMillis();
        switch (scenario) {
            case "chat" -> {
                return post(client, baseUrl + "/api/v1/chat",
                        "{\"sessionId\":\"perf-\" + seq % 4 + \"\",\"message\":\"用一句话介绍Spring AI\"}");
            }
            case "rag" -> {
                return post(client, baseUrl + "/api/v1/rag/ask",
                        "{\"question\":\"What is RAG?\",\"knowledgeBaseName\":\"perf-kb\",\"topK\":3}");
            }
            case "agent" -> {
                return post(client, baseUrl + "/api/v1/agent/invoke",
                        "{\"prompt\":\"现在几点？\"}");
            }
            case "rate-limit" -> {
                // 每个请求独立 sessionId，绕开幂等维度，专测 @RateLimit 令牌桶
                return post(client, baseUrl + "/api/v1/chat",
                        "{\"sessionId\":\"perf-rl-" + seq + "\",\"message\":\"hi\"}");
            }
            case "stream" -> {
                // SSE 流式：度量首 token 延迟（第一行 data 到达）
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/rag/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"question\":\"What is RAG?\",\"knowledgeBaseName\":\"perf-kb\",\"topK\":3}"))
                        .build();
                HttpResponse<java.io.InputStream> resp = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:") && line.length() > 5) {
                            break;
                        }
                    }
                }
                return new Result(resp.statusCode(), System.currentTimeMillis() - t0, false);
            }
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    private static Result post(HttpClient client, String url, String json) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        // 统一返回体业务码判定：D0001 限流（HTTP 200 + code=D0001 是本项目限流的返回形态）
        String body = resp.body() == null ? "" : resp.body();
        if (System.getenv("LR_DEBUG") != null) {
            System.out.println("[debug] status=" + resp.statusCode() + " body=" + body.substring(0, Math.min(120, body.length())));
        }
        boolean rateLimited = body.contains("\"D0001\"");
        return new Result(resp.statusCode(), System.currentTimeMillis() - t0, rateLimited);
    }

    private static void report(String scenario, int concurrency, int total, List<Long> latencies,
                               int errors, long rateLimited, long elapsedMs) {
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.println("========== " + scenario + " ==========");
        System.out.println("concurrency=" + concurrency + " requests=" + total
                + " errors=" + errors + " rateLimited=" + rateLimited);
        System.out.printf("throughput=%.2f req/s, elapsed=%dms%n", total * 1000.0 / Math.max(1, elapsedMs), elapsedMs);
        if (sorted.length == 0) {
            return;
        }
        System.out.printf("latency(ms): avg=%d tp50=%d tp90=%d tp95=%d tp99=%d min=%d max=%d%n",
                Arrays.stream(sorted).sum() / sorted.length,
                pct(sorted, 50), pct(sorted, 90), pct(sorted, 95), pct(sorted, 99),
                sorted[0], sorted[sorted.length - 1]);
    }

    private static long pct(long[] sorted, int p) {
        int index = (int) Math.ceil(sorted.length * p / 100.0) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
