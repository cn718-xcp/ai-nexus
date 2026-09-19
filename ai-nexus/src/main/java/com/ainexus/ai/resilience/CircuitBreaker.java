package com.ainexus.ai.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量熔断器（CLOSED / OPEN / HALF_OPEN 三态）：
 * - CLOSED：统计滚动窗口内失败率，超阈值熔断
 * - OPEN：直接拒绝调用（fail-fast），冷却期结束后进入半开
 * - HALF_OPEN：放行试探请求，成功恢复闭合，失败重回熔断
 * 自研实现规避 Resilience4j 与 Boot 4 的兼容性风险，语义与主流熔断一致。
 * 线程安全：状态与计数均使用原子引用/原子类。
 */
@Slf4j
public class CircuitBreaker {

    private enum State {CLOSED, OPEN, HALF_OPEN}

    private final String name;
    private final int windowSize;
    private final double failureRateThreshold;
    private final java.time.Duration openDuration;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger windowTotal = new AtomicInteger();
    private final AtomicInteger windowFailures = new AtomicInteger();
    private volatile Instant openedAt;

    /** @param name 熔断器名（日志/指标维度） @param windowSize 滚动窗口样本数 @param failureRateThreshold 失败率阈值 0-1 @param openDuration 熔断冷却时长 */
    public CircuitBreaker(String name, int windowSize, double failureRateThreshold, java.time.Duration openDuration) {
        this.name = name;
        this.windowSize = windowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.openDuration = openDuration;
    }

    /** 是否放行本次调用（HALF_OPEN 仅放行试探，由 onResult 决定恢复或保持） */
    public boolean tryAcquire() {
        State current = state.get();
        switch (current) {
            case CLOSED:
                return true;
            case OPEN:
                if (openedAt != null && Instant.now().isAfter(openedAt.plus(openDuration))) {
                    // 冷却期结束：CAS 进入半开，放行试探请求
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        log.info("[circuit-breaker] {} OPEN -> HALF_OPEN, probing...", name);
                        return true;
                    }
                }
                return false;
            case HALF_OPEN:
                return false;
            default:
                return true;
        }
    }

    /** 记录调用结果：true 成功 / false 失败，驱动状态机迁移 */
    public void onResult(boolean success) {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (success) {
                reset();
                state.set(State.CLOSED);
                log.info("[circuit-breaker] {} HALF_OPEN -> CLOSED, recovered", name);
            } else {
                // 试探失败：重新熔断，重置冷却计时
                openedAt = Instant.now();
                state.set(State.OPEN);
                log.warn("[circuit-breaker] {} HALF_OPEN -> OPEN, probe failed", name);
            }
            return;
        }
        // CLOSED：滚动窗口统计失败率
        windowTotal.incrementAndGet();
        if (!success) {
            windowFailures.incrementAndGet();
        }
        if (windowTotal.get() >= windowSize) {
            double rate = windowFailures.get() / (double) windowTotal.get();
            if (rate >= failureRateThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt = Instant.now();
                log.warn("[circuit-breaker] {} CLOSED -> OPEN, failureRate={} >= {}", name, rate, failureRateThreshold);
            }
            reset();
        }
    }

    /** 当前状态名（观测用） */
    public String stateName() {
        return state.get().name();
    }

    private void reset() {
        windowTotal.set(0);
        windowFailures.set(0);
    }
}
