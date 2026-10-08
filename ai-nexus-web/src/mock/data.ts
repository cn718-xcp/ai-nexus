/**
 * Mock 演示数据 —— 无后端环境时预览全部页面效果
 * 结构与后端契约完全一致，CRUD 变更持久化到 localStorage，保证演示可交互
 */
import type {
  KnowledgeBase,
  KnowledgeDocument,
  SysTenant,
  SysConfig,
  ModelNode,
  ModelRouteItem,
  AuditLog,
  BadCase,
  ToolInfo,
  ChunkReference,
} from '@/types/api'

export const DB_KEY = 'ainexus.mock.db.v1'

/** 知识库及其文档/片段（片段仅供备份导出与分片查看演示） */
export interface MockKB extends KnowledgeBase {
  docs: KnowledgeDocument[]
  chunks: Record<string, { idx: number; content: string }[]> // docUuid → chunks
}

export interface MockDB {
  kbSeq: number
  kbs: MockKB[]
  tenants: SysTenant[]
  tenantSeq: number
  configHistory: SysConfig[]
  configEffective: Record<string, string>
  configSeq: number
  modelNodes: ModelNode[]
  modelRoutes: ModelRouteItem[]
  auditLogs: AuditLog[]
  badcases: BadCase[]
  loggers: Record<string, string>
  username: string
}

const now = () => new Date().toISOString()
const daysAgo = (n: number) => new Date(Date.now() - n * 86400_000).toISOString()

function makeChunkContent(doc: string, i: number): string {
  const samples = [
    'AI-Nexus 平台采用 Spring Boot 4.0 与 Spring AI 2.0 构建，提供 RAG 检索增强问答、多智能体编排、多租户隔离与模型路由等核心能力，支持私有化部署。',
    `检索增强生成（RAG）通过向量相似度匹配知识片段，相似度阈值建议配置在 0.6 ~ 0.75 之间，超过阈值的内容才会进入上下文，以平衡召回率与准确率。（${doc} 第${i + 1}节）`,
    '平台内置模型节点池与按场景路由：CHAT、RAG、AGENT 场景默认使用 qwen2.5:7b，MULTIMODAL 场景使用 llava:7b，支持主备切换与灰度发布。',
    '熔断与限流策略：单用户 QPS 默认 5，模型最大并发 8，排队超时 10 秒；连续失败率超过 50% 时熔断器打开 60 秒，避免雪崩。',
    '多租户配额体系：知识库数量上限、每日模型调用限额、并发数限制三类配额独立控制，超出后返回 F0003/F0004/F0005 错误码。',
    `文档解析流程：上传 txt/md/json/csv 文件后按字符偏移切片，每个 Chunk 记录 idx 与原文偏移量，向量化写入 Milvus 集合 ai_nexus_kb（维度 768，COSINE 度量）。（${doc} 第${i + 1}节）`,
  ]
  return samples[i % samples.length]
}

function makeDoc(kbId: number, id: number, name: string, chunkCount: number, created: string): {
  doc: KnowledgeDocument
  chunks: { idx: number; content: string }[]
} {
  const doc: KnowledgeDocument = {
    id,
    kbId,
    fileName: name,
    docUuid: 'doc-' + id.toString().padStart(4, '0') + '-uuid',
    charCount: chunkCount * 210,
    chunkCount,
    status: 1,
    errorMessage: null,
    createdAt: created,
  }
  const chunks = Array.from({ length: chunkCount }, (_, i) => ({
    idx: i,
    content: makeChunkContent(name, i),
  }))
  return { doc, chunks }
}

