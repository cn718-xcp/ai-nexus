/**
 * 后端 API 类型定义 —— 与 ai-nexus 后端契约一一对应（无需字段映射即可联调）
 * 统一响应包装：{ code: "A0000" | 业务错误码, message, data, timestamp }
 * 注意：后端所有业务错误均返回 HTTP 200，前端只以 code === 'A0000' 判定成功
 */

/** 统一响应包装 */
export interface ApiResponse<T = unknown> {
  code: string
  message: string
  data: T
  timestamp: number
}

/* ================= 认证 /auth ================= */
export interface LoginRequest {
  username: string
  password: string
}
export interface RegisterRequest {
  username: string
  password: string
}
export interface RefreshRequest {
  refreshToken: string
}
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

/* ================= 对话 /chat ================= */
export interface ChatRequest {
  sessionId?: string
  message: string
}
export interface ChatAnswerResponse {
  sessionId: string
  answer: string
}
export type ChatRole = 'user' | 'assistant' | 'system'
export interface ChatMessageRecord {
  id: number
  sessionId: string
  role: ChatRole
  content: string
  modelName: string | null
  userId: number | null
  tenantId: number | null
  createdAt: string
}
export interface ExtractedInfo {
  title: string | null
  person: string | null
  date: string | null
  summary: string | null
}

/* ================= RAG /rag ================= */
export type ModelScene = 'CHAT' | 'RAG' | 'AGENT' | 'MULTIMODAL'
export interface RagChatRequest {
  sessionId?: string
  question: string
  knowledgeBaseName?: string
  topK?: number
  scene?: ModelScene
}
/** 引用片段 */
export interface ChunkReference {
  content: string
  documentName: string
  documentId: string
  chunkIndex: number | null
  score: number | null
  startOffset: number | null
  endOffset: number | null
  section: string | null
}
export interface RagAnswerResponse {
  sessionId: string
  answer: string
  referencedChunks: string[]
  references: ChunkReference[]
  ungroundedSentences: string[]
  fallback: boolean
}

/* ================= 智能体 /agent ================= */
export interface AgentChatRequest {
  sessionId?: string
  prompt: string
}
export interface AgentPlanResult {
  answer: string
  executedSteps: number
  degraded: boolean
  thinkingChain?: string
}

/* ================= 知识库 /kb ================= */
export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  collectionName: string
  status: number
  userId: number | null
  tenantId: number | null
  createdAt: string
  updatedAt: string | null
}
export interface KnowledgeBaseCreateRequest {
  name: string
  description?: string
}
/** 文档状态：0-处理中 1-已入库 2-失败 */
export type DocStatus = 0 | 1 | 2
export interface KnowledgeDocument {
  id: number
  kbId: number
  fileName: string
  docUuid: string
  charCount: number
  chunkCount: number
  status: DocStatus
  errorMessage: string | null
  createdAt: string
}
/** 备份载荷（GET /kb/{id}/backup 返回的 JSON 字符串结构） */
export interface BackupPayload {
  kb: KnowledgeBase
  documents: {
    fileName: string
    docUuid: string
    chunks: { idx: number; content: string }[]
  }[]
}

/* ================= 系统配置 /config ================= */
export type ConfigScope = 'global' | 'gray'
export interface SysConfig {
  id: number
  configKey: string
  configValue: string
  scope: ConfigScope
  tenantId: number | null
  grayTenants: string | null
  updatedBy: string | null
  updatedAt: string | null
}
export interface ConfigUpdateRequest {
  key: string
  value: string
  tenantId?: number | null
  scope?: ConfigScope
  grayTenants?: string
}

/* ================= 统计 /stats ================= */
export interface SceneCallStat {
  scene: string
  date: string
  calls: number
  avgCostMs: number
}
export interface ModelCallStat {
  model: string
  calls: number
  promptTokens: number
  completionTokens: number
  failures: number
}
export interface StatsOverview {
  bySceneAndDate: SceneCallStat[]
  byModel: ModelCallStat[]
}

/* ================= 租户 /tenants ================= */
export interface SysTenant {
  id: number
  name: string
  code: string
  status: number
  kbQuota: number | null
  concurrentQuota: number | null
  dailyModelQuota: number | null
  modelRoutes: string | null
  createdAt: string
}
export interface TenantCreateRequest {
  name: string
  code: string
  kbQuota?: number
  concurrentQuota?: number
  dailyModelQuota?: number
}
export interface TenantQuotaRequest {
  kbQuota?: number
  concurrentQuota?: number
  dailyModelQuota?: number
}

/* ================= 评估 /eval ================= */
export interface BadCase {
  id: number
  question: string
  answer: string
  consistency: number
  relevance: number
  completeness: number
  createdAt: string
}

/* ================= 健康与监控 /health、/actuator ================= */
export interface ActuatorHealth {
  status: string
  components?: Record<string, { status: string; details?: Record<string, unknown> }>
}
export interface LoggerEntry {
  name: string
  configuredLevel: string | null
  effectiveLevel: string
}
export interface LoggersResponse {
  levels: string[]
  loggers: Record<string, { configuredLevel: string | null; effectiveLevel: string }>
  groups?: Record<string, { members?: string[] }>
}
export interface MetricDetail {
  name: string
  description: string | null
  baseUnit: string | null
  measurements: { statistic: string; value: number }[]
  availableTags: { tag: string; values: string[] }[]
}

/* ================= 前端预留模块（后端接口规划中，当前由 Mock 驱动）================= */
export interface ModelNode {
  id: string
  name: string
  url: string
  region: string
  status: 'online' | 'offline' | 'busy'
  load: number
  models: string[]
  maxConcurrent: number
  activeConcurrent: number
}
export interface ModelRouteItem {
  scene: ModelScene
  primary: string
  backup: string
  grayRatio: number
  description: string
}
export interface ToolInfo {
  name: string
  description: string
  params: string
}
export interface AuditLog {
  id: number
  traceId: string
  module: string
  action: string
  operator: string
  ip: string
  result: '成功' | '失败'
  detail: string
  createdAt: string
}
export interface TraceSpan {
  spanId: string
  parentSpanId: string | null
  service: string
  name: string
  startMs: number
  durationMs: number
  status: 'ok' | 'error'
  tags: Record<string, string>
}

/* ================= 前端本地模型 ================= */
export interface ChatUIMessage {
  id: string
  role: ChatRole
  content: string
  time: string
  references?: ChunkReference[]
  modelName?: string | null
  kbName?: string | null
  streaming?: boolean
  error?: boolean
}
export interface ChatUISession {
  id: string
  title: string
  kbName: string | null
  topK: number
  mode: 'stream' | 'sync'
  createdAt: number
  updatedAt: number
  messages: ChatUIMessage[]
}
