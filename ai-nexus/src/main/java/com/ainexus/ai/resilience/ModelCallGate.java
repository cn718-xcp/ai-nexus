package com.ainexus.ai.resilience;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 模型调用并发闸门：基于 Redisson RSemaphore 实现跨实例并发上限，
 * 超出并发数的请求排队等待（tryAcquire 带超时），排队超时快速降级。
 * `max-concurrent <= 0` 时直通（dev 默认关闭，行为与优化前一致）。
 */
@Slf4j
@Component
public class ModelCallGate {

    private final RedissonClient redissonClient;
    private final int maxConcurrent;
    private final int queueTimeoutSeconds;

    public ModelCallGate(RedissonClient redissonClient,
                         @Value("${ainexus.concurrency.model-max:0}") int maxConcurrent,
                         @Value("${ainexus.concurrency.queue-timeout-seconds:15}") int queueTimeoutSeconds) {
        this.redissonClient = redissonClient;
        this.maxConcurrent = maxConcurrent;
        this.queueTimeoutSeconds = queueTimeoutSeconds;
    }

    public <T> T call(Supplier<T> action) {
        if (maxConcurrent <= 0) {
            // 直通模式：未启用并发管控
            return action.get();
        }
        RSemaphore semaphore = redissonClient.getSemaphore("ainexus:concurrency:model");
        // trySetPermits 幂等：仅首次设置生效
        semaphore.trySetPermits(maxConcurrent);
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(queueTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.MODEL_TIMEOUT, "模型调用排队被中断");
        }
        if (!acquired) {
            log.warn("[model-gate] queue timeout {}s, reject", queueTimeoutSeconds);
            throw new BizException(ErrorCode.MODEL_TIMEOUT, "模型调用排队超时，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }
}
