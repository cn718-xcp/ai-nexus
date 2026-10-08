<script setup lang="ts">
import { onMounted } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAppStore } from '@/stores/app'

const app = useAppStore()
onMounted(() => {
  // 应用主题 / 动效档位，并探测后端可达性（决定是否 Mock 兜底）
  app.init()
})
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view v-slot="{ Component }">
      <transition name="page" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </el-config-provider>
</template>
