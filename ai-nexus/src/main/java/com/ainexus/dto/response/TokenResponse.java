package com.ainexus.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 令牌响应：access 用于业务鉴权，refresh 用于无感换发。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresIn;
}
