<script setup lang="ts">
/**
 * 智能问答（RAG 对话）
 * - 左侧会话列表（localStorage 持久化，刷新不丢失）+ 云端会话恢复（/chat/{id}/replay）
 * - 右侧对话窗口：SSE 流式逐字渲染（/rag/stream）或同步问答（/rag/ask，含引用片段）
 * - 顶部：模型选择 / 知识库绑定 / topK / 模式切换
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ChatUIMessage, ChatUISession, ChunkReference, KnowledgeBase } from '@/types/api'
import { chatApi, knowledgeApi, modelApi, ragApi, createRagStream } from '@/api'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import CitationPanel from '@/components/CitationPanel.vue'
import ThinkingDots from '@/components/ThinkingDots.vue'
import EmptyState from '@/components/EmptyState.vue'
import { genId } from '@/utils/jwt'
import { timeAgo } from '@/utils/format'

const SESSIONS_KEY = 'ainexus.chat.sessions.v1'
const SUGGESTIONS = ['AI-Nexus 平台有哪些核心能力？', 'RAG 相似度阈值如何配置？', '多租户配额超限会怎样？', '知识库支持哪些文件格式？']

/* ===== 会话存储 ===== */
const sessions = ref<ChatUISession[]>(loadSessions())
const activeId = ref<string>('')
const input = ref('')
const streaming = ref(false)
const waitingFirst = ref(false)
const streamCtl = createRagStream()

function loadSessions(): ChatUISession[] {
  try {
    return JSON.parse(localStorage.getItem(SESSIONS_KEY) || '[]') as ChatUISession[]
  } catch {
    return []
  }
}
watch(
  sessions,
  (v) => {
    try {
      localStorage.setItem(SESSIONS_KEY, JSON.stringify(v.slice(0, 50)))
    } catch {
      /* 存储满忽略 */
    }
  },
  { deep: true },
)

const activeSession = computed(() => sessions.value.find((s) => s.id === activeId.value))
const sortedSessions = computed(() => [...sessions.value].sort((a, b) => b.updatedAt - a.updatedAt))

/* ===== 顶部配置项 ===== */
const kbList = ref<KnowledgeBase[]>([])
const modelOptions = ref<string[]>([])
const sessionSearch = ref('')

onMounted(async () => {
  const [kbRes, routeRes] = await Promise.allSettled([knowledgeApi.list(), modelApi.routes()])
  if (kbRes.status === 'fulfilled') kbList.value = kbRes.value
  if (routeRes.status === 'fulfilled' && Array.isArray(routeRes.value)) {
    modelOptions.value = [...new Set(routeRes.value.map((r: { primary: string }) => r.primary))]
  }
  if (!sessions.value.length) newSession()
  else activeId.value = sessions.value[0].id
})

function newSession() {
  const s: ChatUISession = {
    id: genId(),
    title: '新对话',
    kbName: kbList.value[0]?.name ?? null,
    topK: 5,
    mode: 'stream',
    createdAt: Date.now(),
    updatedAt: Date.now(),
    messages: [],
  }
  sessions.value.unshift(s)
  activeId.value = s.id
}

function removeSession(s: ChatUISession, e: Event) {
  e.stopPropagation()
  ElMessageBox.confirm(`删除会话「${s.title}」？`, '删除确认', { type: 'warning', confirmButtonText: '删除' })
    .then(() => {
      sessions.value = sessions.value.filter((x) => x.id !== s.id)
      if (activeId.value === s.id) {
        if (sessions.value.length) activeId.value = sessions.value[0].id
        else newSession()
      }
    })
    .catch(() => {})
}

const filteredSessions = computed(() => {
  const kw = sessionSearch.value.trim().toLowerCase()
  if (!kw) return sortedSessions.value
  return sortedSessions.value.filter((s) => s.title.toLowerCase().includes(kw))
})

/* ===== 消息滚动 ===== */
const scrollRef = ref<HTMLElement>()
function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
watch(
  () => activeSession.value?.messages.map((m) => m.content.length).join(',') + '|' + activeId.value,
  () => scrollToBottom(),
)

/* ===== 发送 ===== */
function pushMessage(msg: ChatUIMessage) {
  activeSession.value?.messages.push(msg)
}

