package com.ainexus.common.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计落库器：独立线程池异步写入，避免阻塞业务主链路。
 * 独立 Bean 保证 @Async 经 Spring 代理生效（规避自调用失效）。
 */
@Slf4j
@Component
public class AuditWriter {

    private final AuditLogRepository repository;

    public AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Async("aiCallExecutor")
    public void write(Long userId, Long tenantId, String username, String action, String target, String ip, boolean success, String error) {
        try {
            repository.save(AuditLog.builder()
                    .operator(username == null ? "anonymous" : username)
                    .userId(userId)
                    .tenantId(tenantId)
                    .action(action)
                    .target(target)
                    .detail(error == null ? "success" : "error: " + error)
                    .clientIp(ip)
                    .success(success ? 1 : 0)
                    .build());
        } catch (Exception e) {
            // 审计失败仅告警不影响业务
            log.warn("[audit] persist failed: {}", e.getMessage());
        }
    }
}
