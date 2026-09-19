package com.ainexus.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用级配置：异步线程池（AI 调用日志落库等旁路任务）。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({com.ainexus.ai.route.ModelRouteProperties.class,
        com.ainexus.common.auth.JwtProperties.class,
        com.ainexus.ai.rag.RagProperties.class,
        com.ainexus.ai.modelpool.ModelNodeProperties.class})
public class AppConfig {

    @Bean("aiCallExecutor")
    public Executor aiCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ai-call-log-");
        executor.initialize();
        return executor;
    }
}
