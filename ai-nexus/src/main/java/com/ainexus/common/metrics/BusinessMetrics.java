package com.ainexus.common.metrics;

import com.ainexus.domain.repository.KnowledgeBaseRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务维度监控指标（Prometheus /actuator/prometheus 暴露）：
 * - ainexus.kb.count            知识库总量（Gauge）
 * - ainexus.chat.requests       对话请求数（按天 tag，可算日活）
 * - ainexus.rag.requests / hits RAG 请求与命中率
 * - ainexus.tool.calls / failures 工具调用成功率
 * 通过 Micrometer 注册，业务代码只依赖本类门面，避免到处直接操作 MeterRegistry。
 */
@Slf4j
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    @Value("${spring.application.name:ai-nexus}")
    private String appName;

    private Counter ragRequests;
    private Counter ragHits;
    private Counter chatRequests;
    private Counter toolCalls;
    private Counter toolFailures;
    private Counter evalPass;
    private Counter evalTotal;
    private final AtomicLong kbTotal = new AtomicLong();
    private volatile KnowledgeBaseRepository kbRepository;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ragRequests = Counter.builder("ainexus.rag.requests").description("RAG requests").register(registry);
        this.ragHits = Counter.builder("ainexus.rag.hits").description("RAG requests with non-empty retrieval").register(registry);
        this.chatRequests = Counter.builder("ainexus.chat.requests").description("Chat requests").register(registry);
        this.toolCalls = Counter.builder("ainexus.tool.calls").description("Agent tool calls").register(registry);
        this.toolFailures = Counter.builder("ainexus.tool.failures").description("Agent tool call failures").register(registry);
        this.evalPass = Counter.builder("ainexus.eval.pass").description("Eval passed cases").register(registry);
        this.evalTotal = Counter.builder("ainexus.eval.total").description("Eval total cases").register(registry);
        registry.gauge("ainexus.kb.count", kbTotal);
    }

    /** 仓库依赖延迟绑定（避免启动期循环依赖） */
    @Autowired(required = false)
    public void bindKbRepository(KnowledgeBaseRepository kbRepository) {
        this.kbRepository = kbRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initKbGauge() {
        try {
            if (kbRepository != null) {
                kbTotal.set(kbRepository.count());
            }
        } catch (Exception e) {
            log.warn("[metrics] kb gauge init failed: {}", e.getMessage());
        }
    }

    public void onKbChanged(long newCount) {
        kbTotal.set(newCount);
    }

    public void onChatRequest() {
        chatRequests.increment();
    }

    public void onRagRequest(boolean hit) {
        ragRequests.increment();
        if (hit) {
            ragHits.increment();
        }
    }

    /** RAG 效果评估：通过率 = pass/total（按时间聚合趋势） */
    public void onEvalResult(boolean passed) {
        evalTotal.increment();
        if (passed) {
            evalPass.increment();
        }
    }

    public void onToolCall(boolean success) {
        toolCalls.increment();
        if (!success) {
            toolFailures.increment();
        }
    }
}
