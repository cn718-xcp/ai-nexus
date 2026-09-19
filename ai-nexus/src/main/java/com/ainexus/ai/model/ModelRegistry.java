package com.ainexus.ai.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表：统一模型键解析与 ChatModel 工厂缓存。
 * 模型键格式：{@code ollama:qwen2.5:7b} | {@code openai:gpt-4o-mini}（openai 需配置 base-url/api-key）。
 * 无前缀的模型名按 Ollama 处理（兼容既有路由配置）。
 * 同键 ChatModel 复用（工厂缓存），跨 Provider 统一为 Spring AI ChatModel 抽象。
 */
@Slf4j
public class ModelRegistry {

    /** openai 协议端点配置（base-url/api-key），来自 ainexus.models.openai.* */
    private final String openaiBaseUrl;
    private final String openaiApiKey;

    private final OllamaApi ollamaApi;
    /** modelKey -> ChatModel 工厂缓存 */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ModelRegistry(OllamaApi ollamaApi, String openaiBaseUrl, String openaiApiKey) {
        this.ollamaApi = ollamaApi;
        this.openaiBaseUrl = openaiBaseUrl;
        this.openaiApiKey = openaiApiKey;
    }

    /** 解析模型键并惰性构建 ChatModel */
    public ChatModel resolve(String modelKey) {
        return cache.computeIfAbsent(normalize(modelKey), this::build);
    }

    /** 已知 provider 前缀（Ollama 模型名自带 tag 冒号，不能简单按冒号切分） */
    private static final List<String> PROVIDERS = List.of("ollama", "openai");

    /** 规范化：无已知 provider 前缀按 Ollama 处理 */
    public static String normalize(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return "ollama:";
        }
        String provider = modelKey.split(":", 2)[0].toLowerCase();
        return PROVIDERS.contains(provider) ? modelKey : "ollama:" + modelKey;
    }

    public static String provider(String modelKey) {
        return normalize(modelKey).split(":", 2)[0];
    }

    /** 模型名（Ollama 键的模型名可自带 tag 冒号，取首个 provider: 之后的完整串） */
    public static String modelName(String modelKey) {
        String normalized = normalize(modelKey);
        return normalized.substring(normalized.indexOf(':') + 1);
    }

    private ChatModel build(String normalizedKey) {
        String provider = provider(normalizedKey);
        String model = modelName(normalizedKey);
        log.info("[model-registry] building ChatModel, key={}", normalizedKey);
        return switch (provider) {
            case "ollama" -> OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .options(OllamaChatOptions.builder().model(model).build())
                    .build();
            case "openai" -> buildOpenAi(model);
            default -> throw new IllegalArgumentException("不支持的模型提供方: " + provider);
        };
    }

    /** OpenAI 协议适配：自研轻量 ChatModel（POST /v1/chat/completions），兼容任意 OpenAI 协议端点 */
    private ChatModel buildOpenAi(String model) {
        return new OpenAiCompatChatModel(openaiBaseUrl, openaiApiKey, model);
    }
}
