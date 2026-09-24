package com.ainexus.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 协议轻量适配器：POST /v1/chat/completions，统一为 Spring AI ChatModel 抽象。
 * 兼容任意 OpenAI 协议端点（OpenAI 官方 / vLLM / Ollama OpenAI 兼容模式等）。
 * 仅实现同步调用（本项目所有场景均为同步/流式由上层另行处理），协议消息组装与
 * 响应解析为纯函数（可单测），HTTP 经 RestClient 完成。
 */
@Slf4j
public class OpenAiCompatChatModel implements ChatModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final transient RestClient restClient;

    /** 共享 ObjectMapper：请求体序列化与响应解析（避免每次调用新建） */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl == null ? "https://api.openai.com" : baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder().baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String requestBody = buildRequestBody(prompt, model);
        String response = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return parseResponse(response);
    }

    /** ChatModel 契约要求非 null：协议端点的默认选项由服务端决定，返回空默认选项 */
    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
    }

    /** 组装 /v1/chat/completions 请求体（纯函数，Jackson 序列化完整转义控制字符） */
    String buildRequestBody(Prompt prompt, String model) {
        List<Map<String, String>> messages = new ArrayList<>();
        prompt.getInstructions().forEach(m ->
                messages.add(Map.of("role", roleOf(m.getMessageType().getValue()),
                        "content", m.getText() == null ? "" : m.getText())));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        // 透传调用方设置的采样参数（未设置则省略，交由服务端默认值）
        org.springframework.ai.chat.prompt.ChatOptions opts = prompt.getOptions();
        if (opts != null) {
            if (opts.getTemperature() != null) {
                body.put("temperature", opts.getTemperature());
            }
            if (opts.getMaxTokens() != null) {
                body.put("max_tokens", opts.getMaxTokens());
            }
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("构建 OpenAI 协议请求体失败: " + e.getMessage(), e);
        }
    }

    /** 解析响应：choices[0].message.content（纯函数）。解析失败上抛交由上层重试/降级，避免静默空回答 */
    ChatResponse parseResponse(String response) {
        String content;
        try {
            JsonNode root = MAPPER.readTree(response);
            content = root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI 协议响应解析失败: " + e.getMessage(), e);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private String roleOf(String springRole) {
        return switch (springRole) {
            case "assistant" -> "assistant";
            case "system" -> "system";
            default -> "user";
        };
    }
}
