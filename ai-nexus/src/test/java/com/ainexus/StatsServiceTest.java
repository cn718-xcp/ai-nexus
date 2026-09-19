package com.ainexus;

import com.ainexus.domain.repository.AiCallLogRepository;
import com.ainexus.service.StatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 调用统计服务单元测试：聚合转换与空值防护。
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    @Test
    @SuppressWarnings("unchecked")
    void shouldAggregateWithNullSafety() {
        // 模拟极端数据：token 全空、avg 为 null（全空表 AVG 不会出现，但防御性保留）
        when(aiCallLogRepository.statBySceneAndDate()).thenReturn(List.<Object[]>of(
                new Object[]{"RAG", "2026-09-19", 3L, 620.5},
                new Object[]{"CHAT", "2026-09-19", 2L, null}));
        when(aiCallLogRepository.statByModel()).thenReturn(List.<Object[]>of(
                new Object[]{"qwen2.5:7b", 5L, null, null, 1L}));

        Map<String, Object> overview = new StatsService(aiCallLogRepository).overview(null);

        List<Map<String, Object>> sceneStats = (List<Map<String, Object>>) overview.get("bySceneAndDate");
        assertEquals(2, sceneStats.size());
        assertEquals(621L, sceneStats.get(0).get("avgCostMs"));      // 四舍五入
        assertEquals(0L, sceneStats.get(1).get("avgCostMs"));        // null 防护

        List<Map<String, Object>> modelStats = (List<Map<String, Object>>) overview.get("byModel");
        assertEquals(0L, modelStats.get(0).get("promptTokens"));     // null 防护，不抛 NPE
        assertEquals(0L, modelStats.get(0).get("completionTokens"));
        assertEquals(1L, modelStats.get(0).get("failures"));
        assertEquals(5L, modelStats.get(0).get("calls"));
    }
}
