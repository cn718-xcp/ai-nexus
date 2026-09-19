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
    private boolean success;
    private long costMs;
    private Long promptTokens;
    private Long completionTokens;
    private boolean fallback;
    private String errorMessage;
}
