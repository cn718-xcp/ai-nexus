package com.ainexus;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.pipeline.AbstractRagPipeline;
import com.ainexus.ai.pipeline.RagContext;
import com.ainexus.domain.entity.ChatMessageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 流水线（模板方法模式）单元测试：检索参数、过滤表达式、提示词组装。
 */
@ExtendWith(MockitoExtension.class)
class RagPipelineTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatHistoryService chatHistoryService;

    private AbstractRagPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new AbstractRagPipeline(vectorStore, chatHistoryService) {
        };
    }

    @Test
    void shouldAssemblePromptWithRetrievedChunks() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("片段一：Spring AI 是 AI 应用框架"),
                new Document("片段二：Milvus 是向量数据库")));
        when(chatHistoryService.loadWindow("s1", null, null)).thenReturn(List.of(
                ChatMessageRecord.builder().sessionId("s1").role("user").content("你好").build()));

        RagContext ctx = pipeline.run(RagContext.builder()
                .question("什么是 Spring AI？")
                .sessionId("s1")
                .knowledgeBaseName("测试库")
                .topK(5)
                .build());

        // 提示词包含：历史、参考资料、用户问题
        assertTrue(ctx.getFinalPrompt().contains("历史对话"));
        assertTrue(ctx.getFinalPrompt().contains("片段一"));
        assertTrue(ctx.getFinalPrompt().contains("什么是 Spring AI？"));
        assertEquals(2, ctx.getChunks().size());
        // 校验检索请求携带知识库过滤与 topK
        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertEquals(5, request.getTopK());
        Filter.Expression filter = request.getFilterExpression();
        assertNotNull(filter, "按知识库名过滤的表达式不应为空");
    }

    @Test
    void shouldHandleEmptyRetrieval() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagContext ctx = pipeline.run(RagContext.builder()
                .question("未知问题").sessionId(null).knowledgeBaseName(null).topK(3).build());

        assertTrue(ctx.getChunks().isEmpty());
        assertTrue(ctx.getFinalPrompt().contains("未检索到相关资料"));
    }

    @Test
    void shouldFilterByOwnerWhenUserIdPresent() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        // 鉴权开启：即使 kbName 为空（全库检索），也必须带 user_id 过滤，防止跨租户读取
        pipeline.run(RagContext.builder()
                .question("什么是 Spring AI？").knowledgeBaseName(null).topK(5).userId(42L).build());

        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        Filter.Expression filter = captor.getValue().getFilterExpression();
        assertNotNull(filter, "鉴权开启时全库检索也必须携带归属过滤");
        assertTrue(filter.toString().contains("user_id"), "过滤表达式应限定 user_id，实际: " + filter);
    }
}
