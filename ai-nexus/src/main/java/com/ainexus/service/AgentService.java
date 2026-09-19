package com.ainexus.service;

import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import com.ainexus.ai.stats.AiCallEvent;
import com.ainexus.ai.stats.AiCallEventPublisher;
import com.ainexus.ai.tools.AiTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

/**
 * Agent 服务：基于 ChatClient 的 Function Calling。
 * Spring AI 2.0 内置工具调用循环（模型发起 -> 框架执行工具 -> 结果回填 -> 模型总结），
 * 无需手写多轮编排即可实现自主决策式 Agent。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ModelRouteContext routeContext;
    private final AiTools aiTools;
    private final AiCallEventPublisher eventPublisher;

    public String invoke(String sessionId, String prompt) {
        long start = System.currentTimeMillis();
        // 每次请求构建会话级 ChatClient：注册 @Tool 工具集（工厂模式）
        ChatClient client = ChatClient.builder(routeContext.route(ModelScene.AGENT))
                .defaultToolCallbacks(MethodToolCallbackProvider.builder().toolObjects(aiTools).build())
                .build();

        String answer = client.prompt()
                .system("你可以调用工具获取实时信息。请用简体中文回答。")
                .user(prompt)
                .call()
                .content();

        eventPublisher.publish(AiCallEvent.builder()
                .scene(ModelScene.AGENT.name())
                .modelName("agent")
                .sessionId(sessionId)
                .success(true)
                .costMs(System.currentTimeMillis() - start)
                .build());
        return answer;
    }
}
