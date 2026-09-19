package com.ainexus.ai.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用高亮标记与事实性校验：
 * - mark：答案中与引用片段内容重叠的句子追加来源序号标记（如 【1】），明确内容来源
 * - ungroundedSentences：标记答案中未被任何引用片段支持的句子（潜在幻觉可视化）
 * 基于字符 bigram 重叠的轻量实现，中文友好且无需分词器。
 */
@Component
public class CitationMarker {

    private static final double OVERLAP_THRESHOLD = 0.35;

    /** 句子切分正则：中英文句号/叹号/问号/换行后断句（中文标点以 Unicode 转义书写） */
    private static final String SENTENCE_SPLIT = "(?<=[\u3002\uff01\uff1f.!?\n])";

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
        for (String sentence : answer.split(SENTENCE_SPLIT)) {
            marked.append(sentence);
            for (int i = 0; i < references.size(); i++) {
                if (overlap(sentence, references.get(i).getContent()) >= OVERLAP_THRESHOLD) {
                    marked.append("\u3010").append(i + 1).append("\u3011");
                    break;
                }
            }
        }
        return marked.toString();
    }

    /** 事实性校验：返回答案中与全部引用片段重叠度均不足的句子（潜在幻觉句） */
    public List<String> ungroundedSentences(String answer, List<ChunkReference> references) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<String> ungrounded = new ArrayList<>();
        for (String sentence : answer.split(SENTENCE_SPLIT)) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean supported = references != null && references.stream()
                    .anyMatch(r -> overlap(trimmed, r.getContent()) >= OVERLAP_THRESHOLD);
            if (!supported) {
                ungrounded.add(trimmed);
            }
        }
        return ungrounded;
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
        Set<String> grams = new HashSet<>();
        String cleaned = text.replaceAll("\\s+", "");
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            grams.add(cleaned.substring(i, i + 2));
        }
        return grams;
    }
}
