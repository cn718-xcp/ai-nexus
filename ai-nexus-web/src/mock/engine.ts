/**
 * Mock 引擎：拦截 Axios 请求并返回与后端契约一致的 ApiResponse 结构
 * 路由表按 METHOD + 路径正则匹配，CRUD 变更写入 localStorage 持久化
 */
import type { ApiResponse } from '@/types/api'
import { chunkRef, defaultTools, isoNow, loadDB, saveDB, type MockDB } from './data'

export class MockBizError extends Error {
  code: string
  constructor(code: string, message: string) {
    super(message)
    this.code = code
  }
}

const ok = <T>(data: T): ApiResponse<T> => ({
  code: 'A0000',
  message: '成功',
  data,
  timestamp: Date.now(),
})

const delay = (ms = 260) => new Promise<void>((r) => setTimeout(r, ms + Math.random() * 220))

/* ================= 会话级内存态 ================= */
const memory = {
  tokens: {
    accessToken: '',
    refreshToken: '',
    tokenType: 'Bearer',
    expiresIn: 1800,
  },
  metricsCounter: 0,
}

function fakeJwt(sub: string, type: 'access' | 'refresh'): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(
    JSON.stringify({
      sub,
      uid: 1,
      tid: 1,
      token_type: type,
      iat: Math.floor(Date.now() / 1000),
      exp: Math.floor(Date.now() / 1000) + (type === 'access' ? 1800 : 7 * 86400),
    }),
  )
  return `${header}.${payload}.mock-signature`
}

/* ================= 演示内容 ================= */
const DEMO_ANSWER = `根据知识库检索结果，为您总结如下：

**AI-Nexus 平台** 是基于 Spring Boot 4.0 + Spring AI 2.0 构建的企业级 AI 能力平台，核心能力包括：

1. **RAG 检索增强问答**：知识库向量化（Milvus，768 维 COSINE），相似度阈值可在线调整；
2. **智能体编排**：计划-执行两阶段协作，支持工具调用与思维链输出；
3. **模型路由**：按 CHAT / RAG / AGENT / MULTIMODAL 场景路由到不同模型节点，支持主备与灰度；
4. **多租户隔离**：知识库配额、每日调用限额、并发数三类配额独立控制。

> 提示：如需调整检索行为，可在「系统配置」中修改 \`rag.similarity-threshold\` 与 \`rag.mode\`。`

function mockThinkingChain(): string {
  return [
    'Thought: 用户询问平台核心能力，需要先检索知识库中的产品介绍片段，再结合系统配置信息作答。',
    'Action: knowledge_retrieval',
    'Action Input: {"kb": "产品手册库", "question": "平台核心能力有哪些"}',
    'Observation: 命中 4 个片段：RAG 检索增强、智能体编排、模型路由、多租户隔离，平均相似度 0.86。',
    'Thought: 检索结果完整覆盖问题，还需要确认当前配置的相似度阈值参数。',
    'Action: sql_query',
    'Action Input: {"sql": "SELECT config_value FROM sys_config WHERE config_key = \'rag.similarity-threshold\'"}',
    'Observation: 查询结果为 0.62。',
    'Thought: 信息已充分，可以整理为结构化答案回复用户。',
    'Finish: 已完成 3 步工具调用，输出最终答案。',
  ].join('\n')
}

/* ================= 统计数据生成 ================= */
function genStats(db: MockDB) {
  const scenes = ['CHAT', 'RAG', 'AGENT', 'MULTIMODAL']
  const bySceneAndDate: { scene: string; date: string; calls: number; avgCostMs: number }[] = []
  const base = [120, 210, 60, 25]
  for (let d = 6; d >= 0; d--) {
    const date = new Date(Date.now() - d * 86400_000).toISOString().slice(0, 10)
    scenes.forEach((s, i) => {
      const calls = Math.round(base[i] * (0.75 + Math.random() * 0.5) + (d === 0 ? 40 : 0))
      bySceneAndDate.push({ scene: s, date, calls, avgCostMs: 420 + Math.round(Math.random() * 900) })
    })
  }
  const byModel = [
    { model: 'Qwen2_5_7bChatModel', calls: 1024, promptTokens: 86000, completionTokens: 132000, failures: 3 },
    { model: 'Qwen2_5_14bChatModel', calls: 312, promptTokens: 41000, completionTokens: 52000, failures: 1 },
    { model: 'LlavaChatModel', calls: 86, promptTokens: 12000, completionTokens: 9000, failures: 2 },
  ]
  void db
  return { bySceneAndDate, byModel }
}

