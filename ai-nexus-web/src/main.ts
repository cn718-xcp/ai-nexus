/**
 * 应用入口：全量注册 Element Plus（中文语言包）与图标，挂载 Pinia / Router
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/assets/styles/index.css'

import App from './App.vue'
import router from './router'
import { useUserStore } from '@/stores/user'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus)

// 全局注册 Element Plus 线性图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 路由挂载前恢复本地会话（刷新不丢登录态）
useUserStore().restore()

app.mount('#app')

// 移除首屏加载占位
document.getElementById('boot-splash')?.remove()
