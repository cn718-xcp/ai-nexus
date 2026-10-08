<script setup lang="ts">
/**
 * 主布局：左侧可折叠玻璃侧边栏 + 顶部导航栏 + 主内容区
 * 层级靠光影区分：侧边栏材质 > 卡片 > 背景；移动端侧边栏变抽屉
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { ElMessageBox } from 'element-plus'
import GlobalSearch from '@/components/GlobalSearch.vue'

const app = useAppStore()
const user = useUserStore()
const route = useRoute()
const router = useRouter()

interface MenuItem {
  path: string
  title: string
  icon: string
  tenantOnly?: boolean
}
const menus: MenuItem[] = [
  { path: '/dashboard', title: '工作台', icon: 'Odometer' },
  { path: '/chat', title: '智能问答', icon: 'ChatDotRound' },
  { path: '/agent', title: '智能体', icon: 'Cpu' },
  { path: '/knowledge', title: '知识库管理', icon: 'Collection' },
  { path: '/model', title: '模型服务', icon: 'MagicStick' },
  { path: '/tenant', title: '租户管理', icon: 'OfficeBuilding', tenantOnly: true },
  { path: '/config', title: '系统配置', icon: 'Setting' },
  { path: '/monitor', title: '运维监控', icon: 'Monitor' },
]

/** 多租户关闭时自动隐藏租户管理菜单 */
const visibleMenus = computed(() => menus.filter((m) => !m.tenantOnly || app.tenantEnabled !== false))

/* 面包屑 */
const breadcrumbs = computed(() => {
  const items: { text: string; path?: string }[] = []
  const parent = route.meta.parent as string | undefined
  if (parent) {
    const parentMenu = menus.find((m) => m.title === parent)
    if (parentMenu) items.push({ text: parent, path: parentMenu.path })
  }
  if (route.meta.title) items.push({ text: route.meta.title as string })
  return items.length ? items : [{ text: '工作台' }]
})

/* 响应式：窄屏侧边栏变抽屉 */
const isMobile = ref(false)
const mobileOpen = ref(false)
function onResize() {
  isMobile.value = window.innerWidth < 900
  if (!isMobile.value) mobileOpen.value = false
}
onMounted(() => {
  onResize()
  window.addEventListener('resize', onResize)
  // 全局搜索快捷键 Ctrl/Cmd + K
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', onKeydown)
})
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    app.searchVisible = true
  }
}

watch(
  () => route.path,
  () => {
    mobileOpen.value = false
  },
)

function onMenuSelect() {
  mobileOpen.value = false
}

