package com.ainexus;

import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelRouteStrategy;
import com.ainexus.ai.route.ModelScene;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 多模型路由（策略模式）单元测试：策略选择优先级与无策略兜底。
 */
class ModelRouteContextTest {

    @Test
    void shouldRouteByFirstSupportingStrategy() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatModel ragModel = mock(ChatModel.class);
        ModelRouteStrategy chatStrategy = new ModelRouteStrategy() {
            @Override
            public boolean supports(ModelScene scene) {
                return scene == ModelScene.CHAT;
            }

            @Override
            public ChatModel route(ModelScene scene) {
                return chatModel;
            }
        };
        ModelRouteStrategy ragStrategy = new ModelRouteStrategy() {
            @Override
            public boolean supports(ModelScene scene) {
                return scene == ModelScene.RAG || scene == ModelScene.AGENT;
            }

            @Override
            public ChatModel route(ModelScene scene) {
                return ragModel;
            }
        };

        ModelRouteContext context = new ModelRouteContext(List.of(chatStrategy, ragStrategy));
        assertSame(chatModel, context.route(ModelScene.CHAT));
        assertSame(ragModel, context.route(ModelScene.RAG));
        assertSame(ragModel, context.route(ModelScene.AGENT));
    }

    @Test
    void shouldThrowWhenNoStrategySupports() {
        ModelRouteContext context = new ModelRouteContext(List.of());
        assertThrows(RuntimeException.class, () -> context.route(ModelScene.MULTIMODAL));
    }
}
