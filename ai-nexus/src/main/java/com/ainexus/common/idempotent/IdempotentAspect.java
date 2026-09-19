package com.ainexus.common.idempotent;

import cn.hutool.crypto.digest.DigestUtil;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.constant.RedisKeyConstants;
import com.ainexus.common.exception.BizException;
import com.ainexus.common.expression.SpelKeyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

/**
 * 幂等切面：SET NX 抢占幂等令牌，抢占失败即视为重复请求。
 * 注意：仅做"防重复提交"语义（快速失败），不做结果缓存回放。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final RedissonClient redissonClient;
    private final SpelKeyResolver spelKeyResolver;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String token = RedisKeyConstants.IDEMPOTENT + resolveKey(pjp, idempotent);
        // NX + TTL：原子抢占，窗口内重复请求被拒绝
        boolean acquired = Boolean.TRUE.equals(redissonClient.getBucket(token)
                .setIfAbsent("1", Duration.ofSeconds(idempotent.ttlSeconds())));
        if (!acquired) {
            log.warn("[idempotent] duplicate request, token={}", token);
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }
        return pjp.proceed();
    }

    /** 解析幂等维度：优先 SpEL 业务 Key，否则回退到请求参数摘要。 */
    private String resolveKey(ProceedingJoinPoint pjp, Idempotent idempotent) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String suffix;
        if (!idempotent.key().isEmpty()) {
            suffix = spelKeyResolver.resolve(idempotent.key(), method, pjp.getArgs());
        } else {
            suffix = DigestUtil.md5Hex(Arrays.toString(pjp.getArgs()));
        }
        return method.getDeclaringClass().getSimpleName() + ":" + method.getName() + ":" + suffix;
    }
}
