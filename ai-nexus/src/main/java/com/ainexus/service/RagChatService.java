package com.ainexus.service;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.rag.ChunkReference;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
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
    private final com.ainexus.ai.resilience.ModelCallGate gate;
    private final AiCallEventPublisher eventPublisher;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatHistoryService chatHistoryService;
    private final com.ainexus.common.metrics.BusinessMetrics metrics;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;
    private final com.ainexus.ai.rag.CitationMarker citationMarker;
    private final com.ainexus.ai.rag.RagProperties ragProperties;
    private final com.ainexus.ai.security.ContentSafetyGuard contentSafetyGuard;
    @org.springframework.beans.factory.annotation.Value("${ainexus.rag.groundedness-check:false}")
    private boolean groundednessCheck;
    private final com.ainexus.ai.eval.RagEvalLoopService evalLoopService;
    @org.springframework.beans.factory.annotation.Value("${ainexus.eval.answer-enabled:false}")
    private boolean answerEvalEnabled;

    public RagAnswerResponse ask(String sessionId, String question, String kbName, int topK, String scene, Long userId, Long tenantId) {
        if (kbName != null && !kbName.isBlank()
                && knowledgeBaseService.list(userId, tenantId).stream().noneMatch(kb -> kb.getName().equals(kbName))) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
        tenantQuotaService.ensureModelCallAllowed(tenantId, userId);
        // 内容安全：注入检测 + 输入敏感词（security.enabled=false 直通）
        contentSafetyGuard.checkInput(question);
        // 会话 ID 在入口统一确定，保证流水线历史加载与响应返回一致
        String sid = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        // 场景归一：未指定走 RAG 专用路由（也是熔断器维度）
        ModelScene modelScene = ModelScene.of(scene) == ModelScene.CHAT ? ModelScene.RAG : ModelScene.of(scene);
        String sceneName = modelScene.name();

        RagContext context = ragPipeline.run(RagContext.builder()
                .question(question)
                .sessionId(sid)
                .userId(userId)
                .tenantId(tenantId)
                .knowledgeBaseName(kbName)
                .topK(topK <= 0 ? 5 : topK)
                .build());

        ChatModel chatModel = routeContext.route(modelScene, tenantId);
        ModelCallResilienceTemplate.ResilienceResult<String> result = resilience.execute(
                sceneName,
                () -> gate.call(() -> chatModel.call(context.getFinalPrompt())),
                "no-context-reply",
                () -> context.getChunks().isEmpty()
                        ? "抱歉，模型暂时不可用。"
                        : "抱歉，模型暂时不可用，以下是与问题相关的知识库片段：\n" + String.join("\n---\n", context.getChunks()));

        metrics.onRagRequest(!context.getChunks().isEmpty());
        eventPublisher.publish(AiCallEvent.builder()
                .scene(sceneName)
                .modelName(chatModel.getClass().getSimpleName())
                .sessionId(sid)
                .userId(userId)
                .tenantId(tenantId)
                .success(!result.fallback())
                .costMs(result.costMs())
                .fallback(result.fallback())
                .errorMessage(result.fallback() ? "model unavailable, fallback applied" : null)
                .build());

        // 效果评估闭环：LLM 三维评审，低分自动入 Bad Case 库（eval.answer-enabled 开关，默认关）
        if (answerEvalEnabled) {
            try {
                evalLoopService.evaluateAnswer(question, context.getChunks(), result.result());
            } catch (Exception e) {
                log.warn("[rag-eval] answer eval failed: {}", e.getMessage());
            }
        }

        // 写入对话历史：下一轮 RAG 才能引用本轮问答（在流水线读取之后写，避免当轮重复）
        chatHistoryService.saveUser(sid, question, null, userId, tenantId);
        chatHistoryService.saveAssistant(sid, result.result(), chatModel.getClass().getSimpleName(), userId, tenantId);

        List<ChunkReference> references = toReferences(context.getDocuments());
        // 输出安全：敏感词掩码
        String answer = contentSafetyGuard.maskOutput(result.result());
        // 引用高亮：答案中与引用片段重叠的句子追加来源序号（rag.citation-markers 开关）
        if (ragProperties.isCitationMarkers()) {
            answer = citationMarker.mark(answer, references);
        }
        // 事实性校验：标记答案中未被任何引用片段支持的句子（幻觉可视化，开关默认关）
        List<String> ungrounded = groundednessCheck
                ? citationMarker.ungroundedSentences(answer, references) : List.of();
        return RagAnswerResponse.builder()
                .sessionId(sid)
                .answer(answer)
                .referencedChunks(context.getChunks())
                .references(references)
                .ungroundedSentences(ungrounded)
                .fallback(result.fallback())
                .build();
    }

    /**
     * RAG 流式问答：检索阶段同步完成（依赖向量库），生成阶段走 ChatModel.stream 原生流式，
     * 首 token 直出，无需等待整段生成（对比伪流式：首包延迟从"整段生成耗时"降为"首 token 耗时"）。
     */
    public Flux<String> streamAsk(String sessionId, String question, String kbName, int topK, String scene, Long userId, Long tenantId) {
        if (kbName != null && !kbName.isBlank()
                && knowledgeBaseService.list(userId, tenantId).stream().noneMatch(kb -> kb.getName().equals(kbName))) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
        tenantQuotaService.ensureModelCallAllowed(tenantId, userId);
        // 内容安全与同步 ask 保持一致：注入检测 + 输入敏感词（security.enabled=false 直通）。
        // 异常在建流前同步抛出 → HTTP 200 JSON ApiResponse，前端流式客户端按 content-type 识别
        contentSafetyGuard.checkInput(question);
        String sid = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        RagContext context = ragPipeline.run(RagContext.builder()
                .question(question)
                .sessionId(sid)
                .userId(userId)
                .tenantId(tenantId)
                .knowledgeBaseName(kbName)
                .topK(topK <= 0 ? 5 : topK)
                .build());
        ChatModel chatModel = routeContext.route(ModelScene.of(scene) == ModelScene.CHAT ? ModelScene.RAG : ModelScene.of(scene), tenantId);

        chatHistoryService.saveUser(sid, question, null, userId, tenantId);
        long start = System.currentTimeMillis();
        StringBuilder answerBuffer = new StringBuilder();

        return chatModel.stream(new Prompt(context.getFinalPrompt()))
                .map(resp -> resp.getResult() == null || resp.getResult().getOutput() == null
                        ? "" : resp.getResult().getOutput().getText())
                .filter(org.springframework.util.StringUtils::hasText)
                // 累积完整答案用于历史落库与埋点
                .doOnNext(answerBuffer::append)
                .doOnComplete(() -> {
                    chatHistoryService.saveAssistant(sid, answerBuffer.toString(), chatModel.getClass().getSimpleName(), userId, tenantId);
                    eventPublisher.publish(AiCallEvent.builder()
                            .scene(ModelScene.RAG.name())
                            .modelName(chatModel.getClass().getSimpleName())
                            .sessionId(sid)
                            .userId(userId)
                            .tenantId(tenantId)
                            .success(true)
                            .costMs(System.currentTimeMillis() - start)
                            .build());
                })
                // 流式失败降级：推送知识片段兜底，保持 SSE 通道正常关闭
                .onErrorResume(e -> {
                    log.warn("[rag-stream] stream failed, fallback to chunks, cause={}", e.getMessage());
                    eventPublisher.publish(AiCallEvent.builder()
                            .scene(ModelScene.RAG.name())
                            .modelName(chatModel.getClass().getSimpleName())
                            .sessionId(sid)
                            .userId(userId)
                            .tenantId(tenantId)
                            .success(false)
                            .costMs(System.currentTimeMillis() - start)
                            .fallback(true)
                            .errorMessage(e.getMessage())
                            .build());
                    String fallback = context.getChunks().isEmpty()
                            ? "抱歉，模型暂时不可用。"
                            : "抱歉，模型暂时不可用，以下是与问题相关的知识库片段：\n" + String.join("\n---\n", context.getChunks());
                    return Flux.just(fallback);
                });
    }

    /** 引用精准定位：从命中文档元数据提取来源文档 / 片段序号 / 相似度分 */
    private List<ChunkReference> toReferences(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(d -> ChunkReference.builder()
                .content(d.getText())
                .documentName((String) d.getMetadata().get("file_name"))
                .documentId((String) d.getMetadata().get("doc_id"))
                .chunkIndex(d.getMetadata().get("chunk_index") instanceof Number n ? n.intValue() : null)
                // 检索缓存命中的文档 score 转存于 metadata["cached_score"]，此处回退读取保持引用分一致
                .score(d.getScore() != null ? d.getScore()
                        : d.getMetadata().get("cached_score") instanceof Number n ? n.doubleValue() : null)
                .startOffset(d.getMetadata().get("char_start") instanceof Number n ? n.intValue() : null)
                .endOffset(d.getMetadata().get("char_end") instanceof Number n ? n.intValue() : null)
                .section((String) d.getMetadata().get("section"))
                .build()).toList();
    }
}
