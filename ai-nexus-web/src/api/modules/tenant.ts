/** 租户模块 /api/v1/tenants（多租户开关开启时展示菜单） */
import { http } from '../request'
import type { SysTenant, TenantCreateRequest, TenantQuotaRequest } from '@/types/api'

export const tenantApi = {
  list() {
    return http.get<SysTenant[]>('/api/v1/tenants', undefined, { silent: true })
  },
  detail(id: number) {
    return http.get<SysTenant>(`/api/v1/tenants/${id}`)
  },
  create(data: TenantCreateRequest) {
    return http.post<SysTenant>('/api/v1/tenants', data)
  },
  /** 启用/禁用（status: 1|0） */
  setStatus(id: number, status: 0 | 1) {
    return http.put<null>(`/api/v1/tenants/${id}/status/${status}`)
  },
  updateQuota(id: number, data: TenantQuotaRequest) {
    return http.put<null>(`/api/v1/tenants/${id}/quota`, data)
  },
  /** 更新专属模型路由（modelRoutes 为 JSON 字符串，如 '{"CHAT":"qwen2.5:7b"}'） */
  updateRoutes(id: number, modelRoutes: string) {
    return http.put<null>(`/api/v1/tenants/${id}/routes`, { modelRoutes })
  },
}