function defaultDB(): MockDB {
  const kbs: MockKB[] = []
  const defs: { name: string; desc: string; docs: [string, number][] }[] = [
    {
      name: '产品手册库',
      desc: 'AI-Nexus 平台产品使用手册、快速入门与功能说明',
      docs: [
        ['平台快速入门.md', 6],
        ['管理员操作手册.txt', 5],
        ['功能清单.json', 3],
      ],
    },
    {
      name: '技术文档库',
      desc: '架构设计、接口规范与部署运维文档',
      docs: [
        ['系统架构设计.md', 6],
        ['部署运维指南.csv', 4],
      ],
    },
    {
      name: 'FAQ 问答库',
      desc: '高频问题与标准答复，客服场景专用',
      docs: [['常见问题汇总.txt', 4]],
    },
    {
      name: '法务合规库',
      desc: '数据安全、隐私合规与开源协议说明',
      docs: [['数据合规白皮书.md', 5]],
    },
  ]
  let kbId = 1
  let docId = 1
  defs.forEach((d, i) => {
    const kb: MockKB = {
      id: kbId++,
      name: d.name,
      description: d.desc,
      collectionName: 'ai_nexus_kb_' + d.name,
      status: 1,
      userId: 1,
      tenantId: 1,
      createdAt: daysAgo(30 - i * 5),
      updatedAt: null,
      docs: [],
      chunks: {},
    }
    d.docs.forEach(([fileName, cnt]) => {
      const { doc, chunks } = makeDoc(kb.id, docId++, fileName, cnt, daysAgo(20 - docId))
      kb.docs.push(doc)
      kb.chunks[doc.docUuid] = chunks
    })
    kbs.push(kb)
  })

  const tenants: SysTenant[] = [
    {
      id: 1,
      name: '总部平台部',
      code: 'hq-platform',
      status: 1,
      kbQuota: 20,
      concurrentQuota: 50,
      dailyModelQuota: 200000,
      modelRoutes: null,
      createdAt: daysAgo(60),
    },
    {
      id: 2,
      name: '华东研发中心',
      code: 'east-rd',
      status: 1,
      kbQuota: 10,
      concurrentQuota: 20,
      dailyModelQuota: 50000,
      modelRoutes: '{"CHAT":"qwen2.5:14b"}',
      createdAt: daysAgo(45),
    },
    {
      id: 3,
      name: '华南客服部',
      code: 'south-cs',
      status: 0,
      kbQuota: 5,
      concurrentQuota: 10,
      dailyModelQuota: 10000,
      modelRoutes: null,
      createdAt: daysAgo(30),
    },
  ]

  const configEffective: Record<string, string> = {
    'resilience.circuit-enabled': 'true',
    'resilience.circuit-window': '60',
    'resilience.circuit-failure-rate': '0.5',
    'resilience.circuit-open-seconds': '60',
    'concurrency.model-max': '8',
    'concurrency.queue-timeout-seconds': '10',
    'rag.similarity-threshold': '0.62',
    'rag.mode': 'vector',
    'rag.rerank-enabled': 'false',
    'rag.cache-enabled': 'true',
  }
  const configHistory: SysConfig[] = [
    {
      id: 1,
      configKey: 'rag.similarity-threshold',
      configValue: '0.62',
      scope: 'global',
      tenantId: null,
      grayTenants: null,
      updatedBy: 'admin',
      updatedAt: daysAgo(2),
    },
    {
      id: 2,
      configKey: 'concurrency.model-max',
      configValue: '8',
      scope: 'global',
      tenantId: null,
      grayTenants: null,
      updatedBy: 'admin',
      updatedAt: daysAgo(6),
    },
    {
      id: 3,
      configKey: 'resilience.circuit-failure-rate',
      configValue: '0.5',
      scope: 'gray',
      tenantId: null,
      grayTenants: '1',
      updatedBy: 'ops.wang',
      updatedAt: daysAgo(9),
    },
  ]

  const modelNodes: ModelNode[] = [
    {
      id: 'node-a',
      name: 'ollama-node-a',
      url: 'http://10.0.12.21:11434',
      region: '华北-北京',
      status: 'online',
      load: 0.42,
      models: ['qwen2.5:7b', 'nomic-embed-text', 'llava:7b'],
      maxConcurrent: 8,
      activeConcurrent: 3,
    },
    {
      id: 'node-b',
      name: 'ollama-node-b',
      url: 'http://10.0.12.22:11434',
      region: '华东-上海',
      status: 'online',
      load: 0.71,
      models: ['qwen2.5:7b', 'qwen2.5:14b', 'nomic-embed-text'],
      maxConcurrent: 8,
      activeConcurrent: 6,
    },
    {
      id: 'node-c',
      name: 'ollama-node-c',
      url: 'http://10.0.12.23:11434',
      region: '华南-深圳',
      status: 'busy',
      load: 0.93,
      models: ['qwen2.5:7b', 'llava:7b'],
      maxConcurrent: 8,
      activeConcurrent: 8,
    },
    {
      id: 'node-d',
      name: 'ollama-node-d',
      url: 'http://10.0.12.24:11434',
      region: '西南-成都',
      status: 'offline',
      load: 0,
      models: ['qwen2.5:7b'],
      maxConcurrent: 8,
      activeConcurrent: 0,
    },
  ]

  const modelRoutes: ModelRouteItem[] = [
    { scene: 'CHAT', primary: 'qwen2.5:7b', backup: 'qwen2.5:14b', grayRatio: 20, description: '多轮对话场景' },
    { scene: 'RAG', primary: 'qwen2.5:7b', backup: 'qwen2.5:14b', grayRatio: 35, description: '检索增强问答场景' },
    { scene: 'AGENT', primary: 'qwen2.5:7b', backup: 'qwen2.5:14b', grayRatio: 0, description: '智能体规划执行场景' },
    { scene: 'MULTIMODAL', primary: 'llava:7b', backup: 'qwen2.5:7b', grayRatio: 10, description: '图片理解等多模态场景' },
  ]

  const auditLogs: AuditLog[] = Array.from({ length: 14 }, (_, i) => {
    const actions: [string, string][] = [
      ['知识库', '创建知识库「产品手册库」'],
      ['认证', '用户 admin 登录成功'],
      ['配置', '修改 rag.similarity-threshold → 0.62'],
      ['知识库', '上传文档 系统架构设计.md'],
      ['租户', '禁用租户「华南客服部」'],
      ['模型', '切换 RAG 场景主模型'],
      ['认证', '用户 ops.wang 登录成功'],
      ['配置', '开启 resilience.circuit-enabled'],
      ['知识库', '删除知识库「测试库」并清理向量'],
      ['租户', '调整租户「华东研发中心」每日配额'],
    ]
    const [module, action] = actions[i % actions.length]
    return {
      id: 100 - i,
      traceId: 'trc-' + (7231000 + i * 137).toString(16),
      module,
      action,
      operator: i % 3 === 0 ? 'admin' : i % 3 === 1 ? 'ops.wang' : 'system',
      ip: '192.168.1.' + (10 + (i % 40)),
      result: i === 5 ? '失败' : '成功',
      detail: i === 5 ? '模型节点不可用（H0001），已自动降级' : '操作正常完成',
      createdAt: new Date(Date.now() - i * 5400_000).toISOString(),
    }
  })

  const badcases: BadCase[] = [
    {
      id: 1,
      question: 'AI-Nexus 如何配置灰度发布？',
      answer: '通过模型路由配置的 grayRatio 字段控制灰度比例。',
      consistency: 2,
      relevance: 4,
      completeness: 2,
      createdAt: daysAgo(1),
    },
    {
      id: 2,
      question: '知识库支持哪些文件格式？',
      answer: '支持 txt、md、json、csv 四种格式，单文件不超过 10MB。',
      consistency: 4,
      relevance: 3,
      completeness: 3,
      createdAt: daysAgo(2),
    },
    {
      id: 3,
      question: '如何查看某次调用的完整链路？',
      answer: '在运维监控页输入 traceId 查询全链路。',
      consistency: 3,
      relevance: 2,
      completeness: 2,
      createdAt: daysAgo(3),
    },
  ]

  const loggers: Record<string, string> = {
    'com.ainexus': 'INFO',
    'com.ainexus.service.RagChatService': 'INFO',
    'com.ainexus.service.AgentService': 'INFO',
    'com.ainexus.controller': 'INFO',
    'org.springframework.ai': 'INFO',
    'org.springframework.security': 'WARN',
    'root': 'INFO',
  }

  return {
    kbSeq: 100,
    kbs,
    tenantSeq: 10,
    tenants,
    configSeq: 10,
    configHistory,
    configEffective,
    modelNodes,
    modelRoutes,
    auditLogs,
    badcases,
    loggers,
    username: 'admin',
  }
}

