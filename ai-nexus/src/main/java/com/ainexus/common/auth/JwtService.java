package com.ainexus.common.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Map;

/**
 * JWT 签发与校验服务：基于 Spring Security 内置 Nimbus 实现（对称 HS256），
 * 不引入第三方 JWT 依赖。双令牌模型：access（短效鉴权）+ refresh（长效换发）。
 */
@Service
public class JwtService {

    /** 自定义 claim：用户 ID（数据隔离维度） */
    public static final String CLAIM_UID = "uid";
    /** 自定义 claim：令牌类型（access/refresh），refresh 接口据此校验 */
    public static final String CLAIM_TYPE = "token_type";

    private final JwtProperties properties;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        SecretKey key = secretKey();
        // 必须显式声明签名算法与用途，NimbusJwtEncoder 才能按 JWS 头匹配到该 JWK
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new JWKSet(new OctetSequenceKey.Builder(key)
                        .keyID("ai-nexus-hs256")
                        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                        .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
                        .build()));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        // 时钟偏差 0：严格过期校验（多实例部署建议 NTP 对时）
        this.decoder = NimbusJwtDecoder.withSecretKey(key)
                .jwtProcessorCustomizer(processor -> {
                    com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier<com.nimbusds.jose.proc.SecurityContext> verifier =
                            new com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier<>();
                    verifier.setMaxClockSkew(0);
                    processor.setJWTClaimsSetVerifier(verifier);
                })
                .build();
    }

    /**
     * 签发令牌。
     *
     * @param username 主题（用户名）
     * @param uid      用户 ID claim
     * @param type     access / refresh
     * @param ttl      有效期
     */
    public String issue(String username, Long uid, String type, java.time.Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(CLAIM_TYPE, type);
        if (uid != null) {
            claims.claim(CLAIM_UID, uid);
        }
        // 显式指定 JWS 头：编码器按算法匹配 JWK，缺省头会导致选择签名密钥失败
        org.springframework.security.oauth2.jwt.JwsHeader jwsHeader =
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                        .keyId("ai-nexus-hs256")
                        .build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims.build())).getTokenValue();
    }

    public String issueAccessToken(String username, Long uid) {
        return issue(username, uid, "access", java.time.Duration.ofMinutes(properties.getAccessTokenMinutes()));
    }

    public String issueRefreshToken(String username, Long uid) {
        return issue(username, uid, "refresh", java.time.Duration.ofDays(properties.getRefreshTokenDays()));
    }

    /** 解析并校验令牌（签名 + 过期），失败抛 JwtValidationException/InvalidBearerToken 等 */
    public Map<String, Object> parse(String token) {
        return decoder.decode(token).getClaims();
    }

    /** 暴露同源解码器供 Resource Server 过滤链复用（签发/校验密钥唯一） */
    public JwtDecoder decoder() {
        return decoder;
    }

    private SecretKey secretKey() {
        // HS256 要求密钥长度 >= 256bit；不足时左填充说明密钥配置过弱
        byte[] bytes = properties.getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new javax.crypto.spec.SecretKeySpec(bytes, "HmacSHA256");
    }
}
