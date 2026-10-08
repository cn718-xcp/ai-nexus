<script setup lang="ts">
/** 页头：标题 + 副标题 + 右侧操作区 */
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="ph-left">
      <h2 class="an-title ph-title">{{ title }}</h2>
      <p v-if="subtitle" class="an-subtle ph-subtitle">{{ subtitle }}</p>
    </div>
    <div class="ph-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.ph-title {
  margin: 0;
  font-size: 20px;
  letter-spacing: 0.01em;
}
.ph-subtitle {
  margin: 4px 0 0;
}
.ph-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
</style>
