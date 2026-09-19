package com.ainexus.domain.repository;

import com.ainexus.domain.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByKbIdOrderByCreatedAtDesc(Long kbId);

    long countByKbId(Long kbId);
}
