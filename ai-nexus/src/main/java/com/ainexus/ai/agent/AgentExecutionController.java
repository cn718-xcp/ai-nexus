package com.ainexus.ai.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 执行控制器：最大步数限制 + 循环调用检测 + 思维链记录。
 * 纯状态逻辑（无模型依赖），便于单测。
 */
public class AgentExecutionController {

    private final int maxSteps;
    /** 循环检测：连续执行的历史步骤签名（工具名+参数摘要） */
    private final List<String> history = new ArrayList<>();
    private final StringBuilder thinkingChain = new StringBuilder();

    public AgentExecutionController(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public enum Decision {PROCEED, STOP_MAX_STEPS, STOP_LOOP}

    /**
     * 步骤执行前判定：达到最大步数或检测到循环立即终止。
     *
     * @param stepSignature 步骤签名（工具名 + 参数摘要）
     */
    public Decision beforeStep(String stepSignature) {
        if (history.size() >= maxSteps) {
            return Decision.STOP_MAX_STEPS;
        }
        // 循环检测：同一签名连续出现 3 次判定为循环（偶发重试不算）
        long repeats = history.stream().filter(s -> s.equals(stepSignature)).count();
        if (history.size() >= 2 && repeats >= 2 && history.get(history.size() - 1).equals(stepSignature)
                && history.get(history.size() - 2).equals(stepSignature)) {
            return Decision.STOP_LOOP;
        }
        history.add(stepSignature);
        return Decision.PROCEED;
    }

    /** 记录思维链：Thought / Action / Observation */
    public void record(String thought, String action, String observation) {
        thinkingChain.append("Thought: ").append(thought).append('\n')
                .append("Action: ").append(action).append('\n')
                .append("Observation: ").append(observation == null ? "" : truncate(observation)).append("\n\n");
    }

    public String thinkingChain() {
        return thinkingChain.toString();
    }

    public int executedSteps() {
        return history.size();
    }

    private String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** 签名生成：工具名 + 参数（规范化后） */
    public static String signature(String toolName, String args) {
        return toolName + ":" + (args == null ? "" : args.trim().toLowerCase());
    }

    /** 防御性：签名集合（供循环检测变体实现） */
    static boolean hasDuplicateTail(List<String> history, int window) {
        if (history.size() < window) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (int i = history.size() - window; i < history.size(); i++) {
            if (!seen.add(history.get(i))) {
                return true;
            }
        }
        return false;
    }
}
