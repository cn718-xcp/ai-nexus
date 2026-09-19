package com.ainexus;

import com.ainexus.ai.eval.LlmJudgeEvaluator;
import com.ainexus.ai.eval.RagEvalLoopService;
import com.ainexus.domain.entity.RagBadCase;
import com.ainexus.domain.repository.RagBadCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 效果评估闭环单元测试：评分解析/降级、Bad Case 收集与人工标注。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RagEvalLoopTest {

    @Mock
    private LlmJudgeEvaluator judge;

    @Mock
    private RagBadCaseRepository badCaseRepository;

    @Mock
    private com.ainexus.common.metrics.BusinessMetrics metrics;

    private RagEvalLoopService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new RagEvalLoopService(judge, badCaseRepository, metrics);
        var f = RagEvalLoopService.class.getDeclaredField("badcaseThreshold");
        f.setAccessible(true);
        f.set(service, 3);
    }

    @Test
    void shouldParseJudgeScoresWithClamp() {
        LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(null);
        var scores = evaluator.parseScores("{\"consistency\":5,\"relevance\":4,\"completeness\":9}");
        assertEquals(5, scores.consistency());
        assertEquals(4, scores.relevance());
        assertEquals(5, scores.completeness(), "超界评分应钳制到 5");
        assertTrue(scores.parseOk());

        // 非 JSON 输出：中性分 + parseOk=false
        var bad = evaluator.parseScores("无法评分");
        assertEquals(2, bad.consistency());
        assertFalse(bad.parseOk());
    }

    @Test
    void shouldCollectBadCaseOnLowScore() {
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(new LlmJudgeEvaluator.Scores(2, 4, 4, true));
        when(badCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var outcome = service.evaluateAnswer("问题", List.of("引用"), "回答");

        assertTrue(outcome.bad(), "一致性 2 低于阈值 3 应判定 Bad Case");
        ArgumentCaptor<RagBadCase> captor = ArgumentCaptor.forClass(RagBadCase.class);
        verify(badCaseRepository).save(captor.capture());
        assertEquals(2, captor.getValue().getConsistency());
        assertEquals(0, captor.getValue().getReviewed());
    }

    @Test
    void shouldNotCollectWhenAllScoresPass() {
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(new LlmJudgeEvaluator.Scores(4, 4, 5, true));
        var outcome = service.evaluateAnswer("问题", List.of("引用"), "回答");
        assertFalse(outcome.bad());
        verify(badCaseRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void shouldApplyFeedbackOnBadCase() {
        RagBadCase stored = RagBadCase.builder().id(1L).question("q").answer("a")
                .consistency(2).relevance(3).completeness(3).reviewed(0).build();
        when(badCaseRepository.findById(1L)).thenReturn(java.util.Optional.of(stored));
        service.feedback(1L, "补充检索词后重测通过");
        assertEquals(1, stored.getReviewed());
        assertEquals("补充检索词后重测通过", stored.getFeedback());
    }
}
