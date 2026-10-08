/** 智能体模块 /api/v1/agent */
import { http } from '../request'
import type { AgentChatRequest, AgentPlanResult } from '@/types/api'

export const agentApi = {
  /** 直接调用（单步） */
  invoke(data: AgentChatRequest) {
    return http.post<string>('/api/v1/agent/invoke', data, { timeout: 180000 })
  },
  /** 计划-执行两阶段协作（含思维链，需后端 ainexus.agent.return-thinking=true 才返回 thinkingChain） */
  planExecute(data: AgentChatRequest) {
    return http.post<AgentPlanResult>('/api/v1/agent/plan-execute', data, { timeout: 180000 })
  },
  /** 图片理解（多模态） */
  vision(formData: FormData) {
    return http.upload<string>('/api/v1/agent/vision', formData, undefined, { timeout: 120000 })
  },
}
