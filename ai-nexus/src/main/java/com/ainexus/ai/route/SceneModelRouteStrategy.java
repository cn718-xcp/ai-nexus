package com.ainexus.ai.route;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ainexus.ai.modelpool.ModelNode;
import com.ainexus.ai.modelpool.ModelNodePool;
import com.ainexus.ai.modelpool.PooledChatModelProxy;
import com.ainexus.ai.tenant.TenantQuotaService;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景路由策略实现：支持三层路由决策（优先级从高到低）：
 * 1. 租户专属路由：sys_tenant.model_routes JSON（多租户开启且配置了该场景时）
 * 2. 全局场景路由：ainexus.model-route.routes
 * 3. 默认兜底模型
 * 同时集成模型节点池：池开启时在健康节点间按策略（轮询/最少连接）分发，
 * 按 (节点, 模型) 粒度惰性构建并缓存 ChatModel（工厂模式）。
 */
@Slf4j
@Component
public class SceneModelRouteStrategy implements ModelRouteStrategy {

    private final ModelRouteProperties properties;
    private final OllamaApi ollamaApi;
    private final OllamaChatModel primaryChatModel;
    private final ModelNodePool nodePool;
    private final TenantQuotaService tenantQuotaService;

    private final com.ainexus.ai.model.ModelRegistry modelRegistry;

    /** (nodeBaseUrl|modelName) -> ChatModel 工厂缓存 */
    private final Map<String, ChatModel> pooledFactory = new ConcurrentHashMap<>();

    public SceneModelRouteStrategy(ModelRouteProperties properties,
                                   OllamaChatModel primaryChatModel,
                                   OllamaApi ollamaApi,
                                   ModelNodePool nodePool,
                                   TenantQuotaService tenantQuotaService,
                                   com.ainexus.ai.model.ModelRegistry modelRegistry) {
        this.properties = properties;
        this.primaryChatModel = primaryChatModel;
        this.ollamaApi = ollamaApi;
        this.nodePool = nodePool;
        this.tenantQuotaService = tenantQuotaService;
        this.modelRegistry = modelRegistry;
    }

    @Override
    public boolean supports(ModelScene scene) {
        return true;
    }

    @Override
    public ChatModel route(ModelScene scene) {
        return route(scene, null);
    }

    @Override
    public ChatModel route(ModelScene scene, Long tenantId) {
        String modelName = resolveModelName(scene, tenantId);
        log.info("[model-route] scene={} tenant={} -> model={}", scene, tenantId, modelName);

        // 节点池模式：健康节点间按策略分发（每次请求重新选择，支持负载均衡与故障转移）
        if (nodePool.isAvailable()) {
            ModelNode node = nodePool.acquire();
            if (node != null) {
                ChatModel pooled = pooledFactory.computeIfAbsent(
                        node.getBaseUrl() + "|" + modelName,
                        key -> OllamaChatModel.builder()
                                .ollamaApi(node.getOllamaApi())
                                .options(OllamaChatOptions.builder().model(modelName).build())
                                .build());
                log.debug("[model-pool] dispatch to node {} ({})", node.getName(), node.getBaseUrl());
                return PooledChatModelProxy.wrap(nodePool, node, pooled);
            }
            // 全部节点不健康：回退主节点（故障兜底）
            log.warn("[model-pool] no healthy node, fallback to primary");
        }

        // 单节点模式：默认模型直接复用容器实例；模型键（provider:model）走注册表；普通名走 Ollama
        if (properties.getDefaultModel().equals(modelName)) {
            return primaryChatModel;
        }
        if (modelName.contains(":")) {
            return modelRegistry.resolve(modelName);
        }
        return modelRegistry.resolve("ollama:" + modelName);
    }

    /** 模型名决策：租户专属路由 > 全局场景路由 > 默认模型 */
    private String resolveModelName(ModelScene scene, Long tenantId) {
        String tenantModel = resolveTenantRoute(tenantId, scene);
        if (tenantModel != null) {
            return tenantModel;
        }
        return properties.getRoutes().getOrDefault(scene.name(), properties.getDefaultModel());
    }

    /** 解析租户专属路由 JSON：{"CHAT":"qwen2.5:7b"}；未配置/解析失败返回 null 走全局 */
    private String resolveTenantRoute(Long tenantId, ModelScene scene) {
        if (tenantId == null) {
            return null;
        }
        SysTenant tenant = tenantQuotaService.getTenantIfEnabled(tenantId);
        if (tenant == null || tenant.getModelRoutes() == null || tenant.getModelRoutes().isBlank()) {
            return null;
        }
        try {
            JSONObject routes = JSONUtil.parseObj(tenant.getModelRoutes());
            return routes.getStr(scene.name(), null);
        } catch (Exception e) {
            log.warn("[model-route] invalid tenant model routes, tenant={}, err={}", tenantId, e.getMessage());
            return null;
        }
    }
}
