/**
 * 路由：登录页独立布局，业务页面挂在 MainLayout 下
 * 守卫：登录校验 + 页面标题 + 多租户菜单探测
 */
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '工作台', icon: 'Odometer' },
      },
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能问答', icon: 'ChatDotRound' },
      },
      {
        path: 'agent',
        name: 'agent',
        component: () => import('@/views/AgentView.vue'),
        meta: { title: '智能体', icon: 'Cpu' },
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/views/KnowledgeListView.vue'),
        meta: { title: '知识库管理', icon: 'Collection' },
      },
      {
        path: 'knowledge/:id',
        name: 'knowledge-detail',
        component: () => import('@/views/KnowledgeDetailView.vue'),
        meta: { title: '知识库详情', parent: '知识库管理', icon: 'Collection' },
      },
      {
        path: 'model',
        name: 'model',
        component: () => import('@/views/ModelView.vue'),
        meta: { title: '模型服务', icon: 'MagicStick' },
      },
      {
        path: 'tenant',
        name: 'tenant',
        component: () => import('@/views/TenantView.vue'),
        meta: { title: '租户管理', icon: 'OfficeBuilding', tenantOnly: true },
      },
      {
        path: 'config',
        name: 'config',
        component: () => import('@/views/ConfigView.vue'),
        meta: { title: '系统配置', icon: 'Setting' },
      },
      {
        path: 'monitor',
        name: 'monitor',
        component: () => import('@/views/MonitorView.vue'),
        meta: { title: '运维监控', icon: 'Monitor' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const user = useUserStore()
  const app = useAppStore()

  // 页面标题
  document.title = to.meta.title ? `${to.meta.title} · AI-Nexus` : 'AI-Nexus · 企业级 AI 能力平台'

  // 登录守卫（静默重定向，不打扰首次访问）
  if (!to.meta.public && !user.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录访问登录页 → 回工作台
  if (to.path === '/login' && user.isLoggedIn) {
    return '/dashboard'
  }

  // 登录后探测多租户能力（决定「租户管理」菜单是否展示）
  if (user.isLoggedIn && to.meta.tenantOnly && app.tenantEnabled === false) {
    return '/dashboard'
  }
  if (user.isLoggedIn) {
    void app.detectTenantEnabled()
  }

  return true
})

router.afterEach(() => {
  /* 页面切换动效由 <transition name="page"> 处理 */
})

export default router
