package com.ainexus.service;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.resilience.ModelCallResilienceTemplate;
import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import com.ainexus.ai.stats.AiCallEvent;
import com.ainexus.ai.stats.AiCallEventPublisher;
import com.ainexus.domain.entity.ChatMessageRecord;
import com.ainexus.dto.response.ChatAnswerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 普通对话服务：多轮上下文 + 多模型路由 + 重试/降级 + 调用埋点（token 用量采集）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String SYSTEM_PROMPT = "你是 AI Nexus 智能助手，请用简体中文简洁、准确地回答。";

    private final ModelRouteContext routeContext;
    private final ChatHistoryService chatHistoryService;
    private final ModelCallResilienceTemplate resilience;
    private final com.ainexus.ai.resilience.ModelCallGate gate;
    private final AiCallEventPublisher eventPublisher;

    /**
     * 多轮对话：返回服务端最终会话 ID（客户端未传时自动生成，需回传给前端以延续会话）。
     *
     * @param userId 用户隔离维度（鉴权开启时由控制器解析传入；关闭态为 null）
     */
    public ChatAnswerResponse chat(String sessionId, String message, Long userId) {
        String sid = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        ChatModel chatModel = routeContext.route(ModelScene.CHAT);

        // 组装消息：系统提示 + 历史滑窗 + 本轮输入
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        chatHistoryService.loadWindow(sid, userId).forEach(h -> toMessage(h, messages));
        messages.add(new UserMessage(message));
        chatHistoryService.saveUser(sid, message, null, userId);

        // 单元素数组承载响应元数据（模型名 / token 用量），避免为 lambda 引入包装类
        ChatResponse[] holder = new ChatResponse[1];
        ModelCallResilienceTemplate.ResilienceResult<String> result = resilience.execute(
                ModelScene.CHAT.name(),
                () -> gate.call(() -> {
                    ChatResponse response = chatModel.call(new Prompt(messages));
                    holder[0] = response;
                    return response.getResult().getOutput().getText();
                }),
                "static-reply",
                () -> "抱歉，模型暂时不可用，请稍后再试。");

        String modelName = resolveModelName(chatModel, holder[0]);
        Usage usage = holder[0] != null && holder[0].getMetadata() != null ? holder[0].getMetadata().getUsage() : null;
        eventPublisher.publish(AiCallEvent.builder()
                .scene(ModelScene.CHAT.name())
                .modelName(modelName)
                .sessionId(sid)
                .userId(userId)
                .success(!result.fallback())
                .costMs(result.costMs())
                .promptTokens(usage == null ? null : Long.valueOf(usage.getPromptTokens()))
                .completionTokens(usage == null ? null : Long.valueOf(usage.getCompletionTokens()))
                .fallback(result.fallback())
                .errorMessage(result.fallback() ? "model unavailable, fallback applied" : null)
                .build());

        String answer = result.result();
        chatHistoryService.saveAssistant(sid, answer, modelName, userId);
        return new ChatAnswerResponse(sid, answer);
    }

    private String resolveModelName(ChatModel chatModel, ChatResponse response) {
        if (response != null && response.getMetadata() != null && response.getMetadata().getModel() != null) {
            return response.getMetadata().getModel();
        }
        return chatModel.getClass().getSimpleName();
    }

    private void toMessage(ChatMessageRecord record, List<Message> out) {
        out.add("assistant".equals(record.getRole())
                ? new AssistantMessage(record.getContent())
                : new UserMessage(record.getContent()));
    }
}
