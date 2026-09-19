package com.ainexus;

import com.ainexus.common.config.DynamicConfigService;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysConfig;
import com.ainexus.domain.repository.SysConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 动态配置中心单元测试：优先级链、灰度命中、开关直通、白名单校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamicConfigServiceTest {

    @Mock
    private com.ainexus.common.audit.AuditWriter auditWriter;

    @Mock
    private com.ainexus.common.auth.CurrentUserResolver currentUserResolver;

    @Mock
    private SysConfigRepository configRepository;

    private DynamicConfigService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DynamicConfigService(configRepository, currentUserResolver, auditWriter);
        var field = DynamicConfigService.class.getDeclaredField("dynamicEnabled");
        field.setAccessible(true);
        field.set(service, true);
        // 空仓库：默认全部无记录
        when(configRepository.findByConfigKeyAndTenantId(anyString(), any())).thenReturn(Optional.empty());
        when(configRepository.findByConfigKey(anyString())).thenReturn(List.of());
    }

    @Test
    void shouldFallBackToYmlDefaultWhenNoRecord() {
        assertEquals("0.6", service.get("resilience.circuit-failure-rate", "0.6", null));
        assertEquals(0.6, service.getNumber("resilience.circuit-failure-rate", 0.6, null), 1e-9);
    }

    @Test
    void shouldPreferTenantSpecificOverGlobal() {
        when(configRepository.findByConfigKeyAndTenantId("rag.mode", 7L))
                .thenReturn(Optional.of(config("rag.mode", "hybrid", 7L, "global")));
        when(configRepository.findByConfigKeyAndTenantId("rag.mode", null))
                .thenReturn(Optional.of(config("rag.mode", "vector", null, "global")));
        // 租户专属优先于全局；其他租户/匿名走全局
        assertEquals("hybrid", service.get("rag.mode", "vector", 7L));
        assertEquals("vector", service.get("rag.mode", "vector", 8L));
    }

    @Test
    void shouldApplyGrayValueOnlyToGrayTenants() {
        when(configRepository.findByConfigKey("concurrency.model-max")).thenReturn(List.of(
                config("concurrency.model-max", "8", null, "gray")));
        Mockito.when(configRepository.findAll()).thenReturn(List.of());
        SysConfig gray = config("concurrency.model-max", "8", null, "gray");
        gray.setGrayTenants("7,9");
        when(configRepository.findByConfigKey("concurrency.model-max")).thenReturn(List.of(gray));
        // 灰度命中租户 7 生效；未命中租户 8 走 yml 默认
        assertEquals("8", service.get("concurrency.model-max", "0", 7L));
        assertEquals("0", service.get("concurrency.model-max", "0", 8L));
    }

    @Test
    void shouldPassThroughWhenDisabled() throws Exception {
        var field = DynamicConfigService.class.getDeclaredField("dynamicEnabled");
        field.setAccessible(true);
        field.set(service, false);
        // 开关关闭：直接返回 yml 默认值，不查 DB
        assertEquals("0.6", service.get("resilience.circuit-failure-rate", "0.6", 7L));
        Mockito.verifyNoInteractions(configRepository);
    }

    @Test
    void shouldRejectUnknownConfigKey() {
        assertThrows(BizException.class, () -> service.put("unknown.key", "1", null, "global", null));
    }

    private SysConfig config(String key, String value, Long tenantId, String scope) {
        return SysConfig.builder().configKey(key).configValue(value).tenantId(tenantId).scope(scope).build();
    }
}
