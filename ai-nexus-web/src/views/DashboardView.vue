<script setup lang="ts">
/**
 * 工作台：核心指标卡片 + 趋势图表（近7日调用量 / 模型占比 / 场景分布）+ 实时健康面板
 * 数据源：/api/v1/kb、/api/v1/stats/overview、/actuator/health
 */
import { computed, onMounted, ref } from 'vue'
import { Collection, ChatDotRound, DataLine, TrendCharts, Coin } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import HealthPanel, { type HealthItem } from '@/components/HealthPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import { knowledgeApi, statsApi, monitorApi } from '@/api'
import { useChart, chartPalette, primaryGradient } from '@/composables/useChart'
import { useAppStore } from '@/stores/app'
import { formatPercent, formatTokens } from '@/utils/format'
import type { KnowledgeBase, StatsOverview } from '@/types/api'

const app = useAppStore()

const loading = ref(true)
const kbList = ref<KnowledgeBase[]>([])
const stats = ref<StatsOverview | null>(null)
const healthItems = ref<HealthItem[]>([])
const healthLoading = ref(true)
const usingDemoHealth = ref(false)

/* ===== 指标计算 ===== */
const todayStr = new Date().toISOString().slice(0, 10)
const kbTotal = computed(() => kbList.value.length)
const todayCalls = computed(
  () => stats.value?.bySceneAndDate.filter((s) => s.date === todayStr).reduce((a, b) => a + b.calls, 0) ?? 0,
)
const totalTokens = computed(
  () => stats.value?.byModel.reduce((a, b) => a + b.promptTokens + b.completionTokens, 0) ?? 0,
)
const modelSuccessRate = computed(() => {
  if (!stats.value?.byModel.length) return NaN
  const { calls, failures } = stats.value.byModel.reduce(
    (acc, m) => ({ calls: acc.calls + m.calls, failures: acc.failures + m.failures }),
    { calls: 0, failures: 0 },
  )
  if (!calls) return NaN
  return (calls - failures) / calls
})
/** RAG 命中率：演示数据展示参考值，真实模式需接入 /rag/eval 评估 */
const ragHitRate = ref<number>(NaN)

/* ===== 图表 ===== */
const trendRef = ref<HTMLElement>()
const pieRef = ref<HTMLElement>()
const barRef = ref<HTMLElement>()
const trendChart = useChart(trendRef)
const pieChart = useChart(pieRef)
const barChart = useChart(barRef)

function buildTrendOption(data: StatsOverview) {
  const dates = [...new Set(data.bySceneAndDate.map((s) => s.date))].sort()
  const scenes = [...new Set(data.bySceneAndDate.map((s) => s.scene))]
  const series = scenes.map((sc, i) => ({
    name: sc,
    type: 'line',
    smooth: true,
    symbol: 'circle',
    symbolSize: 6,
    showSymbol: false,
    emphasis: { focus: 'series' },
    lineStyle: { width: 2.5 },
    areaStyle: { opacity: i === 0 ? 0.14 : 0.08 },
    data: dates.map((d) => data.bySceneAndDate.filter((s) => s.scene === sc && s.date === d).reduce((a, b) => a + b.calls, 0)),
  }))
  return {
    color: chartPalette,
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(30,40,60,0.9)', borderWidth: 0, textStyle: { color: '#fff' } },
    legend: { bottom: 0, icon: 'roundRect', itemWidth: 14, itemHeight: 4 },
    grid: { left: 44, right: 18, top: 18, bottom: 42 },
    xAxis: { type: 'category', data: dates.map((d) => d.slice(5)), boundaryGap: false, axisLine: { lineStyle: { color: 'rgba(127,145,180,0.3)' } }, axisLabel: { color: 'rgba(127,145,180,0.9)' } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(127,145,180,0.14)' } }, axisLabel: { color: 'rgba(127,145,180,0.9)' } },
    series,
  }
}

function buildPieOption(data: StatsOverview) {
  const items = data.byModel.map((m, i) => ({
    name: m.model.replace(/ChatModel$/, ''),
    value: m.calls,
    itemStyle: i === 0 ? { color: primaryGradient('vertical') } : undefined,
  }))
  return {
    color: chartPalette,
    tooltip: { trigger: 'item', formatter: '{b}<br/>调用 {c} 次（{d}%）' },
    legend: { bottom: 0, icon: 'circle', itemWidth: 9, itemHeight: 9 },
    series: [
      {
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 8, borderColor: 'transparent', borderWidth: 2 },
        label: { show: false },
        emphasis: { scaleSize: 6, label: { show: false } },
        data: items,
      },
    ],
  }
}

