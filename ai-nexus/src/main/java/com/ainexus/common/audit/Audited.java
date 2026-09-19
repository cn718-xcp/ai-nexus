package com.ainexus.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计标记注解：命中方法后异步落库 audit_log。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** 操作类型：KB_CREATE/KB_DELETE/DOC_UPLOAD/LOGIN/REGISTER */
    String action();

    /** 目标对象 SpEL（可选），如 "#req.name" */
    String target() default "";
}
