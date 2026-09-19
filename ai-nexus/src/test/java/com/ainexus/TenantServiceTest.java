package com.ainexus;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysTenant;
import com.ainexus.domain.repository.SysTenantRepository;
import com.ainexus.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 租户管理服务单元测试：编码查重、配额更新、启停、存在性校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantServiceTest {

    @Mock
    private SysTenantRepository tenantRepository;

    private TenantService service;

    private final SysTenant tenant = SysTenant.builder()
            .id(1L).name("Acme").code("acme").status(1)
            .kbQuota(5).concurrentQuota(2).dailyModelQuota(1000)
            .build();

    @BeforeEach
    void setUp() {
        service = new TenantService(tenantRepository);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
    }

    @Test
    void shouldCreateTenantWithDefaultQuota() {
        when(tenantRepository.existsByCode("fresh")).thenReturn(false);
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SysTenant created = service.create("Fresh", "fresh", null, null, null);
        assertEquals(5, created.getKbQuota());
        assertEquals(2, created.getConcurrentQuota());
        assertEquals(1000, created.getDailyModelQuota());
    }

    @Test
    void shouldRejectDuplicateCode() {
        when(tenantRepository.existsByCode("acme")).thenReturn(true);
        BizException e = assertThrows(BizException.class, () -> service.create("Acme2", "acme", null, null, null));
        assertEquals(ErrorCode.BIZ_ERROR, e.getErrorCode());
    }

    @Test
    void shouldUpdateQuotaAndStatus() {
        service.updateQuota(1L, 10, 5, 5000);
        assertEquals(10, tenant.getKbQuota());
        assertEquals(5, tenant.getConcurrentQuota());
        assertEquals(5000, tenant.getDailyModelQuota());

        service.updateStatus(1L, 0);
        assertEquals(0, tenant.getStatus());
    }

    @Test
    void shouldThrowWhenTenantMissing() {
        assertThrows(BizException.class, () -> service.get(99L));
    }
}
