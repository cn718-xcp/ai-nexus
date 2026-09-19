package com.ainexus.ai.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 模型调用执行器：
 * - 自动降级：主模型调用失败（网络/服务不可用）依序切换备用模型键，全部失败抛出原异常
 * - 灰度发布：按流量比例将请求路由到灰度模型键（模型版本共存）
 * 降级判定仅针对"模型不可达"类异常；业务/参数异常不切换模型。
 */
@Slf4j
public class ModelInvoker {

    private final ModelRegistry registry;

    public ModelInvoker(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param primaryModelKey 主模型键
     * @param fallbackKeys    备用模型键（依序尝试）
     * @param grayModelKey    灰度模型键（可空）
     * @param grayRatio       灰度流量比例 0-1
     * @param action          以 ChatModel 为入参的调用体
     */
    public <T> T invoke(String primaryModelKey, List<String> fallbackKeys,
                        String grayModelKey, double grayRatio, java.util.function.Function<ChatModel, T> action) {
        // 灰度分流：命中比例则走灰度模型（模型版本共存按流量分配）
        if (grayModelKey != null && !grayModelKey.isBlank()
                && ThreadLocalRandom.current().nextDouble() < grayRatio) {
            log.info("[model-invoker] gray routing to {}", grayModelKey);
            return action.apply(registry.resolve(grayModelKey));
        }
        try {
            return action.apply(registry.resolve(primaryModelKey));
        } catch (Exception primaryError) {
            if (fallbackKeys == null || fallbackKeys.isEmpty() || !isInfrastructureFailure(primaryError)) {
                throw primaryError;
            }
            for (String fallbackKey : fallbackKeys) {
                try {
                    log.warn("[model-invoker] primary {} unavailable ({}), fallback to {}",
                            primaryModelKey, primaryError.getMessage(), fallbackKey);
                    return action.apply(registry.resolve(fallbackKey));
                } catch (Exception fallbackError) {
                    log.error("[model-invoker] fallback {} also failed: {}", fallbackKey, fallbackError.getMessage());
                }
            }
            throw primaryError;
        }
    }

    /** 基础设施类失败判定：连接/超时类才触发模型切换，业务异常不切换 */
    boolean isInfrastructureFailure(Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("connection") || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("refused") || msg.contains("unreachable") || msg.contains("503");
    }

    /** 供测试注入随机源的 invoke 重载（灰度可测） */
    public <T> T invoke(String primaryModelKey, List<String> fallbackKeys,
                        String grayModelKey, boolean grayHit, Supplier<Boolean> unused,
                        java.util.function.Function<ChatModel, T> action) {
        if (grayModelKey != null && grayHit) {
            return action.apply(registry.resolve(grayModelKey));
        }
        return invoke(primaryModelKey, fallbackKeys, null, 0, action);
    }
}
