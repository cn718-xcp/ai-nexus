package com.ainexus.ai.modelpool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 模型节点池：多 Ollama 实例集群化调度。
 * - 负载均衡：round_robin（原子轮询）/ least_connections（当前连接数最少优先）
 * - 健康探测：定时 HTTP 探活（GET /api/tags），故障节点摘除、恢复自动回流
 * - enabled=false 或节点列表为空时为直通模式（单节点行为，向后兼容）
 * - 健康探测函数可注入替换（单测友好）
 */
@Slf4j
@Component
public class ModelNodePool {

    private final ModelNodeProperties properties;

    /** name -> 节点运行时视图 */
    private final Map<String, ModelNode> nodes = new ConcurrentHashMap<>();

    /** 节点插入顺序（ConcurrentHashMap 无序，轮询调度需要稳定顺序） */
    private final List<ModelNode> orderedNodes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 轮询游标 */
    private final AtomicInteger roundRobin = new AtomicInteger();

    /** 健康探测函数：baseUrl -> 是否可达（默认 HTTP /api/tags） */
    private final Function<String, Boolean> healthProbe;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelNodePool(ModelNodeProperties properties) {
        this(properties, baseUrl -> {
            try {
                RestClient.builder().baseUrl(baseUrl).build().get()
                        .uri("/api/tags")
                        .retrieve()
                        .toBodilessEntity();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** 测试构造：注入自定义探测函数（不交由 Spring 管理） */
    public ModelNodePool(ModelNodeProperties properties, Function<String, Boolean> healthProbe) {
        this.properties = properties;
        this.healthProbe = healthProbe;
        if (properties.isEnabled()) {
            // 2.0 中 OllamaApi 仅支持 Builder 构建
            properties.getNodes().forEach(n -> {
                ModelNode node = new ModelNode(n.getName(), n.getBaseUrl(), OllamaApi.builder().baseUrl(n.getBaseUrl()).build());
                nodes.put(n.getName(), node);
                orderedNodes.add(node);
            });
            log.info("[model-pool] initialized with {} nodes, strategy={}", nodes.size(), properties.getStrategy());
        }
    }

    /** 节点池是否可用（开启且有健康节点） */
    public boolean isAvailable() {
        return properties.isEnabled() && healthyNodes().size() > 1;
    }

    /** 按策略选择节点：不可用时返回 null（调用方回退主节点） */
    public ModelNode acquire() {
        List<ModelNode> healthy = healthyNodes();
        if (healthy.isEmpty()) {
            return null;
        }
        if ("least_connections".equalsIgnoreCase(properties.getStrategy())) {
            return healthy.stream()
                    .sorted((a, b) -> Long.compare(a.getActiveConnections(), b.getActiveConnections()))
                    .findFirst()
                    .orElse(healthy.get(0));
        }
        // round_robin：原子轮询，故障节点摘除后自动平滑回流
        int index = Math.floorMod(roundRobin.getAndIncrement(), healthy.size());
        return healthy.get(index);
    }

    /** 连接计数（least_connections 依据；调用结束必须调用 end） */
    public void begin(ModelNode node) {
        node.setActiveConnections(node.getActiveConnections() + 1);
    }

    public void end(ModelNode node) {
        node.setActiveConnections(Math.max(0, node.getActiveConnections() - 1));
    }

    /** 获取全部节点（按插入顺序，供路由层构建 ChatModel 与测试断言） */
    public List<ModelNode> allNodes() {
        return List.copyOf(orderedNodes);
    }

    /** 定时健康探测：故障摘除、恢复回流（探测间隔可配置） */
    @Scheduled(fixedDelayString = "${ainexus.model-pool.health-interval-seconds:15}000",
            initialDelayString = "${ainexus.model-pool.health-interval-seconds:15}000")
    public void healthCheck() {
        if (!properties.isEnabled() || nodes.isEmpty()) {
            return;
        }
        orderedNodes.forEach(node -> {
            boolean reachable;
            try {
                reachable = healthProbe.apply(node.getBaseUrl());
            } catch (Exception e) {
                reachable = false;
            }
            if (reachable) {
                node.markUp();
            } else {
                node.markDown();
            }
        });
    }

    private List<ModelNode> healthyNodes() {
        return orderedNodes.stream().filter(ModelNode::isHealthy).toList();
    }
}