async function onLogout() {
  await ElMessageBox.confirm('确定退出登录吗？', '退出确认', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning',
  })
  user.logout()
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: app.sidebarCollapsed && !isMobile, 'mobile-open': mobileOpen }">
      <div class="logo-area" @click="router.push('/dashboard')">
        <svg class="logo-mark" viewBox="0 0 48 48" width="34" height="34">
          <defs>
            <linearGradient id="lg-g" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#165DFF" />
              <stop offset="1" stop-color="#4080FF" />
            </linearGradient>
          </defs>
          <path d="M24 3 42 13.5v21L24 45 6 34.5v-21z" fill="url(#lg-g)" />
          <circle cx="24" cy="24" r="7" fill="white" fill-opacity="0.92" />
        </svg>
        <div v-show="!(app.sidebarCollapsed && !isMobile)" class="logo-text">
          <div class="logo-name">AI-NEXUS</div>
          <div class="logo-sub">企业级 AI 能力平台</div>
        </div>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="route.path"
          :collapse="app.sidebarCollapsed && !isMobile"
          :collapse-transition="false"
          router
          class="side-menu"
          @select="onMenuSelect"
        >
          <el-menu-item v-for="m in visibleMenus" :key="m.path" :index="m.path">
            <el-icon :size="17"><component :is="m.icon" /></el-icon>
            <template #title>{{ m.title }}</template>
          </el-menu-item>
        </el-menu>
      </el-scrollbar>

      <div v-show="!(app.sidebarCollapsed && !isMobile)" class="sidebar-footer">
        <div class="sf-status">
          <span class="sf-dot" :class="app.mockActive ? 'mock' : 'ok'"></span>
          <span>{{ app.mockActive ? '演示数据模式' : '后端已连接' }}</span>
        </div>
      </div>
    </aside>
    <transition name="fade-mask">
      <div v-if="isMobile && mobileOpen" class="mobile-mask" @click="mobileOpen = false"></div>
    </transition>

    <!-- 右侧区域 -->
    <div class="main-area">
      <header class="topbar">
        <div class="tb-left">
          <el-icon v-if="isMobile" class="tb-btn an-icon-hover" :size="19" @click="mobileOpen = !mobileOpen">
            <Expand />
          </el-icon>
          <el-icon
            v-else
            class="tb-btn an-icon-hover"
            :size="19"
            @click="app.sidebarCollapsed = !app.sidebarCollapsed; app.persist()"
          >
            <Fold v-if="!app.sidebarCollapsed" />
            <Expand v-else />
          </el-icon>
          <nav class="crumbs">
            <template v-for="(b, i) in breadcrumbs" :key="i">
              <span v-if="i > 0" class="crumb-sep">/</span>
              <router-link v-if="b.path" :to="b.path" class="crumb-link">{{ b.text }}</router-link>
              <span v-else class="crumb-current">{{ b.text }}</span>
            </template>
          </nav>
        </div>

        <div class="tb-right">
          <el-tag v-if="app.mockActive" size="small" effect="dark" round class="demo-tag">演示数据</el-tag>
          <button class="tb-search" @click="app.searchVisible = true">
            <el-icon :size="14"><Search /></el-icon>
            <span>搜索</span>
            <kbd>Ctrl K</kbd>
          </button>

          <!-- 设置面板 -->
          <el-popover placement="bottom-end" :width="292" trigger="click" popper-class="glass-strong">
            <template #reference>
              <div class="tb-btn an-icon-hover" title="偏好设置">
                <el-icon :size="18"><Operation /></el-icon>
              </div>
            </template>
            <div class="settings-panel">
              <div class="sp-title">偏好设置</div>
              <div class="sp-row">
                <span class="sp-label">Mock 模式</span>
                <el-radio-group :model-value="app.mockMode" size="small" @update:model-value="app.setMockMode($event as any)">
                  <el-radio-button value="auto">自动</el-radio-button>
                  <el-radio-button value="off">真实后端</el-radio-button>
                  <el-radio-button value="on">演示数据</el-radio-button>
                </el-radio-group>
              </div>
              <div class="sp-tip">自动：优先连接后端，离线时降级演示数据</div>
              <div class="sp-row">
                <span class="sp-label">动效分级</span>
                <el-radio-group :model-value="app.animationLevel" size="small" @update:model-value="app.setAnimLevel($event as any)">
                  <el-radio-button value="high">高</el-radio-button>
                  <el-radio-button value="medium">中</el-radio-button>
                  <el-radio-button value="low">低</el-radio-button>
                  <el-radio-button value="off">关</el-radio-button>
                </el-radio-group>
              </div>
              <div class="sp-tip">默认中档；关闭后所有过渡与动画停用</div>
              <div class="sp-row">
                <span class="sp-label">后端状态</span>
                <span class="sp-value" :class="app.backendOnline === false ? 'bad' : 'good'">
                  {{ app.backendOnline === null ? '探测中…' : app.backendOnline ? '已连接 (:8080)' : '离线（演示数据）' }}
                </span>
                <el-button link type="primary" size="small" @click="app.probeBackend()">重测</el-button>
              </div>
              <div class="sp-row">
                <span class="sp-label">多租户菜单</span>
                <el-switch
                  :model-value="app.tenantEnabled !== false"
                  size="small"
                  @update:model-value="(v: string | number | boolean) => { app.tenantEnabled = !!v; app.persist() }"
                />
              </div>
            </div>
          </el-popover>

          <!-- 主题切换 -->
          <div class="tb-btn an-icon-hover" title="切换主题" @click="app.toggleTheme()">
            <el-icon v-if="app.isDark" :size="18"><Sunny /></el-icon>
            <el-icon v-else :size="18"><Moon /></el-icon>
          </div>

          <!-- 用户信息 -->
          <el-dropdown trigger="click" popper-class="glass-strong">
            <div class="tb-user">
              <span class="tb-avatar">{{ (user.username || 'U').slice(0, 1).toUpperCase() }}</span>
              <span class="tb-username">{{ user.username || '未登录' }}</span>
              <el-icon :size="12" class="tb-user-arrow"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  <el-icon><User /></el-icon>
                  {{ user.username }}
                </el-dropdown-item>
                <el-dropdown-item v-if="user.tenantId !== null" disabled>
                  <el-icon><OfficeBuilding /></el-icon>
                  租户 ID：{{ user.tenantId }}
                </el-dropdown-item>
                <el-dropdown-item divided @click="onLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <GlobalSearch v-model:visible="app.searchVisible" />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: var(--an-sidebar-width);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--an-sidebar-bg);
  backdrop-filter: blur(var(--an-blur)) saturate(150%);
  -webkit-backdrop-filter: blur(var(--an-blur)) saturate(150%);
  border-right: 1px solid var(--an-border);
  box-shadow: 4px 0 24px rgba(20, 35, 80, 0.05);
  transition:
    width var(--an-dur-long) var(--an-ease),
    transform var(--an-dur-long) var(--an-ease);
  z-index: 30;
}
.sidebar.collapsed {
  width: var(--an-sidebar-collapsed);
}
.logo-area {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px 14px;
  cursor: pointer;
  border-bottom: 1px solid var(--an-border);
}
.logo-mark {
  flex-shrink: 0;
  filter: drop-shadow(0 4px 10px rgba(var(--an-primary-rgb), 0.35));
}
.logo-name {
  font-weight: 800;
  font-size: 16px;
  letter-spacing: 0.06em;
  background: var(--an-primary-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  line-height: 1.2;
}
.logo-sub {
  font-size: 10.5px;
  color: var(--an-text-3);
  letter-spacing: 0.04em;
}
.menu-scroll {
  flex: 1;
  padding: 10px 0;
}
.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--an-border);
}
.sf-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--an-text-3);
}
.sf-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.sf-dot.ok {
  background: var(--an-success);
  box-shadow: 0 0 0 3px rgba(0, 180, 42, 0.15);
}
.sf-dot.mock {
  background: var(--an-warning);
  box-shadow: 0 0 0 3px rgba(255, 125, 0, 0.15);
}

