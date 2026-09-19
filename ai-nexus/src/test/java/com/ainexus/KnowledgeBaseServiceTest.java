package com.ainexus;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.KnowledgeDocumentRepository;
import com.ainexus.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库服务单元测试：Collection 名规范化、扩展名校验、Chunk ID 确定性、级联删除。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository kbRepository;

    @Mock
    private KnowledgeDocumentRepository docRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private com.ainexus.ai.rag.RetrievalCacheService retrievalCache;

    @Mock
    private org.redisson.api.RedissonClient redissonClient;

    @Mock
    private com.ainexus.common.metrics.BusinessMetrics metrics;

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        service = new KnowledgeBaseService(kbRepository, docRepository, vectorStore, retrievalCache,
                redissonClient, metrics);
        // 分布式锁：单测环境直接放行
        org.redisson.api.RLock lock = org.mockito.Mockito.mock(org.redisson.api.RLock.class);
        org.mockito.Mockito.when(redissonClient.getLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(lock);
        org.mockito.Mockito.when(lock.tryLock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @Test
    void shouldNormalizeChineseNameToAsciiSafeCollectionName() {
        when(kbRepository.existsByName("产品手册")).thenReturn(false);
        when(kbRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeBase kb = service.create("产品手册", "测试", null);

        // Milvus Collection 名仅允许字母/数字/下划线：不允许中文，且为 kb_+md5前8位+规范化名
        String collectionName = kb.getCollectionName();
        assertTrue(collectionName.matches("kb_[0-9a-f]{8}_+"), "非法 Collection 名: " + collectionName);
        assertTrue(collectionName.startsWith("kb_") && !collectionName.isBlank());
    }

    @Test
    void shouldRejectUnsupportedExtension() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(KnowledgeBase.builder().id(1L).name("kb1").build()));
        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", "binary".getBytes());

        BizException e = assertThrows(BizException.class, () -> service.uploadDocument(1L, pdf, null));
        assertEquals(ErrorCode.DOC_PARSE_ERROR, e.getErrorCode());
    }

    @Test
    void shouldIngestWithDeterministicChunkIdAndMetadata() {
        KnowledgeBase kb = KnowledgeBase.builder().id(1L).name("kb1").build();
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(docRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile txt = new MockMultipartFile("file", "a.txt", "text/plain",
                "Spring AI 是 AI 应用框架。".getBytes(StandardCharsets.UTF_8));

        KnowledgeDocument doc = service.uploadDocument(1L, txt, null);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> docs = captor.getValue();
        assertEquals(1, docs.size());
        // Milvus 主键 VarChar(36)：Chunk ID 必须是标准 36 位 UUID
        String chunkId = docs.get(0).getId();
        assertEquals(36, chunkId.length());
        assertDoesNotThrow(() -> UUID.fromString(chunkId));
        // 元数据必须带知识库名（检索过滤依赖）
        assertEquals("kb1", docs.get(0).getMetadata().get("knowledge_base_name"));
        assertEquals(1, doc.getChunkCount());
        assertEquals(1, doc.getStatus());
    }

    @Test
    void shouldDeleteVectorsByDeterministicChunkIds() {
        KnowledgeBase kb = KnowledgeBase.builder().id(1L).name("kb1").build();
        String docUuid = "11111111-2222-3333-4444-555555555555";
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(docRepository.findByKbIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(
                KnowledgeDocument.builder().kbId(1L).docUuid(docUuid).chunkCount(2).build()));

        service.delete(1L, null);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(captor.capture());
        List<String> ids = captor.getValue();
        assertEquals(2, ids.size());
        // 删除时按入库相同规则重算确定性 Chunk ID
        assertEquals(UUID.nameUUIDFromBytes((docUuid + ":0").getBytes(StandardCharsets.UTF_8)).toString(), ids.get(0));
        assertEquals(UUID.nameUUIDFromBytes((docUuid + ":1").getBytes(StandardCharsets.UTF_8)).toString(), ids.get(1));
        verify(docRepository).deleteAll(anyList());
        verify(kbRepository).delete(kb);
    }
}
