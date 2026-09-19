package com.ainexus.ai.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索进阶配置：重排 / 混合检索 / 引用定位。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ainexus.rag")
public class RagProperties {

    /** 检索模式：vector-纯向量召回；hybrid-向量召回 + 关键词重打分 RRF 融合 */
    private String mode = "vector";

    /** 是否启用二阶段重排（Embedding 余弦精排） */
    private boolean rerankEnabled = false;

    /** 过召回倍数：实际召回 topK*overFetch 条候选，精排后截断回 topK */
    private int overFetch = 3;

    /** RRF 融合常数（越大，排名差异对融合分影响越平缓） */
    private int rrfK = 60;
}
