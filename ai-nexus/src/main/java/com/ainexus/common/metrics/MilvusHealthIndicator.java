package com.ainexus.common.metrics;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Milvus 健康指示器：轻量 RPC 探活，供 /actuator/health 聚合展示向量存储状态。
 * Milvus 客户端不可用（如未启用向量功能）时返回 UNKNOWN 而非 DOWN，避免误报。
 */
@Component("milvus")
public class MilvusHealthIndicator implements HealthIndicator {

    private final ObjectProvider<io.milvus.client.MilvusServiceClient> clientProvider;

    public MilvusHealthIndicator(ObjectProvider<io.milvus.client.MilvusServiceClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public Health health() {
        io.milvus.client.MilvusServiceClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return Health.unknown().withDetail("reason", "milvus client not configured").build();
        }
        try {
            var response = client.getVersion();
            if (response != null && response.getData() != null) {
                return Health.up().withDetail("version", String.valueOf(response.getData())).build();
            }
            return Health.down().withDetail("reason", "empty version response").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
