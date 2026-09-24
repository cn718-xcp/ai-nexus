package com.ainexus.ai.tenant;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysTenant;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.SysTenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
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

    /** 用户级 QPS 限流阈值（多租户开启时生效；0 表示不启用用户维度限流） */
    @Value("${ainexus.tenant.user-qps:5}")
    private int userQps;

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
     * 模型调用前校验（三重管控）：租户状态 + 租户 QPS + 每日调用限额。
     * 租户档案仅查询一次，传递给状态/QPS/每日限额三段校验复用（此前同一 tenantId 重复查库 3 次）。
     * QPS：租户维度复用 concurrent_quota 作为每秒上限；用户维度按 user-qps 配置。
     * 每日限额：Redis 原子自增并判定，计数 Key 按天滚动，TTL 25h 自动清理。
     */
    public void ensureModelCallAllowed(Long tenantId, Long userId) {
        if (!tenantEnabled || tenantId == null) {
            return;
        }
        SysTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_NOT_FOUND));
        if (tenant.getStatus() != 1) {
            throw new BizException(ErrorCode.TENANT_DISABLED);
        }
        ensureTenantQps(tenant);
        ensureUserQps(userId);
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

    /** 租户维度 QPS：令牌桶按秒补充，超限快速拒绝 */
    private void ensureTenantQps(SysTenant tenant) {
        RRateLimiter limiter = redissonClient.getRateLimiter("ainexus:quota:qps:t:" + tenant.getId());
        // trySetRate 幂等：首次设置后不重置已发放的令牌桶
        limiter.trySetRate(RateType.OVERALL, tenant.getConcurrentQuota(), 1, RateIntervalUnit.SECONDS);
        if (!limiter.tryAcquire()) {
            log.warn("[tenant-quota] tenant qps exceeded, tenant={}", tenant.getCode());
            throw new BizException(ErrorCode.TENANT_QPS_EXCEEDED);
        }
    }

    /** 用户维度 QPS：防止单用户占满租户额度（user-qps<=0 时不启用） */
    private void ensureUserQps(Long userId) {
        if (userId == null || userQps <= 0) {
            return;
        }
        RRateLimiter limiter = redissonClient.getRateLimiter("ainexus:quota:qps:u:" + userId);
        limiter.trySetRate(RateType.OVERALL, userQps, 1, RateIntervalUnit.SECONDS);
        if (!limiter.tryAcquire()) {
            log.warn("[tenant-quota] user qps exceeded, user={}", userId);
            throw new BizException(ErrorCode.TENANT_QPS_EXCEEDED);
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
