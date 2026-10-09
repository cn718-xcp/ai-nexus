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
        Long tid = resolveTenantId(tenantId);
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
        dynamicConfigService.put(req.key(), req.value(), resolveTenantId(req.tenantId()),
                req.scope(), req.grayTenants());
        return ApiResponse.ok();
    }

    /**
     * 解析租户维度：鉴权开启时只能取自令牌（tid），请求参数中的 tenantId 一律忽略——
     * 否则无 tid 的令牌可借客户端参数读写他人租户配置，属于越权。
     * 仅鉴权关闭的调试态保留参数回退，保持与无鉴权时期一致的行为。
     */
    private Long resolveTenantId(Long requestedTenantId) {
        Long tid = currentUserResolver.currentTenantId();
        if (tid != null || currentUserResolver.isAuthEnabled()) {
            return tid;
        }
        return requestedTenantId;
    }
}
