package com.ainexus.ai.route;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由上下文（策略模式 + 简单工厂）：
 * 持有全部路由策略，按场景选择首个 supports 的策略执行路由。
 * 注意：路由结果不可缓存——节点选择/租户路由必须每次请求生效，
 * ChatModel 构建成本已由下游（pooledFactory / ModelRegistry）按 key 缓存。
 */
@Slf4j
@Component
public class ModelRouteContext {

    private final List<ModelRouteStrategy> strategies;

    public ModelRouteContext(List<ModelRouteStrategy> strategies) {
        this.strategies = strategies;
    }

    public ChatModel route(ModelScene scene) {
        return route(scene, null);
    }

    /** 带租户维度的路由：租户专属路由（sys_tenant.model_routes）每次实时解析，变更即时生效 */
    public ChatModel route(ModelScene scene, Long tenantId) {
        return strategies.stream()
                .filter(s -> s.supports(scene))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_CALL_ERROR, "无可用路由策略: " + scene))
                .route(scene, tenantId);
    }
}
