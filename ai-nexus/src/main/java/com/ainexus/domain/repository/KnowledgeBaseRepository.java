package com.ainexus.domain.repository;

import com.ainexus.domain.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByName(String name);

    boolean existsByName(String name);

    /** 用户维度知识库列表（鉴权开启时使用） */
    List<KnowledgeBase> findByUserId(Long userId);
}
