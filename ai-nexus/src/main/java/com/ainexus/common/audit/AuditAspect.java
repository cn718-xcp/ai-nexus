package com.ainexus.common.audit;

import com.ainexus.common.auth.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面：@Audited 命中后经独立线程池异步落库，记录操作人/动作/目标/IP/结果，
 * 不阻塞业务主链路；审计失败仅告警不影响业务。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditWriter auditWriter;
    private final CurrentUserResolver currentUserResolver;
    private final ExpressionParser parser = new SpelExpressionParser();

    @AfterReturning("@annotation(audited)")
    public void afterReturning(JoinPoint pjp, Audited audited) {
        write(pjp, audited, true, null);
    }

    @AfterThrowing(pointcut = "@annotation(audited)", throwing = "ex")
    public void afterThrowing(JoinPoint pjp, Audited audited, Exception ex) {
        write(pjp, audited, false, ex.getMessage());
    }

    /** 解析上下文后交由 AuditWriter 异步落库（跨 Bean 调用保证 @Async 生效） */
    private void write(JoinPoint pjp, Audited audited, boolean success, String error) {
        Long userId = currentUserResolver.currentUserId();
        Long tenantId = currentUserResolver.currentTenantId();
        String username = currentUserResolver.currentUsername();
        String target = resolveTarget(audited, pjp);
        auditWriter.write(userId, tenantId, username, audited.action(), target, null,
                com.ainexus.common.web.WebUtils.clientIp(), success, error);
    }

    private String resolveTarget(Audited audited, JoinPoint pjp) {
        if (audited.target().isEmpty()) {
            return null;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] names = new DefaultParameterNameDiscoverer()
                    .getParameterNames(((MethodSignature) pjp.getSignature()).getMethod());
            Object[] args = pjp.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    context.setVariable(names[i], args[i]);
                }
            }
            return String.valueOf(parser.parseExpression(audited.target()).getValue(context));
        } catch (Exception e) {
            return null;
        }
    }

}
