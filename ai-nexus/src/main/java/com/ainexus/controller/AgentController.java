package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.dto.request.AgentChatRequest;
import com.ainexus.service.AgentService;
import com.ainexus.service.MultimodalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent 工具调用 + 多模态接口。
 */
@Tag(name = "Agent & Multimodal", description = "工具调用与多模态")
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final MultimodalService multimodalService;

    @Operation(summary = "Agent 指令（Function Calling：天气/时间等工具）")
    @PostMapping("/invoke")
    @RateLimit(permits = 10, windowSeconds = 10)
    public ApiResponse<String> invoke(@Valid @RequestBody AgentChatRequest req) {
        return ApiResponse.ok(agentService.invoke(req.getSessionId(), req.getPrompt()));
    }

    @Operation(summary = "图片理解（多模态，路由至多模态模型）")
    @PostMapping(value = "/vision", consumes = "multipart/form-data")
    @RateLimit(permits = 5, windowSeconds = 10)
    public ApiResponse<String> vision(@RequestParam(value = "question", required = false) String question,
                                      @RequestParam("image") MultipartFile image) {
        return ApiResponse.ok(multimodalService.understandImage(question, image));
    }
}
