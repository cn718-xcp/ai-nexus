package com.ainexus.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库请求。
 */
@Data
public class KnowledgeBaseCreateRequest {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 64, message = "知识库名称最长 64 字符")
    private String name;

    @Size(max = 512, message = "描述最长 512 字符")
    private String description;
}
