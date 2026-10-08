<script setup lang="ts">
/**
 * 运维监控：
 * - 服务健康（/actuator/health，30s 自动刷新）
 * - 指标看板（/actuator/metrics + Prometheus 原文）
 * - 链路追踪（输入 traceId 查询全链路；预留接口 Mock 兜底）
 * - 审计日志（预留接口 Mock 兜底）
 * - 日志级别动态调整（/actuator/loggers，实时生效）
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import HealthPanel, { type HealthItem } from '@/components/HealthPanel.vue'
import { monitorApi } from '@/api'
import { useChart, chartPalette } from '@/composables/useChart'
import { getRecentTraceIds, onTraceChanged } from '@/utils/trace'
import { formatNumber, formatTime, formatMs } from '@/utils/format'
import type { ActuatorHealth, AuditLog, LoggersResponse, MetricDetail, TraceSpan } from '@/types/api'

const activeTab = ref('health')
const isDemoData = ref(false)

/* ===== 服务健康 ===== */
const healthLoading = ref(true)
const healthItems = ref<HealthItem[]>([])
const overallStatus = ref('')
const appHealth = ref('-')
let healthTimer = 0

async function loadHealth() {
  healthLoading.value = true
  try {
    const health: ActuatorHealth = await monitorApi.actuatorHealth()
    isDemoData.value = false
    overallStatus.value = health.status
    const LABELS: Record<string, string> = {
      db: 'MySQL', redis: 'Redis', milvus: 'Milvus', ollama: 'Ollama', diskSpace: '磁盘', ping: '网络探活',
    }
    if (health.components) {
      healthItems.value = Object.entries(health.components)
        .filter(([k]) => LABELS[k])
        .map(([k, v]) => ({
          key: k,
          label: LABELS[k] || k,
          status: v.status,
          detail: Object.entries(v.details || {}).slice(0, 2).map(([k2, v2]) => `${k2}: ${String(v2)}`).join(' · '),
        }))
    }
  } catch {
    isDemoData.value = true
  } finally {
    healthLoading.value = false
  }
  try {
    appHealth.value = await monitorApi.appHealth()
  } catch {
    appHealth.value = '-'
  }
}

/* ===== 指标看板 ===== */
const metricNames = ref<string[]>([])
const metricName = ref('')
const metricDetail = ref<(MetricDetail & { _series?: number[] }) | null>(null)
const metricLoading = ref(false)
const metricChartRef = ref<HTMLElement>()
const metricChart = useChart(metricChartRef)
const promVisible = ref(false)
const promText = ref('')

async function loadMetrics() {
  try {
    const res = await monitorApi.metrics()
    metricNames.value = res.names || []
    if (metricNames.value.length && !metricName.value) {
      metricName.value = metricNames.value.includes('jvm.memory.used') ? 'jvm.memory.used' : metricNames.value[0]
      void selectMetric()
    }
  } catch {
    /* 静默 */
  }
}

async function selectMetric() {
  if (!metricName.value) return
  metricLoading.value = true
  try {
    metricDetail.value = (await monitorApi.metric(metricName.value)) as MetricDetail & { _series?: number[] }
    // Mock 数据带时间序列，绘制趋势图；真实 Actuator 仅返回当前测量值
    const series = metricDetail.value._series
    if (series?.length) {
      metricChart.setOption({
        color: chartPalette,
        tooltip: { trigger: 'axis' },
        grid: { left: 46, right: 16, top: 16, bottom: 26 },
        xAxis: {
          type: 'category',
          data: series.map((_, i) => `${i - series.length + 1}min`),
          boundaryGap: false,
          axisLine: { lineStyle: { color: 'rgba(127,145,180,0.3)' } },
          axisLabel: { color: 'rgba(127,145,180,0.9)', fontSize: 10 },
        },
        yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(127,145,180,0.14)' } }, axisLabel: { color: 'rgba(127,145,180,0.9)' } },
        series: [{ type: 'line', data: series, smooth: true, showSymbol: false, lineStyle: { width: 2.5 }, areaStyle: { opacity: 0.12 } }],
      }, true)
    }
  } finally {
    metricLoading.value = false
  }
}

