/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端 API 地址（生产用），默认同源 */
  readonly VITE_API_BASE_URL?: string
  /** Vite 代理目标（开发用） */
  readonly VITE_PROXY_TARGET?: string
  /** Mock 模式：auto | on | off */
  readonly VITE_USE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
