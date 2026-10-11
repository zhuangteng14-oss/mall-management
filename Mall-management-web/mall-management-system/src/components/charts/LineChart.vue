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
  chartTokens, baseOption, axisTooltip, legendStyle, categoryAxis, valueAxis,
} from './chartTokens'

const props = defineProps({
  /** x 轴类目（如 `['10-01','10-02',…]` 或 `['00:00',…]`）。必填。 */
  categories: { type: Array, required: true },
  /** 序列：`[{ name, data, area?, dashed?, axis? }]`。`name` 进图例，`data` 与 `categories` 等长。必填。 */
  series: { type: Array, required: true },
  height: { type: String, default: '320px' },
  smooth: { type: Boolean, default: true },
  showLegend: { type: Boolean, default: true },
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
  /** 折点数超过它就不再画数据点（只留线）—— 24 个点以上标点会糊成一条毛边。 */
  symbolLimit: { type: Number, default: 24 },
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

/** 第 i 条序列的颜色（与下面 `series` 里分配的是**同一套**顺序，轴染色要跟它一致）。 */
const seriesColor = (t, i) => t.series[i % t.series.length]

const option = computed(() => {
  const t = chartTokens()
  const longest = props.series.reduce((n, s) => Math.max(n, (s.data || []).length), 0)
  // 右轴要占掉右边一条标签位（56 与左边的 left 同宽）。
  // ⚠️ 两条轴各染各的色 ⇒ 「哪个轴是哪个指标」不靠写轴名（本仓 `valueAxis` 明确不写 `name`，
  //    理由见它）也能一眼对上；右轴的 splitLine 关掉，避免与左轴网格线叠成双线。
  const rightIdx = props.series.findIndex((s) => s.axis === 'right')
  return {
    ...baseOption(t),
    grid: { left: 56, right: dual.value ? 56 : 24, top: 18, bottom: props.showLegend ? 44 : 26 },
    tooltip: axisTooltip(t),
    legend: props.showLegend ? legendStyle(t) : { show: false },
    xAxis: categoryAxis(t, props.categories, { boundaryGap: false }),
    yAxis: dual.value
      ? [
          valueAxis(t, {
            min: props.yMin, max: props.yMax, formatter: props.yFormatter,
            color: seriesColor(t, 0),
          }),
          valueAxis(t, {
            formatter: props.y2Formatter, color: seriesColor(t, rightIdx), splitLine: false,
          }),
        ]
      : valueAxis(t, { min: props.yMin, max: props.yMax, formatter: props.yFormatter }),
    series: props.series.map((s, i) => {
      const color = seriesColor(t, i)
      return {
        name: s.name,
        yAxisIndex: dual.value && s.axis === 'right' ? 1 : 0,
        type: 'line',
        data: s.data,
        smooth: props.smooth,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: longest <= props.symbolLimit,
        lineStyle: { width: 2, color, type: s.dashed ? [4, 3] : 'solid' },
        itemStyle: { color },
        // ⚠️ 面积渐变靠色值**后缀拼 alpha**（`'55'` / `'00'`）⇒ 要求 token 是 6 位 hex。
        //    本仓 `--c1`-`--c10` 两套主题都是 6 位 hex（见 `tokens.css`）⇒ 成立。
        //    哪天换成 `rgb()` 这里会静默退化成不透明色块，改动色板时留意。
        areaStyle: s.area
          ? {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: color + '55' },
                { offset: 1, color: color + '00' },
              ]),
            }
          : undefined,
      }
    }),
  }
})
</script>
