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
 * 系统用户（auth.enabled=true 时启用鉴权与数据隔离）。
 */
@Data
@Entity
@Table(name = "sys_user")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String username;

    /** BCrypt 摘要 */
    @Column(nullable = false, length = 72)
    private String passwordHash;

    /** 所属租户（多租户开启时生效；NULL 表示未分配租户） */
    @Column(name = "tenant_id")
    private Long tenantId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
