package com.ainexus.dto.response;

import com.ainexus.ai.rag.ChunkReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 问答响应：含答案、引用片段与降级标记。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswerResponse {

    private String sessionId;
    private String answer;
    /** 兼容字段：片段文本列表 */
    private List<String> referencedChunks;
    /** 引用精准定位：片段来源文档 / 序号 / 相似度分 */
    private List<ChunkReference> references;
    /** 事实性校验：答案中未被引用片段支持的句子（rag.groundedness-check 开关） */
    private List<String> ungroundedSentences;
    private boolean fallback;
}
