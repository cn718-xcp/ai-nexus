<script setup lang="ts">
/**
 * 登录页：左侧品牌区（流体光斑动效）+ 右侧玻璃登录卡
 * 对接 /auth/login、/auth/register；记住密码；错误码对齐（A0004 用户名或密码错误）
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const app = useAppStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const remember = ref(false)
const regVisible = ref(false)
const regLoading = ref(false)
const regRef = ref<FormInstance>()

const form = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '', confirm: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const regRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,32}$/, message: '4-32 位字母数字下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度 8-64 位', trigger: 'blur' },
  ],
  confirm: [
    {
      validator: (_r, v: string, cb) => {
        if (v !== regForm.password) cb(new Error('两次输入的密码不一致'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
}

const demoMode = computed(() => app.mockActive)

onMounted(() => {
  const remembered = user.rememberedAccount()
  if (remembered) {
    form.username = remembered.username
    form.password = remembered.password
    remember.value = true
  }
})

async function onSubmit() {
  await formRef.value?.validate().catch(() => Promise.reject())
  loading.value = true
  try {
    await user.login({ username: form.username.trim(), password: form.password }, remember.value)
    ElMessage.success('登录成功，欢迎回来')
    await app.detectTenantEnabled()
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch {
    /* 错误提示由请求层统一弹出 */
  } finally {
    loading.value = false
  }
}

