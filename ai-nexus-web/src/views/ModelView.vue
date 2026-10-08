<script setup lang="ts">
/**
 * 模型服务：Ollama 节点池状态 + 按场景路由配置（灰度比例 / 主备切换）+ 真实调用统计
 * 节点池与路由为前端预留模块（后端接口规划中，Mock 兜底）；调用统计来自 /stats/overview（真实）
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { modelApi, statsApi } from '@/api'
import { useAppStore } from '@/stores/app'
import { formatNumber, formatTokens, formatPercent } from '@/utils/format'
import type { ModelNode, ModelRouteItem, StatsOverview } from '@/types/api'

const app = useAppStore()
const loading = ref(true)
const nodes = ref<ModelNode[]>([])
const routes = ref<ModelRouteItem[]>([])
const stats = ref<StatsOverview | null>(null)
const saving = ref(false)

const SCENE_LABELS: Record<string, string> = {
  CHAT: '多轮对话',
  RAG: '检索增强问答',
  AGENT: '智能体',
  MULTIMODAL: '多模态',
}

onMounted(async () => {
  loading.value = true
  const [nodeRes, routeRes, statsRes] = await Promise.allSettled([
    modelApi.nodes(),
    modelApi.routes(),
    statsApi.overview(),
  ])
  if (nodeRes.status === 'fulfilled') nodes.value = nodeRes.value
  if (routeRes.status === 'fulfilled' && Array.isArray(routeRes.value)) routes.value = routeRes.value
  if (statsRes.status === 'fulfilled') stats.value = statsRes.value
  loading.value = false
})

/** 所有节点上的候选模型（路由配置下拉用） */
const allModels = computed(() => [...new Set(nodes.value.flatMap((n) => n.models))])

function statusMeta(status: ModelNode['status']) {
  if (status === 'online') return { text: '在线', cls: 'online' }
  if (status === 'busy') return { text: '繁忙', cls: 'busy' }
  return { text: '离线', cls: 'offline' }
}

/** 主备切换 */
function swapPrimary(route: ModelRouteItem) {
  const tmp = route.primary
  route.primary = route.backup
  route.backup = tmp
  ElMessage.success(`已切换 ${route.scene} 场景主模型为 ${route.primary}，保存后生效`)
}

