package com.ainexus;

import com.ainexus.common.expression.SpelKeyResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SpEL Key 解析器单元测试：限流/幂等注解共用的维度求值。
 */
class SpelKeyResolverTest {

    private final SpelKeyResolver resolver = new SpelKeyResolver();

    /** 模拟 Controller 方法签名：参数名需 -parameters 编译（Boot 默认开启） */
    @SuppressWarnings("unused")
    public static void sampleMethod(String sessionId, ChatRequest req) {
    }

    public record ChatRequest(String sessionId, String message) {
    }

    @Test
    void shouldResolveParameterExpression() throws NoSuchMethodException {
        Method method = SpelKeyResolverTest.class.getMethod("sampleMethod", String.class, ChatRequest.class);
        assertEquals("s-001", resolver.resolve("#sessionId", method, new Object[]{"s-001", null}));
    }

    @Test
    void shouldResolveNestedProperty() throws NoSuchMethodException {
        Method method = SpelKeyResolverTest.class.getMethod("sampleMethod", String.class, ChatRequest.class);
        ChatRequest req = new ChatRequest("s-002", "hello");
        assertEquals("s-002", resolver.resolve("#req.sessionId", method, new Object[]{null, req}));
    }

    @Test
    void shouldNormalizeNullToString() throws NoSuchMethodException {
        Method method = SpelKeyResolverTest.class.getMethod("sampleMethod", String.class, ChatRequest.class);
        assertEquals("null", resolver.resolve("#sessionId", method, new Object[]{null, null}));
    }
}
