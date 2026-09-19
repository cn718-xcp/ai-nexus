package com.ainexus.common.ratelimit;

import java.lang.annotation.*;

/**
 * 接口限流注解，基于 Redisson {@code RRateLimiter}（令牌桶）实现。
 * Key = 前缀 + 接口标识 + 限流维度值（IP 或业务 Key）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 业务 Key SpEL，支持 #paramName / #result 等，空则按 IP 维度限流 */
    String key() default "";

    /** 时间窗口内允许的请求数 */
    int permits() default 10;

    /** 限流时间窗口（秒） */
    int windowSeconds() default 10;
}
