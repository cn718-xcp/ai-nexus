package com.ainexus.ai.route;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型路由配置：场景 -> 模型名。application.yml 中可按环境覆盖。
 * 未配置的场景回退到 default-model。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ainexus.model-route")
public class ModelRouteProperties {

    /** 默认兜底模型 */
    private String defaultModel = "qwen2.5:7b";

    /** 场景 -> 模型名映射，如 RAG -> qwen2.5:7b, MULTIMODAL -> llava:7b */
    private Map<String, String> routes = new LinkedHashMap<>();
}
