package com.ainexus.common.config;

import com.ainexus.common.audit.AuditWriter;
import com.ainexus.common.auth.CurrentUserResolver;
import com.ainexus.common.exception.BizException;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.domain.entity.SysConfig;
import com.ainexus.domain.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置中心：运行时配置管控（无需重启生效）。
 * 解析优先级（从高到低）：租户专属 > 灰度命中 > 全局 DB > 本地 yml 默认值。
 * - 内存缓存 + 写时失效：读路径无 DB 开销；配置变更即时生效
 * - 变更审计：记录操作人/变更前后值（经 AuditWriter 异步落库）
 * - dynamic.enabled=false（dev 默认）时直接返回 yml 默认值，行为与原来一致
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicConfigService {

    /** 内置可管控的配置键白名单（防止误配未知键） */
    public static final List<String> MANAGED_KEYS = List.of(
            "resilience.circuit-enabled", "resilience.circuit-window", "resilience.circuit-failure-rate",
            "resilience.circuit-open-seconds", "concurrency.model-max", "concurrency.queue-timeout-seconds",
            "rag.similarity-threshold", "rag.mode", "rag.rerank-enabled", "rag.cache-enabled");

    private final SysConfigRepository configRepository;
    private final CurrentUserResolver currentUserResolver;
    private final AuditWriter auditWriter;

    @Value("${ainexus.dynamic.enabled:false}")
    private boolean dynamicEnabled;

    /** 配置缓存：key = configKey|tenantId，写时失效 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 读取动态配置（字符串）。
     *
     * @param key          配置键
     * @param defaultValue 本地 yml 默认值（DB 无记录时的兜底）
     * @param tenantId     当前租户（灰度/租户专属判定；可为 null）
     */
    public String get(String key, String defaultValue, Long tenantId) {
        if (!dynamicEnabled) {
            return defaultValue;
        }
        String cacheKey = key + "|" + (tenantId == null ? "_" : tenantId);
        return cache.computeIfAbsent(cacheKey, k -> resolve(key, defaultValue, tenantId));
    }

    /** 布尔便捷读取 */
    public boolean getBool(String key, boolean defaultValue, Long tenantId) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue), tenantId));
    }

    /** 数值便捷读取（解析失败回退默认值，防脏配置拖垮主链路） */
    public double getNumber(String key, double defaultValue, Long tenantId) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue), tenantId));
        } catch (Exception e) {
            log.warn("[dynamic-config] invalid number for key={}, fallback={}", key, defaultValue);
            return defaultValue;
        }
    }

    /** 解析链：租户专属 > 灰度命中 > 全局 > yml 默认 */
    private String resolve(String key, String defaultValue, Long tenantId) {
        try {
            if (tenantId != null) {
                String tenantValue = configRepository.findByConfigKeyAndTenantId(key, tenantId)
                        .map(SysConfig::getConfigValue).orElse(null);
                if (tenantValue != null) {
                    return tenantValue;
                }
                // 灰度：仅对 gray_tenants 中的租户生效
                for (SysConfig cfg : configRepository.findByConfigKey(key)) {
                    if ("gray".equals(cfg.getScope()) && isGrayHit(cfg, tenantId)) {
                        return cfg.getConfigValue();
                    }
                }
            }
            return configRepository.findByConfigKeyAndTenantId(key, null)
                    .map(SysConfig::getConfigValue).orElse(defaultValue);
        } catch (Exception e) {
            // 配置读取失败不阻断业务，回退 yml 默认
            log.warn("[dynamic-config] resolve failed for key={}, fallback default: {}", key, e.getMessage());
            return defaultValue;
        }
    }

    private boolean isGrayHit(SysConfig cfg, Long tenantId) {
        if (cfg.getGrayTenants() == null || cfg.getGrayTenants().isBlank()) {
            return false;
        }
        for (String tid : cfg.getGrayTenants().split(",")) {
            if (String.valueOf(tenantId).equals(tid.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写入配置（全局或租户专属）：校验白名单键 + 写 DB + 失效缓存 + 审计。
     */
    @Transactional
    public void put(String key, String value, Long tenantId, String scope, String grayTenants) {
        if (!MANAGED_KEYS.contains(key)) {
            throw new BizException(ErrorCode.CONFIG_INVALID, "未知的配置键: " + key + "，允许的键: " + MANAGED_KEYS);
        }
        String before = resolve(key, null, tenantId);
        SysConfig cfg = configRepository.findByConfigKeyAndTenantId(key, tenantId)
                .orElseGet(() -> SysConfig.builder().configKey(key).build());
        cfg.setConfigValue(value);
        cfg.setScope(scope == null ? "global" : scope);
        cfg.setTenantId(tenantId);
        cfg.setGrayTenants(grayTenants);
        cfg.setUpdatedBy(currentUserResolver.currentUsername() == null ? "anonymous" : currentUserResolver.currentUsername());
        configRepository.save(cfg);
        invalidate(key, tenantId);
        // 变更审计：操作人/前后值/动作（IP 由 AuditAspect 统一采集）
        auditWriter.write(currentUserResolver.currentUserId(), currentUserResolver.currentTenantId(),
                cfg.getUpdatedBy(), "CONFIG_UPDATE", key,
                key + ": " + (before == null ? "(unset)" : before) + " -> " + value,
                com.ainexus.common.web.WebUtils.clientIp(), true, null);
        log.info("[dynamic-config] updated, key={}, tenant={}, value={}", key, tenantId, value);
    }

    /** 全量配置列表（管控面查询） */
    @Transactional(readOnly = true)
    public List<SysConfig> listAll() {
        return configRepository.findAllByOrderByConfigKeyAsc();
    }

    /** 写时失效：同键全部租户维度缓存清除 */
    private void invalidate(String key, Long tenantId) {
        cache.keySet().removeIf(k -> k.startsWith(key + "|"));
    }
}
