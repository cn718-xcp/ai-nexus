package com.ainexus.service;

import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结构化输出服务：体现 Spring AI 2.0 GA 的 Structured Output 能力。
 * 通过 BeanOutputConverter 将模型输出直接反序列化为强类型 Java 对象 / 列表，
 * 免去手写 JSON 解析与格式校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredOutputService {

    private final ModelRouteContext routeContext;

    /**
     * 实体抽取：从一段文本中抽取关键信息。
     */
    public ExtractedInfo extract(String text) {
        return ChatClient.create(routeContext.route(ModelScene.CHAT))
                .prompt()
                .user(u -> u.text("请从以下文本中抽取信息：{text}").param("text", text))
                .call()
                .entity(ExtractedInfo.class);
    }

    /**
     * 列表抽取：返回结构化 List。
     */
    public List<String> keywords(String topic) {
        return ChatClient.create(routeContext.route(ModelScene.CHAT))
                .prompt()
                .user("列出关于「" + topic + "」的 5 个关键要点，每条不超过 20 字")
                .call()
                .entity(new org.springframework.ai.converter.ListOutputConverter(
                        new org.springframework.core.convert.support.DefaultConversionService()));
    }

    /**
     * 结构化抽取目标类型（Spring AI 自动生成 JSON Schema 并注入提示词）。
     */
    public record ExtractedInfo(String title, String person, String date, String summary) {
    }
}