/* ================= 指标数据 ================= */
function metricSeries(name: string) {
  const n = 24
  const vals: number[] = []
  let v = 40 + Math.random() * 20
  for (let i = 0; i < n; i++) {
    v = Math.max(2, Math.min(96, v + (Math.random() - 0.5) * 14))
    vals.push(Number(v.toFixed(2)))
  }
  const map: Record<string, { unit: string; desc: string }> = {
    'jvm.memory.used': { unit: 'bytes', desc: 'JVM 内存使用量' },
    'process.cpu.usage': { unit: 'percentage', desc: '进程 CPU 使用率' },
    'system.cpu.usage': { unit: 'percentage', desc: '系统 CPU 使用率' },
    'http.server.requests': { unit: 'seconds', desc: 'HTTP 请求耗时' },
    'jvm.gc.pause': { unit: 'seconds', desc: 'GC 暂停时长' },
    'hikaricp.connections.active': { unit: 'connections', desc: '活跃数据库连接' },
    'lettuce.command.completion': { unit: 'commands', desc: 'Redis 命令完成数' },
  }
  const m = map[name] || { unit: '', desc: name }
  return {
    name,
    description: m.desc,
    baseUnit: m.unit,
    measurements: [
      { statistic: 'COUNT', value: vals.length * 137 },
      { statistic: 'TOTAL', value: Number(vals.reduce((a, b) => a + b, 0).toFixed(2)) },
      { statistic: 'MAX', value: Math.max(...vals) },
      { statistic: 'VALUE', value: vals[vals.length - 1] },
    ],
    availableTags: [{ tag: 'uri', values: ['/api/v1/rag/ask', '/api/v1/chat', '/api/v1/agent/invoke'] }],
    _series: vals,
  }
}

/* ================= 全链路追踪 ================= */
function traceDetail(traceId: string) {
  const t0 = Date.now() - 3600_000
  const spans = [
    { spanId: 's1', parentSpanId: null, service: 'ai-nexus-gateway', name: 'HTTP POST /api/v1/rag/stream', startMs: 0, durationMs: 38, status: 'ok' as const, tags: { method: 'POST', status: '200' } },
    { spanId: 's2', parentSpanId: 's1', service: 'ai-nexus-auth', name: 'JWT 认证解析', startMs: 2, durationMs: 4, status: 'ok' as const, tags: { uid: '1', tid: '1' } },
    { spanId: 's3', parentSpanId: 's1', service: 'ai-nexus-rag', name: 'RagChatService#streamAsk', startMs: 8, durationMs: 2240, status: 'ok' as const, tags: { kb: '产品手册库', topK: '5' } },
    { spanId: 's4', parentSpanId: 's3', service: 'ai-nexus-vector', name: 'Milvus 向量检索', startMs: 14, durationMs: 168, status: 'ok' as const, tags: { collection: 'ai_nexus_kb', hits: '5' } },
    { spanId: 's5', parentSpanId: 's3', service: 'ai-nexus-model', name: 'Ollama qwen2.5:7b 流式生成', startMs: 190, durationMs: 2020, status: 'ok' as const, tags: { model: 'qwen2.5:7b', tokens: '486' } },
    { spanId: 's6', parentSpanId: 's3', service: 'ai-nexus-cache', name: 'Redis 检索缓存读写', startMs: 12, durationMs: 9, status: 'ok' as const, tags: { cmd: 'GET/SET' } },
  ]
  return { traceId, totalMs: 2280, spans, startAt: new Date(t0).toISOString() }
}

/* ================= 处理器路由表 ================= */
type Handler = (ctx: { body: any; query: URLSearchParams }, ...g: string[]) => unknown

interface Route {
  m: string
  re: RegExp
  h: Handler
}

