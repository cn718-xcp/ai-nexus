<script setup lang="ts">
/**
 * 智能体 Agent：计划-执行两阶段协作
 * - 思维链可视化：思考 → 调用工具 → 观察 → 总结 竖向时间线
 * - 工具调用高亮（工具名 / 入参 / 返回结果），降级与步数提示
 * - 附加：多模态图片理解（/agent/vision）、可用工具清单（预留接口 Mock 兜底）
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { agentApi, modelApi } from '@/api'
import { parseThinkingChain } from '@/utils/chain'
import type { ChainStep } from '@/components/StepTimeline.vue'
import StepTimeline from '@/components/StepTimeline.vue'
import ThinkingDots from '@/components/ThinkingDots.vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { genId } from '@/utils/jwt'
import type { ToolInfo } from '@/types/api'

const prompt = ref('')
const mode = ref<'plan' | 'invoke'>('plan')
const running = ref(false)

interface AgentRun {
  id: string
  prompt: string
  mode: 'plan' | 'invoke'
  steps: ChainStep[]
  answer: string
  executedSteps: number
  degraded: boolean
  error: string | null
  durationMs: number
}
const runs = ref<AgentRun[]>([])

const tools = ref<ToolInfo[]>([])
const toolsDemo = ref(false)
onMounted(async () => {
  try {
    tools.value = await modelApi.tools()
  } catch {
    toolsDemo.value = true
  }
})

const EXAMPLE_TASK = '调研知识库中关于模型路由的说明，并查询当前 rag.similarity-threshold 配置，最后给出优化建议'

async function run() {
  const task = prompt.value.trim()
  if (!task || running.value) return
  running.value = true
  const started = performance.now()
  const runItem: AgentRun = {
    id: genId(),
    prompt: task,
    mode: mode.value,
    steps: [],
    answer: '',
    executedSteps: 0,
    degraded: false,
    error: null,
    durationMs: 0,
  }
  runs.value.unshift(runItem)

  try {
    if (mode.value === 'plan') {
      const res = await agentApi.planExecute({ prompt: task })
      runItem.answer = res.answer
      runItem.executedSteps = res.executedSteps
      runItem.degraded = res.degraded
      runItem.steps = parseThinkingChain(res.thinkingChain || '')
      if (!res.thinkingChain) {
        runItem.steps = [{ kind: 'finish', title: `任务完成，共执行 ${res.executedSteps} 步` }]
      }
    } else {
      const answer = await agentApi.invoke({ prompt: task })
      runItem.answer = answer
      runItem.executedSteps = 1
      runItem.steps = [{ kind: 'thought', title: '直接调用（单步模式）' }, { kind: 'finish', title: '任务完成' }]
    }
  } catch (e) {
    const err = e as Error & { code?: string }
    runItem.error = err.message || '任务执行失败'
    if (err.message?.includes('timeout')) {
      runItem.error = '任务执行超时（180s），请尝试简化任务描述或拆分为多个步骤'
    }
  } finally {
    runItem.durationMs = Math.round(performance.now() - started)
    running.value = false
  }
}

/* ===== 图片理解（多模态）===== */
const visionFile = ref<File | null>(null)
const visionQuestion = ref('')
const visionRunning = ref(false)
const visionResult = ref('')

function onVisionUpload(options: UploadRequestOptions) {
  visionFile.value = options.file
  return options.file
}

async function runVision() {
  if (!visionFile.value || visionRunning.value) return
  visionRunning.value = true
  visionResult.value = ''
  try {
    const fd = new FormData()
    fd.append('image', visionFile.value)
    if (visionQuestion.value.trim()) fd.append('question', visionQuestion.value.trim())
    visionResult.value = await agentApi.vision(fd)
  } catch {
    /* 统一错误提示 */
  } finally {
    visionRunning.value = false
  }
}
</script>

