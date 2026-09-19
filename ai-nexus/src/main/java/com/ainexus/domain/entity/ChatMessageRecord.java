package com.ainexus.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 对话历史（持久化于 MySQL，构建多轮上下文时按会话滑窗加载）。
 */
@Data
@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_session_created", columnList = "sessionId,createdAt"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID（前端生成，UUID） */
    @Column(name = "sessionId", nullable = false, length = 64)
    private String sessionId;

    /** 角色：user / assistant / system */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 本次调用实际路由到的模型名 */
    @Column(length = 64)
    private String modelName;

    /** 所属用户（鉴权开启时隔离维度；关闭态为 NULL） */
    @Column(name = "user_id")
    private Long userId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
