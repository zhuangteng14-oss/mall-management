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
  /** 序列：`[{ name, data, area?, dashed? }]`。`name` 进图例，`data` 与 `categories` 等长。必填。 */
  series: { type: Array, required: true },
  height: { type: String, default: '320px' },
  smooth: { type: Boolean, default: true },
  showLegend: { type: Boolean, default: true },
  /** y 轴标签格式化（如 `'{value}%'` / `'{value} 元'`）。单位走这里，不走轴名（见 `valueAxis`）。 */
  yFormatter: { type: String, default: '' },
  yMin: { type: Number, default: null },
  yMax: { type: Number, default: null },
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

const option = computed(() => {
  const t = chartTokens()
  const longest = props.series.reduce((n, s) => Math.max(n, (s.data || []).length), 0)
  return {
    ...baseOption(t),
    grid: { left: 56, right: 24, top: 18, bottom: props.showLegend ? 44 : 26 },
    tooltip: axisTooltip(t),
    legend: props.showLegend ? legendStyle(t) : { show: false },
    xAxis: categoryAxis(t, props.categories, { boundaryGap: false }),
    yAxis: valueAxis(t, { min: props.yMin, max: props.yMax, formatter: props.yFormatter }),
    series: props.series.map((s, i) => {
      const color = t.series[i % t.series.length]
      return {
        name: s.name,
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