<template>
  <div class="an-page agent-page">
    <PageHeader title="智能体" subtitle="提交复杂多步任务，可视化规划-执行思维链与工具调用" />

    <div class="agent-layout">
      <!-- 左：任务执行区 -->
      <div class="agent-main">
        <div class="glass task-card stagger-item">
          <div class="task-mode">
            <el-radio-group v-model="mode" size="small">
              <el-radio-button value="plan">计划-执行（多步协作）</el-radio-button>
              <el-radio-button value="invoke">直接调用（单步）</el-radio-button>
            </el-radio-group>
            <el-tooltip content="填入示例任务" placement="top">
              <el-button size="small" text type="primary" @click="prompt = EXAMPLE_TASK">示例任务</el-button>
            </el-tooltip>
          </div>
          <el-input
            v-model="prompt"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 6 }"
            placeholder="描述需要完成的任务，例如：分析知识库中关于熔断策略的说明，并结合当前配置给出调优建议"
            :disabled="running"
            @keydown.enter.ctrl="run"
          />
          <div class="task-actions">
            <span class="an-subtle">Ctrl+Enter 快速提交 · 最长执行 180s</span>
            <el-button
              type="primary"
              :loading="running"
              :class="{ 'an-loading-sweep': running }"
              @click="run"
            >
              {{ running ? '执行中…' : '提交任务' }}
            </el-button>
          </div>
        </div>

        <!-- 执行结果（时间线） -->
        <div v-for="run in runs" :key="run.id" class="glass run-card stagger-item">
          <div class="run-head">
            <span class="run-prompt an-ellipsis" :title="run.prompt">任务：{{ run.prompt }}</span>
            <span class="run-meta">
              <el-tag size="small" effect="plain" round>{{ run.mode === 'plan' ? '计划-执行' : '直接调用' }}</el-tag>
              <el-tag v-if="run.durationMs" size="small" effect="plain" round type="info">
                {{ (run.durationMs / 1000).toFixed(1) }}s
              </el-tag>
            </span>
          </div>

          <el-alert
            v-if="run.degraded"
            type="warning"
            show-icon
            :closable="false"
            class="run-alert"
            title="工具暂不可用，已自动降级为纯问答模式"
          />
          <el-alert v-if="run.error" type="error" show-icon :closable="false" class="run-alert" :title="run.error" />

          <div v-if="running && !run.answer" class="run-loading">
            <ThinkingDots size="lg" text="正在规划任务、调用工具并观察结果…" />
          </div>

          <template v-else>
            <StepTimeline v-if="run.steps.length" :steps="run.steps" :running="false" class="run-chain" />
            <div v-if="run.answer" class="run-answer">
              <div class="run-answer-head">
                <span class="ai-badge">最终结论</span>
                <span v-if="run.executedSteps" class="an-subtle">共执行 {{ run.executedSteps }} 步</span>
              </div>
              <MarkdownRenderer :content="run.answer" />
            </div>
          </template>
        </div>

        <div v-if="!runs.length && !running" class="glass run-card">
          <EmptyState
            text="尚未执行任务"
            sub="提交任务后，这里将展示完整的思维链时间线：思考 → 调用工具 → 观察 → 总结"
          />
        </div>
      </div>

      <!-- 右：工具清单 + 图片理解 -->
      <div class="agent-side">
        <div class="glass tools-card stagger-item" style="animation-delay: 60ms">
          <div class="side-card-head">
            <span class="side-card-title">可用工具</span>
            <el-tag v-if="toolsDemo" size="small" type="warning" effect="light" round>演示数据</el-tag>
          </div>
          <div class="tool-list">
            <div v-for="(t, i) in tools" :key="t.name" class="tool-item fade-in-item" :style="{ animationDelay: i * 50 + 'ms' }">
              <code class="tool-name">{{ t.name }}</code>
              <div class="tool-desc">{{ t.description }}</div>
              <code class="tool-params">{{ t.params }}</code>
            </div>
            <EmptyState v-if="!tools.length" text="暂无工具数据" />
          </div>
        </div>

        <div class="glass vision-card stagger-item" style="animation-delay: 100ms">
          <div class="side-card-head">
            <span class="side-card-title">图片理解（多模态）</span>
            <el-tag size="small" effect="plain" round>llava</el-tag>
          </div>
          <el-upload
            drag
            accept="image/*"
            :auto-upload="false"
            :show-file-list="false"
            :http-request="onVisionUpload"
            class="vision-upload"
          >
            <div v-if="visionFile" class="vision-file">
              <el-icon :size="20" color="var(--an-primary)"><Picture /></el-icon>
              <span class="an-ellipsis">{{ visionFile.name }}</span>
            </div>
            <div v-else class="vision-empty">
              <el-icon :size="22" color="var(--an-text-3)"><UploadFilled /></el-icon>
              <span>点击或拖拽图片到此处</span>
            </div>
          </el-upload>
          <el-input v-model="visionQuestion" placeholder="附加问题（可选，默认描述图片内容）" size="default" clearable />
          <el-button
            type="primary"
            :disabled="!visionFile"
            :loading="visionRunning"
            style="width: 100%; margin-top: 10px"
            @click="runVision"
          >
            {{ visionRunning ? '理解中…' : '开始理解' }}
          </el-button>
          <div v-if="visionResult" class="vision-result">
            <MarkdownRenderer :content="visionResult" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  align-items: start;
}
.agent-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.agent-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.task-mode {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.task-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.run-card {
  padding: 16px 20px;
}
.run-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.run-prompt {
  font-weight: 600;
  color: var(--an-text);
  font-size: 14px;
}
.run-meta {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.run-alert {
  margin-bottom: 12px;
}
.run-loading {
  display: grid;
  place-items: center;
  padding: 26px 0;
}
.run-chain {
  margin-bottom: 14px;
}
.run-answer {
  border-top: 1px dashed var(--an-border);
  padding-top: 12px;
}
.run-answer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.ai-badge {
  background: var(--an-primary-gradient);
  color: #fff;
  font-size: 10.5px;
  font-weight: 800;
  border-radius: 6px;
  padding: 3px 8px;
  letter-spacing: 0.05em;
}

.side-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.side-card-title {
  font-weight: 600;
  color: var(--an-text);
  font-size: 14.5px;
}
.tools-card {
  padding: 16px 18px;
}
.tool-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 320px;
  overflow-y: auto;
}
.tool-item {
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(var(--an-primary-rgb), 0.045);
  border: 1px solid var(--an-border);
}
.tool-name {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--an-warning);
}
.tool-desc {
  font-size: 12px;
  color: var(--an-text-2);
  margin: 4px 0;
}
.tool-params {
  font-size: 11px;
  color: var(--an-text-3);
  display: block;
  word-break: break-all;
}

.vision-card {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.vision-upload :deep(.el-upload-dragger) {
  padding: 14px;
  border-radius: 10px;
  background: rgba(var(--an-primary-rgb), 0.03);
  border: 1.5px dashed var(--an-border-strong);
  transition: all var(--an-dur) var(--an-ease);
}
.vision-upload :deep(.el-upload-dragger:hover) {
  border-color: var(--an-primary);
  background: rgba(var(--an-primary-rgb), 0.06);
}
.vision-empty,
.vision-file {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  font-size: 12.5px;
  color: var(--an-text-2);
}
.vision-file span {
  max-width: 200px;
}
.vision-result {
  border-top: 1px dashed var(--an-border);
  padding-top: 10px;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .agent-layout {
    grid-template-columns: 1fr;
  }
}
</style>
