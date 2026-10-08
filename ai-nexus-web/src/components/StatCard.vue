<script setup lang="ts">
/** 核心指标卡片：渐变图标 + 数字平滑滚动 + 趋势标识 */
import { computed, toRef } from 'vue'
import type { Component } from 'vue'
import { useCountUp } from '@/composables/useCountUp'
import { formatNumber } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    label: string
    value: number
    icon: Component
    tone?: 'blue' | 'green' | 'orange' | 'red' | 'purple'
    formatter?: (n: number) => string
    suffix?: string
    trend?: number
    loading?: boolean
  }>(),
  { tone: 'blue', formatter: formatNumber, suffix: '', loading: false },
)

const display = useCountUp(toRef(props, 'value'))
const text = computed(() => props.formatter(display.value))
</script>

<template>
  <div class="glass hover-lift stat-card stagger-item" :class="`tone-${tone}`">
    <div class="sc-icon-wrap">
      <el-icon :size="22" class="sc-icon">
        <component :is="icon" />
      </el-icon>
    </div>
    <div class="sc-body">
      <div v-if="loading" class="sc-skeleton"></div>
      <template v-else>
        <div class="sc-value">
          {{ text }}<span v-if="suffix" class="sc-suffix">{{ suffix }}</span>
        </div>
        <div class="sc-label">{{ label }}</div>
      </template>
    </div>
    <div v-if="trend !== undefined && !loading" class="sc-trend" :class="trend >= 0 ? 'up' : 'down'">
      {{ trend >= 0 ? '▲' : '▼' }} {{ Math.abs(trend).toFixed(1) }}%
    </div>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  position: relative;
  overflow: hidden;
}
.sc-icon-wrap {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  color: #fff;
  background: var(--an-primary-gradient);
  box-shadow: 0 6px 16px rgba(var(--an-primary-rgb), 0.3);
}
.tone-green .sc-icon-wrap {
  background: linear-gradient(135deg, #23c343, #00b42a);
  box-shadow: 0 6px 16px rgba(0, 180, 42, 0.28);
}
.tone-orange .sc-icon-wrap {
  background: linear-gradient(135deg, #ff9a2e, #ff7d00);
  box-shadow: 0 6px 16px rgba(255, 125, 0, 0.28);
}
.tone-red .sc-icon-wrap {
  background: linear-gradient(135deg, #f76560, #f53f3f);
  box-shadow: 0 6px 16px rgba(245, 63, 63, 0.28);
}
.tone-purple .sc-icon-wrap {
  background: linear-gradient(135deg, #9254de, #722ed1);
  box-shadow: 0 6px 16px rgba(114, 46, 209, 0.28);
}
.sc-body {
  min-width: 0;
  flex: 1;
}
.sc-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
  color: var(--an-text);
}
.sc-suffix {
  font-size: 13px;
  font-weight: 500;
  color: var(--an-text-3);
  margin-left: 3px;
}
.sc-label {
  font-size: 13px;
  color: var(--an-text-3);
  margin-top: 2px;
}
.sc-trend {
  position: absolute;
  top: 12px;
  right: 14px;
  font-size: 11px;
  font-weight: 600;
}
.sc-trend.up {
  color: var(--an-success);
}
.sc-trend.down {
  color: var(--an-danger);
}
.sc-skeleton {
  height: 42px;
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(127, 145, 180, 0.12), rgba(127, 145, 180, 0.24), rgba(127, 145, 180, 0.12));
  background-size: 200% 100%;
  animation: skeleton-wave 1.2s ease infinite;
}
@keyframes skeleton-wave {
  to {
    background-position: -200% 0;
  }
}
</style>
