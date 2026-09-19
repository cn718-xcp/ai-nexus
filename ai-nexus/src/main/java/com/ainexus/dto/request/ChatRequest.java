package com.ainexus.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 普通对话请求。
 */
@Data
public class ChatRequest {

    private String sessionId;

    @NotBlank(message = "消息不能为空")
    private String message;
}