const routes: Route[] = [
  /* ---- 健康探活 ---- */
  { m: 'GET', re: /^\/health$/, h: () => 'UP' },
  {
    m: 'GET',
    re: /^\/actuator\/health$/,
    h: () => ({
      status: 'UP',
      components: {
        db: { status: 'UP', details: { database: 'MySQL', validationQuery: 'SELECT 1' } },
        redis: { status: 'UP', details: { version: '7.2.4' } },
        milvus: { status: 'UP', details: { collection: 'ai_nexus_kb', dim: 768 } },
        ollama: { status: 'UP', details: { models: ['qwen2.5:7b', 'nomic-embed-text'] } },
        diskSpace: { status: 'UP', details: { total: 512110190592, free: 180366946304 } },
        ping: { status: 'UP' },
      },
    }),
  },
  {
    m: 'GET',
    re: /^\/actuator\/loggers$/,
    h: () => {
      const db = loadDB()
      const loggers: Record<string, { configuredLevel: string | null; effectiveLevel: string }> = {}
      Object.entries(db.loggers).forEach(([k, v]) => {
        loggers[k] = { configuredLevel: v, effectiveLevel: v }
      })
      return {
        levels: ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL', 'OFF'],
        loggers,
        groups: { web: { members: ['org.springframework.web', 'org.springframework.http'] } },
      }
    },
  },
  {
    m: 'POST',
    re: /^\/actuator\/loggers\/(.+)$/,
    h: (c, name) => {
      const db = loadDB()
      db.loggers[name] = (c.body?.configuredLevel as string) || 'INFO'
      saveDB(db)
      return null
    },
  },
  {
    m: 'GET',
    re: /^\/actuator\/metrics$/,
    h: () => ({
      names: [
        'jvm.memory.used',
        'process.cpu.usage',
        'system.cpu.usage',
        'http.server.requests',
        'jvm.gc.pause',
        'hikaricp.connections.active',
        'lettuce.command.completion',
      ],
    }),
  },
  { m: 'GET', re: /^\/actuator\/metrics\/(.+)$/, h: (_c, name) => metricSeries(name) },
  {
    m: 'GET',
    re: /^\/actuator\/prometheus$/,
    h: () =>
      [
        '# HELP jvm_memory_used_bytes JVM 内存使用量',
        '# TYPE jvm_memory_used_bytes gauge',
        'jvm_memory_used_bytes{area="heap"} 4.82e8',
        '# HELP process_cpu_usage 进程 CPU 使用率',
        '# TYPE process_cpu_usage gauge',
        'process_cpu_usage 0.23',
        '# HELP http_server_requests_seconds HTTP 请求耗时',
        '# TYPE http_server_requests_seconds summary',
        'http_server_requests_seconds_count{uri="/api/v1/rag/ask"} 1024',
        'http_server_requests_seconds_sum{uri="/api/v1/rag/ask"} 872.4',
      ].join('\n'),
  },

  /* ---- 认证 ---- */
  {
    m: 'POST',
    re: /^\/api\/v1\/auth\/login$/,
    h: (c) => {
      const { username, password } = c.body || {}
      if (!username || !password) throw new MockBizError('A0002', '用户名或密码不能为空')
      if (String(password).length < 6) throw new MockBizError('A0004', '用户名或密码错误')
      const db = loadDB()
      db.username = username
      saveDB(db)
      memory.tokens.accessToken = fakeJwt(username, 'access')
      memory.tokens.refreshToken = fakeJwt(username, 'refresh')
      return { ...memory.tokens }
    },
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/auth\/register$/,
    h: (c) => {
      const { username, password } = c.body || {}
      if (!username || String(username).length < 4) throw new MockBizError('A0002', 'username: 用户名长度 4-32')
      if (!password || String(password).length < 8) throw new MockBizError('A0002', 'password: 密码长度 8-64')
      return 101
    },
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/auth\/refresh$/,
    h: () => {
      memory.tokens.accessToken = fakeJwt('admin', 'access')
      memory.tokens.refreshToken = fakeJwt('admin', 'refresh')
      return { ...memory.tokens }
    },
  },

  /* ---- 知识库 ---- */
  {
    m: 'GET',
    re: /^\/api\/v1\/kb$/,
    h: () => loadDB().kbs.map((k) => ({ ...k, docs: undefined, chunks: undefined })),
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/kb$/,
    h: (c) => {
      const db = loadDB()
      const { name, description } = c.body || {}
      if (!name) throw new MockBizError('A0002', 'name: 知识库名称不能为空')
      if (db.kbs.some((k) => k.name === name)) throw new MockBizError('D0002', '请勿重复提交请求')
      if (db.kbs.length >= 20) throw new MockBizError('F0003', '知识库配额超限')
      const kb = {
        id: ++db.kbSeq,
        name,
        description: description || null,
        collectionName: 'ai_nexus_kb_' + name,
        status: 1,
        userId: 1,
        tenantId: 1,
        createdAt: isoNow(),
        updatedAt: null,
        docs: [],
        chunks: {},
      }
      db.kbs.unshift(kb)
      saveDB(db)
      return { ...kb, docs: undefined, chunks: undefined }
    },
  },
  {
    m: 'DELETE',
    re: /^\/api\/v1\/kb\/(\d+)$/,
    h: (_c, id) => {
      const db = loadDB()
      const i = db.kbs.findIndex((k) => k.id === Number(id))
      if (i === -1) throw new MockBizError('C0001', '知识库不存在')
      db.kbs.splice(i, 1)
      saveDB(db)
      return null
    },
  },
  {
    m: 'GET',
    re: /^\/api\/v1\/kb\/(\d+)\/documents$/,
    h: (_c, id) => {
      const kb = loadDB().kbs.find((k) => k.id === Number(id))
      if (!kb) throw new MockBizError('C0001', '知识库不存在')
      return kb.docs
    },
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/kb\/(\d+)\/documents$/,
    h: (c, id) => {
      const db = loadDB()
      const kb = db.kbs.find((k) => k.id === Number(id))
      if (!kb) throw new MockBizError('C0001', '知识库不存在')
      let fileName = '未命名文件.txt'
      if (c.body instanceof FormData) {
        const f = c.body.get('file')
        if (f && typeof f === 'object' && 'name' in f) fileName = (f as File).name
      }
      const ext = fileName.split('.').pop()?.toLowerCase() || ''
      if (!['txt', 'md', 'json', 'csv'].includes(ext))
        throw new MockBizError('A0002', '仅支持 txt/md/json/csv 格式')
      if (kb.docs.some((d) => d.fileName === fileName))
        throw new MockBizError('D0002', '请勿重复提交请求')
      const cnt = 3 + Math.floor(Math.random() * 5)
      const doc = {
        id: Date.now() % 100000,
        kbId: kb.id,
        fileName,
        docUuid: 'doc-' + (kb.docs.length + 1).toString().padStart(4, '0') + '-' + Math.random().toString(36).slice(2, 8),
        charCount: cnt * 210,
        chunkCount: cnt,
        status: 0 as const,
        errorMessage: null,
        createdAt: isoNow(),
      }
      kb.docs.unshift(doc)
      kb.chunks[doc.docUuid] = Array.from({ length: cnt }, (_, i) => ({
        idx: i,
        content: `「${fileName}」第 ${i + 1} 片段：AI-Nexus 平台文档解析完成后，按字符偏移切片并向量化写入 Milvus，供 RAG 检索增强问答使用。`,
      }))
      saveDB(db)
      // 模拟异步解析入库：3 秒后状态置为已入库
      setTimeout(() => {
        const d2 = loadDB()
        const k = d2.kbs.find((x) => x.id === kb.id)
        const d = k?.docs.find((x) => x.id === doc.id)
        if (d) {
          d.status = 1
          saveDB(d2)
        }
      }, 3000)
      return doc
    },
  },
  {
    m: 'GET',
    re: /^\/api\/v1\/kb\/(\d+)\/backup$/,
    h: (_c, id) => {
      const kb = loadDB().kbs.find((k) => k.id === Number(id))
      if (!kb) throw new MockBizError('C0001', '知识库不存在')
      return JSON.stringify({
        kb: { ...kb, docs: undefined, chunks: undefined },
        documents: kb.docs.map((d) => ({
          fileName: d.fileName,
          docUuid: d.docUuid,
          chunks: kb.chunks[d.docUuid] || [],
        })),
      })
    },
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/kb\/import$/,
    h: (c) => {
      if (!(c.body instanceof FormData)) throw new MockBizError('A0002', '备份文件格式不合法')
      const f = c.body.get('file')
      const db = loadDB()
      const kb = {
        id: ++db.kbSeq,
        name: (f && typeof f === 'object' && 'name' in f ? (f as File).name.replace(/\.json$/, '') : '导入知识库') +
          '-' + (db.kbSeq + 1),
        description: '由备份文件导入',
        collectionName: 'ai_nexus_kb_import_' + db.kbSeq,
        status: 1,
        userId: 1,
        tenantId: 1,
        createdAt: isoNow(),
        updatedAt: null,
        docs: [],
        chunks: {},
      }
      db.kbs.unshift(kb)
      saveDB(db)
      return { ...kb, docs: undefined, chunks: undefined }
    },
  },

  /* ---- RAG ---- */
  {
    m: 'POST',
    re: /^\/api\/v1\/rag\/ask$/,
    h: (c) => {
      const db = loadDB()
      const kbName = c.body?.knowledgeBaseName
      const kb = kbName ? db.kbs.find((k) => k.name === kbName) : db.kbs[0]
      if (kbName && !kb) throw new MockBizError('C0001', '知识库不存在')
      const doc = kb?.docs[0]
      const refs = [
        chunkRef('AI-Nexus 平台采用 Spring Boot 4.0 与 Spring AI 2.0 构建，提供 RAG 检索增强问答、多智能体编排等核心能力。', doc?.fileName || '平台快速入门.md', 0.89, 0),
        chunkRef('检索增强生成（RAG）通过向量相似度匹配知识片段，相似度阈值建议配置在 0.6 ~ 0.75 之间。', doc?.fileName || '平台快速入门.md', 0.84, 1),
        chunkRef('平台内置模型节点池与按场景路由，支持主备切换与灰度发布。', doc?.fileName || '管理员操作手册.txt', 0.78, 2),
      ]
      return {
        sessionId: c.body?.sessionId || 'sess-' + Date.now().toString(36),
        answer: DEMO_ANSWER,
        referencedChunks: refs.map((r) => r.content),
        references: refs,
        ungroundedSentences: [],
        fallback: false,
      }
    },
  },

  /* ---- 对话 ---- */
  {
    m: 'POST',
    re: /^\/api\/v1\/chat$/,
    h: (c) => ({
      sessionId: c.body?.sessionId || 'sess-' + Date.now().toString(36),
      answer: '这是一条模拟多轮对话回复：' + String(c.body?.message || '').slice(0, 40),
    }),
  },
  {
    m: 'GET',
    re: /^\/api\/v1\/chat\/([^/]+)\/replay$/,
    h: () => {
      // Mock 环境返回一次示例历史
      return [
        { id: 1, sessionId: 'demo', role: 'user', content: '你好，介绍一下 AI-Nexus 平台', modelName: null, userId: 1, tenantId: 1, createdAt: isoNow() },
        { id: 2, sessionId: 'demo', role: 'assistant', content: DEMO_ANSWER, modelName: 'Qwen2_5_7bChatModel', userId: 1, tenantId: 1, createdAt: isoNow() },
      ]
    },
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/chat\/extract$/,
    h: (c) => {
      const text = String(c.query.get('text') || '')
      return { title: text.slice(0, 12) || null, person: '张工', date: '2026-09-19', summary: '（演示）结构化抽取结果：' + text.slice(0, 50) }
    },
  },
  { m: 'POST', re: /^\/api\/v1\/chat\/keywords$/, h: (c) => ['RAG 检索增强', '智能体编排', '多租户隔离', '模型路由' + (c.query.get('topic') ? '' : ''), '弹性容错'].slice(0, 5) },

  /* ---- 智能体 ---- */
  { m: 'POST', re: /^\/api\/v1\/agent\/invoke$/, h: () => '（演示）Agent 直接调用结果：任务已完成。' },
  {
    m: 'POST',
    re: /^\/api\/v1\/agent\/plan-execute$/,
    h: () => ({
      answer:
        `已完成对任务的规划与执行，共调用 3 次工具：\n\n1. 通过 **knowledge_retrieval** 检索了知识库中的相关片段（平均相似度 0.86）；\n2. 通过 **sql_query** 读取了 \`rag.similarity-threshold\` 当前配置值 0.62；\n3. 综合以上观察输出结论：当前检索策略配置合理，命中质量良好，建议保持现有阈值并关注低频场景的召回表现。`,
      executedSteps: 3,
      degraded: false,
      thinkingChain: mockThinkingChain(),
    }),
  },

  /* ---- 统计 ---- */
  { m: 'GET', re: /^\/api\/v1\/stats\/overview$/, h: () => genStats(loadDB()) },

  /* ---- 租户 ---- */
  {
    m: 'GET',
    re: /^\/api\/v1\/tenants$/,
    h: () => loadDB().tenants,
  },
  {
    m: 'GET',
    re: /^\/api\/v1\/tenants\/(\d+)$/,
    h: (_c, id) => {
      const t = loadDB().tenants.find((x) => x.id === Number(id))
      if (!t) throw new MockBizError('F0001', '租户不存在')
      return t
    },
  },
  {
    m: 'POST',
    re: /^\/api\/v1\/tenants$/,
    h: (c) => {
      const db = loadDB()
      const { name, code } = c.body || {}
      if (!name || !code) throw new MockBizError('A0002', 'name/code 不能为空')
      if (db.tenants.some((t) => t.code === code)) throw new MockBizError('A0004', '租户编码已存在')
      const t = {
        id: ++db.tenantSeq,
        name,
        code,
        status: 1,
        kbQuota: c.body?.kbQuota ?? null,
        concurrentQuota: c.body?.concurrentQuota ?? null,
        dailyModelQuota: c.body?.dailyModelQuota ?? null,
        modelRoutes: null,
        createdAt: isoNow(),
      }
      db.tenants.unshift(t)
      saveDB(db)
      return t
    },
  },
  {
    m: 'PUT',
    re: /^\/api\/v1\/tenants\/(\d+)\/status\/(\d+)$/,
    h: (_c, id, status) => {
      const db = loadDB()
      const t = db.tenants.find((x) => x.id === Number(id))
      if (!t) throw new MockBizError('F0001', '租户不存在')
      t.status = Number(status)
      saveDB(db)
      return null
    },
  },
  {
    m: 'PUT',
    re: /^\/api\/v1\/tenants\/(\d+)\/quota$/,
    h: (c, id) => {
      const db = loadDB()
      const t = db.tenants.find((x) => x.id === Number(id))
      if (!t) throw new MockBizError('F0001', '租户不存在')
      if (c.body?.kbQuota !== undefined) t.kbQuota = c.body.kbQuota
      if (c.body?.concurrentQuota !== undefined) t.concurrentQuota = c.body.concurrentQuota
      if (c.body?.dailyModelQuota !== undefined) t.dailyModelQuota = c.body.dailyModelQuota
      saveDB(db)
      return null
    },
  },
  {
    m: 'PUT',
    re: /^\/api\/v1\/tenants\/(\d+)\/routes$/,
    h: (c, id) => {
      const db = loadDB()
      const t = db.tenants.find((x) => x.id === Number(id))
      if (!t) throw new MockBizError('F0001', '租户不存在')
      t.modelRoutes = c.body?.modelRoutes ?? null
      saveDB(db)
      return null
    },
  },

  /* ---- 系统配置 ---- */
  {
    m: 'GET',
    re: /^\/api\/v1\/config\/keys$/,
    h: () => [
      'resilience.circuit-enabled',
      'resilience.circuit-window',
      'resilience.circuit-failure-rate',
      'resilience.circuit-open-seconds',
      'concurrency.model-max',
      'concurrency.queue-timeout-seconds',
      'rag.similarity-threshold',
      'rag.mode',
      'rag.rerank-enabled',
      'rag.cache-enabled',
    ],
  },
  { m: 'GET', re: /^\/api\/v1\/config\/effective$/, h: () => loadDB().configEffective },
  {
    m: 'GET',
    re: /^\/api\/v1\/config$/,
    h: () => loadDB().configHistory,
  },
  {
    m: 'PUT',
    re: /^\/api\/v1\/config$/,
    h: (c) => {
      const db = loadDB()
      const { key, value, scope = 'global', grayTenants } = c.body || {}
      if (!key || !value) throw new MockBizError('A0002', 'key/value 不能为空')
      const known = db.configEffective
      if (!(key in known)) throw new MockBizError('G0002', '未知的配置键: ' + key)
      known[key] = value
      db.configHistory.unshift({
        id: ++db.configSeq,
        configKey: key,
        configValue: value,
        scope,
        tenantId: null,
        grayTenants: scope === 'gray' ? grayTenants || null : null,
        updatedBy: 'admin',
        updatedAt: isoNow(),
      })
      saveDB(db)
      return null
    },
  },

  /* ---- 评估 ---- */
  { m: 'GET', re: /^\/api\/v1\/eval\/badcases$/, h: () => loadDB().badcases },
  {
    m: 'POST',
    re: /^\/api\/v1\/eval\/badcases\/(\d+)\/feedback$/,
    h: (c, id) => {
      const db = loadDB()
      const b = db.badcases.find((x) => x.id === Number(id))
      if (!b) throw new MockBizError('A0003', '资源不存在')
      void c
      saveDB(db)
      return null
    },
  },

  /* ---- 前端预留模块（后端规划中）：模型节点池 / 路由 / 审计 / 追踪 / 工具清单 ---- */
  { m: 'GET', re: /^\/api\/v1\/model-pool\/nodes$/, h: () => loadDB().modelNodes },
  { m: 'GET', re: /^\/api\/v1\/model-route\/config$/, h: () => loadDB().modelRoutes },
  {
    m: 'PUT',
    re: /^\/api\/v1\/model-route\/config$/,
    h: (c) => {
      const db = loadDB()
      if (Array.isArray(c.body)) db.modelRoutes = c.body
      saveDB(db)
      return null
    },
  },
  { m: 'GET', re: /^\/api\/v1\/agent\/tool-list$/, h: () => defaultTools() },
  { m: 'GET', re: /^\/api\/v1\/audit\/logs$/, h: () => loadDB().auditLogs },
  { m: 'GET', re: /^\/api\/v1\/trace\/([^/]+)$/, h: (_c, id) => traceDetail(id) },
]

