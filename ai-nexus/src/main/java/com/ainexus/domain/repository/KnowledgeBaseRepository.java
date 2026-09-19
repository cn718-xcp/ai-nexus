package com.ainexus.domain.repository;

import com.ainexus.domain.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByName(String name);

    boolean existsByName(String name);
}
