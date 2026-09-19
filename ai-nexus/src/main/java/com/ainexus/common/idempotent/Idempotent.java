package com.ainexus.common.idempotent;

import java.lang.annotation.*;

/**
 * 幂等注解：基于 Redis SET NX + 请求摘要实现防重复提交。
 * 同一 Key 在 {@link #ttlSeconds()} 内只允许提交一次。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 幂等维度 SpEL，如 "#req.sessionId"；空则用请求参数摘要 */
    String key() default "";

    /** 幂等窗口（秒） */
    int ttlSeconds() default 10;
}