async function openPrometheus() {
  promVisible.value = true
  try {
    promText.value = await monitorApi.prometheus()
  } catch {
    promText.value = '# 无法获取 Prometheus 指标'
  }
}

/* ===== 链路追踪 ===== */
const traceIdInput = ref('')
const traceLoading = ref(false)
const traceSpans = ref<TraceSpan[]>([])
const traceTotal = ref(0)
const traceQueriedId = ref('')
const recentTraces = ref<string[]>(getRecentTraceIds())
onTraceChanged(() => (recentTraces.value = getRecentTraceIds()))

const maxSpanDepth = computed(() => traceSpans.value.length || 1)

async function queryTrace(id?: string) {
  const tid = (id || traceIdInput.value).trim()
  if (!tid) {
    ElMessage.warning('请输入 traceId')
    return
  }
  traceLoading.value = true
  try {
    const detail = await monitorApi.trace(tid)
    traceSpans.value = detail.spans
    traceTotal.value = detail.totalMs
    traceQueriedId.value = detail.traceId
  } catch {
    ElMessage.warning('未查询到该 traceId 的链路数据')
  } finally {
    traceLoading.value = false
  }
}

function spanStyle(span: TraceSpan) {
  const total = Math.max(traceTotal.value, 1)
  return {
    marginLeft: (span.startMs / total) * 100 + '%',
    width: Math.max((span.durationMs / total) * 100, 1.5) + '%',
    background: span.status === 'error' ? 'linear-gradient(90deg,#f76560,#f53f3f)' : 'var(--an-primary-gradient)',
  }
}

/* ===== 审计日志 ===== */
const auditLogs = ref<AuditLog[]>([])
const auditLoading = ref(false)
const auditModule = ref('')
const auditModules = computed(() => [...new Set(auditLogs.value.map((l) => l.module))])
const filteredAudit = computed(() =>
  auditModule.value ? auditLogs.value.filter((l) => l.module === auditModule.value) : auditLogs.value,
)

async function loadAudit() {
  auditLoading.value = true
  try {
    auditLogs.value = await monitorApi.auditLogs()
  } finally {
    auditLoading.value = false
  }
}

/* ===== 日志级别 ===== */
const loggerLevels = ref<string[]>([])
const loggerList = ref<{ name: string; configuredLevel: string | null; effectiveLevel: string }[]>([])
const loggerFilter = ref('')
const loggerSavingName = ref('')
const loggerLoading = ref(false)

const filteredLoggers = computed(() => {
  const kw = loggerFilter.value.trim().toLowerCase()
  return kw ? loggerList.value.filter((l) => l.name.toLowerCase().includes(kw)) : loggerList.value
})

async function loadLoggers() {
  loggerLoading.value = true
  try {
    const res: LoggersResponse = await monitorApi.loggers()
    loggerLevels.value = res.levels || []
    loggerList.value = Object.entries(res.loggers || {}).map(([name, v]) => ({
      name,
      configuredLevel: v.configuredLevel,
      effectiveLevel: v.effectiveLevel,
    }))
  } finally {
    loggerLoading.value = false
  }
}

async function changeLogger(name: string, level: string | null) {
  loggerSavingName.value = name
  try {
    await monitorApi.setLoggerLevel(name, level || 'INFO')
    ElMessage.success(`${name} 日志级别已调整为 ${level}`)
    await loadLoggers()
  } catch {
    /* 统一错误提示 */
  } finally {
    loggerSavingName.value = ''
  }
}

