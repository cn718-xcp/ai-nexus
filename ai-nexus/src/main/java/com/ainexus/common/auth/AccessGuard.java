package com.ainexus.common.auth;

import com.ainexus.domain.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 管理面鉴权守卫：@PreAuthorize("@accessGuard.isAdmin()") 的判定入口。
 * - 鉴权关闭（dev 默认）：直接放行，保持与无鉴权时期一致的调试体验
 * - 鉴权开启：仅 role=ADMIN 的令牌通过（角色来自 JWT claim，由签发时写入）
 * 不用 hasRole('ADMIN') 内置表达式，因为鉴权关闭态下 SecurityContext 为空会一律拒绝。
 */
@Component("accessGuard")
@RequiredArgsConstructor
public class AccessGuard {

    private final CurrentUserResolver currentUserResolver;

    /** 当前请求是否具备管理员权限（供 @PreAuthorize SpEL 调用） */
    @SuppressWarnings("unused") // 经 SpEL 按名调用：@accessGuard.isAdmin()
    public boolean isAdmin() {
        if (!currentUserResolver.isAuthEnabled()) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtToken && jwtToken.getToken() != null) {
            return SysUser.ROLE_ADMIN.equals(jwtToken.getToken().getClaimAsString(JwtService.CLAIM_ROLE));
        }
        return false;
    }
}