/* ================= 入口 ================= */
export interface MockRequestConfig {
  method?: string
  url: string
  data?: unknown
  params?: Record<string, unknown>
}

/** 返回完整 ApiResponse；未匹配路由返回 null */
export async function mockRequest(cfg: MockRequestConfig): Promise<ApiResponse | null> {
  const method = (cfg.method || 'GET').toUpperCase()
  const [pathOnly, queryStr] = cfg.url.split('?')
  const query = new URLSearchParams(queryStr || '')
  if (cfg.params) {
    Object.entries(cfg.params).forEach(([k, v]) => {
      if (v !== undefined && v !== null) query.set(k, String(v))
    })
  }
  for (const r of routes) {
    if (r.m !== method) continue
    const m = pathOnly.match(r.re)
    if (!m) continue
    await delay()
    try {
      const data = r.h({ body: cfg.data, query }, ...m.slice(1))
      return ok(data)
    } catch (e) {
      if (e instanceof MockBizError)
        return { code: e.code, message: e.message, data: null, timestamp: Date.now() }
      return { code: 'A0001', message: '系统繁忙', data: null, timestamp: Date.now() }
    }
  }
  // 未匹配：返回资源不存在，与后端行为一致
  return { code: 'A0003', message: '资源不存在: ' + pathOnly, data: null, timestamp: Date.now() }
}

/**
 * Mock SSE 流式输出：将演示答案切成 token 增量，由调用方按节奏追加
 */
export function mockStreamTokens(): string[] {
  const text = DEMO_ANSWER
  const tokens: string[] = []
  let i = 0
  while (i < text.length) {
    const step = 1 + Math.floor(Math.random() * 4)
    tokens.push(text.slice(i, i + step))
    i += step
  }
  return tokens
}
