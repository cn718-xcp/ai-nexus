package com.ainexus.ai.modelpool;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.api.OllamaApi;

/**
 * 模型节点：池内一个 Ollama 实例的运行时视图。
 * 健康/连接计数状态由 ModelNodePool 统一维护。
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public class ModelNode {

    private final String name;
    private final String baseUrl;
    private final OllamaApi ollamaApi;

    /** 当前连接数（least_connections 调度依据）：AtomicLong 保证并发读改写不丢失更新 */
    private final java.util.concurrent.atomic.AtomicLong activeConnections = new java.util.concurrent.atomic.AtomicLong();

    /** 是否健康（探测失败摘除，恢复回流） */
    @Setter
    private volatile boolean healthy = true;

    public long getActiveConnections() {
        return activeConnections.get();
    }

    /** 兼容保留：整体重置计数（测试/运维场景） */
    public void setActiveConnections(long value) {
        activeConnections.set(value);
    }

    /** 连接开始：原子自增，返回自增后的计数 */
    public long incrementConnections() {
        return activeConnections.incrementAndGet();
    }

    /** 连接结束：原子递减，下限钳制为 0 */
    public long decrementConnections() {
        return activeConnections.updateAndGet(v -> Math.max(0, v - 1));
    }

    public void markUp() {
        if (!healthy) {
            log.info("[model-pool] node {} ({}) recovered, back to pool", name, baseUrl);
        }
        healthy = true;
    }

    public void markDown() {
        if (healthy) {
            log.warn("[model-pool] node {} ({}) health check failed, removed from pool", name, baseUrl);
        }
        healthy = false;
    }
}
