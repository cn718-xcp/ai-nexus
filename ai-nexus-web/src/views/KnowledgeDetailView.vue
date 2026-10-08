<script setup lang="ts">
/**
 * 知识库详情：
 * - 文档列表（txt/md/json/csv 上传，进度条）+ 状态（处理中/已入库/失败）
 * - 分片管理：解析备份 JSON 查看 Chunk（备份含 idx + 原文；相似度分在检索时由引用面板展示）
 * - 检索配置：展示全局生效参数（rag.similarity-threshold / rag.mode 等），跳转系统配置修改
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadRequestOptions } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { knowledgeApi, parseBackup, configApi } from '@/api'
import { downloadTextFile } from '@/utils/download'
import { formatNumber, formatTime } from '@/utils/format'
import type { KnowledgeBase, KnowledgeDocument } from '@/types/api'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => Number(route.params.id))

const loading = ref(true)
const kb = ref<KnowledgeBase | null>(null)
const docs = ref<KnowledgeDocument[]>([])
const activeTab = ref('docs')

/* ===== 上传 ===== */
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadFile = ref<File | null>(null)
const uploadResult = ref<KnowledgeDocument | null>(null)
const uploadError = ref('')

const ACCEPT = ['.txt', '.md', '.json', '.csv']

function pickFile(uploadFileList?: UploadFile[]) {
  const f = uploadFileList?.[0]?.raw as File | undefined
  if (!f) return
  const ext = '.' + (f.name.split('.').pop()?.toLowerCase() || '')
  if (!ACCEPT.includes(ext)) {
    ElMessage.warning('仅支持 txt / md / json / csv 格式')
    return
  }
  if (f.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过 10MB')
    return
  }
  uploadFile.value = f
  uploadResult.value = null
  uploadError.value = ''
}

function beforeUpload() {
  return false // 手动上传
}

async function doUpload() {
  if (!uploadFile.value || uploading.value) return
  uploading.value = true
  uploadPercent.value = 0
  uploadError.value = ''
  try {
    const doc = await knowledgeApi.upload(kbId.value, uploadFile.value, (p) => (uploadPercent.value = p))
    uploadResult.value = doc
    ElMessage.success('上传成功，文档解析入库中…')
    // 3 秒后刷新状态（后端异步解析）
    setTimeout(() => void loadDocs(), 3000)
    void loadDocs()
  } catch (e) {
    uploadError.value = (e as Error).message || '上传失败'
  } finally {
    uploading.value = false
  }
}

function onHttpUpload(options: UploadRequestOptions) {
  pickFile([{ raw: options.file, name: options.file.name } as UploadFile])
  return options.file
}

/* ===== 统计 ===== */
const totalChunks = computed(() => docs.value.reduce((a, d) => a + d.chunkCount, 0))
const totalChars = computed(() => docs.value.reduce((a, d) => a + d.charCount, 0))

/* ===== 分片管理 ===== */
const chunksLoading = ref(false)
const chunkDocs = ref<{ fileName: string; docUuid: string; chunks: { idx: number; content: string }[] }[]>([])
const selectedDocUuid = ref('')
const currentChunks = computed(() => chunkDocs.value.find((d) => d.docUuid === selectedDocUuid.value)?.chunks || [])

async function loadChunks() {
  chunksLoading.value = true
  try {
    const json = await knowledgeApi.backup(kbId.value)
    const payload = parseBackup(json)
    chunkDocs.value = payload?.documents || []
    if (!selectedDocUuid.value && chunkDocs.value.length) {
      selectedDocUuid.value = chunkDocs.value[0].docUuid
    }
  } finally {
    chunksLoading.value = false
  }
}
watch(selectedDocUuid, () => void 0)

/* ===== 检索配置（全局生效）===== */
const ragConfig = ref<Record<string, string>>({})
const RAG_KEYS = ['rag.similarity-threshold', 'rag.mode', 'rag.rerank-enabled', 'rag.cache-enabled']
async function loadRagConfig() {
  try {
    ragConfig.value = await configApi.effective()
  } catch {
    ragConfig.value = {}
  }
}

async function loadDocs() {
  docs.value = await knowledgeApi.documents(kbId.value)
}

onMounted(async () => {
  loading.value = true
  try {
    const list = await knowledgeApi.list()
    kb.value = list.find((k) => k.id === kbId.value) || null
    if (!kb.value) {
      ElMessage.error('知识库不存在或已被删除')
      router.replace('/knowledge')
      return
    }
    await loadDocs()
    void loadRagConfig()
  } finally {
    loading.value = false
  }
})

function onTabChange(tab: string | number) {
  if (tab === 'chunks' && !chunkDocs.value.length) void loadChunks()
}

