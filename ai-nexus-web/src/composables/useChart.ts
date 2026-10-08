/**
 * ECharts 封装：自动初始化 / 容器尺寸自适应 / 主题切换重建 / 卸载销毁
 * 入场动画由 ECharts 原生 animation 承担
 */
import { onUnmounted, ref, watch, type Ref } from 'vue'
import * as echarts from 'echarts'
import { useAppStore } from '@/stores/app'

export function useChart(elRef: Ref<HTMLElement | undefined>) {
  const app = useAppStore()
  let chart: echarts.ECharts | null = null
  let lastOption: echarts.EChartsCoreOption | null = null
  const ready = ref(false)

  const observer = new ResizeObserver(() => {
    chart?.resize()
  })

  function ensure(): echarts.ECharts | null {
    if (!elRef.value) return null
    if (!chart) {
      chart = echarts.init(elRef.value, undefined, { renderer: 'canvas' })
      observer.observe(elRef.value)
      ready.value = true
    }
    return chart
  }

  function setOption(option: echarts.EChartsCoreOption, notMerge = false) {
    const c = ensure()
    if (!c) return
    lastOption = option
    c.setOption(buildThemeOption(option), notMerge)
  }

  /** 注入主题相关的公共配置（文字颜色等） */
  function buildThemeOption(option: echarts.EChartsCoreOption): echarts.EChartsCoreOption {
    const dark = app.isDark
    const base: echarts.EChartsCoreOption = {
      textStyle: { color: dark ? '#a9b4c6' : '#4e5969' },
      animationDuration: app.animationLevel === 'off' ? 0 : 700,
      animationDurationUpdate: app.animationLevel === 'off' ? 0 : 300,
    }
    // 深度合并一层即可满足本场景
    const merged: Record<string, unknown> = { ...base }
    for (const [k, v] of Object.entries(option)) {
      merged[k] = v
    }
    return merged
  }

  // 主题切换：以相同数据重建图表
  watch(
    () => app.theme,
    () => {
      if (lastOption) setOption(lastOption, true)
    },
  )

  onUnmounted(() => {
    observer.disconnect()
    chart?.dispose()
    chart = null
  })

  return { setOption, chartRef: () => chart, ready }
}

/** 主题色渐变工具 */
export function primaryGradient(direction: 'horizontal' | 'vertical' = 'vertical') {
  return new echarts.graphic.LinearGradient(
    0,
    0,
    direction === 'horizontal' ? 1 : 0,
    direction === 'horizontal' ? 0 : 1,
    [
      { offset: 0, color: '#4080FF' },
      { offset: 1, color: '#165DFF' },
    ],
  )
}

/** 图表通用配色 */
export const chartPalette = ['#165DFF', '#00B42A', '#FF7D00', '#722ED1', '#14C9C9', '#F53F3F', '#F7BA1E']
