package com.ainexus.ai.stats;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 调用事件发布器：统一入口，屏蔽事件机制细节。
 */
@Component
@RequiredArgsConstructor
public class AiCallEventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publish(AiCallEvent event) {
        publisher.publishEvent(event);
    }
}
