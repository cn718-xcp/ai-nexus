package com.ainexus.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置：单机模式（生产可按需切换 cluster/sentinel）。
 * 支持通过 spring.data.redis.password 注入密码。
 */
@Configuration
@ConditionalOnProperty(prefix = "ainexus.redisson", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password.isEmpty() ? null : password)
                .setConnectionPoolSize(16)
                .setConnectionMinimumIdleSize(4);
        return Redisson.create(config);
    }
}