async function onBackup() {
  if (!kb.value) return
  const json = await knowledgeApi.backup(kbId.value)
  downloadTextFile(`ai-nexus-kb-${kb.value.name}-${Date.now()}.json`, json)
  ElMessage.success('备份已导出')
}

function onRemove() {
  if (!kb.value) return
  ElMessageBox.confirm(
    `删除知识库「${kb.value.name}」将同时清除全部向量数据，不可恢复。确认删除？`,
    '高危操作确认',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  )
    .then(async () => {
      await knowledgeApi.remove(kbId.value)
      ElMessage.success('知识库已删除')
      router.replace('/knowledge')
    })
    .catch(() => {})
}

function docStatusType(status: number) {
  return status === 1 ? 'success' : status === 2 ? 'danger' : 'warning'
}
function docStatusText(status: number) {
  return status === 1 ? '已入库' : status === 2 ? '失败' : '处理中'
}
</script>

<template>
  <div class="an-page">
    <PageHeader :title="kb?.name || '知识库详情'" :subtitle="kb?.description || ''">
      <template #actions>
        <el-button :icon="'Back'" @click="router.push('/knowledge')">返回列表</el-button>
        <el-button :icon="'Download'" @click="onBackup">导出备份</el-button>
        <el-button type="primary" :icon="'Upload'" @click="uploadVisible = true">上传文档</el-button>
        <el-button type="danger" plain :icon="'Delete'" @click="onRemove">删除</el-button>
      </template>
    </PageHeader>

    <!-- 概览 -->
    <div class="glass detail-overview stagger-item">
      <div class="ov-item">
        <div class="ov-value">{{ formatNumber(docs.length) }}</div>
        <div class="ov-label">文档数</div>
      </div>
      <div class="ov-sep"></div>
      <div class="ov-item">
        <div class="ov-value">{{ formatNumber(totalChunks) }}</div>
        <div class="ov-label">分片 Chunk</div>
      </div>
      <div class="ov-sep"></div>
      <div class="ov-item">
        <div class="ov-value">{{ formatNumber(totalChars) }}</div>
        <div class="ov-label">总字符数</div>
      </div>
      <div class="ov-sep"></div>
      <div class="ov-item">
        <div class="ov-value collection">{{ kb?.collectionName }}</div>
        <div class="ov-label">Milvus Collection</div>
      </div>
    </div>

    <div class="glass detail-tabs stagger-item" style="animation-delay: 60ms">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- 文档列表 -->
        <el-tab-pane label="文档列表" name="docs">
          <el-table v-loading="loading" :data="docs" style="width: 100%">
            <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="doc-name"><el-icon style="vertical-align: -2px"><Document /></el-icon> {{ row.fileName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="charCount" label="字符数" width="110" align="right">
              <template #default="{ row }">{{ formatNumber(row.charCount) }}</template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="分片数" width="100" align="right">
              <template #default="{ row }">{{ row.chunkCount }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tooltip v-if="row.errorMessage" :content="row.errorMessage" placement="top">
                  <el-tag :type="docStatusType(row.status)" size="small" effect="light" round>
                    {{ docStatusText(row.status) }}
                  </el-tag>
                </el-tooltip>
                <el-tag v-else :type="docStatusType(row.status)" size="small" effect="light" round>
                  <span v-if="row.status === 0" class="processing-dot"></span>
                  {{ docStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click="selectedDocUuid = row.docUuid; activeTab = 'chunks'"
                >
                  查看分片
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState text="暂无文档" sub="上传 txt / md / json / csv 文件开始构建知识库" />
            </template>
          </el-table>
        </el-tab-pane>

        <!-- 分片管理 -->
        <el-tab-pane label="分片管理" name="chunks">
          <div class="chunks-toolbar">
            <el-select v-model="selectedDocUuid" placeholder="选择文档" style="width: 300px">
              <el-option v-for="d in chunkDocs" :key="d.docUuid" :label="d.fileName" :value="d.docUuid" />
            </el-select>
            <span class="an-subtle">共 {{ currentChunks.length }} 个分片 · 相似度分在「智能问答」引用面板中展示</span>
          </div>
          <div v-loading="chunksLoading" class="chunk-list">
            <div
              v-for="c in currentChunks"
              :key="c.idx"
              class="chunk-item fade-in-item"
              :style="{ animationDelay: Math.min(c.idx, 10) * 50 + 'ms' }"
            >
              <div class="chunk-head">
                <span class="chunk-idx">#{{ c.idx }}</span>
                <span class="chunk-len">{{ c.content.length }} 字符</span>
              </div>
              <div class="chunk-content">{{ c.content }}</div>
            </div>
            <EmptyState v-if="!chunksLoading && !currentChunks.length" text="暂无分片数据" sub="上传文档并解析入库后可查看" />
          </div>
        </el-tab-pane>

        <!-- 检索配置 -->
        <el-tab-pane label="检索配置" name="settings">
          <div class="settings-note">
            <el-alert
              type="info"
              show-icon
              :closable="false"
              title="以下为平台级检索参数（对所有知识库全局生效）"
              description="在「系统配置」页修改后实时生效，无需重启服务。"
            />
          </div>
          <div class="rag-config-list">
            <div v-for="key in RAG_KEYS" :key="key" class="rag-config-item">
              <code class="rc-key">{{ key }}</code>
              <el-tag size="small" effect="light" round>{{ ragConfig[key] ?? '读取中…' }}</el-tag>
            </div>
          </div>
          <el-button type="primary" plain :icon="'Setting'" style="margin-top: 16px" @click="router.push('/config')">
            前往系统配置修改
          </el-button>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 上传对话框 -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="500px" align-center @close="uploadFile = null; uploadPercent = 0; uploadResult = null; uploadError = ''">
      <el-upload
        drag
        multiple
        :accept="ACCEPT.join(',')"
        :auto-upload="false"
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="onHttpUpload"
      >
        <div class="upload-empty">
          <el-icon :size="30" color="var(--an-primary)"><UploadFilled /></el-icon>
          <div class="upload-text">拖拽文件到此处，或 <em>点击选择</em></div>
          <div class="upload-hint">支持 txt / md / json / csv，单文件 ≤ 10MB</div>
        </div>
      </el-upload>

      <div v-if="uploadFile" class="upload-file-row">
        <el-icon><Document /></el-icon>
        <span class="an-ellipsis">{{ uploadFile.name }}</span>
        <span class="upload-size">{{ (uploadFile.size / 1024).toFixed(1) }} KB</span>
      </div>

      <el-progress
        v-if="uploading || uploadPercent > 0"
        :percentage="uploadPercent"
        :stroke-width="8"
        striped
        striped-flow
      />

      <el-alert v-if="uploadError" type="error" show-icon :closable="false" :title="uploadError" style="margin-top: 10px" />
      <el-alert
        v-if="uploadResult"
        type="success"
        show-icon
        :closable="false"
        style="margin-top: 10px"
        :title="`上传成功：切分为 ${uploadResult.chunkCount} 个分片，正在后台向量化入库`"
      />

      <template #footer>
        <el-button @click="uploadVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!uploadFile" :loading="uploading" @click="doUpload">开始上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-overview {
  display: flex;
  align-items: center;
  gap: 26px;
  padding: 16px 24px;
  margin-bottom: 14px;
}
.ov-item {
  text-align: left;
}
.ov-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--an-text);
  font-variant-numeric: tabular-nums;
}
.ov-value.collection {
  font-size: 15px;
  font-family: Consolas, monospace;
  font-weight: 600;
  color: var(--an-primary);
}
.ov-label {
  font-size: 12px;
  color: var(--an-text-3);
}
.ov-sep {
  width: 1px;
  height: 34px;
  background: var(--an-border);
}

.detail-tabs {
  padding: 6px 18px 16px;
}
.doc-name {
  font-weight: 500;
}
.processing-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--an-warning);
  margin-right: 4px;
  animation: blink 1.1s ease infinite;
}
@keyframes blink {
  50% {
    opacity: 0.25;
  }
}

