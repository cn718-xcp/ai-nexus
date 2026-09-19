package com.ainexus.common.constant;

/**
 * Redis Key 常量，统一命名规范：项目:模块:业务:{变量}。
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
    }

    /** 限流 Key 前缀，后接接口粒度 Key */
    public static final String RATE_LIMIT = "ainexus:ratelimit:";
    /** 幂等 Key 前缀，后接请求摘要 */
    public static final String IDEMPOTENT = "ainexus:idempotent:";
    /** 对话上下文缓存 */
    public static final String CHAT_CONTEXT = "ainexus:chat:context:";
}
