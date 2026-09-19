package com.ainexus;

import com.ainexus.ai.chunk.ParentChildChunker;
import com.ainexus.ai.rag.DocumentIngestor;
import com.ainexus.ai.rag.RagProperties;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;

/**
 * 分块策略与统一入库器测试：父子切分结构、入库元数据（偏移/章节）、子块向量化。
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestorTest {

    @Mock
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private EmbeddingModel embeddingModel;

    private final KnowledgeBase kb = KnowledgeBase.builder().id(1L).name("kb1").build();

    @Test
    void shouldSplitParentChildStructure() {
        // 400 字父块阈值下的小文本：1 父块多子块
        ParentChildChunker chunker = new ParentChildChunker(1600, 400, 50);
        String text = "段落一。".repeat(120);
        List<ParentChildChunker.ParentChild> parents = chunker.split(text);
        assertTrue(parents.size() >= 1);
        assertNotNull(parents.get(0).getParentText());
        // 每个子块偏移必须落在父块范围内
        parents.get(0).getChildren().forEach(c ->
                assertTrue(c.offsetInParent() < parentSize(parents.get(0))));
    }

    private int parentSize(ParentChildChunker.ParentChild pc) {
        return pc.getParentText().length();
    }

    @Test
    void shouldIngestFixedStrategyWithTraceMetadata() {
        RagProperties props = new RagProperties();
        props.setChunkStrategy("fixed");
        DocumentIngestor ingestor = new DocumentIngestor(props, embeddingModel,
                new DocumentIngestor.VectorStoreHolder(vectorStore), chunkRepository);

        String text = "# 第一章 概述\nSpring AI 是框架。Milvus 是向量库。";
        var result = ingestor.ingest(kb, "a.md", text);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document first = captor.getValue().get(0);
        // 溯源元数据：字符起止偏移与章节标题
        assertEquals(36, first.getId().length());
        assertTrue(first.getMetadata().get("char_start") instanceof Number);
        assertTrue((int) first.getMetadata().get("char_end") > (int) first.getMetadata().get("char_start"));
        assertEquals("# 第一章 概述", first.getMetadata().get("section"));
        assertEquals("fixed", result.resolvedStrategy());
        // 固定策略下 Chunk 备份落库
        verify(chunkRepository).saveAll(anyList());
    }

    @Test
    void shouldIngestParentChildWithChildMetadata() {
        RagProperties props = new RagProperties();
        props.setChunkStrategy("parent_child");
        props.setParentSize(300);
        props.setChildSize(120);
        DocumentIngestor ingestor = new DocumentIngestor(props, embeddingModel,
                new DocumentIngestor.VectorStoreHolder(vectorStore), chunkRepository);

        String text = ("知识块内容。").repeat(60);
        var result = ingestor.ingest(kb, "b.txt", text);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document child = captor.getValue().get(0);
        // 子块标记与父块序号：召回扩展的依据
        assertEquals(Boolean.TRUE, child.getMetadata().get("is_child"));
        assertTrue(child.getMetadata().get("chunk_index") instanceof Number);
        assertTrue(child.getMetadata().get("child_index") instanceof Number);
        assertEquals("parent_child", result.resolvedStrategy());
        // 父块原文备份落库（召回扩展数据源）
        verify(chunkRepository).saveAll(anyList());
    }
}
