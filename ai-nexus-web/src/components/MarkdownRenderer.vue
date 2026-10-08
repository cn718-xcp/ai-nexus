<script setup lang="ts">
/**
 * Markdown 渲染器：markdown-it + highlight.js 代码高亮
 * - 代码块：深色玻璃面板 + 语言角标 + 一键复制
 * - streaming 时尾部追加呼吸光标，模拟打字节奏
 * - html:false 防止 AI 生成内容注入 XSS
 */
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import { ElMessage } from 'element-plus'

const props = defineProps<{ content: string; streaming?: boolean }>()

const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      } catch {
        /* fallthrough */
      }
    }
    try {
      return hljs.highlightAuto(code).value
    } catch {
      return ''
    }
  },
})

const defaultFence = md.renderer.rules.fence!
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const lang = (token.info || '').trim().split(/\s+/)[0] || 'text'
  const inner = defaultFence(tokens, idx, options, env, self)
  return (
    `<div class="md-code-block"><span class="md-code-lang">${lang}</span>` +
    inner +
    `<button class="md-copy-btn" type="button">复制</button></div>`
  )
}

const html = computed(() => {
  const body = md.render(props.content || '')
  return props.streaming ? body + '<span class="an-cursor"></span>' : body
})

/** 事件委托处理代码块复制 */
function onClickRoot(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (target.classList?.contains('md-copy-btn')) {
    const block = target.closest('.md-code-block')
    const code = block?.querySelector('pre code')?.textContent || ''
    navigator.clipboard
      .writeText(code)
      .then(() => ElMessage.success('代码已复制'))
      .catch(() => ElMessage.error('复制失败'))
  }
}
</script>

<template>
  <div class="an-md" v-html="html" @click="onClickRoot"></div>
</template>
