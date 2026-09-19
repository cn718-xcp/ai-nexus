package com.ainexus.ai.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划输出解析器：将规划 Agent 的 JSON 步骤解析为执行计划。
 * 兼容模型输出的容错：非法 JSON 时降级为"单步直接回答"。
 */
@Slf4j
public final class AgentPlanParser {

    private AgentPlanParser() {
    }

    /** 执行步骤：action=工具名或 answer；args=工具入参或回答指令 */
    public record Step(String thought, String action, String args) {
    }

    /**
     * 解析规划输出为步骤列表；解析失败返回单步 answer（保底可执行）。
     */
    public static List<Step> parse(String planText) {
        List<Step> steps = new ArrayList<>();
        if (planText == null || planText.isBlank()) {
            return List.of(new Step("no plan", "answer", planText));
        }
        try {
            // 容错提取：模型可能用 ```json 包裹
            String json = planText.replaceAll("```json|```", "").trim();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            JSONArray arr = JSONUtil.parseArray(json);
            for (Object o : arr) {
                JSONObject step = (JSONObject) o;
                steps.add(new Step(
                        step.getStr("thought", ""),
                        step.getStr("action", "answer"),
                        step.getStr("args", "")));
            }
        } catch (Exception e) {
            log.warn("[agent-plan] parse failed, fallback to single answer step: {}", e.getMessage());
        }
        if (steps.isEmpty()) {
            steps.add(new Step("plan empty", "answer", planText));
        }
        return steps;
    }
}
