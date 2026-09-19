package com.ainexus.ai.route;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景路由策略实现：根据 yml 配置的 场景->模型名 映射，
 * 基于 OllamaChatModel 按"同名模型复用"原则惰性构建各场景的 ChatModel（工厂模式）。
 */
@Slf4j
@Component
public class SceneModelRouteStrategy implements ModelRouteStrategy {

    private final ModelRouteProperties properties;
    private final OllamaApi ollamaApi;
    private final OllamaChatModel primaryChatModel;
    private final ObservationRegistry observationRegistry;

    /** modelKey -> 预构建的 ChatModel（工厂缓存） */
    private final Map<String, ChatModel> modelFactory = new ConcurrentHashMap<>();

    public SceneModelRouteStrategy(ModelRouteProperties properties,
                                   OllamaChatModel primaryChatModel,
                                   OllamaApi ollamaApi,
                                   ObservationRegistry observationRegistry) {
        this.properties = properties;
        this.primaryChatModel = primaryChatModel;
        this.ollamaApi = ollamaApi;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public boolean supports(ModelScene scene) {
        return true;
    }

    @Override
    public ChatModel route(ModelScene scene) {
        String modelName = properties.getRoutes().getOrDefault(scene.name(), properties.getDefaultModel());
        log.info("[model-route] scene={} -> model={}", scene, modelName);
        return modelFactory.computeIfAbsent(modelName, this::buildModel);
    }

    /** 工厂方法：主模型命中默认名则直接复用容器实例，否则按配置新建。 */
    private ChatModel buildModel(String modelName) {
        if (properties.getDefaultModel().equals(modelName)) {
            return primaryChatModel;
        }
        log.info("[model-factory] creating ChatModel for {}", modelName);
        // 挂载与主模型相同的 ObservationRegistry，保证非默认模型的观测指标（耗时/token）不缺失
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder().model(modelName).build())
                .observationRegistry(observationRegistry)
                .build();
    }
}
