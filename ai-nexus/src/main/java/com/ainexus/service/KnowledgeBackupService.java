package com.ainexus.service;

import cn.hutool.json.JSONUtil;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.KnowledgeChunk;
import com.ainexus.domain.entity.KnowledgeDocument;
import com.ainexus.domain.entity.KnowledgeBase;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.KnowledgeChunkRepository;
import com.ainexus.domain.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库备份服务：元数据 + 向量数据（Chunk 原文）导出/导入。
 * - 导出：JSON（知识库信息 + 文档清单 + 全部片段原文）
 * - 导入：按片段原文重新切分向量化入库（重建新 docUuid，向量与元数据全量重建）
 * 支持数据备份与跨环境迁移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBackupService {

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeDocumentRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final VectorStore vectorStore;

    /** 导出载荷结构 */
    public record BackupPayload(KnowledgeBase kb, List<DocumentInfo> documents) {
    }

    public record DocumentInfo(String fileName, String docUuid, List<ChunkInfo> chunks) {
    }

    public record ChunkInfo(int idx, String content) {
    }

    /** 导出：知识库元数据 + 全部片段原文（归属校验由调用方保证） */
    @Transactional(readOnly = true)
    public String export(Long kbId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KB_NOT_FOUND));
        List<DocumentInfo> docs = new ArrayList<>();
        for (KnowledgeDocument doc : docRepository.findByKbIdOrderByCreatedAtDesc(kbId)) {
            List<ChunkInfo> chunks = chunkRepository.findByKbIdOrderByDocUuidAscChunkIdxAsc(kbId).stream()
                    .filter(c -> c.getDocUuid().equals(doc.getDocUuid()))
                    .map(c -> new ChunkInfo(c.getChunkIdx(), c.getContent()))
                    .toList();
            docs.add(new DocumentInfo(doc.getFileName(), doc.getDocUuid(), chunks));
        }
        return JSONUtil.toJsonStr(new BackupPayload(kb, docs));
    }

    /**
     * 导入：新建知识库并按片段原文重新向量化（新 docUuid，向量元数据全量重建）。
     */
    @Transactional
    public KnowledgeBase importBackup(String backupJson, Long userId, Long tenantId) {
        BackupPayload payload;
        try {
            payload = JSONUtil.toBean(backupJson, BackupPayload.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "备份文件格式不合法: " + e.getMessage());
        }
        if (payload == null || payload.kb() == null || payload.documents() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "备份文件缺少必要字段");
        }
        KnowledgeBase source = payload.kb();
        KnowledgeBase target = KnowledgeBase.builder()
                .name(source.getName() + "-restored")
                .description("restored from backup: " + source.getName())
                .collectionName("kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .status(1)
                .userId(userId)
                .tenantId(tenantId)
                .build();
        target = kbRepository.save(target);

        for (DocumentInfo docInfo : payload.documents()) {
            String docUuid = UUID.randomUUID().toString();
            List<Document> vectors = new ArrayList<>(docInfo.chunks().size());
            List<KnowledgeChunk> backups = new ArrayList<>(docInfo.chunks().size());
            for (ChunkInfo chunk : docInfo.chunks()) {
                String chunkId = UUID.nameUUIDFromBytes((docUuid + ":" + chunk.idx())
                        .getBytes(StandardCharsets.UTF_8)).toString();
                vectors.add(new Document(chunkId, chunk.content(),
                        Map.of("knowledge_base_name", target.getName(), "doc_id", docUuid,
                                "file_name", docInfo.fileName(), "chunk_index", chunk.idx())));
                backups.add(KnowledgeChunk.builder()
                        .kbId(target.getId()).docUuid(docUuid)
                        .chunkIdx(chunk.idx()).content(chunk.content()).build());
            }
            if (!vectors.isEmpty()) {
                vectorStore.add(vectors);
                chunkRepository.saveAll(backups);
            }
            docRepository.save(KnowledgeDocument.builder()
                    .kbId(target.getId())
                    .fileName(docInfo.fileName())
                    .docUuid(docUuid)
                    .charCount(docInfo.chunks().stream().mapToLong(c -> c.content().length()).sum())
                    .chunkCount(docInfo.chunks().size())
                    .status(1)
                    .build());
        }
        log.info("[kb-backup] imported, source={}, target={}, docs={}", source.getName(), target.getName(), payload.documents().size());
        return target;
    }

    /** 上传时写入 Chunk 备份（供备份导出） */
    @Transactional
    public void saveChunkBackups(Long kbId, String docUuid, List<String> chunks) {
        List<KnowledgeChunk> backups = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            backups.add(KnowledgeChunk.builder()
                    .kbId(kbId).docUuid(docUuid).chunkIdx(i).content(chunks.get(i)).build());
        }
        chunkRepository.saveAll(backups);
    }
}
