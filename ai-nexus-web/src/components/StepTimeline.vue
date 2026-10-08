<script setup lang="ts">
/**
 * 思维链竖向时间线：思考 → 调用工具 → 观察 → 总结
 * 执行中节点带呼吸光环，工具调用完成打勾动画
 */
export interface ChainStep {
  kind: 'thought' | 'action' | 'observation' | 'finish'
  title: string
  tool?: string
  input?: string
  content?: string
}

withDefaults(defineProps<{ steps: ChainStep[]; running?: boolean }>(), { running: false })

const kindMeta: Record<ChainStep['kind'], { label: string; icon: string }> = {
  thought: { label: '思考', icon: '☀' },
  action: { label: '调用工具', icon: '⚒' },
  observation: { label: '观察', icon: '◉' },
  finish: { label: '总结', icon: '✓' },
}
</script>

<template>
  <div class="chain-timeline">
    <div
      v-for="(s, i) in steps"
      :key="i"
      class="chain-node"
      :class="[`kind-${s.kind}`, { current: running && i === steps.length - 1 }]"
    >
      <div class="chain-rail">
        <span class="chain-dot" :class="{ 'an-breathing': running && i === steps.length - 1 }">
          <span class="chain-dot-icon">{{ kindMeta[s.kind].icon }}</span>
          <svg v-if="s.kind === 'finish' && !running" class="an-check" viewBox="0 0 24 24" width="12" height="12">
            <path d="M4 12.5l5 5L20 6.5" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" />
          </svg>
        </span>
        <span v-if="i < steps.length - 1" class="chain-line"></span>
      </div>
      <div class="chain-body">
        <div class="chain-head">
          <span class="chain-kind" :class="`kind-text-${s.kind}`">{{ kindMeta[s.kind].label }}</span>
          <span v-if="s.tool" class="chain-tool">{{ s.tool }}</span>
          <span class="chain-title an-ellipsis">{{ s.title }}</span>
        </div>
        <code v-if="s.input" class="chain-input">{{ s.input }}</code>
        <div v-if="s.content" class="chain-content">{{ s.content }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chain-timeline {
  --rail: var(--an-border-strong);
}
.chain-node {
  display: flex;
  gap: 12px;
  min-height: 52px;
}
.chain-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 26px;
  flex-shrink: 0;
}
.chain-dot {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  background: var(--an-surface-strong);
  border: 1.5px solid var(--rail);
  color: var(--an-text-3);
  font-size: 12px;
  z-index: 1;
  transition: all var(--an-dur) var(--an-ease);
}
.chain-dot-icon {
  line-height: 1;
}
.kind-thought .chain-dot {
  border-color: var(--an-primary);
  color: var(--an-primary);
}
.kind-action .chain-dot {
  border-color: var(--an-warning);
  color: var(--an-warning);
}
.kind-observation .chain-dot {
  border-color: #14c9c9;
  color: #14c9c9;
}
.kind-finish .chain-dot {
  background: var(--an-primary-gradient);
  border-color: transparent;
  color: #fff;
}
.chain-line {
  flex: 1;
  width: 2px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--rail), rgba(127, 145, 180, 0.1));
  margin: 3px 0;
}
.chain-body {
  flex: 1;
  min-width: 0;
  padding-bottom: 14px;
}
.chain-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.chain-kind {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
  flex-shrink: 0;
}
.kind-text-thought {
  color: var(--an-primary);
  background: rgba(var(--an-primary-rgb), 0.1);
}
.kind-text-action {
  color: var(--an-warning);
  background: rgba(255, 125, 0, 0.12);
}
.kind-text-observation {
  color: #0fb2b2;
  background: rgba(20, 201, 201, 0.12);
}
.kind-text-finish {
  color: var(--an-success);
  background: rgba(0, 180, 42, 0.12);
}
.chain-tool {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--an-warning);
  background: rgba(255, 125, 0, 0.1);
  border: 1px solid rgba(255, 125, 0, 0.25);
  padding: 1px 8px;
  border-radius: 999px;
  flex-shrink: 0;
}
.chain-title {
  font-size: 13px;
  color: var(--an-text);
  font-weight: 500;
}
.chain-input {
  display: block;
  margin-top: 6px;
  font-size: 11.5px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  color: var(--an-text-2);
  background: rgba(127, 145, 180, 0.1);
  border-radius: 6px;
  padding: 5px 9px;
  word-break: break-all;
  white-space: pre-wrap;
}
.chain-content {
  margin-top: 6px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--an-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
