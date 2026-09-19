package com.ainexus.service;

import com.ainexus.ai.chunk.TextChunker;
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
    private final TextChunker chunker = new TextChunker(800, 100);

    @Transactional
    public KnowledgeBase create(String name, String description) {
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
                .build();
        return kbRepository.save(kb);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBase> list() {
        return kbRepository.findAll();
    }

    /** 支持的纯文本文档扩展名（二进制格式按 UTF-8 强读会产生乱码向量，必须在入口拦截） */
    private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of("txt", "md", "json", "csv");

    /**
     * 上传文档：扩展名校验 -> 解析 -> 切分 -> 带元数据向量化入库（knowledge_base_name / doc_id 元数据用于检索过滤与删除）。
     */
    @Transactional
    public KnowledgeDocument uploadDocument(Long kbId, MultipartFile file) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KB_NOT_FOUND));
        validateExtension(file);
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
        List<String> chunks = chunker.split(text);
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            // Milvus 主键为 VarChar(36)：用确定性 UUID 保证可复现删除；删除时按相同规则重算
            String chunkId = UUID.nameUUIDFromBytes((docUuid + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            documents.add(new Document(chunkId, chunks.get(i),
                    Map.of("knowledge_base_name", kb.getName(), "doc_id", docUuid)));
        }
        vectorStore.add(documents);

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .kbId(kbId)
                .fileName(file.getOriginalFilename() == null ? docUuid : file.getOriginalFilename())
                .docUuid(docUuid)
                .charCount((long) text.length())
                .chunkCount(chunks.size())
                .status(1)
                .build();
        log.info("[kb] document ingested, kb={}, file={}, chunks={}", kb.getName(), doc.getFileName(), chunks.size());
        return docRepository.save(doc);
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
    public void delete(Long kbId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KB_NOT_FOUND));
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
        log.info("[kb] knowledge base deleted, name={}, docs={}", kb.getName(), docs.size());
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocument> listDocuments(Long kbId) {
        return docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }
}