async function onRegister() {
  await regRef.value?.validate().catch(() => Promise.reject())
  regLoading.value = true
  try {
    await user.register({ username: regForm.username.trim(), password: regForm.password })
    ElMessage.success('注册成功，请登录')
    form.username = regForm.username
    regVisible.value = false
    regForm.password = regForm.confirm = ''
  } catch {
    /* 统一错误提示 */
  } finally {
    regLoading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <div class="brand-side">
      <div class="orb orb-1 anim-decor"></div>
      <div class="orb orb-2 anim-decor"></div>
      <div class="orb orb-3 anim-decor"></div>
      <div class="brand-content">
        <div class="brand-logo fade-in-item">
          <svg viewBox="0 0 48 48" width="52" height="52">
            <defs>
              <linearGradient id="login-g" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#4080FF" />
                <stop offset="1" stop-color="#165DFF" />
              </linearGradient>
            </defs>
            <path d="M24 3 42 13.5v21L24 45 6 34.5v-21z" fill="url(#login-g)" />
            <circle cx="24" cy="24" r="7" fill="white" fill-opacity="0.92" />
          </svg>
        </div>
        <h1 class="brand-title fade-in-item" style="animation-delay: 60ms">
          AI-NEXUS <span class="an-gradient-text">Platform</span>
        </h1>
        <p class="brand-tagline fade-in-item" style="animation-delay: 120ms">
          企业级 AI 能力平台 · RAG 检索增强 / 智能体编排 / 多租户隔离
        </p>
        <ul class="brand-features fade-in-item" style="animation-delay: 180ms">
          <li><el-icon><Collection /></el-icon>知识库向量化检索，相似度阈值在线可调</li>
          <li><el-icon><Cpu /></el-icon>计划-执行两阶段智能体，思维链全程可视</li>
          <li><el-icon><MagicStick /></el-icon>模型节点池按场景路由，主备切换灰度发布</li>
          <li><el-icon><OfficeBuilding /></el-icon>多租户配额隔离，SSE 流式毫秒级响应</li>
        </ul>
        <div class="brand-foot">Spring Boot 4.0 · Spring AI 2.0 · Milvus · Ollama</div>
      </div>
    </div>

    <!-- 右侧登录卡 -->
    <div class="form-side">
      <div class="glass-strong glass login-card fade-in-item">
        <h2 class="login-title">欢迎回来</h2>
        <p class="login-sub">登录以进入管理控制台</p>

        <el-alert
          v-if="demoMode"
          class="demo-alert"
          type="warning"
          :closable="false"
          show-icon
          title="演示数据模式"
          description="后端未连接，任意账号密码均可登录体验全部页面"
        />

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="'User'" autocomplete="username" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="'Lock'"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="remember">记住密码</el-checkbox>
            <el-link type="primary" :underline="false" @click="regVisible = true">注册账号</el-link>
          </div>
          <el-button
            type="primary"
            size="large"
            class="login-btn"
            :class="{ 'an-loading-sweep': loading }"
            :loading="loading"
            @click="onSubmit"
          >
            {{ loading ? '登录中…' : '登 录' }}
          </el-button>
        </el-form>

        <div class="login-foot">
          <span class="sf-dot" :class="app.mockActive ? 'mock' : 'ok'"></span>
          {{ app.backendOnline === null ? '正在探测后端服务…' : app.mockActive ? '后端离线 · 已切换演示数据' : '后端服务已连接 (localhost:8080)' }}
        </div>
      </div>
    </div>

    <!-- 注册对话框 -->
    <el-dialog v-model="regVisible" title="注册新账号" width="420px" align-center>
      <el-form ref="regRef" :model="regForm" :rules="regRules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="regForm.username" placeholder="4-32 位字母数字下划线" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="regForm.password" type="password" show-password placeholder="8-64 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="regForm.confirm" type="password" show-password placeholder="再次输入密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="regVisible = false">取消</el-button>
        <el-button type="primary" :loading="regLoading" @click="onRegister">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  height: 100dvh;
  display: flex;
  overflow: hidden;
}

/* 品牌区 */
.brand-side {
  flex: 1.15;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-right: 1px solid var(--an-border);
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  opacity: 0.55;
  pointer-events: none;
}
.orb-1 {
  width: 420px;
  height: 420px;
  left: -120px;
  top: -120px;
  background: radial-gradient(circle, rgba(64, 128, 255, 0.5), transparent 70%);
}
.orb-2 {
  width: 360px;
  height: 360px;
  right: -80px;
  bottom: -100px;
  background: radial-gradient(circle, rgba(114, 46, 209, 0.36), transparent 70%);
  animation-delay: 1.2s;
}
.orb-3 {
  width: 260px;
  height: 260px;
  right: 22%;
  top: 12%;
  background: radial-gradient(circle, rgba(20, 201, 201, 0.3), transparent 70%);
  animation-delay: 2.4s;
}
.brand-content {
  position: relative;
  z-index: 1;
  max-width: 460px;
  padding: 40px;
}
.brand-title {
  font-size: 40px;
  font-weight: 800;
  letter-spacing: 0.04em;
  margin: 18px 0 8px;
  color: var(--an-text);
}
.brand-tagline {
  color: var(--an-text-2);
  font-size: 15px;
  margin: 0 0 30px;
  line-height: 1.7;
}
.brand-features {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.brand-features li {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--an-text-2);
  font-size: 13.5px;
}
.brand-features .el-icon {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  color: var(--an-primary);
  background: rgba(var(--an-primary-rgb), 0.1);
  flex-shrink: 0;
}
.brand-foot {
  margin-top: 40px;
  font-size: 11.5px;
  color: var(--an-text-3);
  letter-spacing: 0.08em;
}

/* 表单区 */
.form-side {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px;
}
.login-card {
  width: 400px;
  max-width: 100%;
  padding: 34px 34px 26px;
  border-radius: 20px;
}
.login-title {
  margin: 0 0 4px;
  font-size: 22px;
  color: var(--an-text);
}
.login-sub {
  margin: 0 0 20px;
  color: var(--an-text-3);
  font-size: 13px;
}
.demo-alert {
  margin-bottom: 16px;
}
.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.login-btn {
  width: 100%;
  letter-spacing: 0.2em;
  border-radius: 10px;
}
.login-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 18px;
  font-size: 11.5px;
  color: var(--an-text-3);
  justify-content: center;
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

@media (max-width: 860px) {
  .brand-side {
    display: none;
  }
}
</style>
