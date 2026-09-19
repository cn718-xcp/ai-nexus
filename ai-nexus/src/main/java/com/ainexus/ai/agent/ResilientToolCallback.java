package com.ainexus.ai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用可靠性增强（装饰器模式）：
 * - 白名单过滤：不在白名单内的工具直接从回调集合剔除（按场景收敛可调用范围）
 * - 参数容错：非法 JSON 入参自动包装为空参数重试一次
 * - 失败重试：指数退避，最大次数可配置
 * - 超时控制：工具执行超时自动中断并返回兜底结果（模型可感知失败原因并调整策略）
 * 装饰后仍实现 ToolCallback，对 Spring AI 工具循环完全透明。
 */
@Slf4j
public class ResilientToolCallback implements ToolCallback {

    /** 工具执行兜底返回 */
    private static final String TIMEOUT_FALLBACK = "工具执行超时，本次结果不可用。请跳过该信息或调整策略继续任务。";

    private final ToolCallback delegate;
    private final int maxRetries;
    private final long timeoutMs;
    /** 单例守护线程池：工具调用超时中断（daemon 不阻塞退出） */
    private static final ExecutorService TIMEOUT_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-timeout");
        t.setDaemon(true);
        return t;
    });

    private ResilientToolCallback(ToolCallback delegate, int maxRetries, long timeoutMs) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 白名单过滤 + 逐个装饰工具回调。
     *
     * @param callbacks    原始回调集合
     * @param whitelist    白名单（空=全部放行）
     * @param maxRetries   最大重试次数
     * @param timeoutMs    单次执行超时
     */
    public static List<ToolCallback> decorate(List<ToolCallback> callbacks, List<String> whitelist,
                                              int maxRetries, long timeoutMs) {
        return callbacks.stream()
                .filter(cb -> whitelist.isEmpty()
                        || whitelist.contains(cb.getToolDefinition().name()))
                .map(cb -> (ToolCallback) new ResilientToolCallback(cb, maxRetries, timeoutMs))
                .toList();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return callWithRetry(toolInput, null);
    }

    @Override
    public String call(String toolInput, org.springframework.ai.chat.model.ToolContext context) {
        return callWithRetry(toolInput, context);
    }

    /** 重试骨架：参数错误自动修正（清空入参重试一次）+ 指数退避 + 超时中断 */
    private String callWithRetry(String toolInput, org.springframework.ai.chat.model.ToolContext context) {
        String name = delegate.getToolDefinition().name();
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return callWithTimeout(toolInput, context);
            } catch (Exception e) {
                last = e;
                log.warn("[tool-guard] {} failed (attempt {}/{}): {}", name, attempt + 1, maxRetries + 1, e.getMessage());
                // 参数解析类失败：用空参数自动重试一次（模型偶发产出非法 JSON）
                boolean badArgs = e.getMessage() != null && e.getMessage().toLowerCase().contains("json");
                if (badArgs && toolInput != null && !toolInput.isBlank() && attempt == 0) {
                    log.info("[tool-guard] {} invalid args, retry with empty args", name);
                    toolInput = "{}";
                    continue;
                }
                if (attempt < maxRetries) {
                    sleep(200L << attempt);
                }
            }
        }
        return String.format("工具 %s 调用失败（已重试 %d 次）：%s", name, maxRetries + 1,
                last == null ? "unknown" : last.getMessage());
    }

    private String callWithTimeout(String toolInput, org.springframework.ai.chat.model.ToolContext context) throws Exception {
        Future<String> future = TIMEOUT_POOL.submit(() -> context == null
                ? delegate.call(toolInput) : delegate.call(toolInput, context));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return TIMEOUT_FALLBACK;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Duration 便捷（供配置读取） */
    public static Duration ofMillis(long ms) {
        return Duration.ofMillis(ms);
    }
}
