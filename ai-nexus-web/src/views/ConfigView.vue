<script setup lang="ts">
/**
 * 系统配置：分类展示可管控配置项（熔断 / 并发 / 检索），在线修改实时生效
 * 配置变更历史：操作人 + 变更时间 + 作用域
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { configApi } from '@/api'
import { formatTime } from '@/utils/format'
import type { SysConfig } from '@/types/api'

interface KeyMeta {
  key: string
  desc: string
  kind: 'boolean' | 'number' | 'enum' | 'text'
  options?: string[]
  unit?: string
  min?: number
  max?: number
}

const KEY_META: KeyMeta[] = [
  { key: 'resilience.circuit-enabled', desc: '熔断器总开关', kind: 'boolean' },
  { key: 'resilience.circuit-window', desc: '熔断统计窗口', kind: 'number', unit: '秒', min: 1, max: 600 },
  { key: 'resilience.circuit-failure-rate', desc: '触发熔断的失败率阈值', kind: 'number', min: 0, max: 1 },
  { key: 'resilience.circuit-open-seconds', desc: '熔断开启持续时间', kind: 'number', unit: '秒', min: 1, max: 3600 },
  { key: 'concurrency.model-max', desc: '模型最大并发数', kind: 'number', min: 1, max: 999 },
  { key: 'concurrency.queue-timeout-seconds', desc: '排队超时时间', kind: 'number', unit: '秒', min: 1, max: 300 },
  { key: 'rag.similarity-threshold', desc: 'RAG 相似度阈值', kind: 'number', min: 0, max: 1 },
  { key: 'rag.mode', desc: '检索模式', kind: 'enum', options: ['vector', 'hybrid'] },
  { key: 'rag.rerank-enabled', desc: '是否启用重排', kind: 'boolean' },
  { key: 'rag.cache-enabled', desc: '是否启用检索缓存', kind: 'boolean' },
]

const GROUPS: { prefix: string; title: string; desc: string; icon: string }[] = [
  { prefix: 'resilience', title: '熔断与韧性', desc: '错误率熔断保护，防止雪崩', icon: 'Warning' },
  { prefix: 'concurrency', title: '并发控制', desc: '模型并发与排队超时', icon: 'Histogram' },
  { prefix: 'rag', title: 'RAG 检索', desc: '相似度阈值与检索模式', icon: 'Search' },
]

const loading = ref(true)
const effective = ref<Record<string, string>>({})
const history = ref<SysConfig[]>([])
const activeTab = ref('settings')

/* ===== 编辑对话框 ===== */
const editVisible = ref(false)
const editSaving = ref(false)
const editMeta = ref<KeyMeta | null>(null)
const editForm = reactive({
  value: '',
  scope: 'global' as 'global' | 'gray',
  grayTenants: '',
  tenantId: undefined as number | undefined,
})
const editBool = ref(true)
const editNum = ref(0)

const grouped = computed(() =>
  GROUPS.map((g) => ({
    ...g,
    items: KEY_META.filter((k) => k.key.startsWith(g.prefix + '.')),
  })).filter((g) => g.items.length),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [eff, hist] = await Promise.allSettled([configApi.effective(), configApi.history()])
    if (eff.status === 'fulfilled') effective.value = eff.value
    if (hist.status === 'fulfilled') history.value = hist.value
  } finally {
    loading.value = false
  }
}

function openEdit(meta: KeyMeta) {
  editMeta.value = meta
  const cur = effective.value[meta.key] ?? ''
  if (meta.kind === 'boolean') editBool.value = cur === 'true'
  else if (meta.kind === 'number') editNum.value = Number(cur) || 0
  editForm.value = cur
  editForm.scope = 'global'
  editForm.grayTenants = ''
  editForm.tenantId = undefined
  editVisible.value = true
}

async function saveEdit() {
  const meta = editMeta.value
  if (!meta) return
  let value = editForm.value
  if (meta.kind === 'boolean') value = String(editBool.value)
  else if (meta.kind === 'number') value = String(editNum.value)
  if (!value) {
    ElMessage.warning('配置值不能为空')
    return
  }
  editSaving.value = true
  try {
    await configApi.update({
      key: meta.key,
      value,
      scope: editForm.scope,
      grayTenants: editForm.scope === 'gray' ? editForm.grayTenants || undefined : undefined,
      tenantId: editForm.tenantId ?? undefined,
    })
    ElMessage.success('配置已保存，实时生效')
    editVisible.value = false
    await load()
  } catch {
    /* 统一错误提示 */
  } finally {
    editSaving.value = false
  }
}

function isModified(key: string): boolean {
  return !!history.value.find((h) => h.configKey === key)
}

function boolTag(v: string | undefined) {
  if (v === 'true') return { text: '开启', type: 'success' as const }
  if (v === 'false') return { text: '关闭', type: 'info' as const }
  return { text: v ?? '—', type: 'primary' as const }
}
</script>

