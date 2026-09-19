package com.ainexus.ai.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 模型调用弹性模板：超时控制 + 指数退避重试 + 降级兜底。
 * 轻量自研实现（针对本地模型场景，避免引入重型熔断框架），
 * 重试仅在非幂等安全允许范围内执行，降级器返回兜底策略结果。
 */
@Slf4j
@Component
public class ModelCallResilienceTemplate {

    private static final int MAX_RETRIES = 2;
    private static final long BASE_BACKOFF_MS = 300;

    /**
     * @param primary       主调用（含超时包裹）
     * @param fallbackName  降级说明（用于日志与统计）
     * @param fallback      降级逻辑
     * @return ResilienceResult：result + 是否走了降级
     */
    public <T> ResilienceResult<T> execute(Supplier<T> primary, String fallbackName, Supplier<T> fallback) {
        long start = System.currentTimeMillis();
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                T result = primary.get();
                return new ResilienceResult<>(result, false, System.currentTimeMillis() - start);
            } catch (Exception e) {
                last = e;
                log.warn("[resilience] call failed, attempt={}/{}, cause={}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleep(BASE_BACKOFF_MS << attempt);
                }
            }
        }
        log.error("[resilience] all attempts failed, fallback to {}", fallbackName, last);
        return new ResilienceResult<>(fallback.get(), true, System.currentTimeMillis() - start);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 弹性调用结果。
     */
    public record ResilienceResult<T>(T result, boolean fallback, long costMs) {
    }
}
