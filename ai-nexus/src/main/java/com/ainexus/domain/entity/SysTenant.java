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
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 租户（tenant.enabled=true 时启用多租户体系）。
 */
@Data
@Entity
@Table(name = "sys_tenant")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysTenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 租户编码，业务唯一 */
    @Column(nullable = false, length = 32, unique = true)
    private String code;

    /** 状态：1-启用 0-禁用（禁用后租户内所有请求被拒绝） */
    @Column(nullable = false)
    private Integer status;

    /** 知识库数量上限 */
    @Column(name = "kb_quota", nullable = false)
    private Integer kbQuota;

    /** 单租户并发调用上限（模型调用闸门按租户维度使用） */
    @Column(name = "concurrent_quota", nullable = false)
    private Integer concurrentQuota;

    /** 每日模型调用限额 */
    @Column(name = "daily_model_quota", nullable = false)
    private Integer dailyModelQuota;

    /** 租户专属模型路由 JSON：{"CHAT":"qwen2.5:7b"}（优先于全局路由） */
    @Column(name = "model_routes", length = 1024)
    private String modelRoutes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
