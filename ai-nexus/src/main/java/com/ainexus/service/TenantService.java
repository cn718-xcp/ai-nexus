package com.ainexus.service;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import com.ainexus.domain.entity.SysTenant;
import com.ainexus.domain.repository.SysTenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 租户管理服务：CRUD / 启停 / 配额配置。
 * 配额字段：知识库数量上限、单租户并发上限、每日模型调用限额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final SysTenantRepository tenantRepository;

    @Transactional
    public SysTenant create(String name, String code, Integer kbQuota, Integer concurrentQuota, Integer dailyModelQuota) {
        if (tenantRepository.existsByCode(code)) {
            throw new BizException(ErrorCode.BIZ_ERROR, "租户编码已存在: " + code);
        }
        SysTenant tenant = SysTenant.builder()
                .name(name)
                .code(code)
                .status(1)
                .kbQuota(kbQuota == null ? 5 : kbQuota)
                .concurrentQuota(concurrentQuota == null ? 2 : concurrentQuota)
                .dailyModelQuota(dailyModelQuota == null ? 1000 : dailyModelQuota)
                .build();
        log.info("[tenant] created, code={}", code);
        return tenantRepository.save(tenant);
    }

    @Transactional(readOnly = true)
    public List<SysTenant> list() {
        return tenantRepository.findAll();
    }

    @Transactional(readOnly = true)
    public SysTenant get(Long id) {
        return requireTenant(id);
    }

    /** 启用/禁用租户：禁用后该租户下所有业务请求被拒绝（配额校验入口统一拦截） */
    @Transactional
    public void updateStatus(Long id, int status) {
        SysTenant tenant = requireTenant(id);
        tenant.setStatus(status);
        tenantRepository.save(tenant);
        log.info("[tenant] status updated, code={}, status={}", tenant.getCode(), status);
    }

    /** 更新配额配置（运行时生效：配额校验每次请求实时读取） */
    @Transactional
    public void updateQuota(Long id, Integer kbQuota, Integer concurrentQuota, Integer dailyModelQuota) {
        SysTenant tenant = requireTenant(id);
        if (kbQuota != null) {
            tenant.setKbQuota(kbQuota);
        }
        if (concurrentQuota != null) {
            tenant.setConcurrentQuota(concurrentQuota);
        }
        if (dailyModelQuota != null) {
            tenant.setDailyModelQuota(dailyModelQuota);
        }
        tenantRepository.save(tenant);
        log.info("[tenant] quota updated, code={}, kb={}, concurrent={}, daily={}",
                tenant.getCode(), tenant.getKbQuota(), tenant.getConcurrentQuota(), tenant.getDailyModelQuota());
    }

    /** 更新租户专属模型路由 JSON（优先于全局场景路由） */
    @Transactional
    public void updateModelRoutes(Long id, String modelRoutesJson) {
        SysTenant tenant = requireTenant(id);
        tenant.setModelRoutes(modelRoutesJson);
        tenantRepository.save(tenant);
    }

    private SysTenant requireTenant(Long id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_NOT_FOUND));
    }
}