.chunks-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}
.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}
.chunk-item {
  border: 1px solid var(--an-border);
  border-radius: 10px;
  padding: 12px 14px;
  background: rgba(var(--an-primary-rgb), 0.03);
  transition: border-color var(--an-dur) var(--an-ease), box-shadow var(--an-dur) var(--an-ease);
}
.chunk-item:hover {
  border-color: rgba(var(--an-primary-rgb), 0.35);
  box-shadow: var(--an-focus-ring);
}
.chunk-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}
.chunk-idx {
  font-weight: 700;
  color: var(--an-primary);
  font-size: 12.5px;
}
.chunk-len {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.chunk-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--an-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.settings-note {
  margin-bottom: 16px;
}
.rag-config-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rag-config-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 11px 14px;
  border-radius: 10px;
  border: 1px solid var(--an-border);
  background: rgba(var(--an-primary-rgb), 0.03);
}
.rc-key {
  font-size: 12.5px;
  color: var(--an-text-2);
  font-family: Consolas, monospace;
}

.upload-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
}
.upload-text {
  font-size: 13.5px;
  color: var(--an-text);
}
.upload-text em {
  color: var(--an-primary);
  font-style: normal;
}
.upload-hint {
  font-size: 11.5px;
  color: var(--an-text-3);
}
.upload-file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 9px 12px;
  border-radius: 8px;
  background: rgba(var(--an-primary-rgb), 0.05);
  font-size: 13px;
}
.upload-size {
  margin-left: auto;
  color: var(--an-text-3);
  font-size: 12px;
}
</style>
