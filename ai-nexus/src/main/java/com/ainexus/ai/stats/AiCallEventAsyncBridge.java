package com.ainexus.ai.stats;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 将监听器方法桥接为异步执行（独立线程池落库，不影响主链路 RT）。
 */
@Component
@RequiredArgsConstructor
public class AiCallEventAsyncBridge {

    private final AiCallEventListener listener;

    @Async("aiCallExecutor")
    @EventListener
    public void onAiCall(AiCallEvent event) {
        listener.onCall(event);
    }
}
