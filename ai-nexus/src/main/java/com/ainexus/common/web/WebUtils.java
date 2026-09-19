package com.ainexus.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Web 上下文工具：客户端 IP 解析（限流/审计/配置等多处共用）。
 */
public final class WebUtils {

    private WebUtils() {
    }

    /** 当前请求客户端 IP：多级代理取第一个 X-Forwarded-For；无请求上下文返回 null */
    public static String clientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        return ip != null && !ip.isBlank() ? ip.split(",")[0].trim() : request.getRemoteAddr();
    }
}
