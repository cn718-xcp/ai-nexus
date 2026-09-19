package com.ainexus.ai.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Agent 可控性配置：工具容错/步数限制/思维链输出/白名单。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ainexus.agent")
public class AgentProperties {

    /** 工具调用最大重试次数（指数退避） */
    private int toolMaxRetries = 2;

    /** 工具单次执行超时（毫秒） */
    private long toolTimeoutMs = 8000;

    /** 工具白名单（空=全部放行；非空=仅允许列出的工具名） */
    private List<String> toolWhitelist = List.of();

    /** 规划+执行两阶段协作的最大执行步数（防失控循环） */
    private int maxSteps = 8;

    /** 是否向前端返回思维链（Thought/Action/Observation 过程） */
    private boolean returnThinking = false;

    /** 工具全部不可用/失败时自动降级为纯问答 */
    private boolean fallbackToPlainQa = true;
}
