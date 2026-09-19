package com.ainexus.common.expression;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * SpEL Key 解析器：限流/幂等注解共用的维度 Key 求值工具。
 * 将方法参数名与实参绑定到求值上下文，解析注解中的 SpEL 表达式。
 */
@Component
public class SpelKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * @param expression SpEL 表达式（如 "#req.sessionId"）
     * @param method     目标方法（用于解析参数名）
     * @param args       方法实参
     * @return 求值结果字符串（null 归一化为 "null"）
     */
    public String resolve(String expression, Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] names = nameDiscoverer.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        return String.valueOf(parser.parseExpression(expression).getValue(context));
    }
}
