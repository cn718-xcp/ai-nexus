package com.ainexus;

import com.ainexus.ai.agent.AgentExecutionController;
import com.ainexus.ai.agent.AgentExecutionController.Decision;
import com.ainexus.ai.agent.AgentPlanParser;
import com.ainexus.ai.agent.ResilientToolCallback;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 可靠性单元测试：步数限制、循环检测、工具重试/超时/白名单、规划解析。
 */
class AgentReliabilityTest {

    // ---------------- 步数限制与循环检测 ----------------

    @Test
    void shouldStopAtMaxSteps() {
        AgentExecutionController controller = new AgentExecutionController(3);
        assertEquals(Decision.PROCEED, controller.beforeStep("a:1"));
        assertEquals(Decision.PROCEED, controller.beforeStep("a:2"));
        assertEquals(Decision.PROCEED, controller.beforeStep("a:3"));
        assertEquals(Decision.STOP_MAX_STEPS, controller.beforeStep("a:4"));
        assertEquals(3, controller.executedSteps());
    }

    @Test
    void shouldDetectLoopAndTerminate() {
        AgentExecutionController controller = new AgentExecutionController(50);
        // 同一签名连续 3 次：第 3 次前置检查判定循环
        assertEquals(Decision.PROCEED, controller.beforeStep("getWeather:{\"city\":\"北京\"}"));
        assertEquals(Decision.PROCEED, controller.beforeStep("getWeather:{\"city\":\"北京\"}"));
        assertEquals(Decision.STOP_LOOP, controller.beforeStep("getWeather:{\"city\":\"北京\"}"));
    }

    @Test
    void shouldRecordThinkingChain() {
        AgentExecutionController controller = new AgentExecutionController(10);
        controller.record("查天气", "getWeather", "晴，18°C");
        String chain = controller.thinkingChain();
        assertTrue(chain.contains("Thought: 查天气"));
        assertTrue(chain.contains("Action: getWeather"));
        assertTrue(chain.contains("Observation: 晴，18°C"));
    }

    // ---------------- 工具装饰器：白名单 / 重试 / 超时 ----------------

    private ToolCallback callback(String name, RuntimeException fail) {
        ToolCallback cb = Mockito.mock(ToolCallback.class);
        ToolDefinition def = Mockito.mock(ToolDefinition.class);
        Mockito.when(def.name()).thenReturn(name);
        Mockito.when(cb.getToolDefinition()).thenReturn(def);
        if (fail == null) {
            Mockito.when(cb.call(Mockito.anyString())).thenReturn("ok");
        } else {
            Mockito.when(cb.call(Mockito.anyString())).thenThrow(fail);
        }
        return cb;
    }

    @Test
    void shouldFilterToolsByWhitelist() {
        ToolCallback weather = callback("getWeather", null);
        ToolCallback time = callback("getCurrentTime", null);
        List<ToolCallback> decorated = ResilientToolCallback.decorate(
                List.of(weather, time), List.of("getWeather"), 0, 5000);
        assertEquals(1, decorated.size());
        assertEquals("getWeather", decorated.get(0).getToolDefinition().name());
        // 空白名单全部放行
        assertEquals(2, ResilientToolCallback.decorate(List.of(weather, time), List.of(), 0, 5000).size());
    }

    @Test
    void shouldRetryAndReturnFailureMessage() {
        ToolCallback flaky = callback("getCurrentTime",
                new RuntimeException("connection refused"));
        List<ToolCallback> decorated = ResilientToolCallback.decorate(List.of(flaky), List.of(), 1, 5000);
        String result = decorated.get(0).call("{}");
        // 模型可感知失败原因并调整策略，而非抛异常中断任务
        assertTrue(result.contains("getCurrentTime"));
        assertTrue(result.contains("调用失败"));
        Mockito.verify(flaky, Mockito.times(2)).call(Mockito.anyString());
    }

    @Test
    void shouldReturnTimeoutFallback() {
        ToolCallback slow = Mockito.mock(ToolCallback.class);
        ToolDefinition def = Mockito.mock(ToolDefinition.class);
        Mockito.when(def.name()).thenReturn("slowTool");
        Mockito.when(slow.getToolDefinition()).thenReturn(def);
        Mockito.when(slow.call(Mockito.anyString())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return "late";
        });
        List<ToolCallback> decorated = ResilientToolCallback.decorate(List.of(slow), List.of(), 0, 200);
        String result = decorated.get(0).call("{}");
        assertTrue(result.contains("超时"), "超时应返回兜底结果");
    }

    // ---------------- 规划解析 ----------------

    @Test
    void shouldParsePlanWithAnswerFallback() {
        List<AgentPlanParser.Step> steps = AgentPlanParser.parse(
                "[{\"thought\":\"查时间\",\"action\":\"getCurrentTime\",\"args\":\"{}\"}," +
                        "{\"thought\":\"回答\",\"action\":\"answer\",\"args\":\"\"}]");
        assertEquals(2, steps.size());
        assertEquals("getCurrentTime", steps.get(0).action());
        assertEquals("answer", steps.get(1).action());
    }

    @Test
    void shouldFallbackToSingleAnswerOnInvalidPlan() {
        List<AgentPlanParser.Step> steps = AgentPlanParser.parse("这不是 JSON");
        assertEquals(1, steps.size());
        assertEquals("answer", steps.get(0).action());
    }
}
