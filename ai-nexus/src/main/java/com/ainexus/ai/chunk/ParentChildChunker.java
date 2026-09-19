package com.ainexus.ai.chunk;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分块器（Parent-Child Chunk）：
 * - 子块（小粒度）用于向量召回：语义聚焦，命中率更高
 * - 父块（大粒度）用于回答生成：上下文完整，回答更准确
 * 召回命中子块后，按 (docUuid, parentIdx) 从备份表取回父块原文拼入提示词。
 */
public class ParentChildChunker {

    private final int parentSize;
    private final int childSize;
    private final int overlap;

    public ParentChildChunker(int parentSize, int childSize, int overlap) {
        if (childSize >= parentSize || overlap >= childSize) {
            throw new IllegalArgumentException("要求 childSize < parentSize 且 overlap < childSize");
        }
        this.parentSize = parentSize;
        this.childSize = childSize;
        this.overlap = overlap;
    }

    /** 父块与其子块集合 */
    @Getter
    public static class ParentChild {
        private final int parentIdx;
        private final String parentText;
        /** 子块在父块内的起始偏移（用于计算全局 char 偏移） */
        private final List<Child> children = new ArrayList<>();

        public ParentChild(int parentIdx, String parentText) {
            this.parentIdx = parentIdx;
            this.parentText = parentText;
        }

        public record Child(int childIdx, String text, int offsetInParent) {
        }
    }

    /**
     * 父子切分：先按段落粗切父块，再将每个父块细切子块（带 overlap）。
     *
     * @return 父块列表（含子块）
     */
    public List<ParentChild> split(String text) {
        List<ParentChild> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        TextChunker parentCutter = new TextChunker(parentSize, parentSize / 10);
        TextChunker childCutter = new TextChunker(childSize, overlap);
        List<String> parents = parentCutter.split(text);
        for (int p = 0; p < parents.size(); p++) {
            String parentText = parents.get(p);
            ParentChild pc = new ParentChild(p, parentText);
            List<String> children = childCutter.split(parentText);
            // 计算子块在父块内的偏移（带 overlap 的滑动窗口）
            int cursor = 0;
            for (int c = 0; c < children.size(); c++) {
                int offset = parentText.indexOf(children.get(c), Math.max(0, cursor - overlap));
                if (offset < 0) {
                    offset = cursor;
                }
                pc.getChildren().add(new ParentChild.Child(c, children.get(c), offset));
                cursor = offset + children.get(c).length();
            }
            result.add(pc);
        }
        return result;
    }
}
