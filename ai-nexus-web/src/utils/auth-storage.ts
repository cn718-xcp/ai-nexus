/**
 * 令牌与租户信息的本地存储（纯函数层，避免与 Pinia 相互依赖造成循环引用）
 * 请求拦截器直接从这里读取，保证 Axios 封装无副作用
 */
import type { TokenResponse } from '@/types/api'

const TOKEN_KEY = 'ainexus.auth.tokens'
const TENANT_KEY = 'ainexus.auth.tenantId'
const USER_KEY = 'ainexus.auth.username'

export function loadTokens(): TokenResponse | null {
  try {
    const raw = localStorage.getItem(TOKEN_KEY)
    return raw ? (JSON.parse(raw) as TokenResponse) : null
  } catch {
    return null
  }
}

export function saveTokens(tokens: TokenResponse): void {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(tokens))
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(TENANT_KEY)
  localStorage.removeItem(USER_KEY)
}

export function saveTenantId(tenantId: number | null): void {
  if (tenantId === null || tenantId === undefined) localStorage.removeItem(TENANT_KEY)
  else localStorage.setItem(TENANT_KEY, String(tenantId))
}

export function loadTenantId(): string | null {
  return localStorage.getItem(TENANT_KEY)
}

export function saveUsername(username: string): void {
  localStorage.setItem(USER_KEY, username)
}

export function loadUsername(): string {
  return localStorage.getItem(USER_KEY) || ''
}

/** 记住密码（Base64 轻度混淆，仅本机记住用） */
const REMEMBER_KEY = 'ainexus.auth.remember'
export function saveRememberAccount(username: string, password: string): void {
  localStorage.setItem(REMEMBER_KEY, btoa(encodeURIComponent(JSON.stringify({ username, password }))))
}
export function loadRememberAccount(): { username: string; password: string } | null {
  try {
    const raw = localStorage.getItem(REMEMBER_KEY)
    if (!raw) return null
    return JSON.parse(decodeURIComponent(atob(raw)))
  } catch {
    return null
  }
}
export function clearRememberAccount(): void {
  localStorage.removeItem(REMEMBER_KEY)
}
