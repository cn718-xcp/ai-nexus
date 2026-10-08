<script setup lang="ts">
/**
 * 租户管理：列表 / 创建 / 启用禁用（二次确认）/ 配额编辑 / 专属模型路由
 * 多租户开关（ainexus.tenant.enabled）开启时由侧边栏展示本菜单
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { tenantApi } from '@/api'
import { useAppStore } from '@/stores/app'
import { formatNumber, formatTime } from '@/utils/format'
import type { SysTenant } from '@/types/api'

const app = useAppStore()
const loading = ref(true)
const list = ref<SysTenant[]>([])

/* ===== 创建 ===== */
const createVisible = ref(false)
const createLoading = ref(false)
const createRef = ref<FormInstance>()
const createForm = reactive({ name: '', code: '', kbQuota: 5, concurrentQuota: 10, dailyModelQuota: 1000 })
const createRules: FormRules = {
  name: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入租户编码', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9-]{2,32}$/, message: '2-32 位字母数字与中划线', trigger: 'blur' },
  ],
}

/* ===== 配额编辑 ===== */
const quotaVisible = ref(false)
const quotaSaving = ref(false)
const quotaTarget = ref<SysTenant | null>(null)
const quotaForm = reactive({ kbQuota: 0, concurrentQuota: 0, dailyModelQuota: 0 })

/* ===== 路由配置 ===== */
const routesVisible = ref(false)
const routesSaving = ref(false)
const routesTarget = ref<SysTenant | null>(null)
const routesJson = ref('')

/* ===== 详情 ===== */
const detailVisible = ref(false)
const detailData = ref<SysTenant | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    list.value = await tenantApi.list()
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  await createRef.value?.validate().catch(() => Promise.reject())
  createLoading.value = true
  try {
    await tenantApi.create({
      name: createForm.name.trim(),
      code: createForm.code.trim(),
      kbQuota: createForm.kbQuota || undefined,
      concurrentQuota: createForm.concurrentQuota || undefined,
      dailyModelQuota: createForm.dailyModelQuota || undefined,
    })
    ElMessage.success('租户创建成功')
    createVisible.value = false
    createForm.name = ''
    createForm.code = ''
    await load()
  } catch {
    /* 统一错误提示 */
  } finally {
    createLoading.value = false
  }
}

function onToggleStatus(t: SysTenant) {
  const target = t.status === 1 ? 0 : 1
  const action = target === 1 ? '启用' : '禁用'
  ElMessageBox.confirm(`确认${action}租户「${t.name}」？${target === 0 ? '禁用后该租户下所有请求将被拒绝。' : ''}`, `${action}确认`, {
    type: target === 0 ? 'warning' : 'info',
    confirmButtonText: action,
    cancelButtonText: '取消',
  })
    .then(async () => {
      await tenantApi.setStatus(t.id, target as 0 | 1)
      ElMessage.success(`已${action}`)
      await load()
    })
    .catch(() => {})
}

function openQuota(t: SysTenant) {
  quotaTarget.value = t
  quotaForm.kbQuota = t.kbQuota ?? 0
  quotaForm.concurrentQuota = t.concurrentQuota ?? 0
  quotaForm.dailyModelQuota = t.dailyModelQuota ?? 0
  quotaVisible.value = true
}

async function saveQuota() {
  if (!quotaTarget.value) return
  quotaSaving.value = true
  try {
    await tenantApi.updateQuota(quotaTarget.value.id, {
      kbQuota: quotaForm.kbQuota || undefined,
      concurrentQuota: quotaForm.concurrentQuota || undefined,
      dailyModelQuota: quotaForm.dailyModelQuota || undefined,
    })
    ElMessage.success('配额已更新')
    quotaVisible.value = false
    await load()
  } catch {
    /* 统一错误提示 */
  } finally {
    quotaSaving.value = false
  }
}

function openRoutes(t: SysTenant) {
  routesTarget.value = t
  try {
    routesJson.value = t.modelRoutes ? JSON.stringify(JSON.parse(t.modelRoutes), null, 2) : '{\n  "CHAT": "qwen2.5:7b"\n}'
  } catch {
    routesJson.value = t.modelRoutes || ''
  }
  routesVisible.value = true
}

async function saveRoutes() {
  if (!routesTarget.value) return
  try {
    JSON.parse(routesJson.value)
  } catch {
    ElMessage.error('路由 JSON 格式不合法')
    return
  }
  routesSaving.value = true
  try {
    await tenantApi.updateRoutes(routesTarget.value.id, routesJson.value)
    ElMessage.success('专属模型路由已更新')
    routesVisible.value = false
    await load()
  } catch {
    /* 统一错误提示 */
  } finally {
    routesSaving.value = false
  }
}

async function openDetail(t: SysTenant) {
  detailVisible.value = true
  detailData.value = t
  try {
    detailData.value = await tenantApi.detail(t.id)
  } catch {
    /* 使用列表数据兜底 */
  }
}
</script>