/* ===== 生命周期 ===== */
onMounted(() => {
  void loadHealth()
  void loadMetrics()
  void loadAudit()
  void loadLoggers()
  healthTimer = window.setInterval(() => void loadHealth(), 30000)
})
onBeforeUnmount(() => {
  if (healthTimer) clearInterval(healthTimer)
})
</script>

<template>
  <div class="an-page">
    <PageHeader title="运维监控" subtitle="全链路追踪、业务指标、审计日志与日志级别动态调整">
      <template #actions>
        <el-tag v-if="isDemoData" type="warning" effect="light" round>部分数据为演示数据</el-tag>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="glass monitor-tabs stagger-item">
      <!-- 服务健康 -->
      <el-tab-pane label="服务健康" name="health">
        <div class="health-top">
          <div class="overall-badge" :class="overallStatus === 'UP' ? 'up' : 'down'">
            <span class="ob-dot"></span>
            <div>
              <div class="ob-status">{{ overallStatus || '探测中' }}</div>
              <div class="ob-label">Actuator 整体状态 · 探活接口 /health → {{ appHealth }}</div>
            </div>
          </div>
          <el-button :icon="'Refresh'" circle @click="loadHealth" />
        </div>
        <HealthPanel :items="healthItems" :loading="healthLoading" />
        <div class="health-hint an-subtle">每 30 秒自动刷新 · 数据源 /actuator/health（含组件详情）</div>
      </el-tab-pane>

      <!-- 指标看板 -->
      <el-tab-pane label="指标看板" name="metrics">
        <div class="metric-toolbar">
          <el-select v-model="metricName" filterable style="width: 300px" @change="selectMetric">
            <el-option v-for="n in metricNames" :key="n" :label="n" :value="n" />
          </el-select>
          <span v-if="metricDetail?.description" class="an-subtle">{{ metricDetail.description }}</span>
          <div class="toolbar-spacer"></div>
          <el-button :icon="'DataAnalysis'" @click="openPrometheus">Prometheus 原文</el-button>
        </div>

        <div v-loading="metricLoading" class="metric-body">
          <div v-if="metricDetail?._series?.length" ref="metricChartRef" class="metric-chart"></div>
          <div v-if="metricDetail" class="metric-stats">
            <div v-for="m in metricDetail.measurements" :key="m.statistic" class="glass-static metric-stat">
              <div class="ms-value">{{ formatNumber(Number(m.value.toFixed(2))) }}</div>
              <div class="ms-label">{{ m.statistic }}<span v-if="metricDetail.baseUnit" class="ms-unit"> · {{ metricDetail.baseUnit }}</span></div>
            </div>
          </div>
          <div v-if="metricDetail?.availableTags?.length" class="metric-tags">
            <span class="an-subtle">可用标签：</span>
            <code v-for="t in metricDetail.availableTags" :key="t.tag" class="metric-tag">
              {{ t.tag }} → {{ t.values.slice(0, 3).join(', ') }}
            </code>
          </div>
          <EmptyState v-if="!metricDetail && !metricLoading" text="暂无指标数据" />
        </div>
      </el-tab-pane>

      <!-- 链路追踪 -->
      <el-tab-pane label="链路追踪" name="trace">
        <div class="trace-toolbar">
          <el-input
            v-model="traceIdInput"
            placeholder="输入 traceId 查询完整调用链路"
            clearable
            style="width: 380px"
            @keyup.enter="queryTrace()"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" :icon="'Search'" :loading="traceLoading" @click="queryTrace()">查询</el-button>
        </div>
        <div v-if="recentTraces.length" class="trace-recent">
          <span class="an-subtle">最近请求（响应头 X-Trace-Id 自动捕获）：</span>
          <code v-for="t in recentTraces.slice(0, 5)" :key="t" class="trace-chip" @click="queryTrace(t)">{{ t.slice(0, 14) }}…</code>
        </div>

        <div v-loading="traceLoading" class="trace-result">
          <template v-if="traceSpans.length">
            <div class="trace-head">
              <span class="trace-id">{{ traceQueriedId }}</span>
              <span class="an-subtle">总耗时 {{ formatMs(traceTotal) }} · {{ traceSpans.length }} 个 Span</span>
            </div>
            <div v-for="s in traceSpans" :key="s.spanId" class="trace-span">
              <div class="ts-label">
                <span class="ts-service">{{ s.service }}</span>
                <span class="ts-name">{{ s.name }}</span>
              </div>
              <div class="ts-bar-track">
                <div class="ts-bar" :style="spanStyle(s)" :title="`${s.durationMs}ms`"></div>
                <span class="ts-duration">{{ formatMs(s.durationMs) }}</span>
              </div>
            </div>
            <div class="trace-tags">
              <div v-for="s in traceSpans" :key="'tag-' + s.spanId" class="trace-tag-row">
                <span class="ts-service">{{ s.service }}</span>
                <code v-for="(v, k) in s.tags" :key="k" class="metric-tag">{{ k }}={{ v }}</code>
              </div>
            </div>
          </template>
          <EmptyState v-else-if="!traceLoading" text="输入 traceId 开始查询" sub="每次请求的响应头都会返回 X-Trace-Id，可在上方快速回查" />
        </div>
      </el-tab-pane>

      <!-- 审计日志 -->
      <el-tab-pane label="审计日志" name="audit">
        <div class="metric-toolbar">
          <el-select v-model="auditModule" clearable placeholder="按模块筛选" style="width: 200px">
            <el-option v-for="m in auditModules" :key="m" :label="m" :value="m" />
          </el-select>
          <span class="an-subtle">关键操作记录：知识库增删、登录、配置变更等</span>
        </div>
        <el-table v-loading="auditLoading" :data="filteredAudit" style="width: 100%">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="module" label="模块" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" round>{{ row.module }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="action" label="操作" min-width="220" show-overflow-tooltip />
          <el-table-column prop="operator" label="操作人" width="110" />
          <el-table-column prop="ip" label="来源 IP" width="130" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.result === '成功' ? 'success' : 'danger'" size="small" effect="light" round>
                {{ row.result }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="traceId" label="TraceId" width="150">
            <template #default="{ row }">
              <code class="trace-chip static" @click="activeTab = 'trace'; queryTrace(row.traceId)">{{ row.traceId.slice(0, 12) }}…</code>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="备注" min-width="180" show-overflow-tooltip />
          <template #empty>
            <EmptyState text="暂无审计日志" />
          </template>
        </el-table>
      </el-tab-pane>

      <!-- 日志级别 -->
      <el-tab-pane label="日志级别" name="loggers">
        <div class="metric-toolbar">
          <el-input v-model="loggerFilter" placeholder="筛选日志器（如 com.ainexus）" clearable style="width: 320px">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="'Refresh'" circle @click="loadLoggers" />
        </div>
        <el-table v-loading="loggerLoading" :data="filteredLoggers.slice(0, 60)" style="width: 100%" max-height="480">
          <el-table-column prop="name" label="日志器" min-width="280" show-overflow-tooltip>
            <template #default="{ row }"><code class="cr-key" style="color: var(--an-text-2)">{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column label="生效级别" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" round>{{ row.effectiveLevel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="配置级别" width="240">
            <template #default="{ row }">
              <el-select
                :model-value="row.configuredLevel"
                size="small"
                clearable
                placeholder="继承"
                :loading="loggerSavingName === row.name"
                @change="(v: string) => changeLogger(row.name, v)"
              >
                <el-option v-for="l in loggerLevels" :key="l" :label="l" :value="l" />
              </el-select>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState text="暂无日志器数据" />
          </template>
        </el-table>
        <div class="health-hint an-subtle">修改后实时生效，无需重启 · 数据源 /actuator/loggers</div>
      </el-tab-pane>
    </el-tabs>

    <!-- Prometheus 抽屉 -->
    <el-drawer v-model="promVisible" title="Prometheus 指标原文" size="50%">
      <pre class="prom-text">{{ promText || '加载中…' }}</pre>
    </el-drawer>
  </div>
</template>

<style scoped>
.monitor-tabs {
  padding: 6px 18px 18px;
  min-height: 420px;
}
.health-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.overall-badge {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  border-radius: 12px;
  border: 1px solid var(--an-border);
  background: rgba(var(--an-primary-rgb), 0.04);
}
.ob-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}
.overall-badge.up .ob-dot {
  background: var(--an-success);
  box-shadow: 0 0 0 4px rgba(0, 180, 42, 0.15);
  animation: op 2s var(--an-ease) infinite;
}
.overall-badge.down .ob-dot {
  background: var(--an-danger);
}
@keyframes op {
  50% {
    box-shadow: 0 0 0 8px rgba(0, 180, 42, 0);
  }
}
.ob-status {
  font-weight: 800;
  font-size: 16px;
  letter-spacing: 0.06em;
}
.overall-badge.up .ob-status {
  color: var(--an-success);
}
.overall-badge.down .ob-status {
  color: var(--an-danger);
}
.ob-label {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.health-hint {
  margin-top: 12px;
}

.metric-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.toolbar-spacer {
  flex: 1;
}
.metric-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 240px;
}
.metric-chart {
  height: 230px;
  width: 100%;
}
.metric-stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}
.metric-stat {
  padding: 12px 16px;
  border: 1px solid var(--an-border);
  border-radius: 10px;
  background: rgba(var(--an-primary-rgb), 0.03);
}
.ms-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--an-text);
  font-variant-numeric: tabular-nums;
}
.ms-label {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.ms-unit {
  opacity: 0.7;
}
.metric-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.metric-tag {
  font-size: 11px;
  color: var(--an-text-2);
  background: rgba(127, 145, 180, 0.1);
  border-radius: 6px;
  padding: 3px 8px;
}

.trace-toolbar,
.trace-recent {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.trace-chip {
  font-size: 11px;
  color: var(--an-primary);
  background: rgba(var(--an-primary-rgb), 0.08);
  border-radius: 6px;
  padding: 3px 8px;
  cursor: pointer;
  transition: all var(--an-dur) var(--an-ease);
}
.trace-chip:hover {
  background: rgba(var(--an-primary-rgb), 0.16);
}
.trace-chip.static {
  cursor: pointer;
}
.trace-result {
  min-height: 240px;
}
.trace-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.trace-id {
  font-family: Consolas, monospace;
  font-weight: 700;
  color: var(--an-primary);
}
.trace-span {
  display: grid;
  grid-template-columns: 300px 1fr;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.ts-label {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.ts-service {
  font-size: 12px;
  font-weight: 600;
  color: var(--an-text);
}
.ts-name {
  font-size: 11px;
  color: var(--an-text-3);
}
.ts-bar-track {
  position: relative;
  height: 18px;
  border-radius: 6px;
  background: rgba(127, 145, 180, 0.1);
  overflow: hidden;
}
.ts-bar {
  height: 100%;
  border-radius: 6px;
  min-width: 4px;
  transition: width var(--an-dur-long) var(--an-ease);
}
.ts-duration {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 10.5px;
  color: var(--an-text-2);
}
.trace-tags {
  margin-top: 14px;
  border-top: 1px dashed var(--an-border);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trace-tag-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.trace-tag-row .ts-service {
  width: 130px;
  flex-shrink: 0;
}

.prom-text {
  font-size: 12px;
  line-height: 1.7;
  color: var(--an-text-2);
  white-space: pre-wrap;
  word-break: break-all;
  font-family: Consolas, monospace;
  background: rgba(127, 145, 180, 0.07);
  border-radius: 10px;
  padding: 14px;
  margin: 0;
}
</style>
