package com.ainexus.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 3 文档配置。
 * 访问地址：/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiNexusOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI Nexus - Spring AI 智能平台 API")
                .description("知识库 / RAG 问答 / 多模型路由 / Function Calling / 多模态 / 调用统计")
                .version("1.0.0"));
    }
}
