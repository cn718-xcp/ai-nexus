package com.ainexus;

import com.ainexus.ai.resilience.CircuitBreaker;
import com.ainexus.ai.resilience.ModelCallGate;
import com.ainexus.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 高可用组件单元测试：熔断三态状态机、并发闸门排队/超时降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResilienceTest {

    @Test
    void shouldOpenCircuitWhenFailureRateExceeded() {
        // 窗口 4、阈值 60%：首个窗口失败率 50% 低于阈值保持闭合；新窗口统计独立
        CircuitBreaker breaker = new CircuitBreaker("test", 4, 0.6, Duration.ofSeconds(60));
        breaker.onResult(false);
        breaker.onResult(false);
        breaker.onResult(true);
        breaker.onResult(true);
        assertTrue(breaker.tryAcquire(), "失败率 50% 低于 60% 阈值应保持闭合");
        assertEquals("CLOSED", breaker.stateName());

        // 新窗口全失败：达到样本数即熔断并拒绝放行
        breaker.onResult(false);
        breaker.onResult(false);
        breaker.onResult(false);
        breaker.onResult(false);
        assertEquals("OPEN", breaker.stateName(), "失败率 100% 达到阈值应熔断");
        assertFalse(breaker.tryAcquire(), "熔断打开应拒绝放行");
    }

    @Test
    void shouldFailFastWhileOpenAndRecoverAfterHalfOpenProbe() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 2, 1.0, Duration.ofMillis(100));
        breaker.onResult(false);
        breaker.onResult(false);
        assertEquals("OPEN", breaker.stateName(), "全部失败达到阈值应熔断");
        assertFalse(breaker.tryAcquire(), "熔断打开应拒绝放行");

        // 冷却期过后进入半开并放行试探；试探成功恢复闭合
        Thread.sleep(150);
        assertTrue(breaker.tryAcquire(), "冷却期结束应放行试探请求");
        assertEquals("HALF_OPEN", breaker.stateName());
        breaker.onResult(true);
        assertEquals("CLOSED", breaker.stateName(), "试探成功应恢复闭合");
    }

    @Test
    void shouldReturnToOpenWhenHalfOpenProbeFails() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 2, 1.0, Duration.ofMillis(80));
        breaker.onResult(false);
        breaker.onResult(false);
        Thread.sleep(120);
        assertTrue(breaker.tryAcquire());
        breaker.onResult(false);
        assertEquals("OPEN", breaker.stateName(), "半开试探失败应重回熔断");
    }

    @Mock
    private RedissonClient redissonClient;

    @Test
    void shouldPassThroughWhenGateDisabled(@Mock RSemaphore semaphore) throws InterruptedException {
        when(redissonClient.getSemaphore(anyString())).thenReturn(semaphore);
        ModelCallGate gate = new ModelCallGate(redissonClient, 0, 1);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(1, gate.call(calls::incrementAndGet));
        assertEquals(1, calls.get(), "maxConcurrent<=0 时应直通");
    }

    @Test
    void shouldQueueAndReleasePermit(@Mock RSemaphore semaphore) throws InterruptedException {
        when(redissonClient.getSemaphore(anyString())).thenReturn(semaphore);
        when(semaphore.trySetPermits(2)).thenReturn(true);
        when(semaphore.tryAcquire(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        ModelCallGate gate = new ModelCallGate(redissonClient, 2, 5);
        assertEquals("ok", gate.call(() -> "ok"));
        org.mockito.Mockito.verify(semaphore).release();
    }

    @Test
    void shouldRejectWhenQueueTimeout(@Mock RSemaphore semaphore) throws InterruptedException {
        when(redissonClient.getSemaphore(anyString())).thenReturn(semaphore);
        when(semaphore.trySetPermits(1)).thenReturn(true);
        when(semaphore.tryAcquire(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class))).thenReturn(false);
        ModelCallGate gate = new ModelCallGate(redissonClient, 1, 1);
        assertThrows(BizException.class, () -> gate.call(() -> "ok"), "排队超时应快速降级拒绝");
    }
}
