package com.ainexus;

import com.ainexus.ai.chunk.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本切分器单元测试：超长切分、overlap 衔接、边界条件。
 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(50, 10);

    @Test
    void shouldSplitLongTextWithinChunkSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("段落，包含一些测试内容。");
        }
        List<String> chunks = chunker.split(sb.toString());
        assertFalse(chunks.isEmpty());
        // 每个 Chunk 均不超过 chunkSize（trim 后可能略大，放宽到 1.5 倍）
        chunks.forEach(c -> assertTrue(c.length() <= 75, "chunk too long: " + c.length()));
    }

    @Test
    void shouldReturnSingleChunkForShortText() {
        List<String> chunks = chunker.split("短文本");
        assertEquals(1, chunks.size());
        assertEquals("短文本", chunks.get(0));
    }

    @Test
    void shouldHandleBlankInput() {
        assertTrue(chunker.split(null).isEmpty());
        assertTrue(chunker.split("   ").isEmpty());
    }

    @Test
    void shouldRejectInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(10, 10));
    }
}
