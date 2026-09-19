package com.ainexus.ai.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 引用高亮标记：在答案中为与引用片段内容重叠的句子追加来源序号标记（如 【1】），
 * 明确标注内容来源，便于人工核验（幻觉可视化）。基于词面重叠的轻量实现。
 */
@Component
public class CitationMarker {

    private static final double OVERLAP_THRESHOLD = 0.35;

    /**
     * @param answer     模型答案
     * @param references 引用片段（顺序即来源编号）
     * @return 标记后的答案（无引用或重叠不足时原样返回）
     */
    public String mark(String answer, List<ChunkReference> references) {
        if (answer == null || answer.isBlank() || references == null || references.isEmpty()) {
            return answer;
        }
        StringBuilder marked = new StringBuilder();
        for (String sentence : answer.split("(?<=[。！？.!?\n])")) {
            marked.append(sentence);
            for (int i = 0; i < references.size(); i++) {
                if (overlap(sentence, references.get(i).getContent()) >= OVERLAP_THRESHOLD) {
                    marked.append("【").append(i + 1).append("】");
                    break;
                }
            }
        }
        return marked.toString();
    }

    /** 字符 bigram 重叠率（Jaccard）：中文友好且无需分词器 */
    double overlap(String sentence, String reference) {
        if (sentence == null || reference == null || sentence.isBlank() || reference.isBlank()) {
            return 0;
        }
        Set<String> s = bigrams(sentence);
        Set<String> r = bigrams(reference);
        if (s.isEmpty() || r.isEmpty()) {
            return 0;
        }
        long inter = s.stream().filter(r::contains).count();
        return (double) inter / s.size();
    }

    private Set<String> bigrams(String text) {
        Set<String> grams = new java.util.HashSet<>();
        String cleaned = text.replaceAll("\\s+", "");
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            grams.add(cleaned.substring(i, i + 2));
        }
        return grams;
    }
}
