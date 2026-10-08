/** JWT 载荷解析（仅前端展示用，不做签名校验） */
export interface JwtPayload {
  sub?: string
  uid?: number
  tid?: number
  token_type?: 'access' | 'refresh'
  exp?: number
  iat?: number
  [key: string]: unknown
}

export function decodeJwtPayload(token: string): JwtPayload | null {
  try {
    const part = token.split('.')[1]
    if (!part) return null
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join(''),
    )
    return JSON.parse(json) as JwtPayload
  } catch {
    return null
  }
}

/** 生成短随机 ID（会话/消息用） */
export function genId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 20)
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36)
}
