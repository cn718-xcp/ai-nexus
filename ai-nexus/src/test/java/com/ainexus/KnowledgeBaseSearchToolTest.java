package com.ainexus;

import com.ainexus.ai.tools.KnowledgeBaseSearchTool;
import com.ainexus.common.auth.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索工具隔离测试：鉴权开启时按用户过滤，模型可控的 kbName 参数无法越权读他人库。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseSearchToolTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private CurrentUserResolver currentUserResolver;

    private KnowledgeBaseSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeBaseSearchTool(vectorStore, currentUserResolver);
    }

    @Test
    void shouldScopeSearchByUserWhenAuthEnabled() {
        when(currentUserResolver.currentUserId()).thenReturn(42L);
        when(currentUserResolver.currentTenantId()).thenReturn(null);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        // 传 "all"（全库检索）也必须带 user_id 过滤
        tool.searchKnowledgeBase("问题", "all");

        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        var filter = captor.getValue().getFilterExpression();
        assertNotNull(filter, "鉴权开启时工具检索必须携带归属过滤");
        assertTrue(filter.toString().contains("user_id"), "应限定 user_id，实际: " + filter);
    }

    @Test
    void shouldCombineKbNameAndOwnerFilter() {
        when(currentUserResolver.currentUserId()).thenReturn(42L);
        when(currentUserResolver.currentTenantId()).thenReturn(null);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("片段", Map.of("file_name", "a.txt"))));

        tool.searchKnowledgeBase("问题", "产品手册");

        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        var filter = captor.getValue().getFilterExpression();
        assertNotNull(filter);
        String expr = filter.toString();
        assertTrue(expr.contains("knowledge_base_name") && expr.contains("user_id"),
                "应同时限定知识库名与 user_id，实际: " + expr);
    }
}
