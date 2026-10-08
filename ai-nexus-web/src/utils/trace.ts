/**
 * 链路追踪辅助：后端 TraceIdFilter 会读取/回写 X-Trace-Id 响应头。
 * 前端在响应拦截器中捕获 traceId，供「运维监控-链路追踪」快速回查。
 */

const MAX_RECENT = 30
const recent: string[] = []
const listeners = new Set<() => void>()

export function recordTraceId(traceId: string | undefined | null): void {
  if (!traceId) return
  const idx = recent.indexOf(traceId)
  if (idx !== -1) recent.splice(idx, 1)
  recent.unshift(traceId)
  if (recent.length > MAX_RECENT) recent.pop()
  listeners.forEach((fn) => fn())
}

export function getRecentTraceIds(): string[] {
  return [...recent]
}

export function onTraceChanged(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export function genTraceId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return 't-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}
