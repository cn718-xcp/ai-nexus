package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.domain.entity.SysTenant;
import com.ainexus.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 租户管理接口：CRUD / 启停 / 配额配置（tenant.enabled=true 时生效的多租户管控面）。
 */
@Tag(name = "Tenant", description = "租户管理")
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @Operation(summary = "创建租户（含配额）")
    @PostMapping
    @RateLimit(permits = 5, windowSeconds = 60)
    public ApiResponse<SysTenant> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(tenantService.create(
                (String) req.get("name"),
                (String) req.get("code"),
                req.get("kbQuota") == null ? null : ((Number) req.get("kbQuota")).intValue(),
                req.get("concurrentQuota") == null ? null : ((Number) req.get("concurrentQuota")).intValue(),
                req.get("dailyModelQuota") == null ? null : ((Number) req.get("dailyModelQuota")).intValue()));
    }

    @Operation(summary = "租户列表")
    @GetMapping
    public ApiResponse<List<SysTenant>> list() {
        return ApiResponse.ok(tenantService.list());
    }

    @Operation(summary = "租户详情")
    @GetMapping("/{id}")
    public ApiResponse<SysTenant> get(@PathVariable Long id) {
        return ApiResponse.ok(tenantService.get(id));
    }

    @Operation(summary = "启用/禁用租户")
    @PutMapping("/{id}/status/{status}")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @PathVariable int status) {
        tenantService.updateStatus(id, status);
        return ApiResponse.ok();
    }

    @Operation(summary = "更新租户配额")
    @PutMapping("/{id}/quota")
    public ApiResponse<Void> updateQuota(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        tenantService.updateQuota(id,
                req.get("kbQuota") == null ? null : ((Number) req.get("kbQuota")).intValue(),
                req.get("concurrentQuota") == null ? null : ((Number) req.get("concurrentQuota")).intValue(),
                req.get("dailyModelQuota") == null ? null : ((Number) req.get("dailyModelQuota")).intValue());
        return ApiResponse.ok();
    }

    @Operation(summary = "更新租户专属模型路由")
    @PutMapping("/{id}/routes")
    public ApiResponse<Void> updateRoutes(@PathVariable Long id, @RequestBody Map<String, String> req) {
        tenantService.updateModelRoutes(id, req.get("modelRoutes"));
        return ApiResponse.ok();
    }
}
