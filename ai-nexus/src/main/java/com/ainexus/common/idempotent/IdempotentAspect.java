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
    private final com.ainexus.common.auth.CurrentUserResolver currentUserResolver;

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
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            // 业务失败即释放令牌：模型超时/DB 抖动属瞬时故障，不应把客户端重试挡在幂等窗口之外
            redissonClient.getBucket(token).delete();
            throw t;
        }
    }

    /** 解析幂等维度：优先 SpEL 业务 Key，否则回退到请求参数摘要。 */
    private String resolveKey(ProceedingJoinPoint pjp, Idempotent idempotent) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String suffix;
        if (!idempotent.key().isEmpty()) {
            // 维度值可能来自用户文本（消息体/知识库名/上传文件名），统一摘要化：
            // 避免分隔符与任意长度内容构造出与其他维度碰撞或逃逸命名空间的 Key
            suffix = DigestUtil.md5Hex(spelKeyResolver.resolve(idempotent.key(), method, pjp.getArgs()));
        } else {
            // 维度优先级：登录用户 > 请求参数摘要（鉴权开启后幂等按用户维度生效）
            Long userId = currentUserResolver.currentUserId();
            suffix = userId != null ? "u:" + userId : DigestUtil.md5Hex(Arrays.toString(pjp.getArgs()));
        }
        return method.getDeclaringClass().getSimpleName() + ":" + method.getName() + ":" + suffix;
    }
}
