package com.ainexus;

import com.ainexus.common.auth.JwtProperties;
import com.ainexus.common.auth.JwtService;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysUser;
import com.ainexus.domain.repository.SysUserRepository;
import com.ainexus.dto.request.LoginRequest;
import com.ainexus.dto.request.RegisterRequest;
import com.ainexus.dto.response.TokenResponse;
import com.ainexus.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试：注册查重、登录校验、统一错误话术、刷新令牌。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private SysUserRepository userRepository;

    private AuthService authService;
    private JwtService jwtService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final SysUser user = SysUser.builder()
            .id(1L).username("alice")
            .passwordHash(new BCryptPasswordEncoder().encode("password123"))
            .build();

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties props = new JwtProperties();
        props.setSecret("unit-test-secret-key-for-ai-nexus-jwt-0123456789abcdef");
        props.setAccessTokenMinutes(30);
        jwtService = new JwtService(props);
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
        // @Value 字段在纯单测中不注入，手动置值
        Field f = AuthService.class.getDeclaredField("accessTokenMinutes");
        f.setAccessible(true);
        f.set(authService, 30);
    }

    @Test
    void shouldRegisterAndIssueTokensOnLogin() {
        RegisterRequest register = new RegisterRequest();
        register.setUsername("newuser");
        register.setPassword("password123");
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.save(any())).thenReturn(SysUser.builder().id(9L).username("newuser").build());
        assertEquals(9L, authService.register(register));

        LoginRequest login = new LoginRequest();
        login.setUsername("alice");
        login.setPassword("password123");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        TokenResponse tokens = authService.login(login);
        assertNotNull(tokens.getAccessToken());
        assertNotNull(tokens.getRefreshToken());
        assertEquals(1800L, tokens.getExpiresIn());
        // access token 可解析出 uid
        assertEquals(1, ((Number) jwtService.parse(tokens.getAccessToken()).get("uid")).intValue());
    }

    @Test
    void shouldRejectDuplicateUsername() {
        RegisterRequest register = new RegisterRequest();
        register.setUsername("alice");
        register.setPassword("password123");
        when(userRepository.existsByUsername("alice")).thenReturn(true);
        assertThrows(BizException.class, () -> authService.register(register));
    }

    @Test
    void shouldRejectWrongPasswordWithUnifiedMessage() {
        LoginRequest login = new LoginRequest();
        login.setUsername("alice");
        login.setPassword("wrong-password");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        // 统一话术防止用户名枚举
        BizException e = assertThrows(BizException.class, () -> authService.login(login));
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void shouldRefreshByRefreshTokenOnly() {
        String refreshToken = jwtService.issueRefreshToken("alice", 1L);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        TokenResponse tokens = authService.refresh(refreshToken);
        assertNotNull(tokens.getAccessToken());

        // access 令牌不能当 refresh 用（token_type 校验）
        String accessToken = jwtService.issueAccessToken("alice", 1L);
        assertThrows(BizException.class, () -> authService.refresh(accessToken));
    }
}
