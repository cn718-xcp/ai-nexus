package com.ainexus.ai.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 模型调用弹性模板：排队并发管控 + 超时重试(指数退避) + 熔断 + 降级兜底。
 * - 熔断：按场景维度注册 CircuitBreaker，`ainexus.resilience.circuit-enabled` 开启（dev 默认关闭保持原行为）
 * - 重试与降级：始终生效
 * 熔断打开时跳过主调用直接降级（fail-fast），避免雪崩式无效重试。
 */
@Slf4j
@Component
public class ModelCallResilienceTemplate {

    private static final int MAX_RETRIES = 2;
    private static final long BASE_BACKOFF_MS = 300;

    /** yml 静态默认值（动态配置优先级高于静态默认） */
    private final boolean circuitEnabled;
    private final int circuitWindow;
    private final double circuitFailureRate;
    private final long circuitOpenSeconds;

    private final com.ainexus.common.config.DynamicConfigService dynamicConfig;

    /** scene -> 熔断器注册表（携带参数指纹：参数变更时重建熔断器使新配置即时生效） */
    private final Map<String, BreakerEntry> breakers = new ConcurrentHashMap<>();

    private record BreakerEntry(String paramsKey, CircuitBreaker breaker) {
    }

    public ModelCallResilienceTemplate(
            @Value("${ainexus.resilience.circuit-enabled:false}") boolean circuitEnabled,
            @Value("${ainexus.resilience.circuit-window:10}") int circuitWindow,
            @Value("${ainexus.resilience.circuit-failure-rate:0.6}") double circuitFailureRate,
            @Value("${ainexus.resilience.circuit-open-seconds:30}") long circuitOpenSeconds,
            com.ainexus.common.config.DynamicConfigService dynamicConfig) {
        this.circuitEnabled = circuitEnabled;
        this.circuitWindow = circuitWindow;
        this.circuitFailureRate = circuitFailureRate;
        this.circuitOpenSeconds = circuitOpenSeconds;
        this.dynamicConfig = dynamicConfig;
    }

    /**
     * 带熔断的弹性调用（推荐入口）。
     *
     * @param scene      场景名（CHAT/RAG/AGENT...，熔断器维度）
     * @param primary    主调用
     * @param fallbackName 降级说明（日志/埋点）
     * @param fallback   降级逻辑
     */
    public <T> ResilienceResult<T> execute(String scene, Supplier<T> primary, String fallbackName, Supplier<T> fallback) {
        CircuitBreaker breaker = null;
        // 熔断参数运行时动态读取（配置中心优先，yml 默认兜底），变更即时生效无需重启
        boolean dynamicCircuitEnabled = dynamicConfig.getBool(
                "resilience.circuit-enabled", circuitEnabled, null);
        if (dynamicCircuitEnabled) {
            int window = (int) dynamicConfig.getNumber("resilience.circuit-window", circuitWindow, null);
            double failureRate = dynamicConfig.getNumber("resilience.circuit-failure-rate", circuitFailureRate, null);
            long openSeconds = (long) dynamicConfig.getNumber("resilience.circuit-open-seconds", circuitOpenSeconds, null);
            String paramsKey = window + ":" + failureRate + ":" + openSeconds;
            BreakerEntry entry = breakers.computeIfAbsent(scene,
                    s -> new BreakerEntry(paramsKey, new CircuitBreaker(s, window, failureRate, Duration.ofSeconds(openSeconds))));
            if (!entry.paramsKey().equals(paramsKey)) {
                // 参数变更：重建熔断器（窗口统计随之重置，属配置变更的可接受代价）
                entry = new BreakerEntry(paramsKey, new CircuitBreaker(scene, window, failureRate, Duration.ofSeconds(openSeconds)));
                breakers.put(scene, entry);
            }
            breaker = entry.breaker();
            if (!breaker.tryAcquire()) {
                log.warn("[resilience] {} circuit OPEN, skip primary call", scene);
                return new ResilienceResult<>(fallback.get(), true, 0);
            }
        }
        ResilienceResult<T> result = execute(primary, fallbackName, fallback);
        if (breaker != null) {
            breaker.onResult(!result.fallback());
        }
        return result;
    }

    /**
     * 基础弹性调用：重试（指数退避）+ 降级兜底。
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
