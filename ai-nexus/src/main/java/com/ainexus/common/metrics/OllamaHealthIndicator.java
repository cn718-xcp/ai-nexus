package com.ainexus.common.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Ollama 模型服务健康指示器：GET {base-url}/api/tags 探活并回报已加载模型数。
 */
@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    /** 探活超时：健康检查会被编排/网关高频调用，Ollama 卡死时不得让 /actuator/health 无限挂起 */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;

    public OllamaHealthIndicator(@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public Health health() {
        try {
            String body = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(String.class);
            int models = body == null ? 0 : body.split("\"name\"").length - 1;
            return Health.up().withDetail("models", models).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
