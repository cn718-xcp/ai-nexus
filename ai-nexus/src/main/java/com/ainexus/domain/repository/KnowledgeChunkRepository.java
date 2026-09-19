package com.ainexus.domain.repository;

import com.ainexus.domain.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByKbIdOrderByDocUuidAscChunkIdxAsc(Long kbId);

    /** 父子分块召回扩展：按文档与父块序号定位父块原文 */
    Optional<KnowledgeChunk> findByDocUuidAndChunkIdx(String docUuid, Integer chunkIdx);
}
