/** 评估模块 /api/v1/eval */
import { http } from '../request'
import type { BadCase } from '@/types/api'

export const evalApi = {
  /** 待标注 Bad Case 列表 */
  badcases() {
    return http.get<BadCase[]>('/api/v1/eval/badcases', undefined, { silent: true })
  },
  /** 人工标注反馈 */
  feedback(id: number, feedback: string) {
    return http.post<null>(`/api/v1/eval/badcases/${id}/feedback`, { feedback })
  },
}