async function send(text?: string) {
  const question = (text ?? input.value).trim()
  const session = activeSession.value
  if (!question || !session || streaming.value) return

  input.value = ''
  if (session.title === '新对话') session.title = question.slice(0, 24)
  session.updatedAt = Date.now()

  pushMessage({ id: genId(), role: 'user', content: question, time: new Date().toISOString() })
  const assistant: ChatUIMessage = {
    id: genId(),
    role: 'assistant',
    content: '',
    time: new Date().toISOString(),
    kbName: session.kbName,
    streaming: true,
  }
  pushMessage(assistant)
  streaming.value = true
  waitingFirst.value = true

  const params = {
    sessionId: session.id,
    question,
    knowledgeBaseName: session.kbName || undefined,
    topK: session.topK,
    scene: 'RAG' as const,
  }

  if (session.mode === 'stream') {
    await streamCtl.start(params, {
      onToken: (t) => {
        if (waitingFirst.value) waitingFirst.value = false
        assistant.content += t
      },
      onDone: () => {
        assistant.streaming = false
        streaming.value = false
        waitingFirst.value = false
        if (!assistant.content) {
          assistant.content = '（模型未返回内容，请重试）'
          assistant.error = true
        }
      },
      onError: (e) => {
        assistant.streaming = false
        streaming.value = false
        waitingFirst.value = false
        assistant.error = true
        assistant.content = assistant.content || '—'
        ElMessage.error(e.message || '流式请求异常')
      },
    })
  } else {
    try {
      const res = await ragApi.ask(params)
      assistant.content = res.answer
      assistant.references = res.references as ChunkReference[]
      assistant.modelName = null
      if (res.fallback) ElMessage.warning('模型暂不可用，已返回降级兜底内容')
    } catch (e) {
      assistant.error = true
      assistant.content = '（请求失败：' + ((e as Error).message || '未知错误') + '）'
    } finally {
      assistant.streaming = false
      streaming.value = false
      waitingFirst.value = false
    }
  }
}

function stopStream() {
  streamCtl.abort()
  streaming.value = false
  waitingFirst.value = false
  const last = activeSession.value?.messages[activeSession.value.messages.length - 1]
  if (last?.streaming) {
    last.streaming = false
    last.content += '\n\n> ⏹ 已手动中断本次生成'
  }
}

/** 异常重连：重发最后一条用户消息 */
function retryLast() {
  const msgs = activeSession.value?.messages || []
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'user') {
      // 移除失败回答后重发
      if (msgs[i + 1]?.role === 'assistant') msgs.splice(i + 1, 1)
      const q = msgs[i].content
      msgs.splice(i, 1)
      send(q)
      return
    }
  }
}

/* ===== 云端会话恢复（/chat/{sessionId}/replay）===== */
const restoreVisible = ref(false)
const restoreId = ref('')
const restoreLoading = ref(false)
async function doRestore() {
  const sid = restoreId.value.trim()
  if (!sid) return
  restoreLoading.value = true
  try {
    const records = await chatApi.replay(sid)
    if (!records.length) {
      ElMessage.warning('该会话在服务端没有历史记录')
      return
    }
    const s: ChatUISession = {
      id: genId(),
      title: `云端会话 ${sid.slice(0, 8)}`,
      kbName: null,
      topK: 5,
      mode: 'stream',
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: records.map((r) => ({
        id: genId(),
        role: r.role,
        content: r.content,
        time: r.createdAt,
        modelName: r.modelName,
      })),
    }
    sessions.value.unshift(s)
    activeId.value = s.id
    restoreVisible.value = false
    restoreId.value = ''
    ElMessage.success(`已恢复 ${records.length} 条云端消息`)
  } catch {
    ElMessage.warning('未查询到该会话记录（RAG 流式会话仅保存在本地）')
  } finally {
    restoreLoading.value = false
  }
}

onBeforeUnmount(() => streamCtl.abort())
</script>

