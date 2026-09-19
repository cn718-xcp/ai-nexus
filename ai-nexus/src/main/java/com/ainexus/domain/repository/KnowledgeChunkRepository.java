package com.ainexus.domain.repository;

import com.ainexus.domain.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByKbIdOrderByDocUuidAscChunkIdxAsc(Long kbId);
}
