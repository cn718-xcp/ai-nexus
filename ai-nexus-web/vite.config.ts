import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 后端地址：开发环境通过代理转发，避免 CORS（后端未开启 CORS）
  const backend = env.VITE_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      host: '0.0.0.0',
      open: false,
      proxy: {
        // 业务接口统一前缀
        '/api': { target: backend, changeOrigin: true },
        // Spring Boot Actuator（健康检查 / 指标 / 日志级别）
        '/actuator': { target: backend, changeOrigin: true },
        // 平台探活接口（无 /api/v1 前缀）
        '/health': { target: backend, changeOrigin: true },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 1600,
      rollupOptions: {
        output: {
          // 大依赖单独分包，提升首屏加载
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            element: ['element-plus', '@element-plus/icons-vue'],
            echarts: ['echarts'],
            markdown: ['markdown-it', 'highlight.js'],
          },
        },
      },
    },
  }
})
