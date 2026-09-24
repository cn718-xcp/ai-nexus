package com.ainexus;

import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.KnowledgeChunkRepository;
import com.ainexus.domain.repository.KnowledgeDocumentRepository;
import com.ainexus.service.KnowledgeBackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库备份服务单元测试：导出结构、导入重建（新 docUuid 与确定性 Chunk ID）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBackupServiceTest {

    @Mock
    private KnowledgeBaseRepository kbRepository;

    @Mock
    private KnowledgeDocumentRepository docRepository;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private com.ainexus.ai.rag.RetrievalCacheService retrievalCache;

    private KnowledgeBackupService service;

    private final KnowledgeBase kb = KnowledgeBase.builder()
            .id(1L).name("src-kb").collectionName("kb_x").status(1).build();

    @BeforeEach
    void setUp() {
        service = new KnowledgeBackupService(kbRepository, docRepository, chunkRepository, vectorStore, retrievalCache);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(docRepository.findByKbIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(
                KnowledgeDocument.builder().kbId(1L).fileName("a.txt").docUuid("uuid-1").chunkCount(2).build()));
        when(chunkRepository.findByKbIdOrderByDocUuidAscChunkIdxAsc(1L)).thenReturn(List.of(
                com.ainexus.domain.entity.KnowledgeChunk.builder().kbId(1L).docUuid("uuid-1").chunkIdx(0).content("片段零").build(),
                com.ainexus.domain.entity.KnowledgeChunk.builder().kbId(1L).docUuid("uuid-1").chunkIdx(1).content("片段一").build()));
    }

    @Test
    void shouldExportFullPayload() {
        String json = service.export(1L);
        assertTrue(json.contains("src-kb"));
        assertTrue(json.contains("片段零") && json.contains("片段一"));
        assertTrue(json.contains("a.txt"));
    }

    @Test
    void shouldImportWithRebuiltVectorsAndBackup() {
        String json = service.export(1L);
        when(docRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeBase restored = service.importBackup(json, 9L, 7L);

        assertEquals("src-kb-restored", restored.getName());
        assertEquals(9L, restored.getUserId());
        assertEquals(7L, restored.getTenantId());
        // 向量重建：2 个 Chunk 全部重新入库
        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals(2, captor.getValue().size());
        // Chunk ID 为确定性 UUID（36 位）
        assertEquals(36, captor.getValue().get(0).getId().length());
    }
}
