package com.ainexus.ai.agent;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import com.ainexus.ai.stats.AiCallEvent;
import com.ainexus.ai.stats.AiCallEventPublisher;
import com.ainexus.ai.tools.AiTools;
import com.ainexus.ai.tools.KnowledgeBaseSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 两阶段协作 Agent：规划 Agent（任务拆解为步骤序列）+ 执行 Agent（按步执行工具/整合回答）。
 * 可控性保障：
 * - 最大步数限制 + 循环调用检测（连续重复步骤自动终止）
 * - 工具调用统一经 ResilientToolCallback（重试/超时/白名单）
 * - 思维链（Thought/Action/Observation）记录，可配置返回前端
 * - 工具全部失败/规划失败自动降级为纯问答
 * ainexus.agent.two-phase-enabled=false（默认）时走单阶段 AgentService。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoPhaseAgentService {

    private static final String PLANNER_PROMPT =
            "你是任务规划 Agent。将用户任务拆解为有序步骤，输出 JSON 数组，每个元素含字段：\n"
            + "thought(该步骤意图)、action(工具名或 answer)、args(工具入参或回答要求)。\n"
            + "可用工具：getWeather(城市)、getCurrentTime()、searchKnowledgeBase(query, knowledgeBaseName)。\n"
            + "最后一步必须是 {\"action\":\"answer\"}。只输出 JSON 数组，不要输出其他内容。";

    private static final String SYNTHESIZE_PROMPT =
            "你是执行 Agent。基于以下已收集的观察结果回答用户任务；观察不足时明确说明，不要编造。\n\n观察结果：\n";

    private final ModelRouteContext routeContext;
    private final AiTools aiTools;
    private final KnowledgeBaseSearchTool knowledgeBaseSearchTool;
    private final ChatHistoryService chatHistoryService;
    private final AiCallEventPublisher eventPublisher;
    private final AgentProperties properties;
    private final com.ainexus.ai.resilience.ModelCallGate gate;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;
    private final com.ainexus.ai.security.ContentSafetyGuard contentSafetyGuard;

    public record AgentResult(String answer, String thinkingChain, int executedSteps, boolean degraded) {
    }

    public AgentResult execute(String sessionId, String prompt, Long userId, Long tenantId) {
        long start = System.currentTimeMillis();
        // 内容安全：规划/执行/降级三条链路共用同一入口守卫，避免两阶段 Agent 绕过检测
        contentSafetyGuard.checkInput(prompt);
        String sid = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        // 租户配额三重管控：租户禁用拦截 + 租户/用户 QPS + 每日限额（与单阶段 AgentService 对齐；tenant 关闭态直通）
        tenantQuotaService.ensureModelCallAllowed(tenantId, userId);
        ChatModelHolder holder = new ChatModelHolder(routeContext.route(ModelScene.AGENT, tenantId));

        AgentExecutionController controller = new AgentExecutionController(properties.getMaxSteps());
        List<ToolCallback> tools = ResilientToolCallback.decorate(Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(aiTools, knowledgeBaseSearchTool).build()
                                .getToolCallbacks()).toList(),
                properties.getToolWhitelist(), properties.getToolMaxRetries(), properties.getToolTimeoutMs());
        Map<String, ToolCallback> byName = Arrays.stream(tools.toArray(new ToolCallback[0]))
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));

        // 阶段一：规划（解析失败由 AgentPlanParser 内部兜底；模型调用异常时降级纯问答，与类注释承诺一致）
        List<AgentPlanParser.Step> plan;
        try {
            plan = gate.call(() -> AgentPlanParser.parse(ChatClient.create(holder.model)
                    .prompt().system(PLANNER_PROMPT).user(prompt).call().content()));
        } catch (Exception e) {
            log.warn("[agent] plan failed, fallback to plain qa, cause={}", e.getMessage());
            plan = List.of();
        }

        // 阶段二：执行（步数限制 + 循环检测）
        StringBuilder observations = new StringBuilder();
        boolean degraded = false;
        String answer = null;
        for (AgentPlanParser.Step step : plan) {
            var decision = controller.beforeStep(
                    AgentExecutionController.signature(step.action(), step.args()));
            if (decision != AgentExecutionController.Decision.PROCEED) {
                log.warn("[agent] stopped, reason={}, steps={}", decision, controller.executedSteps());
                break;
            }
            if ("answer".equals(step.action())) {
                try {
                    answer = synthesize(holder.model, prompt, observations.toString());
                } catch (Exception e) {
                    // 整合失败：留给降级链处理（plainQa / 兜底文案），保证历史与埋点照常落库
                    log.warn("[agent] synthesize failed, cause={}", e.getMessage());
                }
                controller.record(step.thought(), "synthesize", answer);
                break;
            }
            ToolCallback tool = byName.get(step.action());
            if (tool == null) {
                controller.record(step.thought(), step.action(), "工具不在白名单或不存在，跳过");
                continue;
            }
            String observation = tool.call(step.args() == null ? "{}" : step.args());
            observations.append("[").append(step.action()).append("] ").append(observation).append('\n');
            controller.record(step.thought(), step.action(), observation);
        }

        // 降级链：未产出回答 / 工具全失败 → 纯问答
        if (answer == null) {
            if (properties.isFallbackToPlainQa()) {
                try {
                    answer = plainQa(holder.model, prompt);
                } catch (Exception e) {
                    log.warn("[agent] plain qa fallback failed, cause={}", e.getMessage());
                    answer = "抱歉，模型暂时不可用，请稍后再试。";
                }
                degraded = true;
            } else {
                answer = "任务未能完成：执行步数达到上限或发生循环。";
            }
        }

        // 输出安全：敏感词掩码（与 ChatService/RagChatService 一致）
        answer = contentSafetyGuard.maskOutput(answer);
        chatHistoryService.saveUser(sid, prompt, "agent-2p", userId, tenantId);
        chatHistoryService.saveAssistant(sid, answer, "agent-2p", userId, tenantId);
        eventPublisher.publish(AiCallEvent.builder()
                .scene(ModelScene.AGENT.name())
                .modelName("agent-two-phase")
                .sessionId(sid)
                .userId(userId)
                .tenantId(tenantId)
                .success(true)
                .costMs(System.currentTimeMillis() - start)
                .build());

        return new AgentResult(answer,
                properties.isReturnThinking() ? controller.thinkingChain() : null,
                controller.executedSteps(), degraded);
    }

    private String synthesize(org.springframework.ai.chat.model.ChatModel model, String prompt, String observations) {
        return gate.call(() -> ChatClient.create(model).prompt()
                .system(SYNTHESIZE_PROMPT + observations)
                .user(prompt)
                .call().content());
    }

    private String plainQa(org.springframework.ai.chat.model.ChatModel model, String prompt) {
        return gate.call(() -> ChatClient.create(model).prompt()
                .system("工具暂不可用，请基于已有知识用简体中文直接回答。")
                .user(prompt)
                .call().content());
    }

    /** ChatModel 持有器（lambda 内可变引用） */
    private record ChatModelHolder(org.springframework.ai.chat.model.ChatModel model) {
    }
}
