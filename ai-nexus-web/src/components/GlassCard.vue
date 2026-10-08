<script setup lang="ts">
/** 玻璃拟态卡片容器：hover 上浮 + 阴影加深（hoverLift=false 时为静态展示） */
withDefaults(defineProps<{ hoverLift?: boolean; padding?: string }>(), { hoverLift: true, padding: '18px 20px' })
</script>

<template>
  <div class="glass" :class="{ 'hover-lift': hoverLift }" :style="{ padding }">
    <slot />
  </div>
</template>
