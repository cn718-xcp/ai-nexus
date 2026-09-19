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
    public Map<String, Object> overview(Long userId, Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        // [scene, callDate, count, avgCostMs]；Map.of 不允许 null 值，聚合字段需判空后装入 HashMap
        // userId 为 null（鉴权关闭）时全局统计，否则按用户隔离
        // 隔离语义：租户统计（含全部成员）> 用户统计 > 全局
        List<Object[]> byScene = tenantId != null
                ? aiCallLogRepository.statBySceneAndDateForTenant(tenantId)
                : (userId == null ? aiCallLogRepository.statBySceneAndDate()
                : aiCallLogRepository.statBySceneAndDateForUser(userId));
        List<Map<String, Object>> sceneStats = byScene.stream().map(row -> {
            Map<String, Object> item = new HashMap<>();
            item.put("scene", row[0]);
            item.put("date", String.valueOf(row[1]));
            item.put("calls", ((Number) row[2]).longValue());
            item.put("avgCostMs", row[3] == null ? 0L : Math.round(((Number) row[3]).doubleValue()));
            return item;
        }).toList();
        result.put("bySceneAndDate", sceneStats);

        // [modelName, count, promptTokens, completionTokens, failCount]
        List<Object[]> byModel = tenantId != null
                ? aiCallLogRepository.statByModelForTenant(tenantId)
                : (userId == null ? aiCallLogRepository.statByModel()
                : aiCallLogRepository.statByModelForUser(userId));
        List<Map<String, Object>> modelStats = byModel.stream().map(row -> {
            Map<String, Object> item = new HashMap<>();
            item.put("model", row[0]);
            item.put("calls", ((Number) row[1]).longValue());
            item.put("promptTokens", row[2] == null ? 0L : ((Number) row[2]).longValue());
            item.put("completionTokens", row[3] == null ? 0L : ((Number) row[3]).longValue());
            item.put("failures", row[4] == null ? 0L : ((Number) row[4]).longValue());
            return item;
        }).toList();
        result.put("byModel", modelStats);
        return result;
    }
}
