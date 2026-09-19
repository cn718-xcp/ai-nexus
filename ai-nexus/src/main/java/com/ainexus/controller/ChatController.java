package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.idempotent.Idempotent;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.dto.request.ChatRequest;
import com.ainexus.dto.request.KnowledgeBaseCreateRequest;
import com.ainexus.dto.response.ChatAnswerResponse;
import com.ainexus.service.ChatService;
import com.ainexus.service.StructuredOutputService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 对话接口：普通对话（限流 + 幂等）与结构化输出。
 */
@Tag(name = "Chat", description = "智能对话")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final StructuredOutputService structuredOutputService;

    @Operation(summary = "多轮对话（限流 + 幂等）")
    @PostMapping
    @RateLimit(permits = 20, windowSeconds = 10)
    // sessionId 为空时回退到消息内容做幂等维度，避免所有匿名请求共享 "null" Key 互相误杀
    @Idempotent(key = "#req.sessionId != null ? #req.sessionId : #req.message", ttlSeconds = 3)
    public ApiResponse<ChatAnswerResponse> chat(@Valid @RequestBody ChatRequest req) {
        return ApiResponse.ok(chatService.chat(req.getSessionId(), req.getMessage()));
    }

    @Operation(summary = "结构化输出：信息抽取（Spring AI 2.0 Structured Output）")
    @PostMapping("/extract")
    @RateLimit(permits = 10, windowSeconds = 10)
    public ApiResponse<StructuredOutputService.ExtractedInfo> extract(@RequestParam String text) {
        return ApiResponse.ok(structuredOutputService.extract(text));
    }

    @Operation(summary = "结构化输出：关键要点列表")
    @PostMapping("/keywords")
    @RateLimit(permits = 10, windowSeconds = 10)
    public ApiResponse<java.util.List<String>> keywords(@RequestParam String topic) {
        return ApiResponse.ok(structuredOutputService.keywords(topic));
    }
}
