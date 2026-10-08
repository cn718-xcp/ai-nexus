/**
 * RAG 模块 /api/v1/rag
 * - ask：同步问答（含引用片段与相似度分）
 * - stream：SSE 流式问答（POST + fetch 流式读取，data: 为纯文本 token 增量，流关闭即生成完毕）
 */
import type { RagAnswerResponse, RagChatRequest } from '@/types/api'
import { BizError, refreshTokenOnce, request } from '../request'
import { mockStreamTokens } from '@/mock/engine'
import { loadTenantId, loadTokens } from '@/utils/auth-storage'
import { genTraceId } from '@/utils/trace'

export const ragApi = {
  /** 同步 RAG 问答 */
  ask(data: RagChatRequest) {
    return request<RagAnswerResponse>({ method: 'POST', url: '/api/v1/rag/ask', data }, { timeout: 120000 })
  },
  /** 检索质量评估 */
  eval(data: { kbName?: string; topK?: number; cases: { question: string; expectedKeywords: string[] }[] }) {
    return request({
      method: 'POST',
      url: '/api/v1/rag/eval',
      data,
    }, { timeout: 120000 })
  },
}

/* ================= SSE 流式问答 ================= */

export interface RagStreamHandlers {
  onToken: (token: string) => void
  onDone: () => void
  onError: (err: Error) => void
}

export interface RagStreamController {
  start: (params: RagChatRequest, handlers: RagStreamHandlers) => Promise<void>
  abort: () => void
}

/** 创建可中断的 RAG 流式会话控制器 */
export function createRagStream(): RagStreamController {
  let controller: AbortController | null = null
  let aborted = false

  const abort = () => {
    aborted = true
    controller?.abort()
  }

  async function start(params: RagChatRequest, handlers: RagStreamHandlers): Promise<void> {
    aborted = false
    controller = new AbortController()

    // ---- Mock 模式：本地生成 token 增量，模拟打字节奏 ----
    const { useAppStore } = await import('@/stores/app')
    if (useAppStore().mockActive) {
      try {
        for (const token of mockStreamTokens()) {
          if (aborted) return
          handlers.onToken(token)
          await new Promise((r) => setTimeout(r, 22 + Math.random() * 40))
        }
        handlers.onDone()
      } catch {
        /* 用户中断 */
      }
      return
    }

    // ---- 真实 SSE：POST + ReadableStream 读取（EventSource 不支持 POST）----
    const base = import.meta.env.VITE_API_BASE_URL || ''
    const tokens = loadTokens()
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Trace-Id': genTraceId(),
    }
    if (tokens?.accessToken) headers['Authorization'] = `Bearer ${tokens.accessToken}`
    const tid = loadTenantId()
    if (tid) headers['X-Tenant-Id'] = tid

    try {
      let resp = await fetch(base + '/api/v1/rag/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify(params),
        signal: controller.signal,
      })

      // 与 axios 路径对齐：HTTP 401（令牌缺失/失效）先单飞刷新重试一次，仍失败再走错误分支
      if (resp.status === 401 && !aborted) {
        const refreshed = await refreshTokenOnce()
        if (refreshed) {
          const retryTokens = loadTokens()
          if (retryTokens?.accessToken) headers['Authorization'] = `Bearer ${retryTokens.accessToken}`
          resp = await fetch(base + '/api/v1/rag/stream', {
            method: 'POST',
            headers,
            body: JSON.stringify(params),
            signal: controller.signal,
          })
        }
      }

      const contentType = resp.headers.get('content-type') || ''
      if (!resp.ok || contentType.includes('application/json')) {
        // 建流前校验失败（鉴权/知识库不存在/配额超限等）：返回普通 JSON 错误体
        let code = 'A0001'
        let message = '模型服务暂时不可用'
        try {
          const body = (await resp.json()) as { code?: string; message?: string }
          if (body.code) {
            code = body.code
            message = body.message || message
          }
        } catch {
          /* 非 JSON 响应体 */
        }
        handlers.onError(new BizError(code, message))
        return
      }

      const reader = resp.body?.getReader()
      if (!reader) {
        handlers.onError(new Error('响应流不可用'))
        return
      }
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let dataLines: string[] = []

      // SSE 规范：空行为事件边界；同一事件的多条 data: 行以 \n 还原
      // （Spring MVC 会把 token 内的换行拆成多行 data: 发送，逐行独立派发会丢失所有换行）
      const flushEvent = () => {
        if (dataLines.length === 0) return
        const token = dataLines.join('\n')
        dataLines = []
        if (token) handlers.onToken(token)
      }
      const handleLine = (line: string) => {
        if (line === '') {
          flushEvent()
          return
        }
        if (!line.startsWith('data:')) return
        // SSE 规范：去除 "data:" 后单个前导空格，保留其余内容；注释行/其它字段忽略
        let value = line.slice(5)
        if (value.startsWith(' ')) value = value.slice(1)
        dataLines.push(value)
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() || ''
        for (const line of lines) handleLine(line)
      }
      if (buffer) handleLine(buffer)
      flushEvent()
      handlers.onDone()
    } catch (e) {
      if (aborted || (e as Error).name === 'AbortError') {
        handlers.onDone()
        return
      }
      handlers.onError(e instanceof Error ? e : new Error('流式连接异常'))
    }
  }

  return { start, abort }
}
