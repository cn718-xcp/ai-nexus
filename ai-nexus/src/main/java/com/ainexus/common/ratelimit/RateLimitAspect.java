package com.ainexus.common.ratelimit;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.constant.RedisKeyConstants;
import com.ainexus.common.exception.BizException;
import com.ainexus.common.expression.SpelKeyResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 限流切面：每个 方法+维度 组合一个独立的 RRateLimiter（令牌桶）。
 * 限流配置持久化在 Redis 中，多实例天然共享。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;
    private final SpelKeyResolver spelKeyResolver;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String limiterKey = RedisKeyConstants.RATE_LIMIT + resolveKey(pjp, rateLimit);
        RRateLimiter limiter = redissonClient.getRateLimiter(limiterKey);
        // setRate 幂等：配置未变化时不会重置已发放的令牌桶
        limiter.trySetRate(RateType.OVERALL, rateLimit.permits(), rateLimit.windowSeconds(), RateIntervalUnit.SECONDS);
        if (!limiter.tryAcquire()) {
            log.warn("[rate-limit] blocked, key={}", limiterKey);
            throw new BizException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return pjp.proceed();
    }

    /** 解析限流维度：优先 SpEL 业务 Key，否则回退到客户端 IP。 */
    private String resolveKey(ProceedingJoinPoint pjp, RateLimit rateLimit) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String suffix;
        if (!rateLimit.key().isEmpty()) {
            suffix = spelKeyResolver.resolve(rateLimit.key(), method, pjp.getArgs());
        } else {
            suffix = clientIp();
        }
        return method.getDeclaringClass().getSimpleName() + ":" + method.getName() + ":" + suffix;
    }

    private String clientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        // 多级代理取第一个 IP；X-Forwarded-For 可被伪造，生产应结合网关白名单
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
