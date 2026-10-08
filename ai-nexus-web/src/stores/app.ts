/**
 * 应用全局设置：主题 / 动效分级 / Mock 模式 / 侧边栏 / 多租户菜单开关
 */
import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark'
export type AnimLevel = 'high' | 'medium' | 'low' | 'off'
export type MockMode = 'auto' | 'on' | 'off'

const SETTINGS_KEY = 'ainexus.settings'

interface PersistedSettings {
  theme?: ThemeMode
  animationLevel?: AnimLevel
  mockMode?: MockMode
  sidebarCollapsed?: boolean
  tenantEnabled?: boolean
}

function loadPersisted(): PersistedSettings {
  try {
    return JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}')
  } catch {
    return {}
  }
}

function envMockMode(): MockMode {
  const v = (import.meta.env.VITE_USE_MOCK || 'auto').toLowerCase()
  return v === 'on' || v === 'true' ? 'on' : v === 'off' || v === 'false' ? 'off' : 'auto'
}

export const useAppStore = defineStore('app', {
  state: () => {
    const p = loadPersisted()
    return {
      theme: (p.theme || 'light') as ThemeMode,
      animationLevel: (p.animationLevel || 'medium') as AnimLevel,
      mockMode: (p.mockMode || envMockMode()) as MockMode,
      sidebarCollapsed: p.sidebarCollapsed || false,
      /** 后端在线状态：null=探测中，true/false=探测结果 */
      backendOnline: null as boolean | null,
      /** 多租户菜单开关：null=未探测 */
      tenantEnabled: p.tenantEnabled !== undefined ? p.tenantEnabled : null as boolean | null,
      /** 全局搜索框可见性 */
      searchVisible: false,
    }
  },

  getters: {
    /** 当前请求是否走 Mock：强制 on，或 auto 且后端离线 */
    mockActive(state): boolean {
      if (state.mockMode === 'on') return true
      if (state.mockMode === 'off') return false
      return state.backendOnline === false
    },
    /** 是否显示演示模式徽标（在线但局部接口用 Mock 兜底时由页面自行提示） */
    isDark(state): boolean {
      return state.theme === 'dark'
    },
  },

  actions: {
    persist() {
      const data: PersistedSettings = {
        theme: this.theme,
        animationLevel: this.animationLevel,
        mockMode: this.mockMode,
        sidebarCollapsed: this.sidebarCollapsed,
        tenantEnabled: this.tenantEnabled ?? undefined,
      }
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(data))
    },

    /** 初始化：应用主题与动效档位到 <html>，并探测后端可达性 */
    init() {
      this.applyTheme()
      this.applyAnimLevel()
      if (this.mockMode === 'on') {
        this.backendOnline = false
      } else {
        this.probeBackend()
      }
    },

    applyTheme() {
      document.documentElement.setAttribute('data-theme', this.theme)
      // Element Plus 深色变量由 html.dark 类激活
      document.documentElement.classList.toggle('dark', this.theme === 'dark')
      this.persist()
    },

    toggleTheme() {
      this.theme = this.theme === 'dark' ? 'light' : 'dark'
      this.applyTheme()
    },

    applyAnimLevel() {
      document.documentElement.setAttribute('data-anim', this.animationLevel)
      this.persist()
    },

    setAnimLevel(level: AnimLevel) {
      this.animationLevel = level
      this.applyAnimLevel()
    },

    setMockMode(mode: MockMode) {
      this.mockMode = mode
      if (mode === 'on') this.backendOnline = false
      else if (mode === 'off') this.backendOnline = true
      else this.probeBackend()
      this.persist()
    },

    /** 探测后端：GET /health（免鉴权），2.5s 超时 */
    async probeBackend() {
      this.backendOnline = null
      try {
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), 2500)
        const resp = await fetch('/health', { signal: controller.signal })
        clearTimeout(timer)
        this.backendOnline = resp.ok
      } catch {
        this.backendOnline = false
      }
    },

    markOffline() {
      this.backendOnline = false
    },

    /** 探测多租户能力：GET /api/v1/tenants 仅在明确被拒绝（401/403）时隐藏菜单；Mock 模式恒显示（演示数据可预览） */
    async detectTenantEnabled() {
      if (this.tenantEnabled !== null) return
      if (this.mockActive) {
        this.tenantEnabled = true
        this.persist()
        return
      }
      try {
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), 2500)
        const base = import.meta.env.VITE_API_BASE_URL || ''
        const resp = await fetch(base + '/api/v1/tenants', {
          signal: controller.signal,
          headers: this.authHeaders(),
        })
        clearTimeout(timer)
        this.tenantEnabled = resp.ok || ![401, 403].includes(resp.status)
      } catch {
        // 探测失败不隐藏菜单，避免误伤
        this.tenantEnabled = true
      }
      this.persist()
    },

    authHeaders(): Record<string, string> {
      try {
        const tokens = JSON.parse(localStorage.getItem('ainexus.auth.tokens') || 'null')
        const headers: Record<string, string> = {}
        if (tokens?.accessToken) headers['Authorization'] = `Bearer ${tokens.accessToken}`
        const tid = localStorage.getItem('ainexus.auth.tenantId')
        if (tid) headers['X-Tenant-Id'] = tid
        return headers
      } catch {
        return {}
      }
    },

    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      this.persist()
    },
  },
})
