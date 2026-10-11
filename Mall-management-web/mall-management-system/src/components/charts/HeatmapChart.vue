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
import { chartTokens, baseOption, itemTooltip, mixHex } from './chartTokens'

const props = defineProps({
  /** x 轴类目（如 24 个小时）。必填。 */
  xLabels: { type: Array, required: true },
  /** y 轴类目（如星期 / 状态）。必填。 */
  yLabels: { type: Array, required: true },
  /** 格值：`[[x下标, y下标, 值], …]`。缺的格子留白。必填。 */
  data: { type: Array, required: true },
  height: { type: String, default: '320px' },
  /** 色阶上界。不给就按数据最大值自动。 */
  max: { type: Number, default: null },
  /** tooltip / 色阶上的量名（如 `'订单数'`）。 */
  valueName: { type: String, default: '值' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  emptyText: { type: String, default: '区间内暂无数据' },
})

const emit = defineEmits(['retry'])

const hasData = computed(() => props.data.length > 0)

/**
 * 色阶上界：调用方给了就用，没给就按**数据最大值**取一个整数档。
 *
 * ⚠️⚠️ **这个值不给会同时出两件事，而且都不报错**（可视化段 commit 1 实测）：
 *   ① **报错**：`max` 若以「键在、值为 `undefined`」的形式传下去（`max: undefined`），
 *      ECharts 会把它算进 `dataExtent`（`VisualMapModel.resetExtent` = `asc([min, max])`，
 *      **不从数据推算**）⇒ 上界成 `undefined` ⇒ `_makeColorGradient` 采样出 `NaN` 索引
 *      ⇒ `visual[NaN]` = `undefined` ⇒ 画手柄横条时
 *      `addColorStop('undefined')` 抛 `SyntaxError`，**整块画布停在半帧**。
 *   ② **不报错但更阴**：`visualMap` 的默认 `max` 是 **200**。真数据全落在 60 上下时，
 *      整张热力图的格子全挤在色阶最底部深浅难分 —— 图上「有东西」，但读不出梯度。
 *   ⇒ 所以这里**永远给一个数**（`resolvedMax`），且**由数据算**。
 *   档位只为了让色阶刻度是个整数（10 / 20 / 50 /…），与精度无关。
 */
const resolvedMax = computed(() => {
  if (props.max !== null && props.max !== undefined) return props.max
  let m = 0
  for (const d of props.data || []) {
    const v = Number(d && d[2])
    if (Number.isFinite(v) && v > m) m = v
  }
  if (m <= 0) return 1 // 全 0 / 空数据：给 1 让色阶有个合法区间，别落回 undefined
  const step = m <= 10 ? 1 : m <= 50 ? 5 : m <= 200 ? 10 : m <= 1000 ? 50 : 100
  return Math.ceil(m / step) * step
})

const option = computed(() => {
  const t = chartTokens()
  const axisCommon = {
    type: 'category',
    splitArea: { show: true },
    axisLine: { lineStyle: { color: t.axisLine } },
    axisTick: { show: false },
    axisLabel: { color: t.axisLabel },
  }
  return {
    ...baseOption(t),
    grid: { left: 64, right: 24, top: 12, bottom: 56, containLabel: true },
    tooltip: itemTooltip(t),
    xAxis: { ...axisCommon, data: props.xLabels },
    yAxis: { ...axisCommon, data: props.yLabels },
    visualMap: {
      min: 0,
      max: resolvedMax.value, // 见上方 resolvedMax 注释：**绝不能是 `undefined`**
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemWidth: 12,
      itemHeight: 90,
      textStyle: { color: t.ink2, fontSize: 11 },
      // ⚠️ 色阶**起点取当前主题的底色**（浅色=白、暗色=`--surface`），不是写死白或写死黑 ——
      //    写死白的话暗色主题下低值格子会变成一片白斑。`mixHex` 就是干这个的。
      inRange: { color: [mixHex(t.surface, t.series[0], 0.08), t.series[0]] },
    },
    series: [
      {
        name: props.valueName,
        type: 'heatmap',
        data: props.data,
        label: { show: false },
        // 格子之间用底色描边隔开（同饼图：不描白，暗色下会成亮框）。
        itemStyle: { borderColor: t.surface, borderWidth: 2 },
        emphasis: { itemStyle: { borderColor: t.ink, borderWidth: 2 } },
      },
    ],
  }
})
</script>
