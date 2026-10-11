<template>
  <!-- ═══ 图表底座（可视化段 commit 1，2026-10-11）════════════════════════════════
       ECharts 生命周期 + resize + **三态**（空 / 加载 / 错误）。五个具体图
       （折线 / 柱 / 饼 / 环 / 热力）只负责把数据翻成 `option`，其余一律在这里。

       ⚠️ **画布容器常驻、三态是盖在上面的浮层**（不是 `v-if` 换掉）。
       若用 `v-if` 把画布摘掉，`echarts.init` 会拿到 `null`、还得在数据回来时补一次 init
       —— 那就有「init 时机」这个额外状态要维护（典型翻车：切回有数据的 tab 时图是白的）。
       常驻 + 浮层没有这个状态，代价只是浮层要有不透明底。 -->
  <div ref="rootEl" class="bc" :style="{ height }">
    <div ref="chartEl" class="bc-canvas" />

    <div v-if="state" class="bc-state" :class="'bc-state--' + state">
      <div v-if="state === 'loading'" class="bc-skel" aria-hidden="true">
        <i style="width: 100%" /><i style="width: 78%" /><i style="width: 56%" />
      </div>

      <template v-else>
        <p class="bc-msg">{{ state === 'error' ? error : emptyText }}</p>
        <button
          v-if="state === 'error'"
          type="button"
          class="bc-retry"
          @click="emit('retry')"
        >{{ retryText }}</button>
      </template>
    </div>
  </div>
</template>

<script setup>
/* ⚠️ 这条 `eslint-disable` 是**必需**的：本仓 `eslint-plugin-vue` 是 **8.7.1**，其
   `vue3-essential` 不带 `vue/setup-compiler-macros` 宏环境 ⇒ `defineProps` / `defineEmits`
   一律被判 `no-undef`、`npm run build` 直接红。房内先例：`ShoppingCart.vue` /
   `OrderCenter.vue` / `TemplatePicker.vue`。别把这条删掉「试试看」。 */
/* eslint-disable no-undef */
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import echarts from './echarts'

const props = defineProps({
  /** 完整的 ECharts option。五个具体图各自算好传进来；本组件不碰它的内容。必填。 */
  option: { type: Object, required: true },
  /** 画布高度。⚠️ **必须给得出像素值**（`320px` / `18rem`），别给 `100%`：
   *  ECharts 在零尺寸容器上 init 会告警且什么都不画，而父级若也是 auto 高度就永远是 0。 */
  height: { type: String, default: '320px' },
  /** 加载态（骨架）。 */
  loading: { type: Boolean, default: false },
  /** 空态（区间内没数据）。与 `loading` 同时为真时**加载优先**。 */
  empty: { type: Boolean, default: false },
  /** 错误态文案。非空即进错误态，且**优先级最高**（错了就别再说「暂无数据」）。 */
  error: { type: String, default: '' },
  emptyText: { type: String, default: '区间内暂无数据' },
  retryText: { type: String, default: '重试' },
})

const emit = defineEmits(['retry'])

/** 三态判据。错误 > 加载 > 空 > 正常 —— 顺序就是优先级，别调换。 */
const state = computed(() => {
  if (props.error) return 'error'
  if (props.loading) return 'loading'
  if (props.empty) return 'empty'
  return ''
})

const rootEl = ref(null)
const chartEl = ref(null)
let chart = null
let ro = null

function render() {
  if (!chart) return
  // ⚠️ `notMerge: true`：五个具体图的 `series` 形状不同（折线 ↔ 柱 ↔ 饼），
  // 不整体替换的话切图时旧序列会以「合并」的方式留下来（典型症状：改了类型但图上还是两种）。
  chart.setOption(props.option, { notMerge: true })
}

function onWinResize() {
  if (chart) chart.resize()
}

onMounted(async () => {
  await nextTick()
  if (!chartEl.value) return
  chart = echarts.init(chartEl.value, null, { renderer: 'canvas' })
  render()
  // ⚠️ 用 `ResizeObserver` 而不是只监听 `window.resize`：本仓两个壳（`AppShell` / `AdminShell`）
  // 都有可折叠的侧栏/菜单，容器宽度会在窗口尺寸不变时变化 —— 只听 window 的话那些场景图会缺一块。
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => {
      if (chart) chart.resize()
    })
    ro.observe(rootEl.value)
  } else {
    window.addEventListener('resize', onWinResize)
  }
})

onBeforeUnmount(() => {
  if (ro) {
    ro.disconnect()
    ro = null
  }
  window.removeEventListener('resize', onWinResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(() => props.option, render, { deep: true })
// 三态浮层不改变画布尺寸，但它出现/消失时卡片可能被挤压 ⇒ 补一次 resize，
// 免得「从空态变有数据」后图还按旧宽度画着。
watch(state, () => {
  if (chart) nextTick(() => chart && chart.resize())
})
</script>

<style scoped>
.bc {
  position: relative;
  width: 100%;
}
.bc-canvas {
  width: 100%;
  height: 100%;
}

/* ── 三态浮层 ─────────────────────────────────────────────────────────────── */
.bc-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--s3);
  padding: var(--s4);
  /* ⚠️ 必须不透明：画布还在底下（常驻策略），半透明会把图透出来与文案叠字。 */
  background: var(--surface);
}
.bc-msg {
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.bc-state--error .bc-msg {
  color: var(--danger);
}
.bc-retry {
  padding: var(--s1) var(--s4);
  font-size: var(--fs-sm);
  color: var(--brand-ink);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
}
.bc-retry:hover {
  border-color: var(--brand);
  background: var(--brand-tint);
}

/* 骨架：三条宽度递减的脉冲块，比转圈更接近「图要占的位置」。 */
.bc-skel {
  width: 100%;
  max-width: 320px;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.bc-skel i {
  display: block;
  height: 10px;
  border-radius: var(--r1);
  background: var(--surface-3);
  animation: bc-pulse 1.2s ease-in-out infinite;
}
.bc-skel i:nth-child(2) {
  animation-delay: 0.15s;
}
.bc-skel i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes bc-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
/* ⚠️ 尊重系统的「减少动态效果」——骨架的脉冲对前庭敏感人群是干扰。 */
@media (prefers-reduced-motion: reduce) {
  .bc-skel i { animation: none; }
}
</style>
