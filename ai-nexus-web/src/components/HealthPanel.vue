<script setup lang="ts">
/** 组件健康状态面板：UP 呼吸绿光 / DOWN 红光 / UNKNOWN 灰 */
export interface HealthItem {
  key: string
  label: string
  status: string
  detail?: string
}

const props = defineProps<{ items: HealthItem[]; loading?: boolean }>()

function tone(status: string): string {
  const s = (status || '').toUpperCase()
  if (s === 'UP') return 'up'
  if (s === 'DOWN' || s === 'OUT_OF_SERVICE') return 'down'
  return 'unknown'
}
</script>

<template>
  <div class="health-grid">
    <template v-if="loading">
      <div v-for="i in 4" :key="i" class="glass health-card">
        <div class="hp-skeleton"></div>
      </div>
    </template>
    <div v-for="item in props.items" v-else :key="item.key" class="glass hover-lift health-card stagger-item">
      <span class="hp-dot" :class="tone(item.status)"></span>
      <div class="hp-body">
        <div class="hp-label">{{ item.label }}</div>
        <div class="hp-status" :class="tone(item.status)">{{ item.status || 'UNKNOWN' }}</div>
        <div v-if="item.detail" class="hp-detail an-ellipsis" :title="item.detail">{{ item.detail }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.health-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
}
.hp-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
}
.hp-dot.up {
  background: var(--an-success);
  animation: hp-pulse 2s var(--an-ease) infinite;
}
.hp-dot.down {
  background: var(--an-danger);
}
.hp-dot.unknown {
  background: rgba(127, 145, 180, 0.5);
}
@keyframes hp-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(0, 180, 42, 0.4);
  }
  55% {
    box-shadow: 0 0 0 6px rgba(0, 180, 42, 0);
  }
}
.hp-label {
  font-weight: 600;
  color: var(--an-text);
  font-size: 14px;
}
.hp-status {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  margin-top: 1px;
}
.hp-status.up {
  color: var(--an-success);
}
.hp-status.down {
  color: var(--an-danger);
}
.hp-status.unknown {
  color: var(--an-text-3);
}
.hp-detail {
  font-size: 11.5px;
  color: var(--an-text-3);
  margin-top: 3px;
}
.hp-skeleton {
  height: 56px;
  width: 100%;
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(127, 145, 180, 0.1), rgba(127, 145, 180, 0.22), rgba(127, 145, 180, 0.1));
  background-size: 200% 100%;
  animation: sk 1.2s ease infinite;
}
@keyframes sk {
  to {
    background-position: -200% 0;
  }
}
</style>
