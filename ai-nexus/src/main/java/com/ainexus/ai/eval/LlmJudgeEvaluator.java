package com.ainexus.ai.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 评审器：以模型作为评审员，对 RAG 回答做三维度评分（1-5）。
 * - 事实一致性（answer 是否被引用片段支持）
 * - 相关性（是否回答了问题）
 * - 完整度（信息是否充分）
 * 评审函数可注入替换（单测友好）；JSON 解析失败返回中性分并标记解析失败。
 */
@Slf4j
@Component
public class LlmJudgeEvaluator {

    private static final String JUDGE_PROMPT =
            "你是 RAG 回答质量评审员。根据【问题】【引用片段】【回答】三要素，输出 JSON："
            + "{\"consistency\":1-5,\"relevance\":1-5,\"completeness\":1-5}。"
            + "一致性以引用片段为唯一事实依据；引用不足导致无法核实时 consistency 记 2。只输出 JSON。";

    /** 三维评分结果 */
    public record Scores(int consistency, int relevance, int completeness, boolean parseOk) {
        public int total() {
            return consistency + relevance + completeness;
        }
    }

    private final ChatModel chatModel;

    public LlmJudgeEvaluator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 默认评审实现：模型 JSON 输出解析（失败中性分 2/2/2） */
    public Scores judge(String question, String references, String answer) {
        try {
            String raw = ChatClient.create(chatModel).prompt()
                    .system(JUDGE_PROMPT)
                    .user(u -> u.text("【问题】{q}\n\n【引用片段】\n{r}\n\n【回答】\n{a}")
                            .param("q", question).param("r", references).param("a", answer))
                    .call().content();
            return parseScores(raw);
        } catch (Exception e) {
            log.warn("[llm-judge] judge failed: {}", e.getMessage());
            return new Scores(2, 2, 2, false);
        }
    }

    /** 评分 JSON 解析（容错提取 {} 块） */
    public Scores parseScores(String raw) {
        try {
            String json = raw.replaceAll("```json|```", "").trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new Scores(2, 2, 2, false);
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.substring(start, end + 1));
            return new Scores(clamp(node.path("consistency").asInt(2)),
                    clamp(node.path("relevance").asInt(2)),
                    clamp(node.path("completeness").asInt(2)), true);
        } catch (Exception e) {
            return new Scores(2, 2, 2, false);
        }
    }

    private int clamp(int v) {
        return Math.max(1, Math.min(5, v));
    }

    /** 单条评审便捷方法（引用以 Map 列表传入） */
    public Scores judge(Map<String, Object> refsModel, String question, List<String> refs, String answer) {
        return judge(question, String.join("\n---\n", refs), answer);
    }
}
