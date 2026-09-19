package com.ainexus.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索融合器：向量召回排序（语义）与关键词命中排序（字面）通过
 * RRF（Reciprocal Rank Fusion，1/(k+rank)）融合，兼顾语义泛化与精确匹配。
 * 关键词排序在应用层实现（对候选集合分词打分），不依赖外部检索引擎。
 */
@Component
public class HybridRetriever {

    /** 常见停用词（中文/英文混合检索足够用的最小集） */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "什么", "怎么", "如何", "the", "a", "an",
            "is", "are", "what", "how", "of", "to", "for", "and", "or", "in", "on");

    /**
     * RRF 融合：dense 与 sparse 两路排名融合为最终排序。
     *
     * @param dense   向量召回候选（已按相似度降序）
     * @param query   用户查询（用于关键词打分）
     * @param rrfK    RRF 常数
     */
    public List<Document> fuse(List<Document> dense, String query, int rrfK) {
        if (dense.size() <= 1) {
            return dense;
        }
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return dense;
        }
        // sparse：对同一候选集合按关键词命中率打分，形成字面排名
        Map<String, Double> sparseScore = new HashMap<>();
        for (Document d : dense) {
            Set<String> terms = tokenize(d.getText());
            long hits = queryTerms.stream().filter(terms::contains).count();
            sparseScore.put(d.getId(), (double) hits / queryTerms.size());
        }
        List<Document> sparseRank = dense.stream()
                .sorted((a, b) -> Double.compare(sparseScore.get(b.getId()), sparseScore.get(a.getId())))
                .toList();

        // RRF：score = Σ 1/(k + rank_i)，dense/sparse 两路各计一次
        Map<String, Double> rrf = new HashMap<>();
        for (int i = 0; i < dense.size(); i++) {
            rrf.merge(dense.get(i).getId(), 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < sparseRank.size(); i++) {
            rrf.merge(sparseRank.get(i).getId(), 1.0 / (rrfK + i + 1), Double::sum);
        }
        Map<String, Document> byId = new HashMap<>();
        dense.forEach(d -> byId.put(d.getId(), d));
        return rrf.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    /** 极简分词：英文按非字母数字切分 + 小写化；中文按单字切分（bigram 更优，保持实现简单） */
    public Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean isWord = Character.isLetterOrDigit(c);
            if (isWord) {
                current.append(c);
                // 中文单字粒度
                if (c >= 0x4e00 && c <= 0x9fff) {
                    terms.add(String.valueOf(c));
                    current.setLength(0);
                }
            } else {
                flush(terms, current);
            }
        }
        flush(terms, current);
        terms.removeAll(STOP_WORDS);
        return terms;
    }

    private void flush(Set<String> terms, StringBuilder current) {
        if (!current.isEmpty()) {
            String word = current.toString();
            if (word.length() > 1 || word.matches("[a-z0-9]")) {
                terms.add(word);
            }
            current.setLength(0);
        }
    }
}
