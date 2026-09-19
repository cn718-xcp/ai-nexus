package com.ainexus.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 知识库元数据。
 */
@Data
@Entity
@Table(name = "knowledge_base")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名称，业务唯一 */
    @Column(nullable = false, length = 64, unique = true)
    private String name;

    /** 描述 */
    @Column(length = 512)
    private String description;

    /** Milvus Collection 名称（按知识库隔离） */
    @Column(nullable = false, length = 128)
    private String collectionName;

    /** 状态：1-启用 0-禁用 */
    @Column(nullable = false)
    private Integer status;

    /** 所属用户（auth.enabled=true 时隔离维度；关闭态为 NULL 且不过滤） */
    @Column(name = "user_id")
    private Long userId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
