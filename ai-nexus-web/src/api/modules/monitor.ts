/**
 * 运维监控模块
 * - /actuator/*：健康详情 / 指标 / 日志级别（真实后端能力）
 * - /api/v1/trace/{id}、/api/v1/audit/logs：后端规划中，mockFallback 兜底
 */
import { http, request } from '../request'
import type { ActuatorHealth, AuditLog, LoggersResponse, MetricDetail, TraceSpan } from '@/types/api'

export const monitorApi = {
  /** 平台探活（无 /api/v1 前缀） */
  appHealth() {
    return http.get<string>('/health', undefined, { silent: true })
  },
  /**
   * Actuator 系列端点：响应体为 Spring Boot 原生 JSON（无 code 字段），
   * 必须以 raw 模式透传，否则会被 ApiResponse envelope 判定误报失败。
   * 健康 DOWN 返回 503，raw 模式按内容正常渲染。
   */
  actuatorHealth() {
    return http.get<ActuatorHealth>('/actuator/health', undefined, { silent: true, mockFallback: true, raw: true })
  },
  /** 日志器清单 */
  loggers() {
    return http.get<LoggersResponse>('/actuator/loggers', undefined, { silent: true, mockFallback: true, raw: true })
  },
  /** 动态调整日志级别（实时生效，成功响应 204 空 body） */
  setLoggerLevel(name: string, configuredLevel: string) {
    return request<null>({
      method: 'POST',
      url: `/actuator/loggers/${name}`,
      data: { configuredLevel },
    }, { raw: true })
  },
  /** 指标名称清单 */
  metrics() {
    return http.get<{ names: string[] }>('/actuator/metrics', undefined, { silent: true, mockFallback: true, raw: true })
  },
  /** 指标详情 */
  metric(name: string) {
    return http.get<MetricDetail>(`/actuator/metrics/${name}`, undefined, { silent: true, mockFallback: true, raw: true })
  },
  /** Prometheus 原始文本 */
  prometheus() {
    return http.get<string>('/actuator/prometheus', undefined, { silent: true, mockFallback: true, raw: true })
  },
  /** 全链路追踪查询（预留接口，Mock 兜底） */
  trace(traceId: string) {
    return http.get<{ traceId: string; totalMs: number; spans: TraceSpan[]; startAt: string }>(
      `/api/v1/trace/${encodeURIComponent(traceId)}`,
      undefined,
      { silent: true, mockFallback: true },
    )
  },
  /** 审计日志（预留接口，Mock 兜底） */
  auditLogs() {
    return http.get<AuditLog[]>('/api/v1/audit/logs', undefined, { silent: true, mockFallback: true })
  },
}