function buildBarOption(data: StatsOverview) {
  const byScene = new Map<string, number>()
  data.bySceneAndDate.forEach((s) => byScene.set(s.scene, (byScene.get(s.scene) || 0) + s.calls))
  const scenes = [...byScene.keys()]
  const values = scenes.map((s) => byScene.get(s) || 0)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 44, right: 18, top: 18, bottom: 30 },
    xAxis: { type: 'category', data: scenes, axisLabel: { color: 'rgba(127,145,180,0.9)' }, axisLine: { lineStyle: { color: 'rgba(127,145,180,0.3)' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(127,145,180,0.14)' } }, axisLabel: { color: 'rgba(127,145,180,0.9)' } },
    series: [
      {
        type: 'bar',
        data: values,
        barWidth: 26,
        itemStyle: { borderRadius: [8, 8, 0, 0], color: primaryGradient('vertical') },
        animationDelay: (idx: number) => idx * 80,
      },
    ],
  }
}

/* ===== 健康面板 ===== */
const COMPONENT_LABELS: Record<string, string> = {
  db: 'MySQL',
  redis: 'Redis',
  milvus: 'Milvus',
  ollama: 'Ollama',
  rabbitmq: 'RabbitMQ',
  diskSpace: '磁盘',
  ping: '网络探活',
}

async function loadHealth() {
  healthLoading.value = true
  try {
    const health = await monitorApi.actuatorHealth()
    usingDemoHealth.value = false
    if (health.components) {
      healthItems.value = Object.entries(health.components)
        .filter(([key]) => COMPONENT_LABELS[key])
        .map(([key, v]) => ({
          key,
          label: COMPONENT_LABELS[key] || key,
          status: v.status,
          detail: Object.entries(v.details || {})
            .slice(0, 2)
            .map(([k, val]) => `${k}: ${String(val)}`)
            .join(' · '),
        }))
      if (!healthItems.value.length) throw new Error('no components')
    } else {
      healthItems.value = [
        { key: 'app', label: 'AI-Nexus 应用', status: health.status, detail: '整体健康状态' },
      ]
    }
  } catch {
    // 后端不可用且无 Mock 详情时展示四组件占位
    usingDemoHealth.value = true
    healthItems.value = [
      { key: 'mysql', label: 'MySQL', status: 'UP', detail: '数据库连接正常' },
      { key: 'redis', label: 'Redis', status: 'UP', detail: '缓存与限流' },
      { key: 'milvus', label: 'Milvus', status: 'UP', detail: '向量库 ai_nexus_kb' },
      { key: 'ollama', label: 'Ollama', status: 'UP', detail: 'qwen2.5:7b · nomic-embed-text' },
    ]
  } finally {
    healthLoading.value = false
  }
}

async function loadData() {
  loading.value = true
  const [kbRes, statsRes] = await Promise.allSettled([knowledgeApi.list(), statsApi.overview()])
  if (kbRes.status === 'fulfilled') kbList.value = kbRes.value
  if (statsRes.status === 'fulfilled' && statsRes.value) {
    stats.value = statsRes.value
    trendChart.setOption(buildTrendOption(statsRes.value), true)
    pieChart.setOption(buildPieOption(statsRes.value), true)
    barChart.setOption(buildBarOption(statsRes.value), true)
  }
  // 演示数据 / Mock 模式下展示参考命中率；真实后端需接入 /rag/eval
  ragHitRate.value = kbRes.status === 'rejected' || statsRes.status === 'rejected' || app.mockActive ? 0.876 : NaN
  loading.value = false
}

onMounted(() => {
  void loadData()
  void loadHealth()
})

function refreshAll() {
  void loadData()
  void loadHealth()
}
</script>

<template>
  <div class="an-page dashboard">
    <PageHeader title="工作台" subtitle="平台调用统计、核心指标与组件健康总览">
      <template #actions>
        <el-button :icon="'Refresh'" circle @click="refreshAll" />
      </template>
    </PageHeader>

    <!-- 核心指标卡片 -->
    <div class="kpi-grid">
      <StatCard label="知识库总数" :value="kbTotal" :icon="Collection" tone="blue" :loading="loading" />
      <StatCard label="今日对话数" :value="todayCalls" :icon="ChatDotRound" tone="green" :loading="loading" />
      <StatCard
        label="RAG 命中率"
        :value="Number.isNaN(ragHitRate) ? 0 : ragHitRate"
        :icon="DataLine"
        tone="purple"
        :formatter="(n: number) => (Number.isNaN(ragHitRate) ? '—' : formatPercent(n))"
        :loading="loading"
      />
      <StatCard
        label="模型调用成功率"
        :value="modelSuccessRate"
        :icon="TrendCharts"
        tone="orange"
        :formatter="(n: number) => (Number.isNaN(modelSuccessRate) ? '—' : formatPercent(n))"
        :loading="loading"
      />
      <StatCard
        label="总 Token 消耗"
        :value="totalTokens"
        :icon="Coin"
        tone="red"
        :formatter="formatTokens"
        :loading="loading"
      />
    </div>

    <!-- 趋势图表 -->
    <div class="chart-row">
      <div class="glass hover-lift chart-card wide stagger-item">
        <div class="chart-head">
          <span class="chart-title">近 7 日调用量趋势</span>
          <span class="an-subtle">按场景</span>
        </div>
        <div ref="trendRef" class="chart-body tall"></div>
      </div>
      <div class="glass hover-lift chart-card stagger-item" style="animation-delay: 60ms">
        <div class="chart-head">
          <span class="chart-title">模型调用占比</span>
          <span class="an-subtle">近 7 日</span>
        </div>
        <div ref="pieRef" class="chart-body tall"></div>
      </div>
    </div>

    <div class="chart-row">
      <div class="glass hover-lift chart-card wide stagger-item" style="animation-delay: 100ms">
        <div class="chart-head">
          <span class="chart-title">各场景调用分布</span>
          <span class="an-subtle">CHAT / RAG / AGENT / MULTIMODAL</span>
        </div>
        <div ref="barRef" class="chart-body"></div>
      </div>
      <div class="glass hover-lift chart-card stagger-item" style="animation-delay: 140ms">
        <div class="chart-head">
          <span class="chart-title">组件健康状态</span>
          <span v-if="usingDemoHealth" class="an-subtle">演示数据</span>
          <span v-else class="an-subtle">/actuator/health</span>
        </div>
        <div class="health-wrap">
          <HealthPanel :items="healthItems" :loading="healthLoading" />
          <div v-if="!healthLoading && Number.isNaN(modelSuccessRate) && !stats" class="chart-empty">
            <EmptyState text="暂无统计数据" sub="后端接入后自动展示调用量与模型分布" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.chart-row {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 14px;
  margin-bottom: 14px;
}
.chart-card {
  display: flex;
  flex-direction: column;
}
.chart-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}
.chart-title {
  font-weight: 600;
  font-size: 15px;
  color: var(--an-text);
}
.chart-body {
  height: 230px;
  width: 100%;
}
.chart-body.tall {
  height: 260px;
}
.chart-empty {
  position: absolute;
  inset: 44px 0 0;
}
.health-wrap {
  position: relative;
  flex: 1;
  min-height: 230px;
}
.health-wrap :deep(.health-grid) {
  grid-template-columns: repeat(2, 1fr);
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .chart-row {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 720px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