<template>
  <div class="chat-page">
    <!-- 左侧会话面板 -->
    <aside class="glass sessions-panel">
      <div class="sp-head">
        <el-button type="primary" class="new-chat-btn" :icon="'Plus'" @click="newSession">新建对话</el-button>
        <el-input v-model="sessionSearch" placeholder="搜索会话" :prefix-icon="'Search'" size="small" clearable />
      </div>
      <div class="sp-list">
        <div
          v-for="(s, i) in filteredSessions"
          :key="s.id"
          class="session-item stagger-item"
          :class="{ active: s.id === activeId }"
          :style="{ animationDelay: Math.min(i, 12) * 50 + 'ms' }"
          @click="activeId = s.id"
        >
          <el-icon :size="15" class="si-icon"><ChatDotRound /></el-icon>
          <div class="si-body">
            <div class="si-title an-ellipsis">{{ s.title }}</div>
            <div class="si-meta">
              <span v-if="s.kbName" class="si-kb">{{ s.kbName }}</span>
              <span>{{ timeAgo(s.updatedAt) }}</span>
            </div>
          </div>
          <el-icon class="si-del" :size="13" @click="removeSession(s, $event)"><Delete /></el-icon>
        </div>
        <EmptyState v-if="!filteredSessions.length" text="没有匹配的会话" />
      </div>
      <div class="sp-foot" @click="restoreVisible = true">
        <el-icon :size="13"><Cloudy /></el-icon>
        <span>恢复云端会话</span>
      </div>
    </aside>

    <!-- 右侧对话窗口 -->
    <section class="glass chat-window">
      <!-- 顶部配置栏 -->
      <div class="cw-toolbar">
        <el-select
          :model-value="activeSession?.kbName ?? undefined"
          placeholder="绑定知识库（留空全库检索）"
          clearable
          size="default"
          class="tb-select"
          @update:model-value="(v: string) => { if (activeSession) activeSession.kbName = v ?? null }"
        >
          <el-option v-for="k in kbList" :key="k.id" :label="k.name" :value="k.name" />
        </el-select>
        <el-select
          :model-value="activeSession?.mode || 'stream'"
          size="default"
          class="tb-mode"
          @update:model-value="(v: 'stream' | 'sync') => { if (activeSession) activeSession.mode = v }"
        >
          <el-option label="流式输出" value="stream" />
          <el-option label="同步问答" value="sync" />
        </el-select>
        <div class="topk-wrap">
          <span class="topk-label">TopK</span>
          <el-slider
            :model-value="activeSession?.topK ?? 5"
            :min="1"
            :max="20"
            :step="1"
            style="width: 90px"
            @update:model-value="(v: number) => { if (activeSession) activeSession.topK = v }"
          />
          <span class="topk-value">{{ activeSession?.topK ?? 5 }}</span>
        </div>
        <div class="tb-spacer"></div>
        <el-tooltip content="重发最后一条消息" placement="top">
          <el-button circle :icon="'RefreshRight'" :disabled="streaming" @click="retryLast" />
        </el-tooltip>
        <el-tooltip content="清空当前会话消息" placement="top">
          <el-button
            circle
            :icon="'DeleteFilled'"
            :disabled="!activeSession?.messages.length"
            @click="activeSession && (activeSession.messages = [])"
          />
        </el-tooltip>
      </div>

      <!-- 消息区 -->
      <div ref="scrollRef" class="cw-messages">
        <EmptyState
          v-if="!activeSession?.messages.length"
          class="cw-empty"
          text="开始与 AI-Nexus 对话"
          sub="回答基于所选知识库检索增强生成，支持流式输出与引用溯源"
        >
          <div class="suggestion-chips">
            <button v-for="s in SUGGESTIONS" :key="s" class="chip" @click="send(s)">{{ s }}</button>
          </div>
        </EmptyState>

        <template v-else>
          <div
            v-for="(m, i) in activeSession.messages"
            :key="m.id"
            class="msg-row"
            :class="m.role === 'user' ? 'right' : 'left'"
          >
            <div class="msg-bubble bubble-in" :class="[m.role, { error: m.error }]" :style="{ animationDelay: Math.min(i, 8) * 30 + 'ms' }">
              <div v-if="m.role === 'assistant'" class="msg-avatar-row">
                <span class="ai-badge">AI</span>
                <span class="msg-kb an-ellipsis">{{ m.kbName || '全库检索' }}</span>
                <span class="msg-time">{{ timeAgo(m.time) }}</span>
              </div>
              <template v-if="m.role === 'assistant' && waitingFirst && m.streaming">
                <ThinkingDots text="正在检索知识库并生成回答…" />
              </template>
              <template v-else>
                <MarkdownRenderer :content="m.content" :streaming="m.streaming" />
              </template>
              <CitationPanel v-if="m.role === 'assistant' && m.references?.length" :references="m.references" />
            </div>
          </div>
        </template>
      </div>

      <!-- 输入区 -->
      <div class="cw-input-area an-focus-glow">
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          placeholder="输入问题，Enter 发送 / Shift+Enter 换行"
          resize="none"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="input-actions">
          <span class="input-hint">Enter 发送 · Shift+Enter 换行</span>
          <el-button v-if="streaming" type="danger" plain round @click="stopStream">
            <el-icon style="margin-right: 4px"><VideoPause /></el-icon>停止
          </el-button>
          <el-button
            v-else
            type="primary"
            round
            :disabled="!input.trim()"
            @click="send()"
          >
            <el-icon style="margin-right: 4px"><Promotion /></el-icon>发送
          </el-button>
        </div>
      </div>
    </section>

    <!-- 恢复云端会话 -->
    <el-dialog v-model="restoreVisible" title="恢复云端会话" width="440px" align-center>
      <p class="restore-tip">
        输入会话 ID（sessionId），从服务端拉取该会话的历史消息。纯 Chat 会话记录在服务端持久化；RAG 流式会话仅保存在本地浏览器。
      </p>
      <el-input v-model="restoreId" placeholder="例如：b8e19c2e-4f3a-…" clearable @keyup.enter="doRestore" />
      <template #footer>
        <el-button @click="restoreVisible = false">取消</el-button>
        <el-button type="primary" :loading="restoreLoading" @click="doRestore">恢复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  gap: 14px;
  height: calc(100vh - var(--an-header-height));
  padding: 14px 18px 16px;
  max-width: 1600px;
  margin: 0 auto;
}

