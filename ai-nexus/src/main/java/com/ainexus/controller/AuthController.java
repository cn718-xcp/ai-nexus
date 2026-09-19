package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.ratelimit.RateLimit;
import com.ainexus.dto.request.LoginRequest;
import com.ainexus.dto.request.RefreshRequest;
import com.ainexus.dto.request.RegisterRequest;
import com.ainexus.dto.response.TokenResponse;
import com.ainexus.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 刷新（公开端点，auth.enabled=true 时生效）。
 */
@Tag(name = "Auth", description = "认证与令牌")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    @RateLimit(permits = 5, windowSeconds = 60)
    public ApiResponse<Long> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @Operation(summary = "登录（签发双令牌）")
    @PostMapping("/login")
    @RateLimit(permits = 10, windowSeconds = 60)
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @Operation(summary = "刷新令牌")
    @PostMapping("/refresh")
    @RateLimit(permits = 20, windowSeconds = 60)
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.getRefreshToken()));
    }
}
