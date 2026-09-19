package com.ainexus.ai.pipeline;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.domain.entity.ChatMessageRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 流水线 —— 模板方法模式：
 * 固定骨架 {@link #run}（预处理 -> 向量检索 -> 提示词组装 -> 后置处理），
 * 子类按需覆写各阶段（如 {@link #preprocess} 可接入查询改写/多路召回）。
 */
@Slf4j
public abstract class AbstractRagPipeline {

    protected final VectorStore vectorStore;
    private final ChatHistoryService chatHistoryService;

    protected AbstractRagPipeline(VectorStore vectorStore, ChatHistoryService chatHistoryService) {
        this.vectorStore = vectorStore;
        this.chatHistoryService = chatHistoryService;
    }

    /** 固定流水线骨架，final 防止子类破坏流程 */
    public final RagContext run(RagContext context) {
        preprocess(context);
        retrieve(context);
        assemblePrompt(context);
        postProcess(context);
        log.info("[rag-pipeline] {} done, chunks={}", getClass().getSimpleName(),
                context.getChunks() == null ? 0 : context.getChunks().size());
        return context;
    }

    /** 阶段一：预处理（默认透传；子类可做查询改写、意图识别、历史压缩等） */
    protected void preprocess(RagContext context) {
        context.setRewrittenQuery(context.getQuestion());
    }

    /** 阶段二：向量检索 —— 相似度阈值 + topK，可选按知识库过滤 */
    protected void retrieve(RagContext context) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(context.getRewrittenQuery())
                .topK(context.getTopK())
                .similarityThreshold(0.5);
        if (context.getKnowledgeBaseName() != null && !context.getKnowledgeBaseName().isBlank()) {
            // knowledge_base_name 元数据字段在文档入库时写入
            builder.filterExpression(new FilterExpressionBuilder()
                    .eq("knowledge_base_name", context.getKnowledgeBaseName()).build());
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        context.setChunks(documents == null ? List.of() : documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList()));
    }

    /** 阶段三：提示词组装 —— 系统约束 + 多轮历史 + 检索片段 + 用户问题 */
    protected void assemblePrompt(RagContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是企业知识助手，请仅依据下面的参考资料回答问题；参考资料中没有的内容请明确说明不知道，不要编造。\n\n");
        if (context.getSessionId() != null && !context.getSessionId().isBlank()) {
            List<ChatMessageRecord> history = chatHistoryService.loadWindow(context.getSessionId());
            if (!history.isEmpty()) {
                sb.append("## 历史对话\n");
                history.forEach(m -> sb.append(m.getRole()).append(": ").append(m.getContent()).append('\n'));
                sb.append('\n');
            }
        }
        sb.append("## 参考资料\n");
        if (context.getChunks().isEmpty()) {
            sb.append("（未检索到相关资料）\n");
        } else {
            for (int i = 0; i < context.getChunks().size(); i++) {
                sb.append('[').append(i + 1).append("] ").append(context.getChunks().get(i)).append("\n\n");
            }
        }
        sb.append("## 用户问题\n").append(context.getQuestion());
        context.setFinalPrompt(sb.toString());
    }

    /** 阶段四：后置处理（默认空实现；子类可做引用标注、敏感词过滤、幻觉校验等） */
    protected void postProcess(RagContext context) {
    }
}
