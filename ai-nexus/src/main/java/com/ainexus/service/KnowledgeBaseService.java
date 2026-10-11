package com.ainexus.service;

import com.ainexus.ai.chunk.TextChunker;
import com.ainexus.ai.rag.RetrievalCacheService;
import com.ainexus.common.audit.Audited;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import cn.hutool.crypto.digest.DigestUtil;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.entity.KnowledgeChunk;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.KnowledgeChunkRepository;
import com.ainexus.domain.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理：知识库 CRUD + 文档上传/切分/向量化入库 + 向量删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeDocumentRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    private final RetrievalCacheService retrievalCache;
    private final org.redisson.api.RedissonClient redissonClient;
    private final com.ainexus.common.metrics.BusinessMetrics metrics;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;
    private final com.ainexus.ai.rag.DocumentIngestor documentIngestor;
    /** 显式事务模板：锁边界在事务外、事务范围收敛到实际写库段（避免锁等待与向量化 IO 长期占用 DB 连接） */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    @Audited(action = "KB_CREATE", target = "#name")
    @Transactional
    public KnowledgeBase create(String name, String description, Long userId, Long tenantId) {
        if (kbRepository.existsByName(name)) {
            throw new BizException(ErrorCode.BIZ_ERROR, "知识库名称已存在: " + name);
        }
        // 多租户开启时校验租户状态与知识库数量配额（关闭态直通）
        tenantQuotaService.ensureKbCreateAllowed(tenantId);
        // Milvus Collection 名仅允许字母/数字/下划线：中文等字符需剔除，
        // 追加名称 MD5 前 8 位保证不同名称（如 "a_b" 与 "a b"）规范化后仍唯一
        String normalized = name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(name)
                .description(description)
                .collectionName("kb_" + DigestUtil.md5Hex(name).substring(0, 8) + "_" + normalized)
                .status(1)
                .userId(userId)
                .tenantId(tenantId)
                .build();
        KnowledgeBase saved = kbRepository.save(kb);
        metrics.onKbChanged(kbRepository.count());
        return saved;
    }

    /**
     * 知识库列表：userId 为 null（鉴权关闭）返回全部；开启后仅返回该用户的库（数据隔离）。
     */
    /**
     * 知识库列表隔离语义（从强到弱取首个命中）：
     * 租户+用户 二级隔离 > 用户隔离 > 全量（所有开关关闭态）。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeBase> list(Long userId, Long tenantId) {
        if (tenantId != null && userId != null) {
            return kbRepository.findByTenantIdAndUserId(tenantId, userId);
        }
        if (userId != null) {
            return kbRepository.findByUserId(userId);
        }
        return kbRepository.findAll();
    }

    /**
     * 归属校验：鉴权开启时校验知识库属于当前用户，防止越权读写（统一返回 NOT_FOUND 防探测）。
     * 对外的复用入口（如备份导出），避免各调用方自行实现而漏校验。
     */
    public KnowledgeBase requireOwnedKb(Long kbId, Long userId, Long tenantId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KB_NOT_FOUND));
        // 二级隔离：租户维度不匹配或用户维度不匹配一律 404 防探测
        if (userId != null && !userId.equals(kb.getUserId())) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
        if (tenantId != null && kb.getTenantId() != null && !tenantId.equals(kb.getTenantId())) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
        return kb;
    }

    /** 支持的纯文本文档扩展名（二进制格式按 UTF-8 强读会产生乱码向量，必须在入口拦截） */
    private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of("txt", "md", "json", "csv");

    /**
     * 上传文档：扩展名校验 -> 解析 -> 切分 -> 带元数据向量化入库（knowledge_base_name / doc_id 元数据用于检索过滤与删除）。
     * Redisson 分布式锁按 kbId 串行化，防止并发上传导致向量与元数据不一致。
     * 锁在事务外获取：等锁与 embedding 网络调用不占用 DB 连接，且 unlock 发生在事务提交之后（锁窗口覆盖提交点）。
     */
    @Audited(action = "DOC_UPLOAD", target = "#file?.originalFilename")
    public KnowledgeDocument uploadDocument(Long kbId, MultipartFile file, Long userId, Long tenantId) {
        KnowledgeBase kb = requireOwnedKb(kbId, userId, tenantId);
        validateExtension(file);
        return withKbLock(kbId, () -> txTemplate.execute(status -> doUpload(kb, file)));
    }

    /** 分布式锁辅助：按 kbId 串行化知识库级操作，锁在事务外获取（等锁不占 DB 连接），仅在仍持有时释放 */
    private <T> T withKbLock(Long kbId, java.util.function.Supplier<T> action) {
        org.redisson.api.RLock lock = redissonClient.getLock("ainexus:lock:kb:" + kbId);
        boolean locked = false;
        try {
            // 等待 30s / 自动释放 120s（防止持有者宕机死锁）
            locked = lock.tryLock(30, 120, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ErrorCode.BIZ_ERROR, "该知识库正在处理其他操作，请稍后再试");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.BIZ_ERROR, "获取知识库锁被中断");
        } finally {
            // 租约（120s）到期后 Redisson 会自动释放锁，此时再 unlock 会抛 IllegalMonitorStateException，
            // 把一次成功/已提交的操作变成 500 并掩盖真实结果；仅在确认仍持有时释放
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private KnowledgeDocument doUpload(KnowledgeBase kb, MultipartFile file) {
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.DOC_PARSE_ERROR, "读取文件失败: " + e.getMessage());
        }
        if (text.isBlank()) {
            throw new BizException(ErrorCode.DOC_PARSE_ERROR, "文档内容为空");
        }

        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? UUID.randomUUID().toString() : file.getOriginalFilename();

        // 统一入库器：分块策略（fixed/parent_child/semantic/auto）+ 溯源元数据 + 向量化 + Chunk 备份
        var result = documentIngestor.ingest(kb, fileName, text);

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .kbId(kb.getId())
                .fileName(fileName)
                .docUuid(result.docUuid())
                .charCount((long) text.length())
                .chunkCount(result.chunkCount())
                .status(1)
                .build();
        log.info("[kb] document ingested, kb={}, file={}, chunks={}, strategy={}",
                kb.getName(), doc.getFileName(), result.chunkCount(), result.resolvedStrategy());
        KnowledgeDocument saved = docRepository.save(doc);
        // 缓存主动失效：该知识库的检索缓存版本自增
        retrievalCache.bumpVersion(kb.getName());
        return saved;
    }

    /** 扩展名白名单校验：双扩展名（如 a.pdf.txt 取 txt）按最后一个后缀判断 */
    private void validateExtension(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        int dotIndex = fileName == null ? -1 : fileName.lastIndexOf('.');
        String ext = dotIndex < 0 ? "" : fileName.substring(dotIndex + 1).toLowerCase();
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.DOC_PARSE_ERROR, "仅支持 " + SUPPORTED_EXTENSIONS + " 纯文本文档");
        }
    }

    /** 删除知识库：级联删除其全部向量与文档记录（锁在事务外获取，事务范围收敛到实际写库段）。 */
    @Audited(action = "KB_DELETE", target = "#kbId")
    public void delete(Long kbId, Long userId, Long tenantId) {
        KnowledgeBase kb = requireOwnedKb(kbId, userId, tenantId);
        withKbLock(kbId, () -> {
            txTemplate.executeWithoutResult(status -> {
                List<KnowledgeDocument> docs = docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
                for (KnowledgeDocument doc : docs) {
                    List<String> chunkIds = new ArrayList<>(doc.getChunkCount());
                    for (int i = 0; i < doc.getChunkCount(); i++) {
                        chunkIds.add(chunkId(doc.getDocUuid(), i));
                    }
                    // chunkCount 为 0 的脏数据会导致空列表删除调用异常，跳过
                    if (!chunkIds.isEmpty()) {
                        vectorStore.delete(chunkIds);
                    }
                }
                docRepository.deleteAll(docs);
                kbRepository.delete(kb);
                metrics.onKbChanged(kbRepository.count());
                log.info("[kb] knowledge base deleted, name={}, docs={}", kb.getName(), docs.size());
                retrievalCache.bumpVersion(kb.getName());
            });
            return null;
        });
    }

    /** 确定性 Chunk ID：与入库/删除共用同一规则（docUuid:idx 的 nameUUID） */
    private String chunkId(String docUuid, int idx) {
        return UUID.nameUUIDFromBytes((docUuid + ":" + idx).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 向量重建结果 */
    public record ReindexResult(Long kbId, String kbName, int docCount, int chunkCount, int skippedDocs) {
    }

    /**
     * 重建单个知识库向量：从 MySQL Chunk 备份重灌，补写 user_id/tenant_id 归属元数据。
     * 用途：存量向量（归属元数据缺失）迁移——鉴权开启后无归属元数据的向量会被归属过滤排除，
     * 必须重灌才能继续被检索。父子分块文档按父块原文重灌（子块粒度不保留，召回不受影响）。
     * 归属为空的库（鉴权关闭期创建）重灌后仍无归属元数据，仅鉴权关闭态可检索。
     */
    @Audited(action = "KB_REINDEX", target = "#kbId")
    public ReindexResult reindexVectors(Long kbId, Long userId, Long tenantId) {
        KnowledgeBase kb = requireOwnedKb(kbId, userId, tenantId);
        return withKbLock(kbId, () -> doReindex(kb));
    }

    /** 重建全部知识库向量（管理面入口，鉴权由控制器 AccessGuard 把关；逐库串行，单库失败不影响其余） */
    @Audited(action = "KB_REINDEX_ALL", target = "'all'")
    public List<ReindexResult> reindexAll() {
        List<ReindexResult> results = new ArrayList<>();
        for (KnowledgeBase kb : kbRepository.findAll()) {
            try {
                results.add(withKbLock(kb.getId(), () -> doReindex(kb)));
            } catch (Exception e) {
                log.warn("[kb-reindex] failed, kb={}, cause={}", kb.getName(), e.getMessage());
                results.add(new ReindexResult(kb.getId(), kb.getName(), 0, 0, 0));
            }
        }
        return results;
    }

    private ReindexResult doReindex(KnowledgeBase kb) {
        return txTemplate.execute(status -> {
            List<KnowledgeDocument> docs = docRepository.findByKbIdOrderByCreatedAtDesc(kb.getId());
            java.util.Map<String, List<KnowledgeChunk>> chunksByDoc = chunkRepository
                    .findByKbIdOrderByDocUuidAscChunkIdxAsc(kb.getId()).stream()
                    .collect(java.util.stream.Collectors.groupingBy(KnowledgeChunk::getDocUuid));
            int chunkCount = 0, skipped = 0, migrated = 0;
            for (KnowledgeDocument doc : docs) {
                List<KnowledgeChunk> chunks = chunksByDoc.getOrDefault(doc.getDocUuid(), List.of());
                if (chunks.isEmpty()) {
                    skipped++;
                    continue;
                }
                // 旧向量按确定性 ID 删除（与 delete() 同规则）；换新 docUuid 重灌，向量 ID 全部更新
                List<String> oldIds = new ArrayList<>(doc.getChunkCount());
                for (int i = 0; i < doc.getChunkCount(); i++) {
                    oldIds.add(chunkId(doc.getDocUuid(), i));
                }
                if (!oldIds.isEmpty()) {
                    vectorStore.delete(oldIds);
                }
                String newDocUuid = UUID.randomUUID().toString();
                List<Document> vectors = new ArrayList<>(chunks.size());
                for (KnowledgeChunk chunk : chunks) {
                    vectors.add(new Document(chunkId(newDocUuid, chunk.getChunkIdx()), chunk.getContent(),
                            documentIngestor.metadata(kb, newDocUuid, doc.getFileName(),
                                    chunk.getChunkIdx(), 0, chunk.getContent().length(), null)));
                }
                vectorStore.add(vectors);
                // Chunk 备份行原地换绑新 docUuid（内容/序号不变），保证后续备份导出/再次重建可用
                chunks.forEach(c -> c.setDocUuid(newDocUuid));
                chunkRepository.saveAll(chunks);
                doc.setDocUuid(newDocUuid);
                docRepository.save(doc);
                chunkCount += chunks.size();
                migrated++;
            }
            retrievalCache.bumpVersion(kb.getName());
            log.info("[kb-reindex] kb={}, docs={}, chunks={}, skipped={}", kb.getName(), migrated, chunkCount, skipped);
            return new ReindexResult(kb.getId(), kb.getName(), migrated, chunkCount, skipped);
        });
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocument> listDocuments(Long kbId, Long userId, Long tenantId) {
        requireOwnedKb(kbId, userId, tenantId);
        return docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }
}
