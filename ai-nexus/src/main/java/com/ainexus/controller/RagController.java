package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.auth.CurrentUserResolver;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.dto.request.RagChatRequest;
import com.ainexus.dto.response.RagAnswerResponse;
import com.ainexus.ai.rag.RagEvalService;
import com.ainexus.service.RagChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG 问答接口：同步与流式（SSE）。
 */
@Tag(name = "RAG", description = "检索增强问答")
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagChatService ragChatService;
    private final RagEvalService ragEvalService;
    private final CurrentUserResolver currentUserResolver;

    public record EvalRequest(String kbName, Integer topK, List<RagEvalService.EvalCase> cases) {
    }

    @Operation(summary = "RAG 检索质量评估（批量测试集：Recall@K/命中率/相似度分布）")
    @PostMapping("/eval")
    @RateLimit(permits = 3, windowSeconds = 60)
    public ApiResponse<RagEvalService.EvalReport> eval(@RequestBody EvalRequest req) {
        return ApiResponse.ok(ragEvalService.evaluate(req.kbName(),
                req.topK() == null ? 5 : req.topK(), req.cases()));
    }

    @Operation(summary = "RAG 问答（同步，含引用片段）")
    @PostMapping("/ask")
    @RateLimit(permits = 10, windowSeconds = 10)
    public ApiResponse<RagAnswerResponse> ask(@Valid @RequestBody RagChatRequest req) {
        return ApiResponse.ok(ragChatService.ask(req.getSessionId(), req.getQuestion(),
                req.getKnowledgeBaseName(), req.getTopK(), req.getScene(),
                currentUserResolver.currentUserId(), currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "RAG 问答（SSE 流式，检索完成后首 token 直出）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(permits = 5, windowSeconds = 10)
    public Flux<String> stream(@Valid @RequestBody RagChatRequest req) {
        return ragChatService.streamAsk(req.getSessionId(), req.getQuestion(),
                req.getKnowledgeBaseName(), req.getTopK(), req.getScene(),
                currentUserResolver.currentUserId(), currentUserResolver.currentTenantId());
    }
}
