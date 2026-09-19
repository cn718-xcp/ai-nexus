package com.ainexus.ai.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 调用事件：由各服务在调用完成后发布，异步落库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCallEvent {

    private String scene;
    private String modelName;
    private String sessionId;
    /** 用户隔离维度（鉴权开启时有值） */
    private Long userId;
    /** 租户隔离维度（多租户开启时有值） */
    private Long tenantId;
    private boolean success;
    private long costMs;
    private Long promptTokens;
    private Long completionTokens;
    private boolean fallback;
    private String errorMessage;
}
