/** 统计模块 /api/v1/stats */
import { http } from '../request'
import type { StatsOverview } from '@/types/api'

export const statsApi = {
  /** 调用统计总览：按场景日期 + 按模型（含 Token 消耗与失败数） */
  overview() {
    return http.get<StatsOverview>('/api/v1/stats/overview', undefined, { silent: true })
  },
}
