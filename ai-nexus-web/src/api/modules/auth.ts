/** 认证模块 /api/v1/auth */
import { http } from '../request'
import type { LoginRequest, RefreshRequest, RegisterRequest, TokenResponse } from '@/types/api'

export const authApi = {
  /** 登录：返回 accessToken / refreshToken / tokenType / expiresIn(秒) */
  login(data: LoginRequest) {
    return http.post<TokenResponse>('/api/v1/auth/login', data)
  },
  /** 注册：返回新用户 ID */
  register(data: RegisterRequest) {
    return http.post<number>('/api/v1/auth/register', data)
  },
  /** 刷新令牌 */
  refresh(data: RefreshRequest) {
    return http.post<TokenResponse>('/api/v1/auth/refresh', data)
  },
}
