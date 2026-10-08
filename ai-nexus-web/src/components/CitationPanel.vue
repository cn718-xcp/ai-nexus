<script setup lang="ts">
/**
 * 引用片段面板：折叠展开展示原文片段、来源文档、相似度分
 * 展开动画：grid-rows 0fr→1fr 平滑高度过渡 + 内容淡入 + 箭头旋转
 */
import { ref } from 'vue'
import type { ChunkReference } from '@/types/api'
import { formatPercent } from '@/utils/format'

const props = defineProps<{ references: ChunkReference[]; defaultOpen?: boolean }>()
const open = ref(props.defaultOpen || false)

function scoreTone(score: number | null): 'success' | 'warning' | 'info' {
  if (score === null) return 'info'
  if (score >= 0.8) return 'success'
  if (score >= 0.65) return 'warning'
  return 'info'
}
</script>

<template>
  <div v-if="references.length" class="citation-panel" :class="{ 'is-expanded': open }">
    <div class="cite-toggle" @click="open = !open">
      <el-icon class="an-collapse-arrow" :size="12"><CaretRight /></el-icon>
      <span class="cite-title">参考引用</span>
      <span class="cite-count">{{ references.length }} 个片段</span>
    </div>
    <div class="cite-list-wrap">
      <div class="cite-list">
        <div v-for="(refItem, i) in references" :key="i" class="cite-item fade-in-item" :style="{ animationDelay: i * 50 + 'ms' }">
          <div class="cite-head">
            <span class="cite-idx">{{ i + 1 }}</span>
            <span class="cite-doc an-ellipsis">{{ refItem.documentName }}</span>
            <span v-if="refItem.section" class="cite-section an-ellipsis">{{ refItem.section }}</span>
            <el-tag :type="scoreTone(refItem.score)" size="small" effect="light" round>
              {{ formatPercent(refItem.score) }}
            </el-tag>
          </div>
          <div class="cite-content">{{ refItem.content }}</div>
          <div v-if="refItem.startOffset !== null && refItem.endOffset !== null" class="cite-offset">
            原文偏移 [{{ refItem.startOffset }}, {{ refItem.endOffset }}]
            <template v-if="refItem.chunkIndex !== null"> · 片段 #{{ refItem.chunkIndex }}</template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.citation-panel {
  margin-top: 10px;
  border-top: 1px dashed var(--an-border);
  padding-top: 8px;
}
.cite-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 12px;
  color: var(--an-text-3);
  user-select: none;
  padding: 2px 4px;
  border-radius: 6px;
  transition: color var(--an-dur) var(--an-ease);
}
.cite-toggle:hover {
  color: var(--an-primary);
}
.cite-title {
  font-weight: 600;
  color: var(--an-text-2);
}
.cite-count {
  color: var(--an-text-3);
}
.cite-list-wrap {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--an-dur-long) var(--an-ease);
}
.is-expanded .cite-list-wrap {
  grid-template-rows: 1fr;
}
.cite-list {
  overflow: hidden;
  opacity: 0;
  transition: opacity var(--an-dur-long) var(--an-ease);
}
.is-expanded .cite-list {
  opacity: 1;
}
.cite-item {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(var(--an-primary-rgb), 0.045);
  border: 1px solid var(--an-border);
}
.cite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.cite-idx {
  width: 18px;
  height: 18px;
  border-radius: 6px;
  background: var(--an-primary-gradient);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.cite-doc {
  font-size: 12px;
  font-weight: 600;
  color: var(--an-text);
  max-width: 34%;
}
.cite-section {
  font-size: 12px;
  color: var(--an-text-3);
  flex: 1;
}
.cite-content {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--an-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}
.cite-offset {
  margin-top: 6px;
  font-size: 11px;
  color: var(--an-text-3);
}
</style>
