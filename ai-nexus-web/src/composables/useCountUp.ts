/**
 * 数字平滑滚动：指标数值变化时做 rAF 补间，而非直接跳变
 * 动效关闭（data-anim=off）时直接赋值
 */
import { ref, watch, type Ref, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'

export function useCountUp(source: Ref<number>, duration = 600): Ref<number> {
  const app = useAppStore()
  const display = ref(source.value)
  let raf = 0

  watch(source, (to, from) => {
    cancelAnimationFrame(raf)
    // 非有限值（NaN 等）直接赋值，避免补间失效
    if (!Number.isFinite(to)) {
      display.value = to as number
      return
    }
    if (app.animationLevel === 'off' || !Number.isFinite(from)) {
      display.value = to
      return
    }
    const start = performance.now()
    const initial = from ?? 0
    const delta = to - initial
    const step = (now: number) => {
      const p = Math.min(1, (now - start) / duration)
      // easeOutCubic
      const eased = 1 - Math.pow(1 - p, 3)
      display.value = initial + delta * eased
      if (p < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
  })

  onUnmounted(() => cancelAnimationFrame(raf))
  return display
}
