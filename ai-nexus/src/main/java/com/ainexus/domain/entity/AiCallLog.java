package com.ainexus.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * AI 调用流水：面向统计与可观测的明细表（异步落库）。
 */
@Data
@Entity
@Table(name = "ai_call_log", indexes = @Index(name = "idx_scene_day", columnList = "scene,callDate"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用场景：CHAT / RAG / AGENT / MULTIMODAL */
    @Column(nullable = false, length = 32)
    private String scene;

    /** 实际模型名 */
    @Column(nullable = false, length = 64)
    private String modelName;

    /** 会话 ID */
    @Column(name = "sessionId", length = 64)
    private String sessionId;

    /** 是否成功 */
    @Column(nullable = false)
    private Integer success;

    /** 耗时（毫秒） */
    @Column(nullable = false)
    private Long costMs;

    /** 输入 token 数 */
    private Long promptTokens;

    /** 输出 token 数 */
    private Long completionTokens;

    /** 是否发生降级 */
    @Column(nullable = false)
    private Integer fallback;

    /** 失败原因 */
    @Column(length = 512)
    private String errorMessage;

    /** 调用日期（便于按天聚合统计） */
    @Column(nullable = false, length = 10)
    private String callDate;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
