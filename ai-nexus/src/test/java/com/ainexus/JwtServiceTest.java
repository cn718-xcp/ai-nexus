package com.ainexus;

import com.ainexus.common.auth.JwtProperties;
import com.ainexus.common.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 服务单元测试：签发/解析回环、uid claim、过期校验、跨密钥校验失败。
 */
class JwtServiceTest {

    private JwtService jwtService;
    private JwtService otherKeyService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("unit-test-secret-key-for-ai-nexus-jwt-0123456789abcdef");
        props.setAccessTokenMinutes(30);
        jwtService = new JwtService(props);

        JwtProperties otherProps = new JwtProperties();
        otherProps.setSecret("another-secret-key-9876543210-ai-nexus-unit-test");
        otherKeyService = new JwtService(otherProps);
    }

    @Test
    void shouldRoundTripAccessTokenWithUidClaim() {
        String token = jwtService.issueAccessToken("alice", 42L);
        Map<String, Object> claims = jwtService.parse(token);

        assertEquals("alice", claims.get("sub"));
        assertEquals(42, ((Number) claims.get(JwtService.CLAIM_UID)).intValue());
        assertEquals("access", claims.get(JwtService.CLAIM_TYPE));
    }

    @Test
    void shouldMarkRefreshTokenType() {
        Map<String, Object> claims = jwtService.parse(jwtService.issueRefreshToken("bob", 7L));
        assertEquals("refresh", claims.get(JwtService.CLAIM_TYPE));
    }

    @Test
    void shouldRejectExpiredToken() throws InterruptedException {
        // 50ms 有效期：解码器时钟偏差为 0，等待后必须校验失败（过期校验生效）
        String token = jwtService.issue("carol", 1L, "access", Duration.ofMillis(50));
        Thread.sleep(300);
        assertThrows(org.springframework.security.oauth2.jwt.BadJwtException.class, () -> jwtService.parse(token));
    }

    @Test
    void shouldRejectTokenSignedByDifferentSecret() {
        String token = jwtService.issueAccessToken("alice", 1L);
        assertThrows(Exception.class, () -> otherKeyService.parse(token));
    }
}
