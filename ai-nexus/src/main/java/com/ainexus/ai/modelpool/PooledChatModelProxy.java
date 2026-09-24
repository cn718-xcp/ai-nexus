package com.ainexus.ai.modelpool;

import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 池化 ChatModel 包装：JDK 动态代理在调用前后维护节点连接计数，
 * 支撑 least_connections 调度；同步调用结束即释放，流式调用在
 * 流终止（完成/出错/取消）时释放。对调用方完全透明。
 */
public final class PooledChatModelProxy implements InvocationHandler {

    private final ModelNodePool pool;
    private final ModelNode node;
    private final ChatModel delegate;

    private PooledChatModelProxy(ModelNodePool pool, ModelNode node, ChatModel delegate) {
        this.pool = pool;
        this.node = node;
        this.delegate = delegate;
    }

    /** 包装 ChatModel：返回带节点连接感知的代理实例 */
    public static ChatModel wrap(ModelNodePool pool, ModelNode node, ChatModel delegate) {
        return (ChatModel) Proxy.newProxyInstance(
                PooledChatModelProxy.class.getClassLoader(),
                new Class<?>[]{ChatModel.class},
                new PooledChatModelProxy(pool, node, delegate));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        boolean streaming = Flux.class.isAssignableFrom(method.getReturnType());
        if (!streaming) {
            pool.begin(node);
            try {
                return method.invoke(delegate, args);
            } finally {
                pool.end(node);
            }
        }
        // 流式：doFinally 覆盖 完成/异常/取消（含 SSE 客户端断开）全部终止信号；
        // 流创建前的同步异常（如请求构建失败）也必须归还计数，否则 least_connections 永久失真
        pool.begin(node);
        try {
            Object result = method.invoke(delegate, args);
            return ((Flux<?>) result).doFinally(signal -> pool.end(node));
        } catch (Throwable e) {
            pool.end(node);
            throw e;
        }
    }
}
