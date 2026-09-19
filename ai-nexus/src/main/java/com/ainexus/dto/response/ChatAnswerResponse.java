package com.ainexus.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话响应：sessionId 为服务端最终会话 ID（客户端未传时由服务端生成并回传）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatAnswerResponse {

    private String sessionId;
    private String answer;
}
