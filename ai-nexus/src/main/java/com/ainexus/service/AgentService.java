package com.ainexus.service;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import com.ainexus.ai.stats.AiCallEvent;
import com.ainexus.ai.stats.AiCallEventPublisher;
import com.ainexus.ai.tools.AiTools;
import com.ainexus.ai.tools.KnowledgeBaseSearchTool;
import com.ainexus.domain.entity.ChatMessageRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 服务：ChatClient Function Calling 多步任务执行。
 * - 工具集：天气/时间（AiTools）+ 知识库检索（KnowledgeBaseSearchTool）
 * - 多步规划：系统提示要求先规划再执行，框架内置工具循环支撑连续多工具调用
 * - 短期记忆：注入会话历史滑窗，模型可基于上下文自主补全省略信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final String PLANNER_SYSTEM_PROMPT =
            "你是一个能够执行多步任务的智能助手。执行规则：\n"
            + "1. 先在内部规划完成任务需要的步骤与工具；\n"
            + "2. 按计划依次调用工具（可连续多次），逐步补全信息；\n"
            + "3. 需要企业内部资料时调用 searchKnowledgeBase 工具；\n"
            + "4. 所有信息就绪后，整合成最终简体中文回答，并标注信息来源。\n"
            + "对话历史中的上下文信息可以直接引用，无需再次询问。";

    private final ModelRouteContext routeContext;
    private final AiTools aiTools;
    private final KnowledgeBaseSearchTool knowledgeBaseSearchTool;
    private final ChatHistoryService chatHistoryService;
    private final AiCallEventPublisher eventPublisher;
    private final com.ainexus.ai.resilience.ModelCallResilienceTemplate resilience;
    private final com.ainexus.ai.resilience.ModelCallGate gate;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;

    public String invoke(String sessionId, String prompt, Long userId, Long tenantId) {
        long start = System.currentTimeMillis();
        // 会话 ID 缺省时服务端生成（历史与埋点均绑定该会话；多轮记忆需客户端回传）
        String sid = sessionId == null || sessionId.isBlank() ? java.util.UUID.randomUUID().toString() : sessionId;
        // 每次请求构建会话级 ChatClient：注册工具集（工厂模式）
        ChatClient client = ChatClient.builder(routeContext.route(ModelScene.AGENT, tenantId))
                .defaultToolCallbacks(MethodToolCallbackProvider.builder()
                        .toolObjects(aiTools, knowledgeBaseSearchTool).build())
                .build();

        // 短期记忆：历史滑窗 + 本轮指令
        List<Message> history = new ArrayList<>();
        chatHistoryService.loadWindow(sid, userId, tenantId)
                .forEach(h -> history.add("assistant".equals(h.getRole())
                        ? new AssistantMessage(h.getContent())
                        : new UserMessage(h.getContent())));

        tenantQuotaService.ensureModelCallAllowed(tenantId, userId);
        var result = resilience.execute(
                ModelScene.AGENT.name(),
                () -> gate.call(() -> client.prompt()
                        .system(PLANNER_SYSTEM_PROMPT)
                        .messages(history)
                        .user(prompt)
                        .call()
                        .content()),
                "static-reply",
                () -> "抱歉，模型暂时不可用，请稍后再试。");
        String answer = result.result();

        // Agent 轮次同样写入历史，实现跨轮上下文延续
        chatHistoryService.saveUser(sid, prompt, "agent", userId, tenantId);
        chatHistoryService.saveAssistant(sid, answer, "agent", userId, tenantId);

        eventPublisher.publish(AiCallEvent.builder()
                .scene(ModelScene.AGENT.name())
                .modelName("agent")
                .sessionId(sid)
                .userId(userId)
                .tenantId(tenantId)
                .success(!result.fallback())
                .costMs(System.currentTimeMillis() - start)
                .fallback(result.fallback())
                .build());
        return answer;
    }
}
