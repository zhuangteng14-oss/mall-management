<template>
  <BaseChart
    :option="option"
    :height="height"
    :loading="loading"
    :empty="!hasData"
    :error="error"
    :empty-text="emptyText"
    @retry="emit('retry')"
  />
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import BaseChart from './BaseChart.vue'
import {
  chartTokens, TOOLTIP_AXIS, LEGEND_SHOWN, LEGEND_HIDDEN, categoryAxis, valueAxis,
} from './chartTokens'

const props = defineProps({
  /** 类目。排行榜场景就是商品名 / 商家名（长名字时配合 `horizontal` 用）。必填。 */
  categories: { type: Array, required: true },
  /** 序列：`[{ name, data, colorIndex?, stack?, markPoints? }]`。必填。 */
  series: { type: Array, required: true },
  height: { type: String, default: '320px' },
  /** 横向柱（类目在 y 轴）—— **排行榜的默认形态**：长商品名横着写才不被截断。 */
  horizontal: { type: Boolean, default: false },
  /** 堆叠（同 x 的多个序列叠成一根）。`series[].stack` 可逐条覆盖。 */
  stacked: { type: Boolean, default: false },
  showLegend: { type: Boolean, default: true },
  /** 是否画 x 轴。时间栈里第一张 QPS 图不画，由最底的热力图统一画。 */
  showXAxis: { type: Boolean, default: true },
  /** 绘图区内边距（px）。五图共用同一套值才能「共享一根 x 轴」。 */
  gridLeft: { type: Number, default: 56 },
  gridRight: { type: Number, default: 24 },
  gridTop: { type: Number, default: 18 },
  gridBottom: { type: Number, default: null },
  /** 数值轴标签格式化（如 `'{value} 元'`）。单位走这里，不走轴名（见 `valueAxis`）。 */
  valueFormatter: { type: String, default: '' },
  /** 单根柱子的最大宽度 —— 类目少时不至于变成几根巨柱。 */
  maxBarWidth: { type: Number, default: 18 },
  /** 类目文字最长几个字后截断加省略号（横向柱尤其需要）。0 = 不截断。 */
  labelTruncate: { type: Number, default: 0 },
  /** tooltip 自定义 formatter（ECharts 原样接收）。 */
  tooltipFormatter: { type: Function, default: null },
  /** 是否开启 ECharts 的补间动画。时间栈每拍 `notMerge` 重建，关掉可避免每秒重播。 */
  animation: { type: Boolean, default: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  emptyText: { type: String, default: '区间内暂无数据' },
})

const emit = defineEmits(['retry'])

const hasData = computed(
  () => props.categories.length > 0 && props.series.some((s) => s.data && s.data.length > 0)
)

function truncate(label) {
  const n = props.labelTruncate
  const s = String(label)
  return n > 0 && s.length > n ? s.slice(0, n) + '…' : s
}

/** `colorIndex` 是 1-based 的 `--cN` 序号（见 chartTokens 的 series）。 */
function colorOf(t, s, i) {
  const idx = Number(s.colorIndex)
  if (Number.isFinite(idx) && idx >= 1) return t.series[(idx - 1) % t.series.length]
  return t.series[i % t.series.length]
}

/** 峰值 / 谷值标注；形状与 `LineChart.vue` 同款（两处共用一份数据约定）。 */
function markPointOf(points, color, t) {
  if (!points || !points.length) return undefined
  return {
    symbol: 'circle',
    symbolSize: 5,
    itemStyle: { color },
    label: {
      show: true,
      color: t.ink,
      fontSize: 10,
      formatter: (p) => (p.data && p.data.labelText) || '',
    },
    data: points.map((p) => ({
      coord: [p.x, p.y],
      value: p.y,
      labelText: p.text,
      label: { position: p.position || 'top' },
    })),
  }
}

const option = computed(() => {
  const t = chartTokens()

  const cat = categoryAxis(props.categories, {
    boundaryGap: true,
    // 横向柱的类目是「商品名」这类长文本 ⇒ 让位给标签，且只在横向时截断。
    // ⚠️ 只覆盖 `formatter`：标签**颜色**由主题给（覆盖它 = 以后改主题时这里不跟着动）。
    ...(props.horizontal ? { axisLabel: { formatter: truncate } } : {}),
  })
  const val = valueAxis({ formatter: props.valueFormatter })

  return {
    animation: props.animation,
    grid: props.horizontal
      ? { left: 8, right: 32, top: 18, bottom: props.showLegend ? 44 : 26, containLabel: true }
      : {
          left: props.gridLeft,
          right: props.gridRight,
          top: props.gridTop,
          bottom: props.gridBottom === null ? (props.showLegend ? 44 : 26) : props.gridBottom,
        },
    tooltip: props.tooltipFormatter
      ? { ...TOOLTIP_AXIS, formatter: props.tooltipFormatter }
      : TOOLTIP_AXIS,
    legend: props.showLegend ? LEGEND_SHOWN : LEGEND_HIDDEN,
    // 竖向柱不画 x 轴时把轴藏起来，但保留同一个 `grid` 左右内边距 ⇒ 与折线/热力对齐。
    xAxis: props.horizontal ? val : (props.showXAxis ? cat : { ...cat, show: false }),
    yAxis: props.horizontal ? cat : val,
    series: props.series.map((s, i) => {
      const color = colorOf(t, s, i)
      return {
        name: s.name,
        type: 'bar',
        data: s.data,
        stack: s.stack || (props.stacked ? 'total' : undefined),
        barMaxWidth: props.maxBarWidth,
        // 圆角只给「值的那一头」：竖柱给上两角、横柱给右两角。
        itemStyle: {
          color,
          borderRadius: props.horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0],
        },
        markPoint: markPointOf(s.markPoints, color, t),
      }
    }),
  }
})
</script>
