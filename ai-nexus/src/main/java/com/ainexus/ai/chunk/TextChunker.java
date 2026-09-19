package com.ainexus.ai.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分器：按分隔符层级（段落 -> 换行 -> 标点 -> 字符）递归切分，
 * 保证 Chunk 不超长且尽量保持语义完整，带 overlap 防止语义截断。
 * 不依赖 Spring AI TokenTextSplitter，纯 JDK 实现便于单测与离线复用。
 */
public class TextChunker {

    /** 分隔符优先级：从段落级到字符级 */
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "；", "，", ""};

    private final int chunkSize;
    private final int overlap;

    public TextChunker(int chunkSize, int overlap) {
        if (chunkSize <= overlap) {
            throw new IllegalArgumentException("chunkSize 必须大于 overlap");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        recursiveSplit(text, 0, SEPARATORS.length - 1, chunks);
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    private void recursiveSplit(String text, int start, int sepIndex, List<String> out) {
        if (start >= text.length()) {
            return;
        }
        if (text.length() - start <= chunkSize) {
            out.add(text.substring(start).trim());
            return;
        }
        String separator = sepIndex >= 0 ? SEPARATORS[sepIndex] : "";
        int splitAt = findSplit(text, start, separator);
        if (splitAt <= start) {
            // 当前分隔符粒度不足，降级到下一级分隔符
            recursiveSplit(text, start, sepIndex - 1, out);
            return;
        }
        out.add(text.substring(start, splitAt).trim());
        // overlap：下一片起点回退 overlap 个字符，保证上下文衔接
        recursiveSplit(text, Math.max(splitAt - overlap, splitAt - 1), sepIndex, out);
    }

    /** 在 [start+chunkSize] 前的窗口内，尽量找最后一个分隔符位置 */
    private int findSplit(String text, int start, String separator) {
        int hardLimit = Math.min(start + chunkSize, text.length());
        if (separator.isEmpty()) {
            return hardLimit;
        }
        int last = -1;
        int idx = text.indexOf(separator, start);
        while (idx != -1 && idx <= hardLimit) {
            last = idx + separator.length();
            idx = text.indexOf(separator, idx + 1);
        }
        return last > start ? last : -1;
    }
}
