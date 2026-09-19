package com.ainexus.ai.route;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 多模型路由策略接口（策略模式）。
 * 每个场景一个策略实现，返回该场景实际使用的 ChatModel。
 */
public interface ModelRouteStrategy {

    /** 该策略支持的调用场景 */
    boolean supports(ModelScene scene);

    /** 路由决策：返回场景对应的目标模型 */
    ChatModel route(ModelScene scene);

    /** 带租户维度的路由决策（多租户开启时租户专属路由优先）；默认忽略租户维度 */
    default ChatModel route(ModelScene scene, Long tenantId) {
        return route(scene);
    }
}
