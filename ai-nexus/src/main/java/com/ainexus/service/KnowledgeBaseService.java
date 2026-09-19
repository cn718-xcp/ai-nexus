package com.ainexus.service;

import com.ainexus.ai.chunk.TextChunker;
import com.ainexus.ai.rag.RetrievalCacheService;
import com.ainexus.common.audit.Audited;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import cn.hutool.crypto.digest.DigestUtil;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
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
    private final VectorStore vectorStore;
    private final RetrievalCacheService retrievalCache;
    private final org.redisson.api.RedissonClient redissonClient;
    private final com.ainexus.common.metrics.BusinessMetrics metrics;
    private final TextChunker chunker = new TextChunker(800, 100);

    @Audited(action = "KB_CREATE", target = "#name")
    @Transactional
    public KnowledgeBase create(String name, String description, Long userId) {
        if (kbRepository.existsByName(name)) {
            throw new BizException(ErrorCode.BIZ_ERROR, "知识库名称已存在: " + name);
        }
        // Milvus Collection 名仅允许字母/数字/下划线：中文等字符需剔除，
        // 追加名称 MD5 前 8 位保证不同名称（如 "a_b" 与 "a b"）规范化后仍唯一
        String normalized = name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(name)
                .description(description)
                .collectionName("kb_" + DigestUtil.md5Hex(name).substring(0, 8) + "_" + normalized)
                .status(1)
                .userId(userId)
                .build();
        KnowledgeBase saved = kbRepository.save(kb);
        metrics.onKbChanged(kbRepository.count());
        return saved;
    }

    /**
     * 知识库列表：userId 为 null（鉴权关闭）返回全部；开启后仅返回该用户的库（数据隔离）。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeBase> list(Long userId) {
        return userId == null ? kbRepository.findAll() : kbRepository.findByUserId(userId);
    }

    /**
     * 归属校验：鉴权开启时校验知识库属于当前用户，防止越权读写（统一返回 NOT_FOUND 防探测）。
     */
    private KnowledgeBase requireOwnedKb(Long kbId, Long userId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KB_NOT_FOUND));
        if (userId != null && !userId.equals(kb.getUserId())) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
        return kb;
    }

    /** 支持的纯文本文档扩展名（二进制格式按 UTF-8 强读会产生乱码向量，必须在入口拦截） */
    private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of("txt", "md", "json", "csv");

    /**
     * 上传文档：扩展名校验 -> 解析 -> 切分 -> 带元数据向量化入库（knowledge_base_name / doc_id 元数据用于检索过滤与删除）。
     * Redisson 分布式锁按 kbId 串行化，防止并发上传导致向量与元数据不一致。
     */
    @Audited(action = "DOC_UPLOAD", target = "#file?.originalFilename")
    @Transactional
    public KnowledgeDocument uploadDocument(Long kbId, MultipartFile file, Long userId) {
        KnowledgeBase kb = requireOwnedKb(kbId, userId);
        validateExtension(file);
        org.redisson.api.RLock lock = redissonClient.getLock("ainexus:lock:kb:" + kbId);
        boolean locked = false;
        try {
            // 等待 30s / 自动释放 120s（防止持有者宕机死锁）
            locked = lock.tryLock(30, 120, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ErrorCode.BIZ_ERROR, "该知识库正在处理其他操作，请稍后再试");
            }
            return doUpload(kb, file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.BIZ_ERROR, "获取知识库锁被中断");
        } finally {
            if (locked) {
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

        String docUuid = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? docUuid : file.getOriginalFilename();
        List<String> chunks = chunker.split(text);
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            // Milvus 主键为 VarChar(36)：用确定性 UUID 保证可复现删除；删除时按相同规则重算
            String chunkId = UUID.nameUUIDFromBytes((docUuid + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            // file_name/chunk_index 元数据用于 RAG 引用精准定位
            documents.add(new Document(chunkId, chunks.get(i),
                    Map.of("knowledge_base_name", kb.getName(), "doc_id", docUuid,
                            "file_name", fileName, "chunk_index", i)));
        }
        vectorStore.add(documents);

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .kbId(kb.getId())
                .fileName(fileName)
                .docUuid(docUuid)
                .charCount((long) text.length())
                .chunkCount(chunks.size())
                .status(1)
                .build();
        log.info("[kb] document ingested, kb={}, file={}, chunks={}", kb.getName(), doc.getFileName(), chunks.size());
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

    /** 删除知识库：级联删除其全部向量与文档记录。 */
    @Transactional
    @Audited(action = "KB_DELETE", target = "#kbId")
    public void delete(Long kbId, Long userId) {
        KnowledgeBase kb = requireOwnedKb(kbId, userId);
        org.redisson.api.RLock lock = redissonClient.getLock("ainexus:lock:kb:" + kbId);
        boolean locked = false;
        try {
            locked = lock.tryLock(30, 120, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ErrorCode.BIZ_ERROR, "该知识库正在处理其他操作，请稍后再试");
            }
            List<KnowledgeDocument> docs = docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
            for (KnowledgeDocument doc : docs) {
                List<String> chunkIds = new ArrayList<>(doc.getChunkCount());
                for (int i = 0; i < doc.getChunkCount(); i++) {
                    chunkIds.add(UUID.nameUUIDFromBytes((doc.getDocUuid() + ":" + i)
                            .getBytes(StandardCharsets.UTF_8)).toString());
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.BIZ_ERROR, "获取知识库锁被中断");
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocument> listDocuments(Long kbId, Long userId) {
        requireOwnedKb(kbId, userId);
        return docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }
}
