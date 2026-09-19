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

    /** ChatModel 接口默认选项（协议端点由配置决定，无本地默认选项） */
    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return null;
    }

    /** 组装 /v1/chat/completions 请求体（纯函数） */
    String buildRequestBody(Prompt prompt, String model) {
        List<Map<String, String>> messages = new ArrayList<>();
        prompt.getInstructions().forEach(m ->
                messages.add(Map.of("role", roleOf(m.getMessageType().getValue()),
                        "content", m.getText() == null ? "" : m.getText())));
        return String.format("{\"model\":\"%s\",\"messages\":%s}",
                escape(model), toJacksonArray(messages));
    }

    /** 解析响应：choices[0].message.content + usage（纯函数） */
    ChatResponse parseResponse(String response) {
        String content = "";
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            content = root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.warn("[openai-compat] response parse failed: {}", e.getMessage());
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

    private String toJacksonArray(List<Map<String, String>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, String> m = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("{\"role\":\"%s\",\"content\":\"%s\"}",
                    escape(m.get("role")), escape(m.get("content"))));
        }
        return sb.append(']').toString();
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
