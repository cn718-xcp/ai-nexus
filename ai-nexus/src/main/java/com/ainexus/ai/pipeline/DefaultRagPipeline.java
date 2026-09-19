package com.ainexus.ai.pipeline;

import com.ainexus.ai.memory.ChatHistoryService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * 默认 RAG 流水线实现。
 */
@Component
public class DefaultRagPipeline extends AbstractRagPipeline {

    public DefaultRagPipeline(VectorStore vectorStore, ChatHistoryService chatHistoryService) {
        super(vectorStore, chatHistoryService);
    }
}
