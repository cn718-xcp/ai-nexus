package com.ainexus.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户解析器：鉴权开启时从 SecurityContext 提取 JWT 中的用户维度信息；
 * 鉴权关闭（dev 默认）时返回 null，调用方据此保持与无鉴权模式完全一致的行为。
 */
@Component
public class CurrentUserResolver {

    @Value("${ainexus.auth.enabled:false}")
    private boolean authEnabled;

    /** 当前用户 ID：鉴权关闭或未携带有效 JWT 时返回 null（匿名，行为与关闭态一致） */
    public Long currentUserId() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return null;
        }
        Object uid = jwt.getClaims().get(JwtService.CLAIM_UID);
        return uid instanceof Number number ? number.longValue() : null;
    }

    /** 当前用户名：JWT subject */
    public String currentUsername() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : jwt.getSubject();
    }

    /** 当前租户 ID：多租户开启且令牌携带 tid 时返回；关闭态/匿名返回 null */
    public Long currentTenantId() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return null;
        }
        Object tid = jwt.getClaims().get(JwtService.CLAIM_TID);
        return tid instanceof Number number ? number.longValue() : null;
    }

    private Jwt currentJwt() {
        if (!authEnabled) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtToken && jwtToken.getToken() != null) {
            return jwtToken.getToken();
        }
        return null;
    }
}
