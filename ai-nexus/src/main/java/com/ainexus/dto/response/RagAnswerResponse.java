package com.ainexus.dto.response;

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
    private List<String> referencedChunks;
    private boolean fallback;
}
