<script setup lang="ts">
/**
 * 知识库列表：卡片式展示 + 名称搜索 + 创建 / 备份导入 / 删除（二次确认，同步删除向量数据）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { knowledgeApi } from '@/api'
import { downloadTextFile } from '@/utils/download'
import { formatNumber, formatTime } from '@/utils/format'
import type { KnowledgeBase } from '@/types/api'

const router = useRouter()
const loading = ref(true)
const list = ref<KnowledgeBase[]>([])
const keyword = ref('')

const createVisible = ref(false)
const createLoading = ref(false)
const createForm = ref({ name: '', description: '' })

onMounted(load)

async function load() {
  loading.value = true
  try {
    list.value = await knowledgeApi.list()
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter(
    (k) => k.name.toLowerCase().includes(kw) || (k.description || '').toLowerCase().includes(kw),
  )
})

async function onCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  createLoading.value = true
  try {
    await knowledgeApi.create({
      name: createForm.value.name.trim(),
      description: createForm.value.description.trim() || undefined,
    })
    ElMessage.success('知识库创建成功')
    createVisible.value = false
    createForm.value = { name: '', description: '' }
    await load()
  } catch {
    /* 统一错误提示 */
  } finally {
    createLoading.value = false
  }
}

/** 删除知识库：二次确认，级联删除向量数据 */
function onRemove(kb: KnowledgeBase) {
  ElMessageBox.confirm(
    `删除知识库「${kb.name}」将同时清除其在 Milvus 中的全部向量数据，且不可恢复。确认删除？`,
    '高危操作确认',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
  )
    .then(async () => {
      await knowledgeApi.remove(kb.id)
      ElMessage.success('知识库已删除，向量数据同步清理')
      await load()
    })
    .catch(() => {})
}

/** 导出备份：kb 元数据 + 全部片段原文 JSON */
async function onBackup(kb: KnowledgeBase) {
  try {
    const json = await knowledgeApi.backup(kb.id)
    downloadTextFile(`ai-nexus-kb-${kb.name}-${Date.now()}.json`, json)
    ElMessage.success('备份已导出')
  } catch {
    /* 统一错误提示 */
  }
}

const importing = ref(false)
async function onImport(options: UploadRequestOptions) {
  importing.value = true
  try {
    const kb = await knowledgeApi.importBackup(options.file)
    ElMessage.success(`导入成功，已创建知识库「${kb.name}」`)
    await load()
  } catch {
    /* 统一错误提示 */
  } finally {
    importing.value = false
  }
  return options.file
}
</script>

<template>
  <div class="an-page">
    <PageHeader title="知识库管理" subtitle="文档上传、向量化分片与检索配置">
      <template #actions>
        <el-upload :show-file-list="false" accept=".json" :http-request="onImport">
          <el-button :icon="'Upload'" :loading="importing">导入备份</el-button>
        </el-upload>
        <el-button type="primary" :icon="'Plus'" @click="createVisible = true">新建知识库</el-button>
      </template>
    </PageHeader>

    <div class="kb-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索知识库名称 / 描述"
        :prefix-icon="'Search'"
        clearable
        class="kb-search"
      />
      <span class="an-subtle">共 {{ list.length }} 个知识库</span>
    </div>

    <div v-if="loading" class="kb-grid">
      <div v-for="i in 4" :key="i" class="glass kb-card">
        <div class="kb-skeleton"></div>
      </div>
    </div>

    <div v-else-if="filtered.length" class="kb-grid">
      <div
        v-for="(kb, i) in filtered"
        :key="kb.id"
        class="glass hover-lift kb-card stagger-item"
        :style="{ animationDelay: Math.min(i, 12) * 50 + 'ms' }"
        @click="router.push(`/knowledge/${kb.id}`)"
      >
        <div class="kb-head">
          <span class="kb-icon">
            <el-icon :size="20"><Collection /></el-icon>
          </span>
          <div class="kb-name-wrap">
            <div class="kb-name an-ellipsis">{{ kb.name }}</div>
            <div class="kb-time">{{ formatTime(kb.createdAt, 'YYYY-MM-DD') }}</div>
          </div>
          <el-tag :type="kb.status === 1 ? 'success' : 'info'" size="small" effect="light" round>
            {{ kb.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </div>
        <p class="kb-desc">{{ kb.description || '暂无描述' }}</p>
        <div class="kb-collection">
          <el-icon :size="12"><Coin /></el-icon>
          <span class="an-ellipsis">{{ kb.collectionName }}</span>
        </div>
        <div class="kb-actions" @click.stop>
          <el-button size="small" text type="primary" :icon="'View'" @click="router.push(`/knowledge/${kb.id}`)">
            详情
          </el-button>
          <el-button size="small" text :icon="'Download'" @click="onBackup(kb)">备份</el-button>
          <el-button size="small" text type="danger" :icon="'Delete'" @click="onRemove(kb)">删除</el-button>
        </div>
      </div>
    </div>

    <div v-else class="glass">
      <EmptyState text="没有匹配的知识库" sub="调整搜索关键词，或创建一个新的知识库">
        <el-button type="primary" :icon="'Plus'" @click="createVisible = true">新建知识库</el-button>
      </EmptyState>
    </div>

    <!-- 创建对话框 -->
    <el-dialog v-model="createVisible" title="新建知识库" width="460px" align-center>
      <el-form label-position="top" @submit.prevent="onCreate">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="例如：产品手册库" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="知识库用途说明（可选，512 字以内）"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;
}
.kb-search {
  width: 320px;
}
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}
.kb-card {
  padding: 18px 20px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kb-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.kb-icon {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  background: var(--an-primary-gradient);
  box-shadow: 0 6px 14px rgba(var(--an-primary-rgb), 0.28);
  flex-shrink: 0;
}
.kb-name-wrap {
  flex: 1;
  min-width: 0;
}
.kb-name {
  font-weight: 700;
  font-size: 15px;
  color: var(--an-text);
}
.kb-time {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.kb-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--an-text-2);
  line-height: 1.65;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 41px;
}
.kb-collection {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--an-text-3);
  font-family: Consolas, monospace;
}
.kb-actions {
  display: flex;
  justify-content: space-between;
  border-top: 1px dashed var(--an-border);
  padding-top: 8px;
  margin-top: 2px;
}
.kb-skeleton {
  height: 150px;
  border-radius: 10px;
  background: linear-gradient(90deg, rgba(127, 145, 180, 0.1), rgba(127, 145, 180, 0.2), rgba(127, 145, 180, 0.1));
  background-size: 200% 100%;
  animation: sk 1.2s ease infinite;
}
@keyframes sk {
  to {
    background-position: -200% 0;
  }
}
</style>
