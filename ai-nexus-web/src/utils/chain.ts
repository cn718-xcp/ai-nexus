/**
 * 思维链文本解析：将后端 thinkingChain（Thought/Action/Action Input/Observation/Finish 行式文本）
 * 解析为时间线步骤；兼容无 Action Input 的纯 Thought/Observation 流
 */
import type { ChainStep } from '@/components/StepTimeline.vue'

export function parseThinkingChain(chain: string): ChainStep[] {
  const steps: ChainStep[] = []
  if (!chain) return steps
  const lines = chain.split(/\r?\n/)

  const pushThought = (text: string) => {
    if (text.trim()) steps.push({ kind: 'thought', title: text.trim() })
  }

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) continue
    if (/^Thought\s*:/i.test(line)) {
      pushThought(line.replace(/^Thought\s*:/i, ''))
    } else if (/^Action\s*:/i.test(line)) {
      const tool = line.replace(/^Action\s*:/i, '').trim()
      steps.push({ kind: 'action', title: '调用工具', tool })
    } else if (/^Action\s*Input\s*:/i.test(line)) {
      const input = line.replace(/^Action\s*Input\s*:/i, '').trim()
      const last = [...steps].reverse().find((s) => s.kind === 'action' && !s.input)
      if (last) last.input = input
      else steps.push({ kind: 'action', title: '调用工具', input })
    } else if (/^Observation\s*:/i.test(line)) {
      steps.push({ kind: 'observation', title: '工具返回', content: line.replace(/^Observation\s*:/i, '').trim() })
    } else if (/^Finish\s*:/i.test(line)) {
      steps.push({ kind: 'finish', title: line.replace(/^Finish\s*:/i, '').trim() })
    } else if (steps.length === 0) {
      pushThought(line)
    } else {
      // 续行追加到最近一个步骤
      const last = steps[steps.length - 1]
      if (last.kind === 'observation') last.content += '\n' + line
      else if (last.kind === 'finish') last.title += ' ' + line
      else last.title += ' ' + line
    }
  }

  // 无显式 Finish 时补一个总结节点（取最终 answer 由调用方处理，这里不再伪造）
  return steps
}
