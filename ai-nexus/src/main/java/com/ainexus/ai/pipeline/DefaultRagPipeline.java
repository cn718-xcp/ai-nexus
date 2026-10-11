package com.ainexus.ai.pipeline;

import com.ainexus.ai.memory.ChatHistoryService;
import com.ainexus.ai.rag.EmbeddingReranker;
import com.ainexus.ai.rag.HybridRetriever;
import com.ainexus.ai.rag.RagProperties;
import com.ainexus.ai.rag.ParentChildExpander;
import com.ainexus.ai.rag.RetrievalCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认 RAG 流水线：在模板骨架基础上接入检索进阶能力（均为可选开关）。
 * - 混合模式：向量过召回 + 关键词重打分 RRF 融合
 * - 重排：Embedding 余弦二阶段精排
 * - 关闭时行为与纯向量 topK 检索完全一致（向后兼容）
 */
@Slf4j
@Component
public class DefaultRagPipeline extends AbstractRagPipeline {

    private final RagProperties properties;
    private final EmbeddingReranker reranker;
    private final HybridRetriever hybridRetriever;
    private final RetrievalCacheService retrievalCache;
    private final ParentChildExpander parentChildExpander;

    public DefaultRagPipeline(VectorStore vectorStore,
                              ChatHistoryService chatHistoryService,
                              RagProperties properties,
                              EmbeddingReranker reranker,
                              HybridRetriever hybridRetriever,
                              RetrievalCacheService retrievalCache,
                              ParentChildExpander parentChildExpander) {
        super(vectorStore, chatHistoryService);
        this.properties = properties;
        this.reranker = reranker;
        this.hybridRetriever = hybridRetriever;
        this.retrievalCache = retrievalCache;
        this.parentChildExpander = parentChildExpander;
    }

    @Override
    protected List<Document> doSearch(RagContext context) {
        // 过召回：候选池 = topK * overFetch，为融合与精排留出重排空间
        int candidateK = Math.max(context.getTopK(), context.getTopK() * properties.getOverFetch());
        // 检索结果缓存：重复问题（高频知识库 FAQ 场景）直接跳过向量库 IO
        List<Document> cached = retrievalCache.get(context.getKnowledgeBaseName(), context.getRewrittenQuery(),
                candidateK, context.getUserId(), context.getTenantId());
        if (cached != null) {
            return cached;
        }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(context.getRewrittenQuery())
                .topK(candidateK)
                .similarityThreshold(0.5);
        // 知识库名 + 归属过滤（与基类一致，保证缓存命中与回源检索同范围）
        org.springframework.ai.vectorstore.filter.Filter.Expression filter =
                buildFilter(context.getKnowledgeBaseName(), context.getUserId(), context.getTenantId());
        if (filter != null) {
            builder.filterExpression(filter);
        }
        List<Document> candidates = vectorStore.similaritySearch(builder.build());
        List<Document> docs = candidates == null ? List.of() : candidates;

        // 混合模式：RRF 融合语义+字面两路排序，可叠加位置权重（多维打分融合）
        // 融合必须在写缓存之前：保证缓存内容 = 最终候选集，命中与未命中返回同一排序
        if ("hybrid".equalsIgnoreCase(properties.getMode()) && !docs.isEmpty()) {
            docs = hybridRetriever.fuse(docs, context.getRewrittenQuery(),
                    properties.getRrfK(), properties.getPositionWeight());
            log.info("[rag] hybrid fusion applied, candidates={}", docs.size());
        }
        retrievalCache.put(context.getKnowledgeBaseName(), context.getRewrittenQuery(), candidateK, docs,
                context.getUserId(), context.getTenantId());
        return docs;
    }

    @Override
    protected List<Document> refine(RagContext context, List<Document> candidates) {
        // 父子分块扩展：子块命中 → 父块上下文（回答质量优先）
        candidates = parentChildExpander.expand(candidates);
        // 重排：Embedding 余弦精排后截断 topK
        if (properties.isRerankEnabled() && !candidates.isEmpty()) {
            return reranker.rerank(context.getRewrittenQuery(), candidates, context.getTopK());
        }
        return candidates.size() > context.getTopK()
                ? candidates.subList(0, context.getTopK()) : candidates;
    }
}
