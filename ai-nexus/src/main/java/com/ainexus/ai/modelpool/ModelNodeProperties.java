package com.ainexus.ai.modelpool;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型节点池配置：多 Ollama 实例集群化 + 负载均衡策略。
 * nodes 为空或 enabled=false 时直连主节点（与单节点模式行为一致）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ainexus.model-pool")
public class ModelNodeProperties {

    /** 节点池开关：关闭时所有请求走主 Ollama 节点（向后兼容） */
    private boolean enabled = false;

    /** 负载均衡策略：round_robin-轮询 | least_connections-最少连接 */
    private String strategy = "round_robin";

    /** 节点列表：name 仅用于日志，base-url 为 Ollama 服务地址 */
    private List<Node> nodes = new ArrayList<>();

    /** 健康探测间隔（秒）：定时探测节点可用性，故障摘除/恢复回流 */
    private long healthIntervalSeconds = 15;

    /** 探测超时（毫秒） */
    private long healthTimeoutMs = 2000;

    @Getter
    @Setter
    public static class Node {

        private String name;

        private String baseUrl;
    }
}
