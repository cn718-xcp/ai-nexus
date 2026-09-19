package com.ainexus.ai.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具：将 RAG 检索能力封装为 Agent 工具，
 * 模型可在复杂任务中自主决定"先查知识库、再调工具、最后整合回答"的多步执行链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchTool {

    private final VectorStore vectorStore;

    /**
     * 在指定知识库中检索与问题最相关的资料片段。
     */
    @Tool(description = "在企业知识库中检索与问题相关的资料片段。当需要引用内部文档/手册/资料回答问题时必须调用此工具")
    public String searchKnowledgeBase(
            @ToolParam(description = "要检索的问题关键词或完整问题") String query,
            @ToolParam(description = "知识库名称，不确定时传 all 进行全库检索") String knowledgeBaseName) {
        log.info("[tool-call] searchKnowledgeBase query={}, kb={}", query, knowledgeBaseName);
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(5)
                .similarityThreshold(0.5);
        if (knowledgeBaseName != null && !knowledgeBaseName.isBlank() && !"all".equalsIgnoreCase(knowledgeBaseName)) {
            builder.filterExpression(new FilterExpressionBuilder()
                    .eq("knowledge_base_name", knowledgeBaseName).build());
        }
        List<Document> docs = vectorStore.similaritySearch(builder.build());
        if (docs == null || docs.isEmpty()) {
            return "未在知识库中检索到相关资料";
        }
        // 片段带来源标注，模型整合回答时可引用文档名
        return docs.stream()
                .map(d -> {
                    Object file = d.getMetadata().get("file_name");
                    String source = file == null ? "未知文档" : String.valueOf(file);
                    return String.format("[来源: %s] %s", source, d.getText());
                })
                .collect(Collectors.joining("\n---\n"));
    }
}
