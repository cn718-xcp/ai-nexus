package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.auth.CurrentUserResolver;
import com.ainexus.common.idempotent.Idempotent;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.dto.request.KnowledgeBaseCreateRequest;
import com.ainexus.service.KnowledgeBackupService;
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
    private final KnowledgeBackupService backupService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(summary = "创建知识库")
    @PostMapping
    @RateLimit(permits = 5, windowSeconds = 10)
    // 写操作幂等：同名重复提交在 10s 窗口内直接拒绝
    @Idempotent(key = "#req.name", ttlSeconds = 10)
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
    // 写操作幂等：相同知识库+文件名 10s 窗口内防重复入库（防止向量重复）
    @Idempotent(key = "#kbId + ':' + #file?.originalFilename", ttlSeconds = 10)
    public ApiResponse<KnowledgeDocument> upload(@PathVariable Long kbId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(knowledgeBaseService.uploadDocument(kbId, file, currentUserResolver.currentUserId(),
                currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "导出知识库备份（元数据+全部片段原文 JSON，鉴权开启时校验归属）")
    @GetMapping("/{kbId}/backup")
    public ApiResponse<String> backup(@PathVariable Long kbId) {
        return ApiResponse.ok(backupService.export(kbId, currentUserResolver.currentUserId(),
                currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "导入知识库备份（重新向量化重建）")
    @PostMapping("/import")
    @RateLimit(permits = 3, windowSeconds = 60)
    @Idempotent(ttlSeconds = 30)
    public ApiResponse<KnowledgeBase> importBackup(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        String json = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return ApiResponse.ok(backupService.importBackup(json,
                currentUserResolver.currentUserId(), currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "删除知识库（级联删除向量）")
    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> delete(@PathVariable Long kbId) {
        knowledgeBaseService.delete(kbId, currentUserResolver.currentUserId(), currentUserResolver.currentTenantId());
        return ApiResponse.ok();
    }

    @Operation(summary = "重建知识库向量（从 Chunk 备份补写归属元数据，存量数据迁移用）")
    @PostMapping("/{kbId}/reindex")
    @RateLimit(permits = 3, windowSeconds = 60)
    public ApiResponse<KnowledgeBaseService.ReindexResult> reindex(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledgeBaseService.reindexVectors(kbId,
                currentUserResolver.currentUserId(), currentUserResolver.currentTenantId()));
    }

    @Operation(summary = "重建全部知识库向量（仅管理员，存量数据迁移用）")
    @PostMapping("/admin/reindex-all")
    @RateLimit(permits = 1, windowSeconds = 60)
    @org.springframework.security.access.prepost.PreAuthorize("@accessGuard.isAdmin()")
    public ApiResponse<List<KnowledgeBaseService.ReindexResult>> reindexAll() {
        return ApiResponse.ok(knowledgeBaseService.reindexAll());
    }
}
