package com.ainexus.service;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.pipeline.AbstractRagPipeline;
import com.ainexus.ai.pipeline.RagContext;
import com.ainexus.ai.resilience.ModelCallResilienceTemplate;
import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import com.ainexus.ai.stats.AiCallEvent;
import com.ainexus.ai.stats.AiCallEventPublisher;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.dto.response.RagAnswerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RAG 问答服务：模板方法流水线（检索/组装）与模型调用（弹性）解耦。
 * 问答双方均写入对话历史，保证多轮 RAG 的上下文连续性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private final AbstractRagPipeline ragPipeline;
    private final ModelRouteContext routeContext;
    private final ModelCallResilienceTemplate resilience;
    private final AiCallEventPublisher eventPublisher;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatHistoryService chatHistoryService;

    public RagAnswerResponse ask(String sessionId, String question, String kbName, int topK, String scene) {
        if (kbName != null && !kbName.isBlank()
                && knowledgeBaseService.list().stream().noneMatch(kb -> kb.getName().equals(kbName))) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
        // 会话 ID 在入口统一确定，保证流水线历史加载与响应返回一致
        String sid = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;

        RagContext context = ragPipeline.run(RagContext.builder()
                .question(question)
                .sessionId(sid)
                .knowledgeBaseName(kbName)
                .topK(topK <= 0 ? 5 : topK)
                .build());

        ChatModel chatModel = routeContext.route(ModelScene.of(scene) == ModelScene.CHAT ? ModelScene.RAG : ModelScene.of(scene));
        ModelCallResilienceTemplate.ResilienceResult<String> result = resilience.execute(
                () -> chatModel.call(context.getFinalPrompt()),
                "no-context-reply",
                () -> context.getChunks().isEmpty()
                        ? "抱歉，模型暂时不可用。"
                        : "抱歉，模型暂时不可用，以下是与问题相关的知识库片段：\n" + String.join("\n---\n", context.getChunks()));

        String usedScene = scene == null || scene.isBlank() ? ModelScene.RAG.name() : scene.toUpperCase();
        eventPublisher.publish(AiCallEvent.builder()
                .scene(usedScene)
                .modelName(chatModel.getClass().getSimpleName())
                .sessionId(sid)
                .success(!result.fallback())
                .costMs(result.costMs())
                .fallback(result.fallback())
                .errorMessage(result.fallback() ? "model unavailable, fallback applied" : null)
                .build());

        // 写入对话历史：下一轮 RAG 才能引用本轮问答（在流水线读取之后写，避免当轮重复）
        chatHistoryService.saveUser(sid, question, null);
        chatHistoryService.saveAssistant(sid, result.result(), chatModel.getClass().getSimpleName());

        return RagAnswerResponse.builder()
                .sessionId(sid)
                .answer(result.result())
                .referencedChunks(context.getChunks())
                .fallback(result.fallback())
                .build();
    }
}
