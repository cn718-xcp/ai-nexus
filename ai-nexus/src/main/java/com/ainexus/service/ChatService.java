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
    private final com.ainexus.common.metrics.BusinessMetrics metrics;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;
    private final com.ainexus.ai.model.ModelInvoker modelInvoker;
    private final com.ainexus.ai.model.PromptCacheService promptCache;

    @org.springframework.beans.factory.annotation.Value("${ainexus.models.primary:ollama:qwen2.5:7b}")
    private String primaryModelKey;
    @org.springframework.beans.factory.annotation.Value("${ainexus.models.fallback:}")
    private String fallbackModelKeys;

    /** 备用模型键解析（逗号分隔，按序降级） */
    private List<String> fallbackKeys() {
        return fallbackModelKeys == null || fallbackModelKeys.isBlank()
                ? List.of() : java.util.Arrays.stream(fallbackModelKeys.split(","))
                .map(String::trim).filter(k -> !k.isEmpty()).toList();
    }
    @org.springframework.beans.factory.annotation.Value("${ainexus.models.gray-key:}")
    private String grayModelKey;
    @org.springframework.beans.factory.annotation.Value("${ainexus.models.gray-ratio:0.0}")
    private double grayRatio;
    @org.springframework.beans.factory.annotation.Value("${ainexus.model.context-max-chars:4000}")
    private int contextMaxChars;

    /**
     * 多轮对话：返回服务端最终会话 ID（客户端未传时自动生成，需回传给前端以延续会话）。
     *
     * @param userId 用户隔离维度（鉴权开启时由控制器解析传入；关闭态为 null）
     */
    public ChatAnswerResponse chat(String sessionId, String message, Long userId, Long tenantId) {
        String sid = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        metrics.onChatRequest();
        // 多租户开启时校验租户状态与每日调用配额（关闭态直通）
        tenantQuotaService.ensureModelCallAllowed(tenantId, userId);
        ChatModel chatModel = routeContext.route(ModelScene.CHAT, tenantId);

        // 组装消息：系统提示 + 历史滑窗 + 本轮输入
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        var history = chatHistoryService.loadWindow(sid, userId, tenantId);
        // 超长上下文智能压缩：超出字符预算从最早消息裁剪（与滑窗条数限制双重保障）
        com.ainexus.ai.memory.ContextWindowCompressor.compress(history, contextMaxChars)
                .forEach(h -> toMessage(h, messages));
        messages.add(new UserMessage(message));
        chatHistoryService.saveUser(sid, message, null, userId, tenantId);

        // 单元素数组承载响应元数据（模型名 / token 用量），避免为 lambda 引入包装类
        ChatResponse[] holder = new ChatResponse[1];
        // 前缀缓存：相同提示词短 TTL 去重回放（默认关闭）
        String promptKey = cn.hutool.crypto.digest.DigestUtil.md5Hex(messages.toString());
        String cached = promptCache.getAndLock(promptKey);
        ModelCallResilienceTemplate.ResilienceResult<String> result;
        if (cached != null && !cached.isBlank()) {
            result = new ModelCallResilienceTemplate.ResilienceResult<>(cached, false, 0);
        } else {
            // 多模型统一适配：主模型失败（连接类异常）自动降级备用模型；灰度按比例分流
            result = resilience.execute(
                    ModelScene.CHAT.name(),
                    () -> gate.call(() -> {
                        String text = modelInvoker.invoke(primaryModelKey, fallbackKeys(), grayModelKey, grayRatio,
                                cm -> {
                                    ChatResponse response = cm.call(new Prompt(messages));
                                    holder[0] = response;
                                    return response.getResult().getOutput().getText();
                                });
                        promptCache.write(promptKey, text);
                        return text;
                    }),
                    "static-reply",
                    () -> "抱歉，模型暂时不可用，请稍后再试。");
        }

        String modelName = resolveModelName(chatModel, holder[0]);
        Usage usage = holder[0] != null && holder[0].getMetadata() != null ? holder[0].getMetadata().getUsage() : null;
        eventPublisher.publish(AiCallEvent.builder()
                .scene(ModelScene.CHAT.name())
                .modelName(modelName)
                .sessionId(sid)
                .userId(userId)
                .tenantId(tenantId)
                .success(!result.fallback())
                .costMs(result.costMs())
                .promptTokens(usage == null ? null : Long.valueOf(usage.getPromptTokens()))
                .completionTokens(usage == null ? null : Long.valueOf(usage.getCompletionTokens()))
                .fallback(result.fallback())
                .errorMessage(result.fallback() ? "model unavailable, fallback applied" : null)
                .build());

        String answer = result.result();
        chatHistoryService.saveAssistant(sid, answer, modelName, userId, tenantId);
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
