package com.ainexus.domain.repository;

import com.ainexus.domain.entity.ChatMessageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageRecord, Long> {

    /** 按会话取最近 N 条消息（时间正序），用于构建滑窗上下文 */
    @Query(value = "SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit",
            nativeQuery = true)
    List<ChatMessageRecord> findRecent(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /** 用户隔离版：鉴权开启时按用户过滤，防止跨用户会话读取 */
    @Query(value = "SELECT * FROM chat_message WHERE session_id = :sessionId AND user_id = :userId " +
            "ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<ChatMessageRecord> findRecentByUser(@Param("sessionId") String sessionId,
                                             @Param("userId") Long userId,
                                             @Param("limit") int limit);

    /** 租户+用户二级隔离版：多租户开启时防止跨租户会话读取 */
    @Query(value = "SELECT * FROM chat_message WHERE session_id = :sessionId AND user_id = :userId AND tenant_id = :tenantId " +
            "ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<ChatMessageRecord> findRecentByUserAndTenant(@Param("sessionId") String sessionId,
                                                      @Param("userId") Long userId,
                                                      @Param("tenantId") Long tenantId,
                                                      @Param("limit") int limit);
}