/* ===== 顶栏 ===== */
.main-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.topbar {
  height: var(--an-header-height);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  border-bottom: 1px solid var(--an-border);
  background: transparent;
}
.tb-left,
.tb-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.tb-btn {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--an-text-2);
  cursor: pointer;
  transition: all var(--an-dur) var(--an-ease);
}
.tb-btn:hover {
  background: rgba(var(--an-primary-rgb), 0.08);
  color: var(--an-primary);
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.crumb-sep {
  color: var(--an-text-4);
}
.crumb-link {
  color: var(--an-text-3);
}
.crumb-link:hover {
  color: var(--an-primary);
}
.crumb-current {
  color: var(--an-text);
  font-weight: 600;
}
.demo-tag {
  background: linear-gradient(135deg, #ff9a2e, #ff7d00);
  border: none;
  font-size: 11px;
}
.tb-search {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
  border: 1px solid var(--an-border);
  background: rgba(var(--an-primary-rgb), 0.04);
  color: var(--an-text-3);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--an-dur) var(--an-ease);
}
.tb-search:hover {
  border-color: rgba(var(--an-primary-rgb), 0.4);
  color: var(--an-primary);
  box-shadow: var(--an-focus-ring);
}
.tb-search kbd {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid var(--an-border);
  background: var(--an-surface-strong);
}
.tb-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 10px;
  transition: background var(--an-dur) var(--an-ease);
}
.tb-user:hover {
  background: rgba(var(--an-primary-rgb), 0.06);
}
.tb-avatar {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  background: var(--an-primary-gradient);
  box-shadow: 0 4px 10px rgba(var(--an-primary-rgb), 0.3);
}
.tb-username {
  font-size: 13px;
  color: var(--an-text);
  font-weight: 500;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tb-user-arrow {
  color: var(--an-text-3);
}

/* ===== 内容区 ===== */
.content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

/* ===== 设置面板 ===== */
.settings-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sp-title {
  font-weight: 700;
  font-size: 14px;
  color: var(--an-text);
}
.sp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.sp-label {
  font-size: 12.5px;
  color: var(--an-text-2);
  width: 72px;
  flex-shrink: 0;
}
.sp-value {
  font-size: 12.5px;
  font-weight: 600;
}
.sp-value.good {
  color: var(--an-success);
}
.sp-value.bad {
  color: var(--an-warning);
}
.sp-tip {
  font-size: 11px;
  color: var(--an-text-3);
  margin-top: -6px;
}

/* ===== 移动端抽屉 ===== */
.mobile-mask {
  position: fixed;
  inset: 0;
  background: rgba(10, 16, 30, 0.4);
  backdrop-filter: blur(3px);
  z-index: 25;
}
@media (max-width: 900px) {
  .sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    transform: translateX(-102%);
    width: var(--an-sidebar-width) !important;
    box-shadow: 12px 0 40px rgba(10, 20, 50, 0.25);
  }
  .sidebar.mobile-open {
    transform: translateX(0);
  }
  .tb-username {
    display: none;
  }
  .tb-search span,
  .tb-search kbd {
    display: none;
  }
  .tb-search {
    width: 34px;
    justify-content: center;
    padding: 0;
  }
}
.fade-mask-enter-active,
.fade-mask-leave-active {
  transition: opacity var(--an-dur-long) var(--an-ease);
}
.fade-mask-enter-from,
.fade-mask-leave-to {
  opacity: 0;
}
</style>
