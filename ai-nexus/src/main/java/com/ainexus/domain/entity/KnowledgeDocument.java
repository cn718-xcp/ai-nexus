package com.ainexus.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 知识库文档：记录切分后的 Chunk 数量与向量化状态。
 */
@Data
@Entity
@Table(name = "knowledge_document")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(nullable = false, length = 256)
    private String fileName;

    /** 向量库中 Chunk ID 前缀（docUuid-i），用于按文档精确删除向量 */
    @Column(nullable = false, length = 64)
    private String docUuid;

    /** 原始文档字符数 */
    @Column(nullable = false)
    private Long charCount;

    /** 切分后的 Chunk 数量 */
    @Column(nullable = false)
    private Integer chunkCount;

    /** 状态：0-处理中 1-已入库 2-失败 */
    @Column(nullable = false)
    private Integer status;

    @Column(length = 512)
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
