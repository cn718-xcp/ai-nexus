/** 知识库模块 /api/v1/kb */
import { http } from '../request'
import type { BackupPayload, KnowledgeBase, KnowledgeBaseCreateRequest, KnowledgeDocument } from '@/types/api'

export const knowledgeApi = {
  /** 知识库列表 */
  list() {
    return http.get<KnowledgeBase[]>('/api/v1/kb', undefined, { silent: true })
  },
  /** 创建知识库 */
  create(data: KnowledgeBaseCreateRequest) {
    return http.post<KnowledgeBase>('/api/v1/kb', data)
  },
  /** 删除知识库（级联删除向量数据） */
  remove(kbId: number) {
    return http.delete<null>(`/api/v1/kb/${kbId}`)
  },
  /** 文档列表 */
  documents(kbId: number) {
    return http.get<KnowledgeDocument[]>(`/api/v1/kb/${kbId}/documents`, undefined, { silent: true })
  },
  /** 上传文档（txt/md/json/csv，≤10MB），监听上传进度 */
  upload(kbId: number, file: File, onProgress?: (p: number) => void) {
    const fd = new FormData()
    fd.append('file', file)
    return http.upload<KnowledgeDocument>(`/api/v1/kb/${kbId}/documents`, fd, onProgress)
  },
  /** 导出备份：返回 JSON 字符串（kb 元数据 + 全部片段原文） */
  backup(kbId: number) {
    return http.get<string>(`/api/v1/kb/${kbId}/backup`, undefined, { timeout: 120000 })
  },
  /** 导入备份（multipart），返回新建知识库 */
  importBackup(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    return http.upload<KnowledgeBase>('/api/v1/kb/import', fd, undefined, { timeout: 180000 })
  },
}

/** 解析备份 JSON 字符串（兼容字段缺失） */
export function parseBackup(json: string): BackupPayload | null {
  try {
    const p = JSON.parse(json) as BackupPayload
    if (!p.kb || !Array.isArray(p.documents)) return null
    return p
  } catch {
    return null
  }
}
