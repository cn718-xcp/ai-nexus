package com.ainexus.config;

import com.ainexus.common.auth.JwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置：双模式互斥切换，保证向后兼容。
 * - auth.enabled=false（dev 默认）：放行全部请求，行为与无鉴权时期完全一致
 * - auth.enabled=true：JWT Resource Server 无状态鉴权，仅放行认证/健康/文档端点
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 白名单：认证接口、健康探活、Actuator、Swagger 文档 */
    private static final String[] PUBLIC_URIS = {
            "/api/v1/auth/**", "/health", "/actuator/**",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /**
     * 鉴权关闭态：全放行（保持原有调试体验），仅关闭 CSRF 等默认行为。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ainexus.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * 鉴权开启态：JWT 无状态鉴权（Resource Server），业务接口一律要求有效令牌。
     * 解码器复用 JwtService 同源密钥，保证签发与校验配置唯一。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ainexus.auth", name = "enabled", havingValue = "true")
    public SecurityFilterChain jwtFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_URIS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtService.decoder())))
                .build();
    }

    /** BCrypt 密码编码器（注册/登录共用） */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