<template>
  <div class="an-page">
    <PageHeader title="租户管理" subtitle="多租户隔离：配额控制、启停状态与专属模型路由">
      <template #actions>
        <el-button type="primary" :icon="'Plus'" @click="createVisible = true">新建租户</el-button>
      </template>
    </PageHeader>

    <div class="glass tenant-table stagger-item">
      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="租户" min-width="200">
          <template #default="{ row }">
            <div class="t-name">
              <span class="t-avatar">{{ row.name.slice(0, 1) }}</span>
              <div>
                <div class="t-title">{{ row.name }}</div>
                <code class="t-code">{{ row.code }}</code>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              inline-prompt
              active-text="启用"
              inactive-text="禁用"
              @change="onToggleStatus(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="知识库上限" width="120" align="right">
          <template #default="{ row }">{{ row.kbQuota ?? '不限' }}</template>
        </el-table-column>
        <el-table-column label="并发上限" width="110" align="right">
          <template #default="{ row }">{{ row.concurrentQuota ?? '不限' }}</template>
        </el-table-column>
        <el-table-column label="每日调用限额" width="140" align="right">
          <template #default="{ row }">{{ row.dailyModelQuota ? formatNumber(row.dailyModelQuota) : '不限' }}</template>
        </el-table-column>
        <el-table-column label="专属路由" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.modelRoutes" type="primary" size="small" effect="light" round>已配置</el-tag>
            <span v-else class="an-subtle">默认</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" size="small" @click="openQuota(row)">配额</el-button>
            <el-button link type="primary" size="small" @click="openRoutes(row)">路由</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState text="暂无租户" sub="点击右上角「新建租户」开始接入" />
        </template>
      </el-table>
    </div>

    <!-- 创建租户 -->
    <el-dialog v-model="createVisible" title="新建租户" width="480px" align-center>
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="租户名称" prop="name">
          <el-input v-model="createForm.name" placeholder="例如：华东研发中心" maxlength="64" />
        </el-form-item>
        <el-form-item label="租户编码" prop="code">
          <el-input v-model="createForm.code" placeholder="例如：east-rd" maxlength="32" />
        </el-form-item>
        <div class="quota-grid">
          <el-form-item label="知识库上限">
            <el-input-number v-model="createForm.kbQuota" :min="0" :max="9999" style="width: 100%" />
          </el-form-item>
          <el-form-item label="并发上限">
            <el-input-number v-model="createForm.concurrentQuota" :min="0" :max="9999" style="width: 100%" />
          </el-form-item>
          <el-form-item label="每日调用限额">
            <el-input-number v-model="createForm.dailyModelQuota" :min="0" :max="99999999" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 配额编辑 -->
    <el-dialog v-model="quotaVisible" :title="`配置额：${quotaTarget?.name}`" width="440px" align-center>
      <el-form label-position="top">
        <el-form-item label="知识库数量上限">
          <el-input-number v-model="quotaForm.kbQuota" :min="0" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="并发数限制">
          <el-input-number v-model="quotaForm.concurrentQuota" :min="0" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="每日模型调用限额">
          <el-input-number v-model="quotaForm.dailyModelQuota" :min="0" :max="99999999" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" :loading="quotaSaving" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- 专属模型路由 -->
    <el-dialog v-model="routesVisible" :title="`专属模型路由：${routesTarget?.name}`" width="480px" align-center>
      <p class="routes-tip">JSON 字符串，按场景为该租户指定专属模型，例如 {"CHAT":"qwen2.5:14b"}。留空或删除键位回退平台默认路由。</p>
      <el-input v-model="routesJson" type="textarea" :rows="7" style="font-family: Consolas, monospace" />
      <template #footer>
        <el-button @click="routesVisible = false">取消</el-button>
        <el-button type="primary" :loading="routesSaving" @click="saveRoutes">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="detailData?.name" size="380px">
      <el-descriptions v-if="detailData" :column="1" border>
        <el-descriptions-item label="租户编码"><code>{{ detailData.code }}</code></el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detailData.status === 1 ? 'success' : 'danger'" size="small" effect="light" round>
            {{ detailData.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="知识库上限">{{ detailData.kbQuota ?? '不限' }}</el-descriptions-item>
        <el-descriptions-item label="并发上限">{{ detailData.concurrentQuota ?? '不限' }}</el-descriptions-item>
        <el-descriptions-item label="每日调用限额">{{ detailData.dailyModelQuota ? formatNumber(detailData.dailyModelQuota) : '不限' }}</el-descriptions-item>
        <el-descriptions-item label="专属路由">
          <code v-if="detailData.modelRoutes" style="word-break: break-all; font-size: 11px">{{ detailData.modelRoutes }}</code>
          <span v-else class="an-subtle">默认路由</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(detailData.createdAt) }}</el-descriptions-item>
      </el-descriptions>
      <div class="drawer-demo-tip" v-if="app.mockActive">
        <el-alert type="info" :closable="false" show-icon title="当前为演示数据" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.tenant-table {
  padding: 8px 14px 12px;
}
.t-name {
  display: flex;
  align-items: center;
  gap: 10px;
}
.t-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  background: var(--an-primary-gradient);
  flex-shrink: 0;
}
.t-title {
  font-weight: 600;
  color: var(--an-text);
}
.t-code {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.quota-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.routes-tip {
  font-size: 12.5px;
  color: var(--an-text-2);
  line-height: 1.7;
  margin: 0 0 10px;
}
.drawer-demo-tip {
  margin-top: 16px;
}
</style>
