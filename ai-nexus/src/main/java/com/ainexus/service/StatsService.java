package com.ainexus.service;

import com.ainexus.domain.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用统计服务：按场景/日期与按模型聚合（数据来自 ai_call_log 异步流水）。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final AiCallLogRepository aiCallLogRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> result = new HashMap<>();
        // [scene, callDate, count, avgCostMs]
        List<Object[]> byScene = aiCallLogRepository.statBySceneAndDate();
        List<Map<String, Object>> sceneStats = byScene.stream().map(row -> Map.<String, Object>of(
                "scene", row[0],
                "date", String.valueOf(row[1]),
                "calls", ((Number) row[2]).longValue(),
                "avgCostMs", Math.round(((Number) row[3]).doubleValue()))).toList();
        result.put("bySceneAndDate", sceneStats);

        // [modelName, count, promptTokens, completionTokens, failCount]
        List<Object[]> byModel = aiCallLogRepository.statByModel();
        List<Map<String, Object>> modelStats = byModel.stream().map(row -> Map.<String, Object>of(
                "model", row[0],
                "calls", ((Number) row[1]).longValue(),
                "promptTokens", row[2] == null ? 0L : ((Number) row[2]).longValue(),
                "completionTokens", row[3] == null ? 0L : ((Number) row[3]).longValue(),
                "failures", ((Number) row[4]).longValue())).toList();
        result.put("byModel", modelStats);
        return result;
    }
}
