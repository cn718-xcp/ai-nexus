package com.ainexus.ai.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 二阶段精排器：粗召回（向量检索 topK*N）后，用查询向量与候选片段向量的
 * 余弦相似度重新打分排序，提升头部召回准确率。基于 Spring AI EmbeddingModel
 * 原生批量接口实现，无额外依赖。
 */
@Component
@RequiredArgsConstructor
public class EmbeddingReranker {

    private final EmbeddingModel embeddingModel;

    /**
     * 重排：按 query 与 chunk 的余弦相似度降序。
     *
     * @param query      原始查询
     * @param candidates 粗召回候选
     * @param topK       保留条数
     */
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        // 一次批量调用重排全部候选，避免逐条请求的多次 IO 往返
        float[] queryVector = embeddingModel.embed(query);
        float[][] chunkVectors = embeddingModel.embed(
                candidates.stream().map(Document::getText).toList()).toArray(new float[0][]);

        record Scored(Document doc, double score) {
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new Scored(candidates.get(i), cosine(queryVector, chunkVectors[i])));
        }
        // 保持候选原始 score（向量库距离分），仅按重排分调整顺序
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK)
                .map(Scored::doc)
                .toList();
    }

    /** 余弦相似度（向量非零时） */
    private double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
