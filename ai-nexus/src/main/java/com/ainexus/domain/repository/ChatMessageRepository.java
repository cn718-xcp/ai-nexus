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
}
