package com.ainexus.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用级配置：异步线程池（AI 调用日志落库等旁路任务）。
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties({com.ainexus.ai.route.ModelRouteProperties.class,
        com.ainexus.common.auth.JwtProperties.class,
        com.ainexus.ai.rag.RagProperties.class,
        com.ainexus.ai.modelpool.ModelNodeProperties.class,
        com.ainexus.ai.agent.AgentProperties.class})
public class AppConfig {

    /** 模型注册表：统一 ollama/openai 协议适配（模型键缓存） */
    @Bean
    public com.ainexus.ai.model.ModelRegistry modelRegistry(
            org.springframework.ai.ollama.api.OllamaApi ollamaApi,
            @Value("${ainexus.models.openai.base-url:https://api.openai.com}") String openaiBaseUrl,
            @Value("${ainexus.models.openai.api-key:}") String openaiApiKey) {
        return new com.ainexus.ai.model.ModelRegistry(ollamaApi, openaiBaseUrl, openaiApiKey);
    }

    @Bean
    public com.ainexus.ai.model.ModelInvoker modelInvoker(com.ainexus.ai.model.ModelRegistry registry) {
        return new com.ainexus.ai.model.ModelInvoker(registry);
    }

    @Bean("aiCallExecutor")
    public Executor aiCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ai-call-log-");
        // 统计/审计属可丢失旁路：队列满时丢弃而非把拒绝异常抛回业务主链路。
        // 但必须留下告警——DiscardPolicy 是静默丢弃，审计行缺失将无从察觉。
        executor.setRejectedExecutionHandler((r, e) -> log.warn(
                "[async] aiCallExecutor saturated, task discarded: active={}, queued={}",
                e.getActiveCount(), e.getQueue().size()));
        // 停机前排空队列：否则已入队的审计/埋点事件随进程退出直接丢失
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
