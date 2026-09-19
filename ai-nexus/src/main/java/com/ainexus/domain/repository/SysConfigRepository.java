package com.ainexus.domain.repository;

import com.ainexus.domain.entity.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    List<SysConfig> findByConfigKey(String configKey);

    List<SysConfig> findAllByOrderByConfigKeyAsc();

    Optional<SysConfig> findByConfigKeyAndTenantId(String configKey, Long tenantId);

    void deleteByConfigKeyAndTenantIdIsNull(String configKey);
}
