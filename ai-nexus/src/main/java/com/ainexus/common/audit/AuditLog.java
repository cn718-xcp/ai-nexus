package com.ainexus.common.audit;

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
 * 业务审计日志：关键操作（知识库增删/文档上传/用户登录/注册）异步落库。
 */
@Data
@Entity
@Table(name = "audit_log")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人用户名（鉴权关闭时为 anonymous） */
    @Column(nullable = false, length = 64)
    private String operator;

    /** 用户 ID（鉴权开启时） */
    @Column(name = "user_id")
    private Long userId;

    /** 操作类型：KB_CREATE/KB_DELETE/DOC_UPLOAD/LOGIN/REGISTER */
    @Column(nullable = false, length = 32)
    private String action;

    /** 操作对象摘要（如知识库名/文档名） */
    @Column(length = 256)
    private String target;

    /** 操作详情（JSON/文本） */
    @Column(length = 1024)
    private String detail;

    /** 客户端 IP */
    @Column(length = 64)
    private String clientIp;

    /** 是否成功 */
    @Column(nullable = false)
    private Integer success;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
