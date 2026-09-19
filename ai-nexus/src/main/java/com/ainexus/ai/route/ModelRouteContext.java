package com.ainexus.ai.route;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由上下文（策略模式 + 简单工厂）：
 * 持有全部路由策略，按场景选择首个 supports 的策略执行路由，并对结果做客户端缓存。
 */
@Slf4j
@Component
public class ModelRouteContext {

    private final List<ModelRouteStrategy> strategies;
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ModelRouteContext(List<ModelRouteStrategy> strategies) {
        this.strategies = strategies;
    }

    public ChatModel route(ModelScene scene) {
        return cache.computeIfAbsent(scene.name(), key -> strategies.stream()
                .filter(s -> s.supports(scene))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_CALL_ERROR, "无可用路由策略: " + scene))
                .route(scene));
    }
}