/* ===== 会话面板 ===== */
.sessions-panel {
  width: 248px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 14px;
}
.sp-head {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 10px;
}
.new-chat-btn {
  border-radius: 10px;
}
.sp-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  border-radius: 10px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all var(--an-dur) var(--an-ease);
  position: relative;
}
.session-item:hover {
  background: rgba(var(--an-primary-rgb), 0.06);
}
.session-item.active {
  background: rgba(var(--an-primary-rgb), 0.1);
  border-color: rgba(var(--an-primary-rgb), 0.25);
}
.si-icon {
  color: var(--an-text-3);
  flex-shrink: 0;
}
.session-item.active .si-icon {
  color: var(--an-primary);
}
.si-body {
  flex: 1;
  min-width: 0;
}
.si-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--an-text);
}
.si-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--an-text-3);
  margin-top: 1px;
}
.si-kb {
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--an-primary);
}
.si-del {
  opacity: 0;
  color: var(--an-text-3);
  transition: all var(--an-dur) var(--an-ease);
  flex-shrink: 0;
}
.session-item:hover .si-del {
  opacity: 1;
}
.si-del:hover {
  color: var(--an-danger);
  transform: scale(1.15);
}
.sp-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  justify-content: center;
  padding-top: 10px;
  border-top: 1px dashed var(--an-border);
  color: var(--an-text-3);
  font-size: 12px;
  cursor: pointer;
  transition: color var(--an-dur) var(--an-ease);
}
.sp-foot:hover {
  color: var(--an-primary);
}

/* ===== 对话窗口 ===== */
.chat-window {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.cw-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--an-border);
  flex-shrink: 0;
}
.tb-select {
  width: 230px;
}
.tb-mode {
  width: 110px;
}
.tb-spacer {
  flex: 1;
}
.topk-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}
.topk-label {
  font-size: 12px;
  color: var(--an-text-3);
}
.topk-value {
  font-size: 12px;
  font-weight: 700;
  color: var(--an-primary);
  width: 18px;
}
.opt-docs {
  float: right;
  color: var(--an-text-3);
  font-size: 11px;
}

.cw-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px 22px;
}
.cw-empty {
  height: 100%;
  justify-content: center;
}
.suggestion-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 460px;
}
.chip {
  border: 1px solid var(--an-border);
  background: var(--an-surface-strong);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 12.5px;
  color: var(--an-text-2);
  cursor: pointer;
  transition: all var(--an-dur) var(--an-ease);
}
.chip:hover {
  color: var(--an-primary);
  border-color: rgba(var(--an-primary-rgb), 0.45);
  box-shadow: var(--an-focus-ring);
  transform: translateY(-2px);
}

.msg-row {
  display: flex;
  margin-bottom: 18px;
}
.msg-row.right {
  justify-content: flex-end;
}
.msg-row.left {
  justify-content: flex-start;
}
.msg-bubble {
  max-width: 78%;
  border-radius: 16px;
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.65;
}
.msg-bubble.user {
  background: var(--an-primary-gradient);
  color: #fff;
  border-top-right-radius: 6px;
  box-shadow: 0 6px 18px rgba(var(--an-primary-rgb), 0.3);
}
.msg-bubble.assistant {
  background: var(--an-surface-strong);
  border: 1px solid var(--an-border);
  border-top-left-radius: 6px;
  box-shadow: var(--an-shadow);
}
.msg-bubble.error {
  border-color: rgba(245, 63, 63, 0.4);
}
.msg-avatar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.ai-badge {
  background: var(--an-primary-gradient);
  color: #fff;
  font-size: 10px;
  font-weight: 800;
  border-radius: 6px;
  padding: 2px 6px;
  letter-spacing: 0.06em;
}
.msg-kb {
  font-size: 11.5px;
  color: var(--an-primary);
  max-width: 200px;
}
.msg-time {
  font-size: 11px;
  color: var(--an-text-3);
  margin-left: auto;
}

/* ===== 输入区 ===== */
.cw-input-area {
  border-top: 1px solid var(--an-border);
  padding: 12px 16px;
  flex-shrink: 0;
  border-radius: 0;
}
.cw-input-area :deep(.el-textarea__inner) {
  background: transparent;
  box-shadow: none !important;
  padding-left: 4px;
}
.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}
.input-hint {
  font-size: 11px;
  color: var(--an-text-3);
}
.restore-tip {
  font-size: 12.5px;
  color: var(--an-text-2);
  line-height: 1.7;
  margin: 0 0 12px;
}

@media (max-width: 860px) {
  .sessions-panel {
    display: none;
  }
  .tb-select {
    width: 150px;
  }
  .topk-wrap {
    display: none;
  }
}
</style>
