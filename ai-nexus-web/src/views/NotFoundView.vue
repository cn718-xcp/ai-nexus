<script setup lang="ts">
/** 404 页面 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="nf-page">
    <div class="glass-strong glass nf-card fade-in-item">
      <div class="nf-code an-gradient-text">404</div>
      <div class="nf-text">页面不存在或已被移除</div>
      <el-button type="primary" round @click="router.push('/dashboard')">回到工作台</el-button>
    </div>
  </div>
</template>

<style scoped>
.nf-page {
  height: 100vh;
  display: grid;
  place-items: center;
}
.nf-card {
  padding: 50px 70px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
.nf-code {
  font-size: 72px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.04em;
}
.nf-text {
  color: var(--an-text-2);
  font-size: 15px;
  margin-bottom: 8px;
}
</style>
