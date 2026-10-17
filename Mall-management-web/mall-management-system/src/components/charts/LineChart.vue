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
import echarts from './echarts'
import {
  chartTokens, mixHex, TOOLTIP_AXIS, LEGEND_SHOWN, LEGEND_HIDDEN, categoryAxis, valueAxis,
} from './chartTokens'

const props = defineProps({
  /** x 轴类目（如 `['10-01','10-02',…]` 或 `['00:00',…]`）。必填。 */
  categories: { type: Array, required: true },
  /**
   * 序列：`[{ name, data, area?, dashed?, axis?, type?, stack?, colorIndex?, barMaxWidth?,
   * markPoints? }]`。
   *
   * `type` 默认 `'line'`；传 `'bar'` 时本组件退化成「柱 + 线」的组合图（命中率那张就是
   * 命中/未命中柱 + 命中率线）。`markPoints` 是本序列的峰值/谷值标注，形状见 `markPointOf()`。
   */
  series: { type: Array, required: true },
  height: { type: String, default: '320px' },
  smooth: { type: Boolean, default: true },
  showLegend: { type: Boolean, default: true },
  /**
   * 是否画 x 轴。时间栈里前四张图共用最底那张的 x 轴 ⇒ 传 `false` 把轴**隐藏但保留网格几何**
   * （`grid` 的左右内边距不变，否则五张图的绘图区对不齐）。
   */
  showXAxis: { type: Boolean, default: true },
  /**
   * 类目轴 `boundaryGap`。折线默认 `false`、柱默认 `true`；时间栈要求五图逐点对齐 ⇒
   * 统一传 `true`，折线点落在每个时间带中心，与柱子/热力格中心同一条竖线。
   */
  boundaryGap: { type: Boolean, default: false },
  /** 绘图区内边距（px）。五图共用同一套值才能「共享一根 x 轴」。 */
  gridLeft: { type: Number, default: 56 },
  gridRight: { type: Number, default: null },
  gridTop: { type: Number, default: 18 },
  gridBottom: { type: Number, default: null },
  /** y 轴标签格式化（如 `'{value}%'` / `'{value} 元'`）。单位走这里，不走轴名（见 `valueAxis`）。 */
  yFormatter: { type: String, default: '' },
  yMin: { type: Number, default: null },
  yMax: { type: Number, default: null },
  /**
   * **右轴**的标签格式化 —— 只有 `series` 里出现 `axis: 'right'` 时才用得上（2026-10-11）。
   *
   * <p>双 Y 轴的触发条件写在**数据**上而不是 prop 上：`series` 里任一条带 `axis: 'right'`
   * ⇒ 自动变成双轴，那条走右轴、其余走左轴。理由与房规「传数据、不传样式」一致：哪个指标该
   * 配哪条轴是**数据的属性**，不是调用处临时挑的样式。
   *
   * <p>⚠️ 量级差得远才用得上（如 GMV 1e7 与成单数 1e1 同轴会把小的压成一条直线）。
   * **同一量级的两个指标不要用双轴** —— 那会把「谁高谁低」这个可以直接读出来的关系
   * 变成「取决于两条轴各自怎么缩放」，反而不可比。
   */
  y2Formatter: { type: String, default: '' },
  y2Min: { type: Number, default: null },
  y2Max: { type: Number, default: null },
  /** 折点数超过它就不再画数据点（只留线）—— 24 个点以上标点会糊成一条毛边。 */
  symbolLimit: { type: Number, default: 24 },
  /** tooltip 自定义 formatter（ECharts 原样接收）。不传 = 主题里的轴触发默认格式。 */
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

/** 双 Y 轴：`series` 里任一条带 `axis: 'right'` ⇒ 开右轴。见 `y2Formatter` 的 props 注释。 */
const dual = computed(() => props.series.some((s) => s.axis === 'right'))

/** 第 i 条序列的颜色。`colorIndex` 是 1-based 的 `--cN` 序号（见 chartTokens 的 series）。 */
function colorOf(t, s, i) {
  const idx = Number(s.colorIndex)
  if (Number.isFinite(idx) && idx >= 1) return t.series[(idx - 1) % t.series.length]
  return t.series[i % t.series.length]
}

/**
 * 峰值 / 谷值标注。`markPoints` 由调用方算好（跳过 null、持平时只留一个点），
 * 这里只负责翻成 ECharts 的 `markPoint` 形状 —— 数据在哪、标什么文字是页面的判断。
 */
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
  const longest = props.series.reduce((n, s) => Math.max(n, (s.data || []).length), 0)
  // 右轴要占掉右边一条标签位（56 与左边的 left 同宽）。
  // ⚠️ 两条轴各染各的色 ⇒ 「哪个轴是哪个指标」不靠写轴名（本仓 `valueAxis` 明确不写 `name`，
  //    理由见它）也能一眼对上；右轴的 splitLine 关掉，避免与左轴网格线叠成双线。
  const rightIdx = props.series.findIndex((s) => s.axis === 'right')
  const firstColor = colorOf(t, props.series[0] || {}, 0)
  const rightColor = colorOf(t, props.series[rightIdx] || {}, Math.max(0, rightIdx))
  const xAxis = categoryAxis(props.categories, { boundaryGap: props.boundaryGap })
  return {
    animation: props.animation,
    grid: {
      left: props.gridLeft,
      right: props.gridRight === null ? (dual.value ? 56 : 24) : props.gridRight,
      top: props.gridTop,
      bottom: props.gridBottom === null ? (props.showLegend ? 44 : 26) : props.gridBottom,
    },
    tooltip: props.tooltipFormatter
      ? { ...TOOLTIP_AXIS, formatter: props.tooltipFormatter }
      : TOOLTIP_AXIS,
    legend: props.showLegend ? LEGEND_SHOWN : LEGEND_HIDDEN,
    // `show:false` 把轴线/刻度/标签一起藏掉，但 `grid` 的左右留白不变 ⇒ 五图绘图区仍然对齐。
    xAxis: props.showXAxis ? xAxis : { ...xAxis, show: false },
    yAxis: dual.value
      ? [
          valueAxis({
            min: props.yMin, max: props.yMax, formatter: props.yFormatter,
            color: firstColor,
          }),
          valueAxis({
            min: props.y2Min, max: props.y2Max, formatter: props.y2Formatter,
            color: rightColor, splitLine: false,
          }),
        ]
      : valueAxis({ min: props.yMin, max: props.yMax, formatter: props.yFormatter }),
    series: props.series.map((s, i) => {
      const color = colorOf(t, s, i)
      const isBar = s.type === 'bar'
      return {
        name: s.name,
        yAxisIndex: dual.value && s.axis === 'right' ? 1 : 0,
        type: isBar ? 'bar' : 'line',
        data: s.data,
        stack: isBar ? s.stack : undefined,
        barMaxWidth: isBar ? (s.barMaxWidth || 8) : undefined,
        smooth: isBar ? undefined : props.smooth,
        showSymbol: isBar ? undefined : longest <= props.symbolLimit,
        // 线宽 / 数据点形状与大小在主题的 `line` 里；这里只写**本序列特有**的（色、虚线）。
        lineStyle: isBar ? undefined : { color, type: s.dashed ? [4, 3] : 'solid' },
        itemStyle: isBar
          ? { color, borderRadius: [2, 2, 0, 0] }
          : { color },
        // ⚠️ 面积渐变用 `mixHex` 从主题底色起算：顶部深、底部接近底色但**不消失**。
        //    旧版拼 `'55'` / `'00'` 在暗色主题下低值段几乎与背景同色（3a.4 提亮）。
        areaStyle: !isBar && s.area
          ? {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: mixHex(t.surface, color, 0.42) },
                { offset: 1, color: mixHex(t.surface, color, 0.04) },
              ]),
            }
          : undefined,
        markPoint: isBar ? undefined : markPointOf(s.markPoints, color, t),
      }
    }),
  }
})
</script>
