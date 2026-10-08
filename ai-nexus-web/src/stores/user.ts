/**
 * 用户状态：登录 / 注册 / 令牌管理 / 退出
 */
import { defineStore } from 'pinia'
import type { LoginRequest, RegisterRequest, TokenResponse } from '@/types/api'
import { authApi } from '@/api/modules/auth'
import {
  clearTokens,
  clearRememberAccount,
  loadRememberAccount,
  loadTenantId,
  loadTokens,
  loadUsername,
  saveRememberAccount,
  saveTenantId,
  saveTokens,
  saveUsername,
} from '@/utils/auth-storage'
import { decodeJwtPayload } from '@/utils/jwt'

export const useUserStore = defineStore('user', {
  state: () => ({
    tokens: null as TokenResponse | null,
    username: '',
    userId: null as number | null,
    tenantId: null as number | null,
  }),

  getters: {
    isLoggedIn(state): boolean {
      return !!state.tokens?.accessToken
    },
  },

  actions: {
    /** 从本地存储恢复会话（页面刷新后） */
    restore() {
      const tokens = loadTokens()
      if (tokens) {
        this.tokens = tokens
        this.username = loadUsername()
        const payload = decodeJwtPayload(tokens.accessToken)
        this.userId = payload?.uid ?? null
        this.tenantId = payload?.tid ?? (loadTenantId() ? Number(loadTenantId()) : null)
      }
    },

    /**
     * 登录：成功后存储双令牌（access_token + refresh_token），自动跳转由调用方处理
     * remember 为 true 时本机记住账号密码
     */
    async login(req: LoginRequest, remember = false) {
      const tokens = await authApi.login(req)
      saveTokens(tokens)
      saveUsername(req.username)
      this.tokens = tokens
      this.username = req.username
      const payload = decodeJwtPayload(tokens.accessToken)
      this.userId = payload?.uid ?? null
      this.tenantId = payload?.tid ?? null
      saveTenantId(this.tenantId)
      if (remember) saveRememberAccount(req.username, req.password)
      else clearRememberAccount()
      return tokens
    },

    async register(req: RegisterRequest) {
      return authApi.register(req)
    },

    /** 应用刷新后的新令牌 */
    applyRefreshed(tokens: TokenResponse) {
      this.tokens = tokens
      saveTokens(tokens)
    },

    /** 退出：清空令牌与记住的密码 */
    logout() {
      clearTokens()
      this.tokens = null
      this.username = ''
      this.userId = null
      this.tenantId = null
    },

    /** 预填记住的账号 */
    rememberedAccount(): { username: string; password: string } | null {
      return loadRememberAccount()
    },
  },
})
