package com.ainexus.common.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ainexus.jwt")
public class JwtProperties {

    /** HS256 签名密钥，生产必须通过 JWT_SECRET 环境变量注入强随机值 */
    private String secret = "change-me-in-production-ai-nexus-jwt-secret-key-2026";

    /** 访问令牌有效期（分钟） */
    private int accessTokenMinutes = 30;

    /** 刷新令牌有效期（天） */
    private int refreshTokenDays = 7;
}
