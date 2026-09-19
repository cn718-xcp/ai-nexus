package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.auth.CurrentUserResolver;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.dto.request.KnowledgeBaseCreateRequest;
import com.ainexus.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理接口：CRUD + 文档上传（鉴权开启时按用户隔离）。
 */
@Tag(name = "Knowledge Base", description = "知识库管理")
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(summary = "创建知识库")
    @PostMapping
    @RateLimit(permits = 5, windowSeconds = 10)
    public ApiResponse<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseCreateRequest req) {
        return ApiResponse.ok(knowledgeBaseService.create(req.getName(), req.getDescription(),
                currentUserResolver.currentUserId(), currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "知识库列表（鉴权开启时仅返回本人知识库）")
    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list() {
        return ApiResponse.ok(knowledgeBaseService.list(currentUserResolver.currentUserId(),
                currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "知识库文档列表")
    @GetMapping("/{kbId}/documents")
    public ApiResponse<List<KnowledgeDocument>> documents(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledgeBaseService.listDocuments(kbId, currentUserResolver.currentUserId(),
                currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "上传文档（txt/md/json/csv）并切分入库")
    @PostMapping("/{kbId}/documents")
    @RateLimit(permits = 5, windowSeconds = 10)
    public ApiResponse<KnowledgeDocument> upload(@PathVariable Long kbId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(knowledgeBaseService.uploadDocument(kbId, file, currentUserResolver.currentUserId(),
                currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "删除知识库（级联删除向量）")
    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> delete(@PathVariable Long kbId) {
        knowledgeBaseService.delete(kbId, currentUserResolver.currentUserId(), currentUserResolver.currentTenantId());
        return ApiResponse.ok();
    }
}
