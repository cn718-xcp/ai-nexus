/**
 * 模型服务模块（前端预留：后端 /model-pool、/model-route 接口规划中，当前 Mock 兜底驱动）
 * 真实数据源为 /api/v1/stats/overview 的 byModel 统计
 */
import { http } from '../request'
import type { ModelNode, ModelRouteItem, ToolInfo } from '@/types/api'

export const modelApi = {
  /** Ollama 节点池 */
  nodes() {
    return http.get<ModelNode[]>('/api/v1/model-pool/nodes', undefined, { silent: true, mockFallback: true })
  },
  /** 按场景路由配置 */
  routes() {
    return http.get<ModelRouteItem[]>('/api/v1/model-route/config', undefined, { silent: true, mockFallback: true })
  },
  saveRoutes(routes: ModelRouteItem[]) {
    return http.put<null>('/api/v1/model-route/config', routes, { mockFallback: true })
  },
  /** 可用工具清单（智能体页展示） */
  tools() {
    return http.get<ToolInfo[]>('/api/v1/agent/tool-list', undefined, { silent: true, mockFallback: true })
  },
}
