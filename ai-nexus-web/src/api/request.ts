/**
 * Axios 统一封装
 * - 自动注入 Authorization: Bearer {token} / X-Tenant-Id / X-Trace-Id
 * - 统一响应体解析：code === 'A0000' 视为成功（后端所有业务错误均为 HTTP 200 + 业务码）
 * - HTTP 401 → 单飞刷新令牌后重试，失败跳登录；403 → 提示无权限；404 → 静默
 * - 后端离线时自动降级 Mock（auto 模式），保证无后端环境可预览全部页面
 */
import axios, { AxiosError } from 'axios'
import type { AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse, TokenResponse } from '@/types/api'
import { mockRequest } from '@/mock/engine'
import { loadTenantId, loadTokens, saveTokens, clearTokens } from '@/utils/auth-storage'
import { genTraceId, recordTraceId } from '@/utils/trace'

export const SUCCESS_CODE = 'A0000'

/** 业务错误 */
export class BizError extends Error {
  code: string
  data: unknown
  silent: boolean
  constructor(code: string, message: string, data: unknown = null, silent = false) {
    super(message)
    this.code = code
    this.data = data
    this.silent = silent
  }
}

export interface RequestOpts {
  /** 不弹错误提示（轮询等静默场景） */
  silent?: boolean
  /** 接口在后端暂未提供（A0003/A0001）时回退 Mock（预留模块使用） */
  mockFallback?: boolean
  /** 原生端点透传（如 /actuator/**）：响应体非 ApiResponse 包装，直接返回原始 JSON，不按 code 判定成功/失败 */
  raw?: boolean
  timeout?: number
  /** 内部标记：已完成一次刷新重试 */
  _retried?: boolean
}

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

/* ================= 请求拦截：注入认证与追踪头 ================= */
instance.interceptors.request.use((cfg: InternalAxiosRequestConfig) => {
  const tokens = loadTokens()
  if (tokens?.accessToken) {
    cfg.headers.set('Authorization', `Bearer ${tokens.accessToken}`)
  }
  // 多租户兼容：租户 ID 自动注入请求头（后端亦可从 JWT tid claim 识别）
  const tid = loadTenantId()
  if (tid) cfg.headers.set('X-Tenant-Id', tid)
  cfg.headers.set('X-Trace-Id', genTraceId())
  // FormData 上传时让浏览器自动生成 multipart boundary
  if (cfg.data instanceof FormData) {
    cfg.headers.delete('Content-Type')
  }
  return cfg
})

/* ================= 响应拦截：捕获 traceId ================= */
instance.interceptors.response.use(
  (resp) => {
    recordTraceId(resp.headers?.['x-trace-id'])
    return resp
  },
  (error) => {
    recordTraceId(error?.response?.headers?.['x-trace-id'])
    return Promise.reject(error)
  },
)

/* ================= 令牌刷新（单飞）================= */
let refreshing: Promise<boolean> | null = null

function tryRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const tokens = loadTokens()
      if (!tokens?.refreshToken) return false
      try {
        const resp = await instance.post('/api/v1/auth/refresh', { refreshToken: tokens.refreshToken })
        const body = resp.data as ApiResponse<TokenResponse>
        if (body.code === SUCCESS_CODE && body.data?.accessToken) {
          saveTokens(body.data)
          return true
        }
        return false
      } catch {
        return false
      } finally {
        setTimeout(() => (refreshing = null), 200)
      }
    })()
  }
  return refreshing
}

/** 供 SSE 等非 axios 通道复用的单飞令牌刷新（成功后令牌已更新到本地存储） */
export function refreshTokenOnce(): Promise<boolean> {
  return tryRefresh()
}

function forceLogout(reason?: string) {
  clearTokens()
  if (reason) ElMessage.warning(reason)
  // Hash 路由，直接定位登录页；若已在登录页则不重复跳转
  if (!location.hash.startsWith('#/login')) {
    location.hash = '#/login'
  }
}

/* ================= 业务码处理 ================= */
function bizMessage(code: string, message: string): string {
  if (code === 'D0001') return '请求过于频繁，请稍后再试'
  if (code === 'D0002') return '请勿重复提交请求'
  return message || '操作失败'
}

function isAuthCode(code: string): boolean {
  return code === 'E0001' || code === 'E0002'
}

