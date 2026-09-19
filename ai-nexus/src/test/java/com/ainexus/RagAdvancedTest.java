package com.ainexus;

import com.ainexus.ai.rag.EmbeddingReranker;
import com.ainexus.ai.rag.HybridRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RAG 检索进阶单元测试：Embedding 重排、RRF 混合融合、分词。
 */
@ExtendWith(MockitoExtension.class)
class RagAdvancedTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private final HybridRetriever hybrid = new HybridRetriever();

    @Test
    void shouldRerankByCosineSimilarity() {
        Document d1 = new Document("id-1", "Milvus is a vector database", java.util.Map.of());
        Document d2 = new Document("id-2", "totally unrelated content", java.util.Map.of());
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});
        // 按文本内容返回向量：含 Milvus 的片段与查询同向
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(t -> t.contains("Milvus") ? new float[]{1f, 0f, 0f} : new float[]{0f, 1f, 0f})
                    .toList();
        });

        EmbeddingReranker reranker = new EmbeddingReranker(embeddingModel);
        List<Document> result = reranker.rerank("what is milvus", List.of(d2, d1), 2);

        assertEquals("id-1", result.get(0).getId(), "重排后应将语义最相关的片段排到首位");
    }

    @Test
    void shouldFuseDenseAndSparseRankingWithRrf() {
        // dense: dA > dB > dC；查询 "milvus spring" 与 dC 字面命中最强
        Document dA = new Document("a", "Spring AI framework introduction", java.util.Map.of());
        Document dB = new Document("b", "Spring AI supports RAG", java.util.Map.of());
        Document dC = new Document("c", "Milvus vector database spring milvus", java.util.Map.of());
        List<Document> fused = hybrid.fuse(List.of(dA, dB, dC), "milvus spring", 1, 0.0);

        assertEquals(3, fused.size());
        // RRF 融合后：dC（字面最强）从 dense 第 3 越过 dB 升至第 2；dA 语义+字面双命中仍居首
        assertEquals("a", fused.get(0).getId());
        assertEquals("c", fused.get(1).getId(), "关键词强命中的片段应经 RRF 融合上升");
        assertEquals("b", fused.get(2).getId());
    }

    @Test
    void shouldBoostEarlierChunksWithPositionWeight() {
        // 相同 RRF 分时，位置权重应偏好 chunk_index 靠前的片段
        var metaA = new java.util.HashMap<String, Object>(java.util.Map.of("chunk_index", 0));
        var metaB = new java.util.HashMap<String, Object>(java.util.Map.of("chunk_index", 5));
        Document dA = new Document("a", "alpha content", metaA);
        Document dB = new Document("b", "alpha content", metaB);
        List<Document> fused = hybrid.fuse(List.of(dA, dB), "alpha", 1, 0.5);
        assertEquals("a", fused.get(0).getId(), "位置权重应提升文档前部片段");
    }

    @Test
    void shouldTokenizeMixedLanguageAndDropStopWords() {
        var terms = hybrid.tokenize("What is Milvus 的用途?");
        assertTrue(terms.contains("milvus"));
        assertTrue(terms.contains("用") && terms.contains("途"));
        assertTrue(!terms.contains("what") && !terms.contains("的"), "停用词应被剔除");
    }
}
