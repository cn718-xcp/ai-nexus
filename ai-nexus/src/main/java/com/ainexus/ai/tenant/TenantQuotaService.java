package com.ainexus.ai.tenant;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysTenant;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.SysTenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 租户配额管控：
 * - 租户禁用拦截：禁用租户的所有业务请求统一拒绝
 * - 知识库数量配额：建库前实时校验（配额更新立即生效）
 * - 每日模型调用限额：Redis 原子计数按天滚动，超限快速拒绝
 * tenant.enabled=false（dev 默认）时全部直通，行为与单租户模式完全一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantQuotaService {

    private final SysTenantRepository tenantRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final RedissonClient redissonClient;

    @Value("${ainexus.tenant.enabled:false}")
    private boolean tenantEnabled;

    /** 租户是否启用校验：多租户开启且 tenantId 非空时检查租户状态 */
    public void ensureTenantActive(Long tenantId) {
        if (!tenantEnabled || tenantId == null) {
            return;
        }
        SysTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_NOT_FOUND));
        if (tenant.getStatus() != 1) {
            throw new BizException(ErrorCode.TENANT_DISABLED);
        }
    }

    /** 建库前校验：租户状态 + 知识库数量配额 */
    public void ensureKbCreateAllowed(Long tenantId) {
        if (!tenantEnabled || tenantId == null) {
            return;
        }
        ensureTenantActive(tenantId);
        SysTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_NOT_FOUND));
        long count = kbRepository.countByTenantId(tenantId);
        if (count >= tenant.getKbQuota()) {
            log.warn("[tenant-quota] kb quota exceeded, tenant={}, count={}, quota={}",
                    tenant.getCode(), count, tenant.getKbQuota());
            throw new BizException(ErrorCode.TENANT_KB_QUOTA_EXCEEDED);
        }
    }

    /**
     * 模型调用前校验：租户状态 + 每日调用限额（原子自增并判定，超限拒绝）。
     * 计数 Key 按天滚动，TTL 25h 自动清理。
     */
    public void ensureModelCallAllowed(Long tenantId) {
        if (!tenantEnabled || tenantId == null) {
            return;
        }
        ensureTenantActive(tenantId);
        SysTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_NOT_FOUND));
        String key = "ainexus:quota:daily:" + tenantId + ":" + LocalDate.now();
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long used = counter.incrementAndGet();
        if (used == 1) {
            counter.expire(Duration.ofHours(25));
        }
        if (used > tenant.getDailyModelQuota()) {
            log.warn("[tenant-quota] daily quota exceeded, tenant={}, used={}, quota={}",
                    tenant.getCode(), used, tenant.getDailyModelQuota());
            throw new BizException(ErrorCode.TENANT_DAILY_QUOTA_EXCEEDED);
        }
    }

    /** 读取租户信息（多租户开启时；关闭态返回 null） */
    public SysTenant getTenantIfEnabled(Long tenantId) {
        if (!tenantEnabled || tenantId == null) {
            return null;
        }
        return tenantRepository.findById(tenantId).orElse(null);
    }
}