export const defaultTools = () => {
  const t: ToolInfo[] = [
    { name: 'web_search', description: '联网搜索实时信息', params: '{ "query": "string" }' },
    { name: 'calculator', description: '精确数值计算', params: '{ "expression": "string" }' },
    { name: 'knowledge_retrieval', description: '知识库向量检索', params: '{ "kb": "string", "question": "string" }' },
    { name: 'sql_query', description: '业务数据库只读查询', params: '{ "sql": "string" }' },
    { name: 'http_request', description: '调用外部 HTTP 接口', params: '{ "url": "string", "method": "string" }' },
  ]
  return t
}
export const defaultBadcases = (): BadCase[] => defaultDB().badcases
export const defaultAuditLogs = (): AuditLog[] => defaultDB().auditLogs
export const chunkRef = (
  content: string,
  documentName: string,
  score: number,
  idx: number,
): ChunkReference => ({
  content,
  documentName,
  documentId: 'doc-demo',
  chunkIndex: idx,
  score,
  startOffset: idx * 210,
  endOffset: (idx + 1) * 210,
  section: '第' + (idx + 1) + '节',
})

export function loadDB(): MockDB {
  try {
    const raw = localStorage.getItem(DB_KEY)
    if (raw) return JSON.parse(raw) as MockDB
  } catch {
    /* fallthrough */
  }
  const db = defaultDB()
  saveDB(db)
  return db
}

export function saveDB(db: MockDB): void {
  try {
    localStorage.setItem(DB_KEY, JSON.stringify(db))
  } catch {
    /* 忽略持久化失败 */
  }
}

export const isoNow = now