/* ================= 核心请求函数 ================= */
export async function request<T>(config: AxiosRequestConfig, opts: RequestOpts = {}): Promise<T> {
  const { silent = false, mockFallback = false, _retried = false } = opts

  // ---- Mock 模式（强制 on 或 auto 且后端离线）----
  const { useAppStore } = await import('@/stores/app')
  const appStore = useAppStore()
  if (appStore.mockActive) {
    const mockResp = await mockRequest({
      method: config.method,
      url: config.url || '',
      data: config.data,
      params: config.params,
    })
    if (mockResp) {
      if (mockResp.code !== SUCCESS_CODE) throw new BizError(mockResp.code, mockResp.message, mockResp.data, silent)
      return mockResp.data as T
    }
  }

  try {
    // raw 模式：原生端点（如 /actuator/**）响应体非 ApiResponse 包装，
    // 直接透传原始 JSON，任何 HTTP 状态均不抛错（健康 DOWN 503 等由调用方按内容渲染）
    if (opts.raw) {
      const resp = await instance.request({ ...config, timeout: opts.timeout ?? config.timeout, validateStatus: () => true })
      return resp.data as T
    }

    const resp = await instance.request<ApiResponse<T>>({ ...config, timeout: opts.timeout ?? config.timeout })
    const body = resp.data

    if (body.code === SUCCESS_CODE) return body.data

    // ---- 令牌失效：尝试刷新后重试一次 ----
    if (isAuthCode(body.code) && !_retried && loadTokens()?.refreshToken) {
      const refreshed = await tryRefresh()
      if (refreshed) return request<T>(config, { ...opts, _retried: true })
      forceLogout('登录已过期，请重新登录')
    }
    if (isAuthCode(body.code)) {
      forceLogout('登录已过期，请重新登录')
      throw new BizError(body.code, body.message, body.data, silent)
    }

    // ---- 常规业务错误：统一弹窗 ----
    if (!silent) ElMessage.error(bizMessage(body.code, body.message))
    throw new BizError(body.code, body.message, body.data, silent)
  } catch (e) {
    if (e instanceof BizError) {
      // 预留模块兜底：后端未实现的接口（A0003 资源不存在 / A0001 系统繁忙）回退 Mock
      if (mockFallback && (e.code === 'A0003' || e.code === 'A0001') && !appStore.mockActive) {
        const mockResp = await mockRequest({
          method: config.method,
          url: config.url || '',
          data: config.data,
          params: config.params,
        })
        if (mockResp && mockResp.code === SUCCESS_CODE) return mockResp.data as T
      }
      throw e
    }

    const axiosError = e as AxiosError<ApiResponse>
    const status = axiosError.response?.status

    if (status === 401) {
      // 真正的 HTTP 401：Security 过滤链返回（JWT 缺失/无效）
      if (!_retried && loadTokens()?.refreshToken) {
        const refreshed = await tryRefresh()
        if (refreshed) return request<T>(config, { ...opts, _retried: true })
      }
      forceLogout('登录状态已失效，请重新登录')
      throw new BizError('E0002', '登录状态已失效', null, silent)
    }
    if (status === 403) {
      if (!silent) ElMessage.warning('暂无权限执行该操作')
      throw new BizError('A0004', '暂无权限', null, silent)
    }
    if (status === 404) {
      // 404 静默处理；预留模块可回退 Mock
      if (mockFallback) {
        const mockResp = await mockRequest({
          method: config.method,
          url: config.url || '',
          data: config.data,
          params: config.params,
        })
        if (mockResp && mockResp.code === SUCCESS_CODE) return mockResp.data as T
      }
      throw new BizError('A0003', '资源不存在', null, true)
    }

    // ---- 网络层错误：标记离线并降级 Mock ----
    if (!status) {
      appStore.markOffline()
      if (appStore.mockMode !== 'off') {
        const mockResp = await mockRequest({
          method: config.method,
          url: config.url || '',
          data: config.data,
          params: config.params,
        })
        if (mockResp) {
          if (mockResp.code !== SUCCESS_CODE) throw new BizError(mockResp.code, mockResp.message, mockResp.data, silent)
          return mockResp.data as T
        }
      }
      if (!silent) ElMessage.error('网络异常，请检查后端服务是否可用')
      throw new BizError('NETWORK', '网络异常', null, silent)
    }

    if (!silent) ElMessage.error('系统繁忙，请稍后重试')
    throw e
  }
}

/* ================= 便捷方法 ================= */
export const http = {
  get<T>(url: string, params?: Record<string, unknown>, opts?: RequestOpts) {
    return request<T>({ method: 'GET', url, params }, opts)
  },
  post<T>(url: string, data?: unknown, opts?: RequestOpts) {
    return request<T>({ method: 'POST', url, data }, opts)
  },
  put<T>(url: string, data?: unknown, opts?: RequestOpts) {
    return request<T>({ method: 'PUT', url, data }, opts)
  },
  delete<T>(url: string, opts?: RequestOpts) {
    return request<T>({ method: 'DELETE', url }, opts)
  },
  /** 上传（可监听进度） */
  upload<T>(url: string, formData: FormData, onProgress?: (percent: number) => void, opts?: RequestOpts) {
    return request<T>(
      {
        method: 'POST',
        url,
        data: formData,
        timeout: 120000,
        onUploadProgress: (e) => {
          if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
        },
      },
      opts,
    )
  },
}

/** 获取当前 axios 实例（特殊场景使用） */
export function getAxiosInstance() {
  return instance
}
