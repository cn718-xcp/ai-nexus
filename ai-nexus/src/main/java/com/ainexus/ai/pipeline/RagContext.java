package com.ainexus.ai.pipeline;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 流水线中间上下文对象：各阶段之间传递。
 */
@Getter
@Setter
@Builder
public class RagContext {

    /** 原始用户问题 */
    private final String question;

    /** 会话 ID */
    private final String sessionId;

    /** 命中的知识库名称（可能为空：全库检索） */
    private final String knowledgeBaseName;

    /** 检索 topK */
    private final int topK;

    /** 用户隔离维度（鉴权开启时传递；关闭态为 null） */
    private final Long userId;

    /** 重写后的查询（默认等于原始问题） */
    private String rewrittenQuery;

    /** 检索到的文档片段文本 */
    private List<String> chunks;

    /** 检索命中的原始文档（含 score/metadata），供引用精准定位与重排使用 */
    private List<Document> documents;

    /** 最终组装好的提示词 */
    private String finalPrompt;
}
