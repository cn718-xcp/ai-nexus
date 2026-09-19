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

    /** 是否健康（探测失败摘除，恢复回流） */
    @Setter
    private volatile boolean healthy = true;

    /** 当前连接数（least_connections 调度依据） */
    @Setter
    private volatile long activeConnections;

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
