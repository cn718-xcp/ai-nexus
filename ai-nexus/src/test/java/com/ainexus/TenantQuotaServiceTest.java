package com.ainexus;

import com.ainexus.ai.tenant.TenantQuotaService;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysTenant;
import com.ainexus.domain.repository.KnowledgeBaseRepository;
import com.ainexus.domain.repository.SysTenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 租户配额管控单元测试：开关直通、租户禁用拦截、KB 配额、每日限额。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantQuotaServiceTest {

    @Mock
    private SysTenantRepository tenantRepository;

    @Mock
    private KnowledgeBaseRepository kbRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RAtomicLong dailyCounter;

    private TenantQuotaService service;

    private final SysTenant tenant = SysTenant.builder()
            .id(1L).code("acme").status(1)
            .kbQuota(2).dailyModelQuota(3)
            .build();

    @BeforeEach
    void setUp() {
        service = new TenantQuotaService(tenantRepository, kbRepository, redissonClient);
        setEnabled(true);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        when(redissonClient.getAtomicLong(anyString())).thenReturn(dailyCounter);
    }

    private void setEnabled(boolean enabled) throws RuntimeException {
        try {
            var field = TenantQuotaService.class.getDeclaredField("tenantEnabled");
            field.setAccessible(true);
            field.set(service, enabled);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldPassThroughWhenDisabled() {
        // 开关关闭（dev 默认）：所有校验直通，行为与单租户模式一致
        setEnabled(false);
        assertDoesNotThrow(() -> service.ensureKbCreateAllowed(1L));
        assertDoesNotThrow(() -> service.ensureModelCallAllowed(1L));
        assertDoesNotThrow(() -> service.ensureModelCallAllowed(null));
    }

    @Test
    void shouldRejectDisabledTenant() {
        tenant.setStatus(0);
        BizException e = assertThrows(BizException.class, () -> service.ensureModelCallAllowed(1L));
        assertEquals(ErrorCode.TENANT_DISABLED, e.getErrorCode());
    }

    @Test
    void shouldRejectKbOverQuota() {
        when(kbRepository.countByTenantId(1L)).thenReturn(2L);
        BizException e = assertThrows(BizException.class, () -> service.ensureKbCreateAllowed(1L));
        assertEquals(ErrorCode.TENANT_KB_QUOTA_EXCEEDED, e.getErrorCode());

        when(kbRepository.countByTenantId(1L)).thenReturn(1L);
        assertDoesNotThrow(() -> service.ensureKbCreateAllowed(1L));
    }

    @Test
    void shouldRejectDailyModelQuotaExceeded() {
        // 每日限额 3：第 4 次调用被拒绝
        when(dailyCounter.incrementAndGet()).thenReturn(1L, 2L, 3L, 4L);
        assertDoesNotThrow(() -> service.ensureModelCallAllowed(1L));
        assertDoesNotThrow(() -> service.ensureModelCallAllowed(1L));
        assertDoesNotThrow(() -> service.ensureModelCallAllowed(1L));
        BizException e = assertThrows(BizException.class, () -> service.ensureModelCallAllowed(1L));
        assertEquals(ErrorCode.TENANT_DAILY_QUOTA_EXCEEDED, e.getErrorCode());
    }
}
