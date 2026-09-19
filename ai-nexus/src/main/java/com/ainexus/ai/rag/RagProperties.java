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

    /** 位置权重：融合打分中片段位置因子的权重（0=关闭，默认关闭保持原排序） */
    private double positionWeight = 0.0;

    // ---------------- 分块策略 ----------------
    /** 分块策略：fixed-固定长度 | parent_child-父子分块 | semantic-语义分块 | auto-超长文档自动父子 */
    private String chunkStrategy = "fixed";
    /** auto 模式下启用父子分块的文档字符数阈值 */
    private int autoParentChildChars = 20000;
    /** 父块大小（字符） */
    private int parentSize = 1600;
    /** 子块大小（字符） */
    private int childSize = 400;
    /** 语义分块：相邻句余弦相似度低于该阈值处断开 */
    private double semanticThreshold = 0.6;
    /** 语义分块单块最大字符数 */
    private int semanticMaxChars = 800;
    /** 引用高亮标记开关（答案中标注来源序号） */
    private boolean citationMarkers = false;
}
