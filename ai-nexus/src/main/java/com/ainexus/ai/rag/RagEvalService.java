package com.ainexus.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 检索质量评估工具：批量测试集自动评估。
 * 指标：
 * - Recall@K：期望关键词在召回片段中的覆盖率
 * - Top-K 命中率：至少命中一个期望关键词的用例占比
 * - 相似度分布：召回结果相似度均值/最小/最大
 * 测试集结构：[{"question":"...","expectedKeywords":["k1","k2"]}]
 */
@Slf4j
@Service
public class RagEvalService {

    private final VectorStore vectorStore;

    public RagEvalService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 测试用例 */
    public record EvalCase(String question, List<String> expectedKeywords) {
    }

    /** 单用例结果 */
    public record CaseResult(String question, double recall, boolean hit, double avgScore,
                             List<String> retrievedSnippets) {
    }

    /** 评估报告 */
    public record EvalReport(int totalCases, double hitRate, double avgRecall,
                             double scoreAvg, double scoreMin, double scoreMax,
                             List<CaseResult> cases) {
    }

    public EvalReport evaluate(String kbName, int topK, List<EvalCase> testCases) {
        List<CaseResult> results = new ArrayList<>();
        double scoreSum = 0, scoreMin = 1, scoreMax = 0;
        int scoreCount = 0;
        for (EvalCase testCase : testCases) {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(testCase.question()).topK(topK).similarityThreshold(0.0);
            if (kbName != null && !kbName.isBlank()) {
                builder.filterExpression(new FilterExpressionBuilder()
                        .eq("knowledge_base_name", kbName).build());
            }
            List<Document> docs = vectorStore.similaritySearch(builder.build());
            docs = docs == null ? List.of() : docs;

            // Recall@K：期望关键词在召回片段文本中的覆盖比例
            String corpus = docs.stream().map(Document::getText).collect(Collectors.joining("\n")).toLowerCase();
            Set<String> expected = new HashSet<>(testCase.expectedKeywords() == null
                    ? List.of() : testCase.expectedKeywords());
            long hitKeywords = expected.stream().filter(k -> corpus.contains(k.toLowerCase())).count();
            double recall = expected.isEmpty() ? 1.0 : (double) hitKeywords / expected.size();
            boolean hit = !expected.isEmpty() && hitKeywords > 0;

            // 相似度分布
            List<Double> scores = docs.stream().map(d -> d.getScore() == null ? 0.0 : d.getScore()).toList();
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            scoreSum += avg;
            scoreCount++;
            for (double s : scores) {
                scoreMin = Math.min(scoreMin, s);
                scoreMax = Math.max(scoreMax, s);
            }
            results.add(new CaseResult(testCase.question(), recall, hit, avg,
                    docs.stream().limit(3).map(d -> d.getText().length() > 50
                            ? d.getText().substring(0, 50) + "..." : d.getText()).toList()));
        }
        double hitRate = results.stream().filter(CaseResult::hit).count() / (double) Math.max(1, results.size());
        double avgRecall = results.stream().mapToDouble(CaseResult::recall).average().orElse(0);
        EvalReport report = new EvalReport(testCases.size(), hitRate, avgRecall,
                scoreCount == 0 ? 0 : scoreSum / scoreCount,
                scoreCount == 0 ? 0 : scoreMin, scoreCount == 0 ? 0 : scoreMax, results);
        log.info("[rag-eval] cases={}, hitRate={}, avgRecall={}, scoreAvg={}",
                report.totalCases(), report.hitRate(), report.avgRecall(), report.scoreAvg());
        return report;
    }
}
