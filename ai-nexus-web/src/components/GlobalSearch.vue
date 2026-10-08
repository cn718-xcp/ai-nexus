<script setup lang="ts">
/** 全局搜索：Ctrl/Cmd+K 唤起，检索菜单与知识库并快速跳转 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { knowledgeApi } from '@/api'
import type { KnowledgeBase } from '@/types/api'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const router = useRouter()
const keyword = ref('')
const inputRef = ref()
const kbList = ref<KnowledgeBase[]>([])
let kbLoaded = false

const menus = [
  { path: '/dashboard', title: '工作台', desc: '数据看板与组件健康', icon: 'Odometer' },
  { path: '/chat', title: '智能问答', desc: 'RAG 检索增强对话', icon: 'ChatDotRound' },
  { path: '/agent', title: '智能体', desc: '计划-执行多步任务', icon: 'Cpu' },
  { path: '/knowledge', title: '知识库管理', desc: '文档上传与分片管理', icon: 'Collection' },
  { path: '/model', title: '模型服务', desc: '节点池与场景路由', icon: 'MagicStick' },
  { path: '/tenant', title: '租户管理', desc: '配额与状态控制', icon: 'OfficeBuilding' },
  { path: '/config', title: '系统配置', desc: '熔断 / 限流 / 检索参数', icon: 'Setting' },
  { path: '/monitor', title: '运维监控', desc: '链路追踪 / 指标 / 日志', icon: 'Monitor' },
]

interface SearchItem {
  key: string
  title: string
  desc: string
  icon: string
  go: () => void
}

const results = computed<SearchItem[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  const menuHits = menus
    .filter((m) => !kw || m.title.toLowerCase().includes(kw) || m.desc.toLowerCase().includes(kw))
    .map((m) => ({
      key: 'menu-' + m.path,
      title: m.title,
      desc: m.desc,
      icon: m.icon,
      go: () => router.push(m.path),
    }))
  const kbHits = kbList.value
    .filter((k) => !kw || k.name.toLowerCase().includes(kw))
    .slice(0, 6)
    .map((k) => ({
      key: 'kb-' + k.id,
      title: k.name,
      desc: k.description || '知识库',
      icon: 'Notebook',
      go: () => router.push(`/knowledge/${k.id}`),
    }))
  return [...menuHits, ...kbHits]
})

function pick(item: SearchItem) {
  item.go()
  close()
}

function close() {
  emit('update:visible', false)
  keyword.value = ''
}

watch(
  () => props.visible,
  async (v) => {
    if (v) {
      await nextTick()
      inputRef.value?.focus()
      if (!kbLoaded) {
        kbLoaded = true
        knowledgeApi
          .list()
          .then((list) => (kbList.value = list))
          .catch(() => (kbLoaded = false))
      }
    }
  },
)
</script>

<template>
  <el-dialog
    :model-value="visible"
    align-center
    width="520px"
    class="gs-dialog"
    :show-close="false"
    @update:model-value="close"
  >
    <div class="gs-input-wrap an-focus-glow">
      <el-icon class="gs-search-icon" :size="16"><Search /></el-icon>
      <input
        ref="inputRef"
        v-model="keyword"
        class="gs-input"
        placeholder="搜索菜单、知识库…（Esc 关闭）"
        @keydown.esc="close"
        @keydown.enter="results[0] && pick(results[0])"
      />
    </div>
    <div class="gs-results">
      <div
        v-for="(item, i) in results"
        :key="item.key"
        class="gs-item fade-in-item"
        :style="{ animationDelay: i * 40 + 'ms' }"
        @click="pick(item)"
      >
        <el-icon :size="16" class="gs-item-icon"><component :is="item.icon" /></el-icon>
        <div class="gs-item-text">
          <div class="gs-item-title">{{ item.title }}</div>
          <div class="gs-item-desc">{{ item.desc }}</div>
        </div>
        <el-icon :size="12" class="gs-item-arrow"><ArrowRight /></el-icon>
      </div>
      <div v-if="!results.length" class="gs-empty">未找到「{{ keyword }}」相关内容</div>
    </div>
    <div class="gs-footer">
      <span>Enter 跳转首选</span>
      <span>Esc 关闭</span>
    </div>
  </el-dialog>
</template>

<style scoped>
.gs-input-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--an-border);
  border-radius: 10px;
  padding: 10px 14px;
  background: rgba(var(--an-primary-rgb), 0.03);
  transition: box-shadow var(--an-dur) var(--an-ease);
}
.gs-search-icon {
  color: var(--an-text-3);
}
.gs-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: var(--an-text);
}
.gs-input::placeholder {
  color: var(--an-text-3);
}
.gs-results {
  margin-top: 12px;
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.gs-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background var(--an-dur) var(--an-ease);
}
.gs-item:hover {
  background: rgba(var(--an-primary-rgb), 0.07);
}
.gs-item-icon {
  color: var(--an-primary);
}
.gs-item-text {
  flex: 1;
  min-width: 0;
}
.gs-item-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--an-text);
}
.gs-item-desc {
  font-size: 12px;
  color: var(--an-text-3);
}
.gs-item-arrow {
  color: var(--an-text-4);
  opacity: 0;
  transition: opacity var(--an-dur) var(--an-ease);
}
.gs-item:hover .gs-item-arrow {
  opacity: 1;
}
.gs-empty {
  text-align: center;
  color: var(--an-text-3);
  font-size: 13px;
  padding: 26px 0;
}
.gs-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 11px;
  color: var(--an-text-3);
}
</style>
