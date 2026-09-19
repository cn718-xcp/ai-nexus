package com.ainexus.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 动态配置：运行时配置管控（dynamic.enabled=true 时启用）。
 * 解析优先级：租户专属 > 灰度命中 > 全局 > 本地 yml 默认值。
 */
@Data
@Entity
@Table(name = "sys_config")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置键（如 resilience.circuit-failure-rate） */
    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 512)
    private String configValue;

    /** global-全局 | gray-灰度（仅 gray_tenants 中的租户生效） */
    @Column(nullable = false, length = 16)
    private String scope;

    /** 租户专属配置（非空时优先级最高） */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** 灰度租户 ID 列表（逗号分隔，scope=gray 时生效） */
    @Column(name = "gray_tenants", length = 256)
    private String grayTenants;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
