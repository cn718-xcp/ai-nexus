/** 对话模块 /api/v1/chat */
import { http, request } from '../request'
import type { ChatAnswerResponse, ChatMessageRecord, ChatRequest, ExtractedInfo } from '@/types/api'

export const chatApi = {
  /** 多轮对话（同步） */
  send(data: ChatRequest) {
    return http.post<ChatAnswerResponse>('/api/v1/chat', data, { timeout: 120000 })
  },
  /** 会话回放：按 sessionId 拉取服务端历史记录 */
  replay(sessionId: string) {
    return http.get<ChatMessageRecord[]>(`/api/v1/chat/${encodeURIComponent(sessionId)}/replay`, undefined, {
      silent: true,
    })
  },
  /** 结构化抽取 */
  extract(text: string) {
    return request<ExtractedInfo>({ method: 'POST', url: '/api/v1/chat/extract', params: { text } }, { timeout: 60000 })
  },
  /** 关键要点列表 */
  keywords(topic: string) {
    return request<string[]>({ method: 'POST', url: '/api/v1/chat/keywords', params: { topic } }, { timeout: 60000 })
  },
}
