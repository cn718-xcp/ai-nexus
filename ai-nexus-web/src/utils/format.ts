import dayjs from 'dayjs'

/** 千分位数字 */
export function formatNumber(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return '-'
  return n.toLocaleString('zh-CN')
}

/** Token 数量：1.2 万 / 3.4k 风格 */
export function formatTokens(n: number | null | undefined): string {
  if (n === null || n === undefined) return '-'
  if (n >= 100000000) return (n / 100000000).toFixed(2) + ' 亿'
  if (n >= 10000) return (n / 10000).toFixed(1) + ' 万'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

/** 文件大小 */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(2) + ' MB'
}

/** 百分比（0~1 → xx.x%） */
export function formatPercent(ratio: number | null | undefined, digits = 1): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '-'
  return (ratio * 100).toFixed(digits) + '%'
}

/** ISO 时间 → 本地展示 */
export function formatTime(iso: string | null | undefined, tpl = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!iso) return '-'
  return dayjs(iso).format(tpl)
}

/** 相对时间（刚刚 / x 分钟前） */
export function timeAgo(iso: string | number | null | undefined): string {
  if (!iso) return '-'
  const t = typeof iso === 'number' ? iso : dayjs(iso).valueOf()
  const diff = Date.now() - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86400_000) return Math.floor(diff / 3600_000) + ' 小时前'
  if (diff < 7 * 86400_000) return Math.floor(diff / 86400_000) + ' 天前'
  return dayjs(t).format('YYYY-MM-DD')
}

/** 毫秒时长 */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '-'
  if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s'
  return Math.round(ms) + ' ms'
}
