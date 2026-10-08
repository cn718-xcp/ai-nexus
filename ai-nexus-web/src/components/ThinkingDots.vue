<script setup lang="ts">
/** AI 思考中：三点波浪起伏 + 环形微光旋转（替代传统转圈） */
defineProps<{ text?: string; size?: 'sm' | 'lg' }>()
</script>

<template>
  <div class="thinking" :class="size === 'lg' ? 'lg' : ''">
    <div class="an-ring" :class="{ lg: size === 'lg' }"></div>
    <div class="an-dots"><i></i><i></i><i></i></div>
    <span v-if="text" class="thinking-text">{{ text }}</span>
  </div>
</template>

<style scoped>
.thinking {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 6px 2px;
}
.thinking-text {
  font-size: 12px;
  color: var(--an-text-3);
}
.thinking.lg {
  flex-direction: column;
  gap: 12px;
  padding: 20px;
}
</style>
