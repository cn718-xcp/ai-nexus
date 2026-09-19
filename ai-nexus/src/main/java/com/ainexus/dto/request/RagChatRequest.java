package com.ainexus.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RAG 问答请求。
 */
@Data
public class RagChatRequest {

    /** 会话 ID，为空则新建会话 */
    private String sessionId;

    @NotBlank(message = "问题不能为空")
    private String question;

    /** 指定知识库名称，为空则全库检索 */
    private String knowledgeBaseName;

    @Min(1)
    @Max(20)
    private int topK = 5;

    /** 多模型路由场景（可选，默认 RAG） */
    private String scene;
}
