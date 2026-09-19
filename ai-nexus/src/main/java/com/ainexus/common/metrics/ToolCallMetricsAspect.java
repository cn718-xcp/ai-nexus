package com.ainexus.common.metrics;

import com.ainexus.ai.tools.AiTools;
import com.ainexus.ai.tools.KnowledgeBaseSearchTool;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 工具调用指标切面：对所有 @Tool 注解方法统计调用次数与失败次数，
 * 计算工具调用成功率（Prometheus: ainexus_tool_calls_total / ainexus_tool_failures_total）。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallMetricsAspect {

    private final BusinessMetrics metrics;

    @AfterReturning("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public void onSuccess(JoinPoint pjp) {
        metrics.onToolCall(true);
    }

    @AfterThrowing(pointcut = "@annotation(org.springframework.ai.tool.annotation.Tool)", throwing = "ex")
    public void onFailure(JoinPoint pjp, Throwable ex) {
        metrics.onToolCall(false);
    }
}
