package com.ainexus.ai.stats;

import com.ainexus.domain.entity.AiCallLog;
import com.ainexus.domain.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 调用事件监听器：异步写入 ai_call_log，与主链路解耦。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallEventListener {

    private final AiCallLogRepository aiCallLogRepository;

    @Transactional
    public void onCall(AiCallEvent event) {
        try {
            aiCallLogRepository.save(AiCallLog.builder()
                    .scene(event.getScene())
                    .modelName(event.getModelName())
                    .sessionId(event.getSessionId())
                    .userId(event.getUserId())
                    .success(event.isSuccess() ? 1 : 0)
                    .costMs(event.getCostMs())
                    .promptTokens(event.getPromptTokens())
                    .completionTokens(event.getCompletionTokens())
                    .fallback(event.isFallback() ? 1 : 0)
                    .errorMessage(event.getErrorMessage())
                    .callDate(LocalDate.now().format(DateTimeFormatter.ISO_DATE))
                    .build());
        } catch (Exception e) {
            // 统计落库失败不影响主链路
            log.warn("[ai-call-log] persist failed", e);
        }
    }
}
