package com.ainexus.domain.repository;

import com.ainexus.domain.entity.RagBadCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagBadCaseRepository extends JpaRepository<RagBadCase, Long> {

    List<RagBadCase> findByReviewedOrderByCreatedAtDesc(Integer reviewed);
}
