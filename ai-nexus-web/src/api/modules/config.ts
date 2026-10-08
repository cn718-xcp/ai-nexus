/** 系统配置模块 /api/v1/config（在线修改实时生效，无需重启） */
import { http } from '../request'
import type { ConfigUpdateRequest, SysConfig } from '@/types/api'

export const configApi = {
  /** 可管控配置键清单 */
  keys() {
    return http.get<string[]>('/api/v1/config/keys')
  },
  /** 当前生效值（优先级：租户专属 > 灰度 > 全局 > yml 默认） */
  effective(tenantId?: number) {
    return http.get<Record<string, string>>('/api/v1/config/effective', tenantId ? { tenantId } : undefined, {
      silent: true,
    })
  },
  /** 全部配置变更记录（操作人 / 变更时间 / 作用域） */
  history() {
    return http.get<SysConfig[]>('/api/v1/config', undefined, { silent: true })
  },
  /** 修改配置 */
  update(data: ConfigUpdateRequest) {
    return http.put<null>('/api/v1/config', data)
  },
}
