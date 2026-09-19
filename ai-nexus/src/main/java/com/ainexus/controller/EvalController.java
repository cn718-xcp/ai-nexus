package com.ainexus.controller;

import com.ainexus.ai.eval.RagEvalLoopService;
import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 效果评估闭环接口：Bad Case 查询与人工标注反馈。
 */
@Tag(name = "Eval", description = "RAG 效果评估闭环")
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final RagEvalLoopService evalLoopService;

    @Operation(summary = "待标注 Bad Case 列表（低分问答自动收集）")
    @GetMapping("/badcases")
    public ApiResponse<List<RagEvalLoopService.BadCaseView>> pending() {
        return ApiResponse.ok(evalLoopService.pendingBadCases().stream()
                .map(c -> new RagEvalLoopService.BadCaseView(c.getId(), c.getQuestion(), c.getAnswer(),
                        c.getConsistency(), c.getRelevance(), c.getCompleteness(), c.getCreatedAt()))
                .toList());
    }

    @Operation(summary = "Bad Case 人工标注反馈")
    @PostMapping("/badcases/{id}/feedback")
    public ApiResponse<Void> feedback(@PathVariable Long id, @RequestBody Map<String, String> req) {
        evalLoopService.feedback(id, req.get("feedback"));
        return ApiResponse.ok();
    }
}