async function saveRoutes() {
  saving.value = true
  try {
    await modelApi.saveRoutes(routes.value.map((r) => ({ ...r })))
    ElMessage.success('路由配置已保存')
  } catch {
    /* 统一错误提示 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="an-page">
    <PageHeader title="模型服务" subtitle="Ollama 节点池、场景路由与模型调用统计">
      <template #actions>
        <el-tag v-if="app.mockActive" type="warning" effect="light" round>节点池 / 路由为演示数据</el-tag>
      </template>
    </PageHeader>

    <!-- 节点池 -->
    <div class="section-title">
      <span>模型节点池</span>
      <span class="an-subtle">{{ nodes.filter((n) => n.status !== 'offline').length }}/{{ nodes.length }} 节点可用</span>
    </div>
    <div v-loading="loading" class="node-grid">
      <div v-for="(n, i) in nodes" :key="n.id" class="glass hover-lift node-card stagger-item" :style="{ animationDelay: Math.min(i, 8) * 50 + 'ms' }">
        <div class="node-head">
          <span class="node-dot" :class="statusMeta(n.status).cls"></span>
          <span class="node-name">{{ n.name }}</span>
          <el-tag :type="n.status === 'online' ? 'success' : n.status === 'busy' ? 'warning' : 'info'" size="small" effect="light" round>
            {{ statusMeta(n.status).text }}
          </el-tag>
        </div>
        <div class="node-url">
          <el-icon :size="12"><Link /></el-icon>
          {{ n.url }} · {{ n.region }}
        </div>
        <div class="node-load-row">
          <span class="an-subtle">负载</span>
          <el-progress
            :percentage="Math.round(n.load * 100)"
            :stroke-width="7"
            :status="n.load > 0.9 ? 'exception' : n.load > 0.7 ? 'warning' : undefined"
          />
        </div>
        <div class="node-concurrency an-subtle">
          并发 {{ n.activeConcurrent }} / {{ n.maxConcurrent }}
        </div>
        <div class="node-models">
          <el-tag v-for="m in n.models" :key="m" size="small" effect="plain" round class="model-chip">{{ m }}</el-tag>
        </div>
      </div>
      <EmptyState v-if="!loading && !nodes.length" text="暂无节点数据" />
    </div>

    <!-- 路由配置 -->
    <div class="section-title" style="margin-top: 22px">
      <span>场景路由配置</span>
      <span class="an-subtle">按场景分配主模型 / 备用模型，支持灰度比例与主备切换</span>
    </div>
    <div class="glass route-card stagger-item">
      <el-table :data="routes" style="width: 100%">
        <el-table-column label="场景" width="180">
          <template #default="{ row }">
            <span class="scene-name">{{ row.scene }}</span>
            <span class="scene-desc">{{ SCENE_LABELS[row.scene] || row.description }}</span>
          </template>
        </el-table-column>
        <el-table-column label="主模型" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.primary" size="default" style="width: 100%">
              <el-option v-for="m in allModels" :key="m" :label="m" :value="m" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="备用模型" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.backup" size="default" style="width: 100%">
              <el-option v-for="m in allModels" :key="m" :label="m" :value="m" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="灰度发布比例" width="230">
          <template #default="{ row }">
            <div class="gray-cell">
              <el-slider v-model="row.grayRatio" :min="0" :max="100" :step="5" style="flex: 1" />
              <span class="gray-value">{{ row.grayRatio }}%</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="'Switch'" @click="swapPrimary(row)">主备切换</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="route-actions">
        <span class="an-subtle">灰度比例表示新版本模型承接的流量百分比，其余流量走主模型</span>
        <el-button type="primary" :loading="saving" @click="saveRoutes">保存配置</el-button>
      </div>
    </div>

    <!-- 真实调用统计 -->
    <div class="section-title" style="margin-top: 22px">
      <span>模型调用统计</span>
      <span class="an-subtle">真实数据 · /api/v1/stats/overview</span>
    </div>
    <div class="glass stagger-item">
      <el-table :data="stats?.byModel || []" style="width: 100%">
        <el-table-column prop="model" label="模型" min-width="220" show-overflow-tooltip />
        <el-table-column label="调用次数" width="120" align="right">
          <template #default="{ row }">{{ formatNumber(row.calls) }}</template>
        </el-table-column>
        <el-table-column label="输入 Token" width="130" align="right">
          <template #default="{ row }">{{ formatTokens(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出 Token" width="130" align="right">
          <template #default="{ row }">{{ formatTokens(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="失败次数" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ 'fail-num': row.failures > 0 }">{{ row.failures }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成功率" width="110" align="right">
          <template #default="{ row }">{{ formatPercent(row.calls ? (row.calls - row.failures) / row.calls : NaN) }}</template>
        </el-table-column>
        <template #empty>
          <EmptyState text="暂无调用统计" sub="接入真实模型调用后自动展示" />
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-weight: 600;
  font-size: 15px;
  color: var(--an-text);
  margin-bottom: 12px;
}

.node-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
  gap: 14px;
  min-height: 120px;
}
.node-card {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.node-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.node-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}
.node-dot.online {
  background: var(--an-success);
  box-shadow: 0 0 0 3px rgba(0, 180, 42, 0.15);
  animation: pulse 2s var(--an-ease) infinite;
}
.node-dot.busy {
  background: var(--an-warning);
  box-shadow: 0 0 0 3px rgba(255, 125, 0, 0.15);
}
.node-dot.offline {
  background: rgba(127, 145, 180, 0.45);
}
@keyframes pulse {
  50% {
    box-shadow: 0 0 0 6px rgba(0, 180, 42, 0);
  }
}
.node-name {
  font-weight: 700;
  color: var(--an-text);
  font-size: 14px;
  flex: 1;
}
.node-url {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--an-text-3);
  font-family: Consolas, monospace;
}
.node-load-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.node-load-row :deep(.el-progress) {
  flex: 1;
}
.node-load-row :deep(.el-progress__text) {
  font-size: 11.5px !important;
  min-width: 34px;
}
.node-models {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.model-chip {
  font-family: Consolas, monospace;
}

.route-card {
  padding: 8px 16px 14px;
}
.scene-name {
  font-weight: 700;
  color: var(--an-text);
  margin-right: 8px;
}
.scene-desc {
  font-size: 12px;
  color: var(--an-text-3);
}
.gray-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.gray-value {
  font-size: 12px;
  font-weight: 700;
  color: var(--an-primary);
  width: 38px;
  text-align: right;
}
.route-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}
.fail-num {
  color: var(--an-danger);
  font-weight: 700;
}
</style>
