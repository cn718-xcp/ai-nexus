package com.ainexus.ai.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分块器：按句切分后批量向量化，相邻句余弦相似度低于阈值处断开，
 * 使每个分块内部语义一致（替代固定长度切分的"硬截断"）。
 * 依赖 Spring AI EmbeddingModel 批量接口，一次上传仅 1~2 次模型调用。
 */
@RequiredArgsConstructor
public class SemanticChunker {

    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;
    private final int maxChunkChars;

    /** 句子级切分分隔符（中英文标点） */
    private static final String[] SENTENCE_SEPARATORS = {"。", "！", "？", ".", "!", "?", "\n"};

    public List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            result.add(text.trim());
            return result;
        }
        // 批量向量化全部句子（一次模型调用）
        float[][] vectors = embeddingModel.embed(sentences).toArray(new float[0][]);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            current.append(sentences.get(i));
            boolean boundary = i == sentences.size() - 1
                    || cosine(vectors[i], vectors[i + 1]) < similarityThreshold
                    || current.length() + sentences.get(i + 1).length() > maxChunkChars;
            if (boundary) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
        }
        return result;
    }

    /** 按标点/换行切句，保留分隔符，过滤空白句 */
    List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean isSep = false;
            for (String sep : SENTENCE_SEPARATORS) {
                if (c == sep.charAt(0)) {
                    isSep = true;
                    break;
                }
            }
            if (isSep) {
                String s = current.toString().trim();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
