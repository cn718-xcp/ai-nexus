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
 * RAG Bad Case：低评分问答自动收集，支撑人工标注与提示词/检索策略迭代。
 */
@Data
@Entity
@Table(name = "rag_bad_case")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagBadCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "references_json", columnDefinition = "TEXT")
    private String referencesJson;

    /** 事实一致性 1-5 */
    @Column(nullable = false)
    private Integer consistency;

    /** 相关性 1-5 */
    @Column(nullable = false)
    private Integer relevance;

    /** 完整度 1-5 */
    @Column(nullable = false)
    private Integer completeness;

    /** 人工标注：0-未处理 1-已标注 */
    @Column(nullable = false)
    private Integer reviewed;

    @Column(length = 1024)
    private String feedback;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
