<script setup lang="ts">
/** 空状态 / 加载态：轻量插画 + 微动效，保持页面灵气 */
defineProps<{ text?: string; sub?: string }>()
</script>

<template>
  <div class="empty-state">
    <svg class="es-illustration anim-decor" viewBox="0 0 120 100" width="120" height="100">
      <defs>
        <linearGradient id="es-g" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#4080FF" />
          <stop offset="1" stop-color="#165DFF" />
        </linearGradient>
      </defs>
      <ellipse cx="60" cy="86" rx="42" ry="6" fill="rgba(22,93,255,0.10)" />
      <path
        d="M60 18 84 32v28L60 74 36 60V32z"
        fill="url(#es-g)"
        opacity="0.16"
      />
      <path
        d="M60 18 84 32v28L60 74 36 60V32z"
        fill="none"
        stroke="url(#es-g)"
        stroke-width="2"
        stroke-linejoin="round"
      />
      <circle cx="60" cy="46" r="9" fill="url(#es-g)" opacity="0.85" />
      <circle cx="88" cy="24" r="4" fill="#4080FF" opacity="0.5" />
      <circle cx="28" cy="30" r="3" fill="#722ED1" opacity="0.4" />
      <path d="M88 24c8 4 12 10 12 18M28 30c-8 4-12 10-12 18" stroke="#4080FF" stroke-opacity="0.3" stroke-width="1.5" fill="none" stroke-linecap="round" />
    </svg>
    <svg v-if="false" viewBox="0 0 120 100"></svg>
    <div class="es-text">{{ text || '暂无数据' }}</div>
    <div v-if="sub" class="es-sub">{{ sub }}</div>
    <div class="es-action"><slot /></div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 46px 20px;
  gap: 6px;
  text-align: center;
}
.es-text {
  color: var(--an-text-2);
  font-size: 14px;
  margin-top: 8px;
}
.es-sub {
  color: var(--an-text-3);
  font-size: 12px;
}
.es-action {
  margin-top: 12px;
}
</style>
