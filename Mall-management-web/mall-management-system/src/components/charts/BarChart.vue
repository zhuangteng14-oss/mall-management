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
  /** 序列：`[{ name, data }]`。必填。 */
  series: { type: Array, required: true },
  height: { type: String, default: '320px' },
  /** 横向柱（类目在 y 轴）—— **排行榜的默认形态**：长商品名横着写才不被截断。 */
  horizontal: { type: Boolean, default: false },
  /** 堆叠（同 x 的多个序列叠成一根）。 */
  stacked: { type: Boolean, default: false },
  showLegend: { type: Boolean, default: true },
  /** 数值轴标签格式化（如 `'{value} 元'`）。单位走这里，不走轴名（见 `valueAxis`）。 */
  valueFormatter: { type: String, default: '' },
  /** 单根柱子的最大宽度 —— 类目少时不至于变成几根巨柱。 */
  maxBarWidth: { type: Number, default: 18 },
  /** 类目文字最长几个字后截断加省略号（横向柱尤其需要）。0 = 不截断。 */
  labelTruncate: { type: Number, default: 0 },
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
    grid: props.horizontal
      ? { left: 8, right: 32, top: 18, bottom: props.showLegend ? 44 : 26, containLabel: true }
      : { left: 56, right: 24, top: 18, bottom: props.showLegend ? 44 : 26 },
    tooltip: TOOLTIP_AXIS,
    legend: props.showLegend ? LEGEND_SHOWN : LEGEND_HIDDEN,
    // 横向柱 = 轴对调，其余全同。
    xAxis: props.horizontal ? val : cat,
    yAxis: props.horizontal ? cat : val,
    series: props.series.map((s, i) => {
      const color = t.series[i % t.series.length]
      return {
        name: s.name,
        type: 'bar',
        data: s.data,
        stack: props.stacked ? 'total' : undefined,
        barMaxWidth: props.maxBarWidth,
        // 圆角只给「值的那一头」：竖柱给上两角、横柱给右两角。
        itemStyle: {
          color,
          borderRadius: props.horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0],
        },
      }
    }),
  }
})
</script>
