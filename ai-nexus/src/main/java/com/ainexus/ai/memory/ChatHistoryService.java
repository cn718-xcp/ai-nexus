package com.ainexus.ai.memory;

import com.ainexus.domain.entity.ChatMessageRecord;
import com.ainexus.domain.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 对话历史服务：MySQL 持久化 + 滑窗加载（替代内置 ChatMemory，便于审计与冷启动恢复）。
 * 鉴权开启时按 userId 隔离；关闭态 userId 为 null，行为与无鉴权时期一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    /** 多轮上下文窗口大小（消息条数） */
    private static final int WINDOW_SIZE = 10;

    private final ChatMessageRepository chatMessageRepository;

    /**
     * 加载最近 WINDOW_SIZE 条历史消息，返回时间正序。
     *
     * @param userId 用户隔离维度；null 表示不过滤（鉴权关闭态）
     */
    @Transactional(readOnly = true)
    public List<ChatMessageRecord> loadWindow(String sessionId, Long userId) {
        List<ChatMessageRecord> recent = userId == null
                ? chatMessageRepository.findRecent(sessionId, WINDOW_SIZE)
                : chatMessageRepository.findRecentByUser(sessionId, userId, WINDOW_SIZE);
        Collections.reverse(recent);
        return recent;
    }

    @Transactional
    public void saveUser(String sessionId, String content, String modelName, Long userId) {
        save(sessionId, "user", content, modelName, userId);
    }

    @Transactional
    public void saveAssistant(String sessionId, String content, String modelName, Long userId) {
        save(sessionId, "assistant", content, modelName, userId);
    }

    private void save(String sessionId, String role, String content, String modelName, Long userId) {
        chatMessageRepository.save(ChatMessageRecord.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .modelName(modelName)
                .userId(userId)
                .build());
    }
}