<template>
  <div class="an-page">
    <PageHeader title="系统配置" subtitle="熔断参数、限流 QPS、RAG 阈值在线修改，实时生效无需重启">
      <template #actions>
        <el-button :icon="'Refresh'" circle @click="load" />
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="glass config-tabs stagger-item">
      <!-- 配置项 -->
      <el-tab-pane label="配置项" name="settings">
        <div v-loading="loading" class="config-groups">
          <div v-for="g in grouped" :key="g.prefix" class="config-group">
            <div class="cg-head">
              <span class="cg-icon"><el-icon :size="16"><component :is="g.icon" /></el-icon></span>
              <div>
                <div class="cg-title">{{ g.title }}</div>
                <div class="cg-desc">{{ g.desc }}</div>
              </div>
            </div>
            <div class="cg-items">
              <div v-for="meta in g.items" :key="meta.key" class="config-row">
                <div class="cr-info">
                  <code class="cr-key">{{ meta.key }}</code>
                  <span class="cr-desc">{{ meta.desc }}</span>
                </div>
                <div class="cr-value">
                  <el-tag
                    v-if="meta.kind === 'boolean'"
                    :type="boolTag(effective[meta.key]).type"
                    size="small"
                    effect="light"
                    round
                  >
                    {{ boolTag(effective[meta.key]).text }}
                  </el-tag>
                  <span v-else class="cr-current">
                    {{ effective[meta.key] ?? '—' }}<span v-if="meta.unit && effective[meta.key]" class="cr-unit"> {{ meta.unit }}</span>
                  </span>
                  <el-tag v-if="isModified(meta.key)" size="small" type="warning" effect="plain" round>已修改</el-tag>
                  <el-button type="primary" text size="small" :icon="'EditPen'" @click="openEdit(meta)">修改</el-button>
                </div>
              </div>
            </div>
          </div>
          <EmptyState v-if="!grouped.length && !loading" text="未获取到配置项" sub="请检查后端 /api/v1/config/keys 是否可用" />
        </div>
      </el-tab-pane>

      <!-- 变更历史 -->
      <el-tab-pane label="变更历史" name="history">
        <el-table :data="history" style="width: 100%">
          <el-table-column prop="configKey" label="配置键" min-width="220">
            <template #default="{ row }"><code class="cr-key">{{ row.configKey }}</code></template>
          </el-table-column>
          <el-table-column prop="configValue" label="配置值" min-width="120">
            <template #default="{ row }"><span class="cr-current">{{ row.configValue }}</span></template>
          </el-table-column>
          <el-table-column label="作用域" width="150">
            <template #default="{ row }">
              <el-tag :type="row.scope === 'gray' ? 'warning' : 'primary'" size="small" effect="light" round>
                {{ row.scope === 'gray' ? '灰度' : '全局' }}
              </el-tag>
              <span v-if="row.grayTenants" class="an-subtle" style="margin-left: 6px">租户 {{ row.grayTenants }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="updatedBy" label="操作人" width="130" />
          <el-table-column label="变更时间" width="180">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState text="暂无变更记录" sub="修改配置后将在此记录操作人与变更时间" />
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑对话框 -->
    <el-dialog v-model="editVisible" :title="`修改配置：${editMeta?.key}`" width="480px" align-center>
      <el-form label-position="top">
        <el-form-item :label="editMeta?.desc">
          <el-switch v-if="editMeta?.kind === 'boolean'" v-model="editBool" active-text="开启" inactive-text="关闭" />
          <el-input-number
            v-else-if="editMeta?.kind === 'number'"
            v-model="editNum"
            :min="editMeta.min ?? 0"
            :max="editMeta.max ?? 999999"
            :step="editMeta.max === 1 ? 0.05 : 1"
            :precision="editMeta.max === 1 ? 2 : 0"
            style="width: 100%"
          />
          <el-select v-else-if="editMeta?.kind === 'enum'" v-model="editForm.value" style="width: 100%">
            <el-option v-for="o in editMeta.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-input v-else v-model="editForm.value" />
        </el-form-item>
        <el-form-item label="作用域">
          <el-radio-group v-model="editForm.scope">
            <el-radio value="global">全局</el-radio>
            <el-radio value="gray">灰度（指定租户）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editForm.scope === 'gray'" label="灰度租户 ID（逗号分隔）">
          <el-input v-model="editForm.grayTenants" placeholder="例如：1,2" />
        </el-form-item>
        <el-form-item label="租户专属（可选）">
          <el-input-number v-model="editForm.tenantId" :min="1" :max="9999" placeholder="留空为平台级" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.config-tabs {
  padding: 6px 18px 18px;
  min-height: 300px;
}
.config-groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.cg-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.cg-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: var(--an-primary);
  background: rgba(var(--an-primary-rgb), 0.09);
}
.cg-title {
  font-weight: 700;
  color: var(--an-text);
  font-size: 14.5px;
}
.cg-desc {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.cg-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.config-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 14px;
  border-radius: 10px;
  border: 1px solid var(--an-border);
  background: rgba(var(--an-primary-rgb), 0.025);
  transition: border-color var(--an-dur) var(--an-ease), box-shadow var(--an-dur) var(--an-ease);
}
.config-row:hover {
  border-color: rgba(var(--an-primary-rgb), 0.3);
  box-shadow: var(--an-focus-ring);
}
.cr-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.cr-key {
  font-size: 12.5px;
  color: var(--an-primary);
  font-family: Consolas, monospace;
}
.cr-desc {
  font-size: 12px;
  color: var(--an-text-2);
}
.cr-value {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.cr-current {
  font-weight: 600;
  font-size: 13.5px;
  color: var(--an-text);
  font-variant-numeric: tabular-nums;
}
.cr-unit {
  font-size: 11px;
  color: var(--an-text-3);
  font-weight: 400;
}
</style>
