package com.ainexus.ai.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用片段精准定位信息：RAG 答案携带来源可溯源（文档名 / 片段序号 / 相似度分）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkReference {

    /** 片段文本 */
    private String content;

    /** 来源文档名（入库时写入元数据） */
    private String documentName;

    /** 来源文档 ID（docUuid） */
    private String documentId;

    /** 片段在文档内的序号 */
    private Integer chunkIndex;

    /** 相似度得分（0-1，向量检索返回） */
    private Double score;

    /** 片段在原文中的起始字符偏移 */
    private Integer startOffset;
    /** 片段在原文中的结束字符偏移 */
    private Integer endOffset;
    /** 片段所属章节标题 */
    private String section;
}
