package com.ainexus.service;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.auth.JwtService;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysUser;
import com.ainexus.domain.repository.SysUserRepository;
import com.ainexus.dto.request.LoginRequest;
import com.ainexus.dto.request.RegisterRequest;
import com.ainexus.dto.response.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务：注册 / 登录 / 刷新令牌。
 * 密码 BCrypt 单向摘要存储；access 短效鉴权 + refresh 长效换发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${ainexus.jwt.access-token-minutes:30}")
    private int accessTokenMinutes;

    @Transactional
    public Long register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BizException(ErrorCode.BIZ_ERROR, "用户名已存在: " + request.getUsername());
        }
        SysUser user = userRepository.save(SysUser.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build());
        log.info("[auth] user registered, id={}, username={}", user.getId(), user.getUsername());
        return user.getId();
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        SysUser user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BizException(ErrorCode.BIZ_ERROR, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // 统一话术，避免用户名枚举
            throw new BizException(ErrorCode.BIZ_ERROR, "用户名或密码错误");
        }
        return issueTokens(user);
    }

    /** 用 refresh 令牌换发新的双令牌（校验 token_type=refresh 与签名/有效期） */
    public TokenResponse refresh(String refreshToken) {
        var claims = jwtService.parse(refreshToken);
        if (!"refresh".equals(claims.get(JwtService.CLAIM_TYPE))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "非法的刷新令牌");
        }
        String username = String.valueOf(claims.get("sub"));
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.BIZ_ERROR, "用户不存在"));
        return issueTokens(user);
    }

    private TokenResponse issueTokens(SysUser user) {
        return new TokenResponse(
                jwtService.issueAccessToken(user.getUsername(), user.getId()),
                jwtService.issueRefreshToken(user.getUsername(), user.getId()),
                "Bearer",
                accessTokenMinutes * 60L);
    }
}
