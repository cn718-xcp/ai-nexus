package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.auth.CurrentUserResolver;
import com.ainexus.common.config.DynamicConfigService;
import com.ainexus.domain.entity.SysConfig;
import com.ainexus.common.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 动态配置管理接口：查询 / 修改（变更实时生效 + 审计落库）。
 * dynamic.enabled=false 时仅可查询本地默认值，写操作无效（直通模式）。
 */
@Tag(name = "Config", description = "动态配置管控")
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final DynamicConfigService dynamicConfigService;
    private final CurrentUserResolver currentUserResolver;

    public record ConfigRequest(@NotBlank String key, @NotBlank String value,
                                Long tenantId, String scope, String grayTenants) {
    }

    @Operation(summary = "可管控配置键清单")
    @GetMapping("/keys")
    public ApiResponse<List<String>> keys() {
        return ApiResponse.ok(DynamicConfigService.MANAGED_KEYS);
    }

    @Operation(summary = "查询当前生效配置值（含优先级解析）")
    @GetMapping("/effective")
    public ApiResponse<Map<String, String>> effective(@RequestParam(required = false) Long tenantId) {
        Long tid = currentUserResolver.currentTenantId() != null ? currentUserResolver.currentTenantId() : tenantId;
        Map<String, String> result = new java.util.LinkedHashMap<>();
        DynamicConfigService.MANAGED_KEYS.forEach(key ->
                result.put(key, dynamicConfigService.get(key, null, tid)));
        return ApiResponse.ok(result);
    }

    @Operation(summary = "全部配置记录（DB 落库明细）")
    @GetMapping
    public ApiResponse<List<SysConfig>> list() {
        return ApiResponse.ok(dynamicConfigService.listAll());
    }

    @Operation(summary = "修改配置（实时生效，含灰度/租户维度与变更审计）")
    @PutMapping
    @RateLimit(permits = 10, windowSeconds = 60)
    public ApiResponse<Void> put(@RequestBody ConfigRequest req) {
        Long tid = currentUserResolver.currentTenantId() != null ? currentUserResolver.currentTenantId() : req.tenantId();
        dynamicConfigService.put(req.key(), req.value(), tid, req.scope(), req.grayTenants());
        return ApiResponse.ok();
    }
}
