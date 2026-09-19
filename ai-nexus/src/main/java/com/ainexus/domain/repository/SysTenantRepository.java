package com.ainexus.domain.repository;

import com.ainexus.domain.entity.SysTenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysTenantRepository extends JpaRepository<SysTenant, Long> {

    Optional<SysTenant> findByCode(String code);

    boolean existsByCode(String code);
}
