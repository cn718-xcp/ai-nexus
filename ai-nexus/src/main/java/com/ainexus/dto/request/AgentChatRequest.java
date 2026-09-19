package com.ainexus.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent（工具调用）请求。
 */
@Data
public class AgentChatRequest {

    private String sessionId;

    @NotBlank(message = "指令不能为空")
    private String prompt;
}
