package com.ainexus;

import com.ainexus.common.auth.AccessGuard;
import com.ainexus.common.auth.CurrentUserResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理面守卫单元测试：鉴权关闭直通 / 开启态按令牌 role claim 判定 / 无令牌拒绝。
 */
class AccessGuardTest {

    private AccessGuard guard;
    private CurrentUserResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new CurrentUserResolver();
        guard = new AccessGuard(resolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthEnabled(boolean enabled) throws Exception {
        java.lang.reflect.Field f = CurrentUserResolver.class.getDeclaredField("authEnabled");
        f.setAccessible(true);
        f.setBoolean(resolver, enabled);
    }

    private void loginWithRole(String role) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "HS256"), Map.of("sub", "alice", "role", role));
        AbstractAuthenticationToken auth = new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldPermitAllWhenAuthDisabled() throws Exception {
        setAuthEnabled(false);
        // 关闭态无任何 SecurityContext 也放行（与无鉴权时期行为一致）
        assertTrue(guard.isAdmin());
    }

    @Test
    void shouldRejectWhenAuthEnabledAndNoAuthentication() throws Exception {
        setAuthEnabled(true);
        assertFalse(guard.isAdmin());
    }

    @Test
    void shouldRejectNonAdminRole() throws Exception {
        setAuthEnabled(true);
        loginWithRole("USER");
        assertFalse(guard.isAdmin());
    }

    @Test
    void shouldAcceptAdminRole() throws Exception {
        setAuthEnabled(true);
        loginWithRole("ADMIN");
        assertTrue(guard.isAdmin());
    }
}
