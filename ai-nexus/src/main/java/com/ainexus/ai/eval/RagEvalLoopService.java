package com.ainexus.ai.eval;

import com.ainexus.common.metrics.BusinessMetrics;
import com.ainexus.domain.entity.RagBadCase;
import com.ainexus.domain.repository.RagBadCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 效果评估闭环服务：
 * - 自动收集：LLM 三维评分任一维度低于阈值（默认 3）即写入 Bad Case 库
 * - 人工标注：reviewed 标记 + 反馈备注，定向优化提示词与检索策略
 * - 效果指标：评估通过率/低分率埋点 Prometheus（按场景聚合趋势）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvalLoopService {

    private final LlmJudgeEvaluator judge;
    private final RagBadCaseRepository badCaseRepository;
    private final BusinessMetrics metrics;

    @Value("${ainexus.eval.badcase-threshold:3}")
    private int badcaseThreshold;

    /** 评估单条问答（含引用），低分自动入 Bad Case 库 */
    public EvalOutcome evaluateAnswer(String question, List<String> references, String answer) {
        var scores = judge.judge(question,
                references == null ? "" : String.join("\n---\n", references), answer);
        boolean bad = scores.consistency() < badcaseThreshold
                || scores.relevance() < badcaseThreshold
                || scores.completeness() < badcaseThreshold;
        metrics.onEvalResult(!bad);
        if (bad) {
            badCaseRepository.save(RagBadCase.builder()
                    .question(question).answer(answer)
                    .referencesJson(references == null ? "[]" : String.join("\n---\n", references))
                    .consistency(scores.consistency()).relevance(scores.relevance())
                    .completeness(scores.completeness()).reviewed(0)
                    .build());
            log.info("[rag-eval] bad case collected, question={}, scores={}", question, scores.total());
        }
        return new EvalOutcome(scores, bad);
    }

    /** 评估结果：三维评分 + 是否 Bad Case */
    public record EvalOutcome(LlmJudgeEvaluator.Scores scores, boolean bad) {
    }

    /** Bad Case 视图（对外暴露字段收敛） */
    public record BadCaseView(Long id, String question, String answer,
                              Integer consistency, Integer relevance, Integer completeness,
                              java.time.LocalDateTime createdAt) {
    }

    /** Bad Case 列表（reviewed=0 为待人工标注） */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<RagBadCase> pendingBadCases() {
        return badCaseRepository.findByReviewedOrderByCreatedAtDesc(0);
    }

    /** 人工标注反馈 */
    @org.springframework.transaction.annotation.Transactional
    public void feedback(Long id, String feedback) {
        RagBadCase badCase = badCaseRepository.findById(id)
                .orElseThrow(() -> new com.ainexus.common.exception.BizException(
                        com.ainexus.common.api.ErrorCode.NOT_FOUND));
        badCase.setReviewed(1);
        badCase.setFeedback(feedback);
        badCaseRepository.save(badCase);
    }
}
